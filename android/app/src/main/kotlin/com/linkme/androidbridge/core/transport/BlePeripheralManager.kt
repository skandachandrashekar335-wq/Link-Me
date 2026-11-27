package com.linkme.androidbridge.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.linkme.androidbridge.core.crypto.BlePrivacyBeacon
import com.linkme.androidbridge.core.crypto.BluetoothSessionCipher
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.ReplayGuard
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object BleFrameChunker {
    fun chunk(frameData: ByteArray, mtu: Int = 23): List<ByteArray> {
        val maxChunk = maxOf(1, mtu - 3)
        if (frameData.isEmpty()) return emptyList()
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < frameData.size) {
            val end = minOf(frameData.size, offset + maxChunk)
            chunks.add(frameData.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }
}

/**
 * Low-power BLE Peripheral hosting GATT service 414D0001-... ("AM01") + L2CAP CoC server channel
 * with rotating 8-byte BlePrivacyBeacon advertisement data and E2EE ChaCha20-Poly1305 packet transport.
 */
class BlePeripheralManager(
    private val context: Context,
    private val beacon: BlePrivacyBeacon?,
    private val trustStore: TrustStore? = null,
    private val onInboundFrame: ((WireFrame) -> Unit)? = null,
    private val onConnectionChanged: ((Boolean) -> Unit)? = null
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val l2capExecutor = Executors.newCachedThreadPool()
    private val frameDecoder = FrameDecoder()
    private val reassembler = BluetoothPacketReassembler()
    private val replayGuard = ReplayGuard()
    private val packetSeq = AtomicLong(1L)
    private val json = Json { ignoreUnknownKeys = true }

    private var advertiseCallback: AdvertiseCallback? = null
    private var rotationFuture: ScheduledFuture<*>? = null
    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var l2capPsmCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null
    private var negotiatedMtu: Int = 23
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var l2capClientSocket: BluetoothSocket? = null

    @Volatile
    var isBatterySaverSuspended: Boolean = false
        private set

    val isClientConnected: Boolean
        get() = connectedDevice != null || l2capClientSocket?.isConnected == true

    fun setBatterySaverActive(active: Boolean) {
        isBatterySaverSuspended = active
        if (active) {
            stopAdvertising()
        } else {
            startAdvertisingAndGattServer()
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertisingAndGattServer() {
        if (isBatterySaverSuspended) return
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        ensureL2capServer(bluetoothManager)
        ensureGattServer(bluetoothManager)
        publishRotatingBeacon(bluetoothManager)

        rotationFuture?.cancel(false)
        rotationFuture = scheduler.scheduleAtFixedRate(
            {
                if (!isBatterySaverSuspended) {
                    publishRotatingBeacon(bluetoothManager)
                }
            },
            15L,
            15L,
            TimeUnit.MINUTES
        )
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        rotationFuture?.cancel(false)
        rotationFuture = null
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        advertiseCallback?.let { cb ->
            bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
        }
        advertiseCallback = null
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        stopAdvertising()
        runCatching { l2capClientSocket?.close() }
        runCatching { l2capServerSocket?.close() }
        l2capClientSocket = null
        l2capServerSocket = null
        gattServer?.close()
        gattServer = null
        scheduler.shutdownNow()
        l2capExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    fun sendControlFrame(frame: WireFrame): Boolean {
        val envelope = encodeOutgoingEnvelope(frame)

        // Prefer open L2CAP CoC stream when active
        val l2capSock = l2capClientSocket
        if (l2capSock != null && l2capSock.isConnected) {
            val sentOverL2cap = runCatching {
                val out = DataOutputStream(l2capSock.outputStream)
                synchronized(out) {
                    out.writeInt(envelope.size)
                    out.write(envelope)
                    out.flush()
                }
                true
            }.getOrDefault(false)
            if (sentOverL2cap) return true
        }

        val server = gattServer ?: return false
        val device = connectedDevice ?: return false
        val characteristic = notifyCharacteristic ?: return false
        val pid = packetSeq.getAndIncrement()
        for (packet in BluetoothPacketCodec.packetize(envelope, pid, negotiatedMtu)) {
            notifyBytes(server, device, characteristic, packet)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun notifyBytes(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun encodeOutgoingEnvelope(frame: WireFrame): ByteArray {
        val isHandshake = frame.messageType == WireMessageType.PAIR_HELLO ||
            frame.messageType == WireMessageType.PAIR_COMMIT ||
            frame.messageType == WireMessageType.PAIR_ACK
        val peer = trustStore?.allPeers()?.firstOrNull()
        if (!isHandshake && peer != null) {
            runCatching {
                return BluetoothSessionCipher(peer.bluetoothSessionKey, senderTag = "AND0").seal(frame)
            }
        }
        return FrameEncoder.encode(frame)
    }

    private fun decodeIncomingEnvelope(data: ByteArray): WireFrame? {
        if (BluetoothSessionCipher.isEncryptedEnvelope(data)) {
            val peers = trustStore?.allPeers().orEmpty()
            for (peer in peers) {
                val decoded = runCatching {
                    BluetoothSessionCipher(peer.bluetoothSessionKey).openFrame(data)
                }.getOrNull()
                if (decoded != null) return decoded
            }
            return null
        }
        return runCatching { WireFrame.decode(data) }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun ensureL2capServer(bluetoothManager: BluetoothManager) {
        if (l2capServerSocket != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val adapter = bluetoothManager.adapter ?: return
        runCatching {
            val server = adapter.listenUsingInsecureL2capChannel()
            l2capServerSocket = server
            l2capExecutor.submit {
                while (true) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    l2capClientSocket = sock
                    onConnectionChanged?.invoke(true)
                    l2capExecutor.submit { readL2capLoop(sock) }
                }
            }
        }
    }

    private fun readL2capLoop(socket: BluetoothSocket) {
        runCatching {
            val input = DataInputStream(socket.inputStream)
            while (socket.isConnected) {
                val len = input.readInt()
                if (len <= 0 || len > WireFrame.MAX_CONTROL_FRAME_BYTES + 128) break
                val buf = ByteArray(len)
                input.readFully(buf)
                val frame = decodeIncomingEnvelope(buf) ?: continue
                handleVerifiedInbound(frame)
            }
        }
        if (l2capClientSocket == socket) {
            l2capClientSocket = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishRotatingBeacon(bluetoothManager: BluetoothManager) {
        val privacyBeacon = beacon ?: return
        val advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser ?: return
        advertiseCallback?.let { advertiser.stopAdvertising(it) }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), privacyBeacon.token())
            .setIncludeDeviceName(false)
            .build()

        val callback = object : AdvertiseCallback() {}
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    private fun ensureGattServer(bluetoothManager: BluetoothManager) {
        if (gattServer != null) return
        val server = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    connectedDevice = device
                    onConnectionChanged?.invoke(true)
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED && connectedDevice?.address == device.address) {
                    connectedDevice = null
                    frameDecoder.reset()
                    reassembler.reset()
                    onConnectionChanged?.invoke(false)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                negotiatedMtu = mtu.coerceIn(23, 512)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == L2CAP_PSM_CHAR_UUID) {
                    val psm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        l2capServerSocket?.psm ?: 0
                    } else 0
                    val bytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(psm.toShort()).array()
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, bytes)
                } else {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (characteristic.uuid == CONTROL_WRITE_CHAR_UUID) {
                    try {
                        if (value.isNotEmpty() && value[0] == BluetoothPacketCodec.DATA_TAG) {
                            val result = reassembler.ingest(value)
                            result.ack?.let { ack ->
                                notifyCharacteristic?.let { nc ->
                                    gattServer?.let { server -> notifyBytes(server, device, nc, ack.encode()) }
                                }
                            }
                            result.completeEnvelope?.let { env ->
                                decodeIncomingEnvelope(env)?.let { handleVerifiedInbound(it) }
                            }
                        } else {
                            val single = decodeIncomingEnvelope(value)
                            if (single != null) {
                                handleVerifiedInbound(single)
                            } else {
                                frameDecoder.append(value)
                                for (frame in frameDecoder.drainFrames()) {
                                    handleVerifiedInbound(frame)
                                }
                            }
                        }
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                    } catch (_: Throwable) {
                        frameDecoder.reset()
                        reassembler.reset()
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                    }
                }
            }
        }) ?: return

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writeChar = BluetoothGattCharacteristic(
            CONTROL_WRITE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val notifyChar = BluetoothGattCharacteristic(
            CONTROL_NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        val psmChar = BluetoothGattCharacteristic(
            L2CAP_PSM_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)
        service.addCharacteristic(psmChar)
        server.addService(service)

        notifyCharacteristic = notifyChar
        l2capPsmCharacteristic = psmChar
        gattServer = server
    }

    private fun handleVerifiedInbound(frame: WireFrame) {
        val isHandshake = frame.messageType == WireMessageType.PAIR_HELLO ||
            frame.messageType == WireMessageType.PAIR_COMMIT ||
            frame.messageType == WireMessageType.PAIR_ACK
        if (!isHandshake) {
            replayGuard.validateAndAccept(frame.sequenceId)
        }
        if (frame.messageType == WireMessageType.DEVICE_COMMAND) {
            val cmd = runCatching {
                json.decodeFromString<DeviceCommandPayload>(frame.payload.decodeToString())
            }.getOrNull()
            if (cmd?.command == "btPing") {
                val pong = DeviceCommandPayload(command = "btPong", timestampMs = cmd.timestampMs)
                val pongFrame = WireFrame(
                    WireMessageType.DEVICE_COMMAND,
                    frame.sequenceId + 10_000_000L,
                    json.encodeToString(pong).toByteArray(Charsets.UTF_8)
                )
                sendControlFrame(pongFrame)
                return
            }
        }
        onInboundFrame?.invoke(frame)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("414D0001-0000-1000-8000-00805F9B34FB")
        val CONTROL_WRITE_CHAR_UUID: UUID = UUID.fromString("414D0002-0000-1000-8000-00805F9B34FB")
        val CONTROL_NOTIFY_CHAR_UUID: UUID = UUID.fromString("414D0003-0000-1000-8000-00805F9B34FB")
        val L2CAP_PSM_CHAR_UUID: UUID = UUID.fromString("414D0004-0000-1000-8000-00805F9B34FB")
    }
}
