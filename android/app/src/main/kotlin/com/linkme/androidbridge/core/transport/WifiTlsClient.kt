package com.linkme.androidbridge.core.transport

import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.WireFrame
import javax.net.ssl.SSLSocket

/**
 * Mutual TLS 1.3 Wi-Fi Client with strict TrustStore certificate pinning.
 */
class WifiTlsClient(
    private val identityManager: IdentityManager,
    private val trustStore: TrustStore,
    private val expectedPeerDeviceId: String
) {
    private var socket: SSLSocket? = null

    fun connect(host: String, port: Int) {
        val sslContext = buildPinnedMtlsSslContext(identityManager, trustStore, expectedPeerDeviceId)
        val client = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        client.enabledProtocols = arrayOf("TLSv1.3")
        client.startHandshake()
        socket = client
    }

    fun send(frame: WireFrame) {
        val active = socket ?: error("TLS socket is not connected")
        val out = active.outputStream
        out.write(FrameEncoder.encode(frame))
        out.flush()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
