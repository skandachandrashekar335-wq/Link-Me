package com.linkme.androidbridge.core.transport

import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.ReplayGuard
import com.linkme.androidbridge.core.protocol.WireFrame
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

internal fun buildPinnedMtlsSslContext(
    identityManager: IdentityManager,
    trustStore: TrustStore,
    expectedPeerDeviceId: String
): SSLContext {
    val password = CharArray(0)
    val keyStore = identityManager.buildKeyStoreForTls(password)
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore, password)
    }

    val trustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = verify(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = verify(chain)
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun verify(chain: Array<X509Certificate>?) {
            val leaf = chain?.firstOrNull()
            if (leaf == null || !trustStore.verifyPeerCertificate(leaf.encoded, expectedPeerDeviceId)) {
                throw CertificateException("Unpinned peer certificate for $expectedPeerDeviceId")
            }
        }
    }

    return SSLContext.getInstance("TLSv1.3").apply {
        init(kmf.keyManagers, arrayOf<TrustManager>(trustManager), null)
    }
}

/**
 * Mutual TLS 1.3 Wi-Fi Server binding an ephemeral port and enforcing TrustStore certificate pinning.
 */
class WifiTlsServer(
    private val identityManager: IdentityManager,
    private val trustStore: TrustStore,
    private val expectedPeerDeviceId: String,
    private val onFrameReceived: ((WireFrame) -> Unit)? = null
) {
    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: SSLServerSocket? = null

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(port: Int = 0, bindAddress: InetAddress = InetAddress.getLoopbackAddress()): Int {
        val sslContext = buildPinnedMtlsSslContext(identityManager, trustStore, expectedPeerDeviceId)
        val server = sslContext.serverSocketFactory.createServerSocket(port, 16, bindAddress) as SSLServerSocket
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.needClientAuth = true
        serverSocket = server

        executor.submit {
            while (!server.isClosed) {
                val client = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                executor.submit { handleClient(client) }
            }
        }
        return server.localPort
    }

    private fun handleClient(socket: SSLSocket) {
        val decoder = FrameDecoder()
        val replayGuard = ReplayGuard()
        socket.use { tls ->
            tls.startHandshake()
            val input = tls.inputStream
            val buffer = ByteArray(16_384)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                decoder.append(buffer.copyOf(read))
                for (frame in decoder.drainFrames()) {
                    replayGuard.validateAndAccept(frame.sequenceId)
                    onFrameReceived?.invoke(frame)
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
    }
}
