package com.linkme.androidbridge.core.transport

import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.ReplayGuard
import com.linkme.androidbridge.core.protocol.WireFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket

/**
 * Mutual TLS 1.3 Wi-Fi Client with strict TrustStore certificate pinning and bidirectional frame receive loop.
 */
class WifiTlsClient(
    private val identityManager: IdentityManager,
    private val trustStore: TrustStore,
    private val expectedPeerDeviceId: String,
    private val onFrameReceived: ((WireFrame) -> Unit)? = null
) {
    private var socket: SSLSocket? = null
    private var rxExecutor: ExecutorService? = null

    fun connect(host: String, port: Int) {
        val sslContext = buildPinnedMtlsSslContext(identityManager, trustStore, expectedPeerDeviceId)
        val client = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        client.enabledProtocols = arrayOf("TLSv1.3")
        client.startHandshake()
        socket = client

        if (onFrameReceived != null) {
            val exec = Executors.newSingleThreadExecutor()
            rxExecutor = exec
            exec.submit {
                runCatching {
                    val decoder = FrameDecoder()
                    val replayGuard = ReplayGuard()
                    val input = client.inputStream
                    val buffer = ByteArray(16_384)
                    while (!client.isClosed) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        decoder.append(buffer.copyOf(read))
                        for (frame in decoder.drainFrames()) {
                            replayGuard.validateAndAccept(frame.sequenceId)
                            onFrameReceived.invoke(frame)
                        }
                    }
                }
            }
        }
    }

    fun send(frame: WireFrame) {
        val active = socket ?: error("TLS socket is not connected")
        val encoded = FrameEncoder.encode(frame)
        synchronized(active) {
            val out = active.outputStream
            out.write(encoded)
            out.flush()
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        rxExecutor?.shutdownNow()
        rxExecutor = null
    }
}
