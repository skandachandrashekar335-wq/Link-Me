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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class BleDiagnosticSnapshot(
    val bleRadioState: String = "Unknown",
    val bleLinkState: String = "Idle",
    val events: List<String> = emptyList()
)

/**
 * Serialized BLE GATT Client + L2CAP CoC client connecting to MacBridge (`414D0001-...`).
 * Enforces:
 * 1. `requestMtu(512)` -> `onMtuChanged` -> `discoverServices()` -> explicit `setCharacteristicNotification`
 *    + `00002902-...` CCCD `ENABLE_NOTIFICATION_VALUE` write -> `onDescriptorWrite(GATT_SUCCESS)` -> PSM read -> Ready.
 * 2. All outbound GATT characteristic writes (`PAIR_COMMIT`, etc.) blocked/queued behind `onDescriptorWrite`
 *    `GATT_SUCCESS` and strictly serialized via a single writer executor awaiting `onCharacteristicWrite`.
 * 3. `SCAN_MODE_LOW_LATENCY` with unfiltered UUID scan-record fallback + live diagnostic StateFlows.
 */
class BleGattClient(
    private val context: Context,
    private val trustStore: TrustStore,
    private val onInboundFrame: ((WireFrame) -> Unit)? = null,
    private val onConnectionChanged: ((Boolean) -> Unit)? = null,
    private val onLatencyMeasured: ((Int) -> Unit)? = null,
    private val onDiagnosticEvent: ((String) -> Unit)? = null
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
    private val diagLock = Any()

    private val _diagnostics = MutableStateFlow(BleDiagnosticSnapshot(bleRadioState = readAdapterStateLabel()))
    val diagnostics: StateFlow<BleDiagnosticSnapshot> = _diagnostics.asStateFlow()

    private val _diagnosticEvents = MutableStateFlow<List<String>>(emptyList())
    val diagnosticEvents: StateFlow<List<String>> = _diagnosticEvents.asStateFlow()

    @Volatile private var activeWriteLatch: CountDownLatch? = null
    @Volatile private var activeWriteStatus: Int = BluetoothGatt.GATT_FAILURE
    @Volatile private var cccdEnabled: Boolean = false
    @Volatile private var cccdWaitLatch: CountDownLatch? = null
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

    val isCccdReady: Boolean
        get() = cccdEnabled

    val currentNegotiatedMtu: Int
        get() = negotiatedMtu

    private fun readAdapterStateLabel(): String {
        return runCatching {
            val mgr = context.getSystemService(BluetoothManager::class.java)
            val adapter = mgr?.adapter ?: return@runCatching "Unavailable"
            when (adapter.state) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> "PoweredOn"
                android.bluetooth.BluetoothAdapter.STATE_OFF -> "Off"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_ON,
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> "Transitioning"
                else -> "Unknown"
            }
        }.getOrElse { "Unauthorized" }
    }

    fun refreshRadioState(): String {
        val radio = readAdapterStateLabel()
        synchronized(diagLock) {
            _diagnostics.value = _diagnostics.value.copy(bleRadioState = radio)
        }
        return radio
    }

    private fun recordDiagnostic(
        event: String,
        linkState: String? = null,
        radioState: String? = null
    ) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $event"
        synchronized(diagLock) {
            val updatedEvents = (listOf(line) + _diagnosticEvents.value).take(40)
            _diagnosticEvents.value = updatedEvents
            val cur = _diagnostics.value
            _diagnostics.value = cur.copy(
                bleRadioState = radioState ?: cur.bleRadioState,
                bleLinkState = linkState ?: cur.bleLinkState,
                events = updatedEvents
            )
        }
        onDiagnosticEvent?.invoke(line)
    }

    @SuppressLint("MissingPermission")
    fun sendPairingHelloOverBle(pairHelloFrame: WireFrame) = sendPairingFrameOverBle(pairHelloFrame)

    @SuppressLint("MissingPermission")
    fun sendPairingFrameOverBle(frame: WireFrame) {
        if (isConnected && cccdEnabled && isGattReady && rxWriteChar != null) {
            if (sendFrame(frame)) return
        }
        pendingHandshakeQueue.offer(frame)
        if (!isConnected) {
            startScanning(autoReconnect = false)
        } else if (cccdEnabled && isGattReady && rxWriteChar != null) {
            scheduler.execute { flushPendingHandshake() }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning(autoReconnect: Boolean = true) {
        val radio = refreshRadioState()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            recordDiagnostic("GATT_ERROR(scanner_unavailable:$radio)", linkState = "Radio $radio", radioState = radio)
            return
        }
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
                recordDiagnostic("DEVICE_FOUND(rssi=${result.rssi}, addr=${result.device.address})")
                stopScanning()
                connectToDevice(result.device, autoConnect = autoReconnect)
            }

            override fun onScanFailed(errorCode: Int) {
                recordDiagnostic("GATT_ERROR(scan_failed=$errorCode)", linkState = "Error (Scan=$errorCode)")
            }
        }
        scanCallback = cb
        recordDiagnostic("SCAN_START(filter=414D0001)", linkState = "Scanning", radioState = radio)
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
                                recordDiagnostic("DEVICE_FOUND(rssi=${result.rssi}, addr=${result.device.address}, fallback=true)")
                                stopScanning()
                                connectToDevice(result.device, autoConnect = autoReconnect)
                            }
                        }

                        override fun onScanFailed(errorCode: Int) {
                            recordDiagnostic("GATT_ERROR(fallback_scan=$errorCode)", linkState = "Error (Scan=$errorCode)")
                        }
                    }
                    scanCallback = fallbackCb
                    recordDiagnostic("SCAN_START(unfiltered_fallback)", linkState = "Scanning (Fallback)")
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
        cccdEnabled = false
        cccdWaitLatch = CountDownLatch(1)
        isGattReady = false
        servicesDiscovered.set(false)
        recordDiagnostic("GATT_CONNECTING(addr=${device.address})", linkState = "Connecting")
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
                recordDiagnostic("TX_BYTES(${envelope.size}, via=L2CAP)")
                true
            }.getOrDefault(false)
            if (l2capOk) return true
        }

        val client = gatt ?: return false
        val pid = packetSeq.getAndIncrement()
        val packets = BluetoothPacketCodec.packetize(envelope, pid, negotiatedMtu)

        // Block strictly until CCCD onDescriptorWrite(GATT_SUCCESS) finishes, then serialize via single writer
        val job = writeExecutor.submit<Boolean> {
            if (!cccdEnabled) {
                runCatching { cccdWaitLatch?.await(3000L, TimeUnit.MILLISECONDS) }
            }
            val char = rxWriteChar
            if (!cccdEnabled || !isGattReady || char == null) {
                recordDiagnostic("GATT_ERROR(write_blocked_cccd=${cccdEnabled}_ready=${isGattReady})")
                return@submit false
            }
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
            recordDiagnostic("GATT_ERROR(tx_queue_rejected)")
            return false
        }
        val completed = runCatching { latch.await(2000L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        activeWriteLatch = null
        val ok = completed && activeWriteStatus == BluetoothGatt.GATT_SUCCESS
        if (ok) {
            recordDiagnostic("TX_BYTES(${data.size})")
        } else {
            recordDiagnostic("GATT_ERROR(tx_status=${if (completed) activeWriteStatus else -1})")
        }
        return ok
    }

    private fun flushPendingHandshake() {
        if (!cccdEnabled || !isGattReady) return
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
                if (!isConnected || !isGattReady || !cccdEnabled) return@scheduleAtFixedRate
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
                cccdEnabled = false
                cccdWaitLatch = CountDownLatch(1)
                isGattReady = false
                servicesDiscovered.set(false)
                recordDiagnostic("GATT_CONNECTED", linkState = "GATT Connected", radioState = refreshRadioState())
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
                cccdEnabled = false
                cccdWaitLatch?.countDown()
                isGattReady = false
                servicesDiscovered.set(false)
                rxWriteChar = null
                txNotifyChar = null
                l2capPsmChar = null
                activeWriteLatch?.countDown()
                activeWriteLatch = null
                reassembler.reset()
                heartbeatFuture?.cancel(false)
                val linkDesc = if (status == BluetoothGatt.GATT_SUCCESS) "Disconnected" else "Error (status=$status)"
                recordDiagnostic("GATT_ERROR(status=$status, state=$newState)", linkState = linkDesc, radioState = refreshRadioState())
                onConnectionChanged?.invoke(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuFallbackFuture?.cancel(false)
            negotiatedMtu = mtu.coerceIn(23, 512)
            recordDiagnostic("MTU_NEGOTIATED(mtu=$negotiatedMtu, status=$status)")
            if (servicesDiscovered.compareAndSet(false, true)) {
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                recordDiagnostic("GATT_ERROR(discover=$status)", linkState = "Error (Discover=$status)")
                return
            }
            val service = gatt.getService(BlePeripheralManager.SERVICE_UUID)
            if (service == null) {
                recordDiagnostic("GATT_ERROR(service_414D0001_missing)", linkState = "Error (Missing Service)")
                return
            }
            rxWriteChar = service.getCharacteristic(BlePeripheralManager.CONTROL_WRITE_CHAR_UUID)
            txNotifyChar = service.getCharacteristic(BlePeripheralManager.CONTROL_NOTIFY_CHAR_UUID)
            l2capPsmChar = service.getCharacteristic(BlePeripheralManager.L2CAP_PSM_CHAR_UUID)
            recordDiagnostic("SERVICES_DISCOVERED")

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
                    if (!queued) {
                        recordDiagnostic("GATT_ERROR(cccd_write_queue_failed)", linkState = "Error (CCCD Queue Failed)")
                    }
                    return
                }
                recordDiagnostic("GATT_ERROR(cccd_descriptor_missing)", linkState = "Error (CCCD Missing)")
                return
            }
            recordDiagnostic("GATT_ERROR(tx_notify_char_missing)", linkState = "Error (Missing TX Char)")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cccdEnabled = false
                cccdWaitLatch?.countDown()
                recordDiagnostic("GATT_ERROR(cccd_status=$status)", linkState = "Error (CCCD=$status)")
                return
            }
            cccdEnabled = true
            cccdWaitLatch?.countDown()
            recordDiagnostic("CCCD_ENABLED", linkState = "GATT Connected + CCCD Ready")
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
            if (!cccdEnabled) return
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
                    recordDiagnostic("L2CAP_CONNECTED(psm=$psm)")
                    val input = DataInputStream(sock.inputStream)
                    while (sock.isConnected) {
                        val len = input.readInt()
                        if (len <= 0 || len > WireFrame.MAX_CONTROL_FRAME_BYTES + 128) break
                        val buf = ByteArray(len)
                        input.readFully(buf)
                        recordDiagnostic("RX_BYTES($len, via=L2CAP)")
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
            recordDiagnostic("RX_BYTES(${value.size})")
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
