package com.tonyt.magicpaste.tls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLServerSocket

/**
 * Terminates TLS in front of the plain HTTP server, and points plaintext callers
 * at the encrypted address instead of dropping them.
 *
 * Ktor's CIO engine has no TLS support — the jar contains no SSL classes at all —
 * and the alternatives that do (Netty) are a poor fit for Android. So the HTTP
 * server binds to loopback where nothing else can reach it, and this stands in
 * front of it.
 *
 * There are four sockets involved:
 *
 * ```
 *  browser ──► [listenPort]  sniffs the first byte
 *                 │
 *                 ├─ 0x16 ──► [loopback TLS]  handshake, decrypt ──► [http server]
 *                 └─ "GET" ─► 307 to https://…
 *
 *  browser ──► [port 80]  ─► 307 to https://…
 * ```
 *
 * The port-80 socket exists for the bare name: typing `magicpaste.local` into a
 * browser means `http://magicpaste.local` means port 80, whatever port the proxy
 * listens on. Without a catcher there, the friendly URL dies with a connection
 * refused before the sniffing port ever sees a byte.
 *
 * The detour through a loopback TLS socket exists because the JSSE call that
 * layers TLS onto an already-accepted socket while replaying peeked bytes —
 * `createSocket(Socket, InputStream, boolean)` — is in `android.jar` but not in
 * the `javax.*` stubs this module compiles against, so neither Kotlin nor javac
 * will resolve it. Relaying into a real `SSLServerSocket` needs only long-stable
 * API, at the cost of one extra copy per chunk. The handshake still happens end
 * to end with the browser; this only moves bytes.
 */
