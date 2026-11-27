package com.linkme.androidbridge.core.transport

import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.ReplayGuard
import com.linkme.androidbridge.core.protocol.WireFrame
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
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
    private val activeClients = CopyOnWriteArrayList<SSLSocket>()
    private val clientMonitor = Object()
    private var serverSocket: SSLServerSocket? = null

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    val hasActiveClients: Boolean
        get() = activeClients.any { !it.isClosed && it.isConnected }

    fun waitForClient(timeoutMs: Long = 2000L): Boolean {
        if (hasActiveClients) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(clientMonitor) {
            while (!hasActiveClients) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                runCatching { clientMonitor.wait(remaining) }
            }
        }
        return hasActiveClients
    }

    fun send(frame: WireFrame) {
        val client = activeClients.lastOrNull { !it.isClosed && it.isConnected }
            ?: error("No connected Wi-Fi TLS client")
        val encoded = FrameEncoder.encode(frame)
        synchronized(client) {
            val out = client.outputStream
            out.write(encoded)
            out.flush()
        }
    }

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
        try {
            socket.use { tls ->
                tls.startHandshake()
                activeClients.add(tls)
                synchronized(clientMonitor) { clientMonitor.notifyAll() }
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
        } finally {
            activeClients.remove(socket)
        }
    }

    fun stop() {
        activeClients.forEach { runCatching { it.close() } }
        activeClients.clear()
        synchronized(clientMonitor) { clientMonitor.notifyAll() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
    }

    companion object {
        fun localLanIPv4Address(): String? = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
    }
}
