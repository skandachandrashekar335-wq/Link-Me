package com.linkme.androidbridge.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.linkme.androidbridge.core.crypto.BluetoothSessionCipher
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serialized BLE GATT Client + L2CAP CoC client connecting to MacBridge (`414D0001-...`).
 * Enforces:
 * 1. `requestMtu(512)` -> `onMtuChanged` -> `discoverServices()` -> CCCD -> PSM read -> Ready.
 * 2. All outbound GATT characteristic writes strictly serialized via a single writer queue
 *    awaiting `onCharacteristicWrite` (2000ms watchdog timeout) so Samsung/Android stacks never drop packets.
 * 3. `SCAN_MODE_LOW_LATENCY` with unfiltered UUID scan-record fallback if hardware filtering misses Mac.
 */
class BleGattClient(
    private val context: Context,
    private val trustStore: TrustStore,
    private val onInboundFrame: ((WireFrame) -> Unit)? = null,
    private val onConnectionChanged: ((Boolean) -> Unit)? = null,
    private val onLatencyMeasured: ((Int) -> Unit)? = null
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val l2capExecutor = Executors.newCachedThreadPool()
    private val reassembler = BluetoothPacketReassembler()
    private val replayGuard = ReplayGuard()
    private val packetSeq = AtomicLong(1L)
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingHandshakeQueue = ConcurrentLinkedQueue<WireFrame>()
    private val ackWaiters = ConcurrentHashMap<Long, CountDownLatch>()

    @Volatile private var activeWriteLatch: CountDownLatch? = null
    @Volatile private var activeWriteStatus: Int = BluetoothGatt.GATT_FAILURE
    private val servicesDiscovered = AtomicBoolean(false)
    private var scanFallbackFuture: ScheduledFuture<*>? = null
    private var mtuFallbackFuture: ScheduledFuture<*>? = null

    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var rxWriteChar: BluetoothGattCharacteristic? = null
    private var txNotifyChar: BluetoothGattCharacteristic? = null
    private var l2capPsmChar: BluetoothGattCharacteristic? = null
    private var l2capSocket: BluetoothSocket? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var lastPingSentMs: Long = 0L

    @Volatile
    private var negotiatedMtu: Int = 23

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isGattReady: Boolean = false
        private set

    val currentNegotiatedMtu: Int
        get() = negotiatedMtu

    @SuppressLint("MissingPermission")
    fun sendPairingHelloOverBle(pairHelloFrame: WireFrame) = sendPairingFrameOverBle(pairHelloFrame)

    @SuppressLint("MissingPermission")
    fun sendPairingFrameOverBle(frame: WireFrame) {
        if (isConnected && isGattReady && rxWriteChar != null) {
            if (sendFrame(frame)) return
        }
        pendingHandshakeQueue.offer(frame)
        if (!isConnected) {
            startScanning(autoReconnect = false)
        } else if (isGattReady && rxWriteChar != null) {
            scheduler.execute { flushPendingHandshake() }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning(autoReconnect: Boolean = true) {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val scanner = manager.adapter?.bluetoothLeScanner ?: return
        stopScanning()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BlePeripheralManager.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                val matchesUuid = uuids.any { it.uuid == BlePeripheralManager.SERVICE_UUID }
                if (!matchesUuid) return
                stopScanning()
                connectToDevice(result.device, autoConnect = autoReconnect)
            }
        }
        scanCallback = cb
        scanner.startScan(listOf(filter), settings, cb)

        // Fallback unfiltered scan in case OEM controller misses 128-bit UUID hardware filter
        scanFallbackFuture = scheduler.schedule(
            {
                if (!isConnected && scanCallback === cb) {
                    runCatching { scanner.stopScan(cb) }
                    val fallbackCb = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            val uuids = result.scanRecord?.serviceUuids.orEmpty()
                            if (uuids.any { it.uuid == BlePeripheralManager.SERVICE_UUID }) {
                                stopScanning()
                                connectToDevice(result.device, autoConnect = autoReconnect)
                            }
                        }
                    }
                    scanCallback = fallbackCb
                    runCatching { scanner.startScan(null, settings, fallbackCb) }
                }
            },
            2500L,
            TimeUnit.MILLISECONDS
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        scanFallbackFuture?.cancel(false)
        scanFallbackFuture = null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        scanCallback?.let { cb ->
            runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, autoConnect: Boolean = true) {
        isGattReady = false
        servicesDiscovered.set(false)
        gatt?.close()
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun sendFrame(frame: WireFrame): Boolean {
        val isHandshake = frame.messageType == WireMessageType.PAIR_HELLO ||
            frame.messageType == WireMessageType.PAIR_COMMIT ||
            frame.messageType == WireMessageType.PAIR_ACK
        val activePeer = trustStore.allPeers().firstOrNull()
        val envelope = if (!isHandshake && activePeer != null) {
            runCatching {
                BluetoothSessionCipher(activePeer.bluetoothSessionKey, senderTag = "AND0").seal(frame)
            }.getOrElse { FrameEncoder.encode(frame) }
        } else {
            FrameEncoder.encode(frame)
        }

        val sock = l2capSocket
        if (sock != null && sock.isConnected) {
            val l2capOk = runCatching {
                val out = DataOutputStream(sock.outputStream)
                synchronized(out) {
                    out.writeInt(envelope.size)
                    out.write(envelope)
                    out.flush()
                }
                true
            }.getOrDefault(false)
            if (l2capOk) return true
        }

        val client = gatt ?: return false
        val char = rxWriteChar ?: return false
        val pid = packetSeq.getAndIncrement()
        val packets = BluetoothPacketCodec.packetize(envelope, pid, negotiatedMtu)

        // Dispatch strictly serialized via single writer executor awaiting onCharacteristicWrite
        val job = writeExecutor.submit<Boolean> {
            val ackLatch = if (packets.size > 1) CountDownLatch(1).also { ackWaiters[pid] = it } else null
            var allOk = true
            for (pkt in packets) {
                if (!writePacketAndAwaitCallback(client, char, pkt)) {
                    allOk = false
                    break
                }
            }
            if (allOk && ackLatch != null) {
                runCatching { ackLatch.await(1500L, TimeUnit.MILLISECONDS) }
            }
            ackWaiters.remove(pid)
            allOk
        }
        return runCatching { job.get(30L, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun writePacketAndAwaitCallback(
        client: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val latch = CountDownLatch(1)
        activeWriteStatus = BluetoothGatt.GATT_FAILURE
        activeWriteLatch = latch

        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeCharacteristic(char, data, writeType) == 0
        } else {
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            client.writeCharacteristic(char)
        }
        if (!queued) {
            activeWriteLatch = null
            return false
        }
        val completed = runCatching { latch.await(2000L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        activeWriteLatch = null
        return completed && activeWriteStatus == BluetoothGatt.GATT_SUCCESS
    }

    private fun flushPendingHandshake() {
        while (true) {
            val next = pendingHandshakeQueue.peek() ?: break
            if (sendFrame(next)) {
                pendingHandshakeQueue.poll()
            } else {
                break
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            {
                if (!isConnected || !isGattReady) return@scheduleAtFixedRate
                val nowMs = System.currentTimeMillis()
                lastPingSentMs = nowMs
                val cmd = DeviceCommandPayload(command = "btPing", timestampMs = nowMs)
                val bytes = json.encodeToString(cmd).toByteArray(Charsets.UTF_8)
                sendFrame(WireFrame(WireMessageType.DEVICE_COMMAND, nowMs, bytes))
            },
            5L,
            5L,
            TimeUnit.SECONDS
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                isGattReady = false
                servicesDiscovered.set(false)
                onConnectionChanged?.invoke(true)

                // Negotiate MTU=512 BEFORE discovering services
                val mtuRequested = runCatching { gatt.requestMtu(512) }.getOrDefault(false)
                mtuFallbackFuture?.cancel(false)
                if (!mtuRequested) {
                    if (servicesDiscovered.compareAndSet(false, true)) {
                        gatt.discoverServices()
                    }
                } else {
                    mtuFallbackFuture = scheduler.schedule(
                        {
                            if (isConnected && servicesDiscovered.compareAndSet(false, true)) {
                                gatt.discoverServices()
                            }
                        },
                        1200L,
                        TimeUnit.MILLISECONDS
                    )
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                mtuFallbackFuture?.cancel(false)
                isConnected = false
                isGattReady = false
                servicesDiscovered.set(false)
                rxWriteChar = null
                txNotifyChar = null
                l2capPsmChar = null
                activeWriteLatch?.countDown()
                activeWriteLatch = null
                reassembler.reset()
                heartbeatFuture?.cancel(false)
                onConnectionChanged?.invoke(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuFallbackFuture?.cancel(false)
            negotiatedMtu = mtu.coerceIn(23, 512)
            if (servicesDiscovered.compareAndSet(false, true)) {
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(BlePeripheralManager.SERVICE_UUID) ?: return
            rxWriteChar = service.getCharacteristic(BlePeripheralManager.CONTROL_WRITE_CHAR_UUID)
            txNotifyChar = service.getCharacteristic(BlePeripheralManager.CONTROL_NOTIFY_CHAR_UUID)
            l2capPsmChar = service.getCharacteristic(BlePeripheralManager.L2CAP_PSM_CHAR_UUID)

            val notifyChar = txNotifyChar
            if (notifyChar != null) {
                gatt.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (cccd != null) {
                    val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                    if (queued) return
                }
            }
            readPsmOrMarkReady(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            readPsmOrMarkReady(gatt)
        }

        @SuppressLint("MissingPermission")
        private fun readPsmOrMarkReady(gatt: BluetoothGatt) {
            val psmChar = l2capPsmChar
            if (psmChar != null && gatt.readCharacteristic(psmChar)) {
                return
            }
            markReadyAndFlush()
        }

        private fun markReadyAndFlush() {
            isGattReady = true
            startHeartbeat()
            scheduler.execute { flushPendingHandshake() }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handlePsmRead(gatt.device, characteristic, value)
            }
            markReadyAndFlush()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val bytes = characteristic.value
            if (status == BluetoothGatt.GATT_SUCCESS && bytes != null) {
                handlePsmRead(gatt.device, characteristic, bytes)
            }
            markReadyAndFlush()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            activeWriteStatus = status
            activeWriteLatch?.countDown()
        }

        @SuppressLint("MissingPermission")
        private fun handlePsmRead(device: BluetoothDevice, char: BluetoothGattCharacteristic, value: ByteArray) {
            if (char.uuid != BlePeripheralManager.L2CAP_PSM_CHAR_UUID || value.size != 2) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val psm = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            if (psm <= 0) return
            l2capExecutor.submit {
                runCatching {
                    val sock = device.createInsecureL2capChannel(psm)
                    sock.connect()
                    l2capSocket = sock
                    val input = DataInputStream(sock.inputStream)
                    while (sock.isConnected) {
                        val len = input.readInt()
                        if (len <= 0 || len > WireFrame.MAX_CONTROL_FRAME_BYTES + 128) break
                        val buf = ByteArray(len)
                        input.readFully(buf)
                        decodeAndDeliver(buf)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotifyValue(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleNotifyValue(value)
        }

        private fun handleNotifyValue(value: ByteArray) {
            if (value.isEmpty()) return
            if (value[0] == BluetoothPacketCodec.ACK_TAG) {
                BluetoothPacketCodec.AckPacket.decode(value)?.let { ack ->
                    ackWaiters[ack.packetId]?.countDown()
                }
                return
            }
            if (value[0] == BluetoothPacketCodec.DATA_TAG) {
                val res = reassembler.ingest(value)
                res.completeEnvelope?.let { decodeAndDeliver(it) }
            } else {
                decodeAndDeliver(value)
            }
        }
    }

    private fun decodeAndDeliver(envelope: ByteArray) {
        val frame = if (BluetoothSessionCipher.isEncryptedEnvelope(envelope)) {
            trustStore.allPeers().firstNotNullOfOrNull { peer ->
                runCatching { BluetoothSessionCipher(peer.bluetoothSessionKey).openFrame(envelope) }.getOrNull()
            }
        } else {
            runCatching { WireFrame.decode(envelope) }.getOrNull()
        } ?: return

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
            if (cmd?.command == "btPong" && lastPingSentMs > 0L) {
                val rtt = (System.currentTimeMillis() - lastPingSentMs).toInt().coerceAtLeast(1)
                onLatencyMeasured?.invoke(rtt)
                return
            }
        }
        onInboundFrame?.invoke(frame)
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        stopScanning()
        mtuFallbackFuture?.cancel(false)
        heartbeatFuture?.cancel(false)
        activeWriteLatch?.countDown()
        runCatching { l2capSocket?.close() }
        runCatching { gatt?.close() }
        l2capSocket = null
        gatt = null
        writeExecutor.shutdownNow()
        scheduler.shutdownNow()
        l2capExecutor.shutdownNow()
    }
}
