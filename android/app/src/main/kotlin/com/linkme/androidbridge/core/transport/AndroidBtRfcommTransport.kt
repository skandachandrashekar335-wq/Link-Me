package com.linkme.androidbridge.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Continuous Bluetooth Classic RFCOMM stream socket transport for Android.
 * Eliminates BLE GATT chunking, MTU fragmentation, and CCCD negotiation failures.
 */
class AndroidBtRfcommTransport(
    private val scope: CoroutineScope,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "LinkMeBridge"
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile
    private var activeSocket: BluetoothSocket? = null

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    private var serverJob: Job? = null
    private var readJob: Job? = null

    var onFrameReceived: ((WireFrame) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onDiagnostic: ((String) -> Unit)? = null

    val isAvailable: Boolean
        get() = bluetoothAdapter != null && bluetoothAdapter.isEnabled

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!isAvailable) return
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket?.close()
                val server = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                serverSocket = server
                onDiagnostic?.invoke("RFCOMM: Server listening for incoming connections")

                while (isActive) {
                    val socket = try {
                        server?.accept()
                    } catch (e: IOException) {
                        break
                    } ?: break

                    onDiagnostic?.invoke("RFCOMM: Incoming socket accepted from ${socket.remoteDevice?.address}")
                    attachSocket(socket)
                }
            } catch (e: Exception) {
                onDiagnostic?.invoke("RFCOMM: Server listen error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(macAddress: String, timeoutMs: Long = 10_000L): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            onDiagnostic?.invoke("RFCOMM: Bluetooth adapter unavailable or disabled")
            return@withContext false
        }
        val normalized = macAddress.replace("-", ":").uppercase().trim()
        val device: BluetoothDevice = try {
            bluetoothAdapter!!.getRemoteDevice(normalized)
        } catch (e: Exception) {
            onDiagnostic?.invoke("RFCOMM: Invalid device address $macAddress: ${e.message}")
            return@withContext false
        }

        runCatching { bluetoothAdapter.cancelDiscovery() }

        var socket: BluetoothSocket? = null
        try {
            onDiagnostic?.invoke("RFCOMM: Connecting to $normalized...")
            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            onDiagnostic?.invoke("RFCOMM: Connected successfully to $normalized")
            attachSocket(socket)
            return@withContext true
        } catch (e: Exception) {
            onDiagnostic?.invoke("RFCOMM: SPP connect failed, attempting fallback channel 1: ${e.message}")
            runCatching { socket?.close() }

            try {
                // Fallback via reflection to channel 1 directly
                val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                val fallbackSocket = method.invoke(device, 1) as BluetoothSocket
                fallbackSocket.connect()
                onDiagnostic?.invoke("RFCOMM: Fallback channel 1 connected to $normalized")
                attachSocket(fallbackSocket)
                return@withContext true
            } catch (fallbackError: Exception) {
                onDiagnostic?.invoke("RFCOMM: All connection attempts to $normalized failed: ${fallbackError.message}")
                runCatching { socket?.close() }
                return@withContext false
            }
        }
    }

    @Synchronized
    private fun attachSocket(socket: BluetoothSocket) {
        activeSocket?.let { runCatching { it.close() } }
        activeSocket = socket
        _isConnected.value = true
        onConnected?.invoke()

        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val decoder = FrameDecoder()
            val buffer = ByteArray(4096)
            try {
                val input = socket.inputStream
                while (isActive) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    decoder.append(buffer.copyOf(bytesRead))
                    while (true) {
                        val frame = try {
                            decoder.nextFrame()
                        } catch (e: Exception) {
                            onDiagnostic?.invoke("RFCOMM: Frame decode error: ${e.message}")
                            null
                        } ?: break
                        onFrameReceived?.invoke(frame)
                    }
                }
            } catch (e: IOException) {
                onDiagnostic?.invoke("RFCOMM: Socket read ended: ${e.message}")
            } finally {
                onSocketDisconnected()
            }
        }
    }

    @Synchronized
    fun sendFrame(frame: WireFrame): Boolean {
        val socket = activeSocket ?: return false
        if (!socket.isConnected) return false
        return try {
            val bytes = frame.encode()
            val output = socket.outputStream
            output.write(bytes)
            output.flush()
            true
        } catch (e: Exception) {
            onDiagnostic?.invoke("RFCOMM: sendFrame error: ${e.message}")
            false
        }
    }

    @Synchronized
    private fun onSocketDisconnected() {
        if (_isConnected.value) {
            _isConnected.value = false
            runCatching { activeSocket?.close() }
            activeSocket = null
            onDiagnostic?.invoke("RFCOMM: Disconnected")
            onDisconnected?.invoke()
        }
    }

    fun stop() {
        serverJob?.cancel()
        readJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { activeSocket?.close() }
        serverSocket = null
        activeSocket = null
        _isConnected.value = false
    }
}