class TlsProxy(
    private val certificate: DeviceCertificate,
    val listenPort: Int,
    private val forwardToPort: Int,
    /**
     * Where plain-HTTP strays are caught and redirected — port 80 in real use,
     * an ephemeral port in tests, null when [listenPort] already covers it.
     */
    private val plainHttpPort: Int? = HTTP_DEFAULT_PORT.takeIf { it != listenPort },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var publicSocket: ServerSocket? = null
    private var catchSocket: ServerSocket? = null
    private var tlsSocket: SSLServerSocket? = null

    /** Binds the port. Throws if it is taken, matching the plain server's behaviour. */
    fun start() {
        check(publicSocket == null) { "Already listening on $listenPort" }

        // Bound to loopback: the only thing that ever connects to it is the
        // sniffing loop below, relaying a browser's bytes.
        val tls = certificate.sslContext().serverSocketFactory
            .createServerSocket(0, BACKLOG, LOOPBACK) as SSLServerSocket
        tlsSocket = tls
        acceptLoop(tls) { it.relayTo(forwardToPort) }

        val public = ServerSocket(listenPort)
        publicSocket = public
        acceptLoop(public) { it.sniff(tls.localPort) }

        // Best-effort, like mDNS: some devices reserve low ports, and losing
        // this bind only loses the bare-name shortcut, not the server.
        catchSocket = plainHttpPort
            ?.let { port -> runCatching { ServerSocket(port) }.getOrNull() }
            ?.also { catcher -> acceptLoop(catcher) { it.catchPlainHttp() } }
    }

    fun stop() {
        runCatching { publicSocket?.close() }
        runCatching { catchSocket?.close() }
        runCatching { tlsSocket?.close() }
        publicSocket = null
        catchSocket = null
        tlsSocket = null
        scope.cancel()
    }

    private fun acceptLoop(socket: ServerSocket, handle: (Socket) -> Unit) {
        scope.launch {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) } }
            }
        }
    }

    /**
     * Decides whether the caller is speaking TLS, and if not, points them at the
     * address that is.
     *
     * Typing `192.168.1.42:8123` into a browser produces plain HTTP, because
     * browsers default to `http://`. Handing that to a TLS socket produces a
     * failed handshake and `ERR_EMPTY_RESPONSE`, which tells the user nothing —
     * so the first byte decides. A TLS ClientHello always opens with 0x16, a
     * handshake record; an HTTP request always opens with an ASCII method. One
     * byte separates them.
     */
    private fun Socket.sniff(tlsPort: Int) {
        // A caller that connects and then says nothing must not hold a coroutine
        // open forever. Once a real conversation starts the timeout has to go: a
        // long poll is 25 seconds of deliberate silence.
        soTimeout = FIRST_BYTE_TIMEOUT_MILLIS
        val first = runCatching { getInputStream().read() }.getOrDefault(-1)
        if (first == -1) {
            runCatching { close() }
            return
        }

        if (first == TLS_HANDSHAKE_RECORD) {
            soTimeout = 0
            relayTo(tlsPort, replaying = first)
        } else {
            redirectToHttps(first)
        }
    }

    /**
     * The port-80 handler: everything arriving here is a browser that was given
     * a bare hostname, so there is nothing to sniff — just point it at TLS.
     */
    private fun Socket.catchPlainHttp() {
        soTimeout = FIRST_BYTE_TIMEOUT_MILLIS
        val first = runCatching { getInputStream().read() }.getOrDefault(-1)
        if (first == -1) {
            runCatching { close() }
            return
        }
        redirectToHttps(first)
    }

    /**
     * Pumps this socket to a local port, optionally sending [replaying] ahead of
     * everything else — the byte taken off the wire to identify the protocol,
     * which the far end still needs.
     */
    private fun Socket.relayTo(port: Int, replaying: Int? = null) {
        val upstream = runCatching { Socket(LOOPBACK, port) }.getOrElse {
            runCatching { close() }
            return
        }
        use {
            upstream.use {
                val toUpstream = upstream.getOutputStream()
                if (replaying != null) {
                    runCatching {
                        toUpstream.write(replaying)
                        toUpstream.flush()
                    }
                }
                val outbound = scope.launch { pump(getInputStream(), toUpstream) }
                pump(upstream.getInputStream(), getOutputStream())
                runCatching { outbound.cancel() }
            }
        }
    }

    /**
     * Answers a plaintext request with a redirect to the same address over HTTPS.
     *
     * Temporary rather than permanent on purpose: a 308 would be cached, and
     * switching encryption back off would leave browsers forcing HTTPS at a port
     * that no longer speaks it.
     */
    private fun Socket.redirectToHttps(consumedByte: Int) {
        use {
            val request = readRequestHead(consumedByte)
            val host = request.host ?: localAddress.hostAddress ?: return
            val response = buildString {
                append("HTTP/1.1 307 Temporary Redirect\r\n")
                append("Location: ${httpsLocation(host, listenPort, request.path)}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n\r\n")
            }
            runCatching {
                getOutputStream().apply {
                    write(response.toByteArray())
                    flush()
                }
            }
        }
    }

    /** The path and Host of a plaintext request, read far enough to redirect it. */
    private fun Socket.readRequestHead(consumedByte: Int): RequestHead {
        val reader = SequenceInputStream(
            ByteArrayInputStream(byteArrayOf(consumedByte.toByte())),
            getInputStream(),
        ).bufferedReader()

        val requestLine = runCatching { reader.readLine() }.getOrNull().orEmpty()
        val path = requestLine.split(' ').getOrNull(1)?.takeIf { it.startsWith("/") } ?: "/"

        var host: String? = null
        runCatching {
            repeat(MAX_HEADER_LINES) {
                val line = reader.readLine() ?: return@runCatching
                if (line.isEmpty()) return@runCatching
                if (line.startsWith("Host:", ignoreCase = true)) {
                    // Strip any port; the HTTPS URL uses the port we listen on.
                    host = line.substringAfter(':').trim().substringBeforeLast(':').ifEmpty { null }
                }
            }
        }
        return RequestHead(host, path)
    }

    private data class RequestHead(val host: String?, val path: String)

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        runCatching {
            while (true) {
                val read = from.read(buffer)
                if (read == -1) break
                to.write(buffer, 0, read)
                // Flushed every chunk: a long-poll reply is small and must not sit
                // waiting for a buffer to fill.
                to.flush()
            }
        }
    }

    companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        /** The record type that opens every TLS handshake. */
        private const val TLS_HANDSHAKE_RECORD = 0x16

        private const val HTTP_DEFAULT_PORT = 80
        private const val HTTPS_DEFAULT_PORT = 443

        /** The HTTPS address of [host], portless when `https://` already implies [port]. */
        internal fun httpsLocation(host: String, port: Int, path: String): String {
            val suffix = if (port == HTTPS_DEFAULT_PORT) "" else ":$port"
            return "https://$host$suffix$path"
        }

        private const val RELAY_BUFFER_BYTES = 32 * 1024
        private const val FIRST_BYTE_TIMEOUT_MILLIS = 10_000
        private const val MAX_HEADER_LINES = 40
        private const val BACKLOG = 50

        /**
         * A free loopback port for the plain server to hide behind.
         *
         * Asking the OS for one and releasing it leaves a small race, which is
         * hard to avoid without letting Ktor pick the port and report it back.
         * The window is microseconds on a device with one app doing this.
         */
        fun findFreeLoopbackPort(): Int =
            ServerSocket(0, 1, LOOPBACK).use { it.localPort }
    }
}
