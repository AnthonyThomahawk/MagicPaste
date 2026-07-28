package com.tonyt.magicpaste.tls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Exercises the TLS layer against a real socket on the JVM.
 *
 * Neither class under test touches an Android API — the whole point of putting
 * TLS termination in a plain `SSLServerSocket` was to avoid needing an engine or
 * a device for it — so a plain unit test can drive the genuine handshake and
 * relay rather than a mock of them.
 */
class TlsProxyTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a browser can complete a request through the proxy`() {
        val origin = FakeOrigin("hello over tls").apply { start() }
        val certificate = DeviceCertificate.loadOrCreate(folder.newFolder(), listOf(LOOPBACK))
        val proxy = TlsProxy(certificate, listenPort = freePort(), forwardToPort = origin.port)

        try {
            proxy.start()
            val connection = proxy.openHttps(certificate)

            assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
            assertEquals("hello over tls", connection.inputStream.bufferedReader().readText())
        } finally {
            proxy.stop()
            origin.stop()
        }
    }

    @Test
    fun `the fingerprint shown in the app is the one the client actually sees`() {
        val origin = FakeOrigin("ok").apply { start() }
        val certificate = DeviceCertificate.loadOrCreate(folder.newFolder(), listOf(LOOPBACK))
        val proxy = TlsProxy(certificate, listenPort = freePort(), forwardToPort = origin.port)

        try {
            proxy.start()
            val connection = proxy.openHttps(certificate)
            connection.inputStream.readBytes()

            // What the visitor would compare against what the app displays. If
            // these ever diverge the fingerprint UI is worse than useless, so it
            // is checked here rather than assumed.
            val served = connection.serverCertificates.first() as X509Certificate
            val seenByClient = MessageDigest.getInstance("SHA-256").digest(served.encoded)

            assertArrayEquals(certificate.fingerprint.bytes, seenByClient)
        } finally {
            proxy.stop()
            origin.stop()
        }
    }

    @Test
    fun `a plain http request is redirected to https instead of dropped`() {
        val origin = FakeOrigin("should not be reached").apply { start() }
        val certificate = DeviceCertificate.loadOrCreate(folder.newFolder(), listOf(LOOPBACK))
        val port = freePort()
        val proxy = TlsProxy(certificate, listenPort = port, forwardToPort = origin.port)

        try {
            proxy.start()
            // Exactly what a browser sends when you type "192.168.1.42:8123" and
            // it defaults to http:// — previously a failed handshake and
            // ERR_EMPTY_RESPONSE.
            val response = plainRequest(port, "GET /files?at=%2FDCIM HTTP/1.1\r\nHost: $LOOPBACK:$port\r\n\r\n")

            assertTrue("expected a redirect, got: ${response.take(80)}", response.startsWith("HTTP/1.1 307"))
            assertTrue(
                "expected an https Location, got: ${response.take(200)}",
                response.contains("Location: https://$LOOPBACK:$port/files?at=%2FDCIM"),
            )
            // A cached permanent redirect would strand the user if TLS is later
            // switched off, so it must not be one.
            assertTrue("redirect is cacheable", response.contains("Cache-Control: no-store"))
        } finally {
            proxy.stop()
            origin.stop()
        }
    }

    @Test
    fun `the redirect falls back to the local address when there is no Host header`() {
        val origin = FakeOrigin("unused").apply { start() }
        val certificate = DeviceCertificate.loadOrCreate(folder.newFolder(), listOf(LOOPBACK))
        val port = freePort()
        val proxy = TlsProxy(certificate, listenPort = port, forwardToPort = origin.port)

        try {
            proxy.start()
            val response = plainRequest(port, "GET / HTTP/1.0\r\n\r\n")

            assertTrue(
                "expected a usable Location, got: ${response.take(200)}",
                response.contains("Location: https://$LOOPBACK:$port/"),
            )
        } finally {
            proxy.stop()
            origin.stop()
        }
    }

    @Test
    fun `sniffing the first byte does not disturb the tls handshake`() {
        // The byte peeked to tell HTTP from TLS is handed back to the SSL layer;
        // if that were wrong, every handshake would fail. Covered by the request
        // test above too, but stated here as its own claim.
        val origin = FakeOrigin("still works").apply { start() }
        val certificate = DeviceCertificate.loadOrCreate(folder.newFolder(), listOf(LOOPBACK))
        val proxy = TlsProxy(certificate, listenPort = freePort(), forwardToPort = origin.port)

        try {
            proxy.start()
            repeat(3) {
                val connection = proxy.openHttps(certificate)
                assertEquals("still works", connection.inputStream.bufferedReader().readText())
            }
        } finally {
            proxy.stop()
            origin.stop()
        }
    }

    @Test
    fun `the fingerprint is rendered in the shapes the UI needs`() {
        val certificate = DeviceCertificate.loadOrCreate(folder.newFolder(), listOf(LOOPBACK))
        val fingerprint = certificate.fingerprint

        assertEquals(32, fingerprint.bytes.size)
        assertEquals(8, fingerprint.head.length)
        assertEquals(8, fingerprint.tail.length)
        // The full form has to line up with how browsers present it, colon-separated.
        assertEquals(fingerprint.bytes.size * 3 - 1, fingerprint.full.length)
        assertTrue(fingerprint.full.startsWith(fingerprint.head.chunked(2).joinToString(":")))
        assertTrue(fingerprint.grouped.replace(" ", "").endsWith(fingerprint.tail))
    }

    @Test
    fun `the certificate is reused across restarts, so the fingerprint stays stable`() {
        val directory = folder.newFolder()

        val first = DeviceCertificate.loadOrCreate(directory, listOf(LOOPBACK))
        val second = DeviceCertificate.loadOrCreate(directory, listOf(LOOPBACK))

        assertArrayEquals(first.fingerprint.bytes, second.fingerprint.bytes)
    }

    @Test
    fun `a new address means a new certificate, so the SAN keeps matching`() {
        val directory = folder.newFolder()

        val onOneNetwork = DeviceCertificate.loadOrCreate(directory, listOf(LOOPBACK))
        val afterDhcpMovedUs = DeviceCertificate.loadOrCreate(directory, listOf(LOOPBACK, "10.0.0.7"))

        assertNotEquals(
            onOneNetwork.fingerprint.full,
            afterDhcpMovedUs.fingerprint.full,
        )
        assertTrue("10.0.0.7" in afterDhcpMovedUs.addresses)
    }

    /** Opens an HTTPS connection that trusts exactly this certificate. */
    private fun TlsProxy.openHttps(certificate: DeviceCertificate): HttpsURLConnection {
        val url = URL("https://$LOOPBACK:$listenPort/")
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = trustOnly(certificate.fingerprint.bytes)
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TIMEOUT_MILLIS = 10_000

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /** Sends a raw HTTP request to a port and returns whatever comes back. */
        fun plainRequest(port: Int, request: String): String =
            java.net.Socket(LOOPBACK, port).use { socket ->
                socket.soTimeout = TIMEOUT_MILLIS
                socket.getOutputStream().apply {
                    write(request.toByteArray())
                    flush()
                }
                socket.getInputStream().readBytes().decodeToString()
            }

        /**
         * A trust manager that accepts one certificate by digest — the programmatic
         * equivalent of a user who checked the fingerprint.
         */
        fun trustOnly(expected: ByteArray): SSLSocketFactory {
            val manager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)
                    check(digest.contentEquals(expected)) { "unexpected certificate" }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS")
                .apply { init(null, arrayOf(manager), null) }
                .socketFactory
        }
    }
}

/** A minimal HTTP origin, standing in for the Ktor server on loopback. */
private class FakeOrigin(private val body: String) {

    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort

    fun start() {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    client.use {
                        // Read the request line and headers, then answer.
                        val reader = it.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: return@use
                            if (line.isEmpty()) break
                        }
                        it.getOutputStream().apply {
                            write(
                                buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Length: ${body.toByteArray().size}\r\n")
                                    append("Connection: close\r\n\r\n")
                                    append(body)
                                }.toByteArray()
                            )
                            flush()
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { socket.close() }
    }
}
