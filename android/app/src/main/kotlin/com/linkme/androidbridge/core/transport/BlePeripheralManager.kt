package com.linkme.androidbridge.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import com.linkme.androidbridge.core.crypto.BlePrivacyBeacon
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.ReplayGuard
import com.linkme.androidbridge.core.protocol.WireFrame
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
 * Low-power BLE Peripheral hosting GATT service 414D0001-... ("AM01") with rotating 8-byte
 * BlePrivacyBeacon advertisement data and ControlWrite/ControlNotify characteristics.
 */
class BlePeripheralManager(
    private val context: Context,
    private val beacon: BlePrivacyBeacon?,
    private val onInboundFrame: ((WireFrame) -> Unit)? = null
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val frameDecoder = FrameDecoder()
    private val replayGuard = ReplayGuard()

    private var advertiseCallback: AdvertiseCallback? = null
    private var rotationFuture: ScheduledFuture<*>? = null
    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null
    private var negotiatedMtu: Int = 23

    @Volatile
    var isBatterySaverSuspended: Boolean = false
        private set

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
        gattServer?.close()
        gattServer = null
        scheduler.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    fun sendControlFrame(frame: WireFrame) {
        val server = gattServer ?: return
        val device = connectedDevice ?: return
        val characteristic = notifyCharacteristic ?: return
        val raw = FrameEncoder.encode(frame)
        for (chunk in BleFrameChunker.chunk(raw, negotiatedMtu)) {
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
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
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED && connectedDevice?.address == device.address) {
                    connectedDevice = null
                    frameDecoder.reset()
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                negotiatedMtu = mtu.coerceAtLeast(23)
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
                        frameDecoder.append(value)
                        for (frame in frameDecoder.drainFrames()) {
                            replayGuard.validateAndAccept(frame.sequenceId)
                            onInboundFrame?.invoke(frame)
                        }
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                    } catch (_: Throwable) {
                        frameDecoder.reset()
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
        )
        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)
        server.addService(service)

        notifyCharacteristic = notifyChar
        gattServer = server
    }

    companion object {
        // ASCII "AM" = 0x414D (valid RFC 4122 hex representing "0000AM01/02/03")
        val SERVICE_UUID: UUID = UUID.fromString("414D0001-0000-1000-8000-00805F9B34FB")
        val CONTROL_WRITE_CHAR_UUID: UUID = UUID.fromString("414D0002-0000-1000-8000-00805F9B34FB")
        val CONTROL_NOTIFY_CHAR_UUID: UUID = UUID.fromString("414D0003-0000-1000-8000-00805F9B34FB")
    }
}
