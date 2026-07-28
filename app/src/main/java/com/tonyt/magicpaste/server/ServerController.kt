package com.tonyt.magicpaste.server

import com.tonyt.magicpaste.domain.ClipboardAccess
import com.tonyt.magicpaste.domain.FileStore
import com.tonyt.magicpaste.domain.MagicPasteServer
import com.tonyt.magicpaste.domain.PinGate
import com.tonyt.magicpaste.domain.TokenSource
import com.tonyt.magicpaste.net.LocalAddresses
import com.tonyt.magicpaste.tls.DeviceCertificate
import com.tonyt.magicpaste.tls.Fingerprint
import com.tonyt.magicpaste.tls.TlsProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.BindException

/** What the UI and the notification need to know about the server. */
sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data object Starting : ServerStatus

    /**
     * Serving. [urls] is every address other devices can try, Wi-Fi first, and
     * [fingerprint] is present only when the connection is encrypted — it is what
     * a visitor compares against to know they reached this device and not a relay.
     */
    data class Running(
        val port: Int,
        val urls: List<String>,
        val fingerprint: Fingerprint? = null,
    ) : ServerStatus {
        val isEncrypted: Boolean get() = fingerprint != null
    }

    data class Failed(val reason: String) : ServerStatus
}

/**
 * Owns the single [MagicPasteServer] instance for the process and exposes its
 * state as a flow, so the activity and the foreground service can both drive and
 * observe it without holding a reference to each other.
 */
class ServerController(
    private val clipboard: ClipboardAccess,
    private val tokens: TokenSource,
    private val device: String,
    /** Where the TLS keystore lives; app-private storage. */
    private val certificateDirectory: File,
    /**
     * Consulted at every start rather than once: storage access can be granted
     * after the app launches, and restarting sharing is what picks it up.
     * Null means the file manager stays off.
     */
    private val files: () -> FileStore?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val state = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val status: StateFlow<ServerStatus> = state.asStateFlow()

    private var server: MagicPasteServer? = null
    private var proxy: TlsProxy? = null

    /** The port visitors connect to, which is the proxy's when TLS is on. */
    private var listeningPort: Int? = null
    private var encrypted = false

    /**
     * Starts serving on [port], guarded by [pin], offering whichever of the
     * clipboard and files is switched on, over TLS when [useTls].
     *
     * Everything here is settled per start — the gate, what is shared, and the
     * certificate — so changing any of it takes a stop and a start. That is also
     * what makes changing the PIN lock out visitors who already had a session.
     */
    fun start(
        port: Int,
        pin: String,
        shareClipboard: Boolean,
        shareFiles: Boolean,
        useTls: Boolean,
    ) {
        scope.launch {
            lock.withLock {
                if (server != null) {
                    if (listeningPort == port && encrypted == useTls) return@withLock
                    shutdown()
                }
                state.value = ServerStatus.Starting

                // With TLS the HTTP server hides on loopback and only the proxy is
                // reachable, so nothing can bypass the encryption by aiming at the
                // plain port.
                val internalPort = if (useTls) TlsProxy.findFreeLoopbackPort() else port
                val starting = MagicPasteServer(
                    clipboard = clipboard.takeIf { shareClipboard },
                    gate = PinGate(pin, tokens),
                    device = device,
                    files = if (shareFiles) files() else null,
                    secureCookies = useTls,
                    port = internalPort,
                    host = if (useTls) LOOPBACK_HOST else MagicPasteServer.ALL_INTERFACES,
                )

                try {
                    starting.start()
                    val certificate = if (useTls) startProxy(port, internalPort) else null
                    server = starting
                    listeningPort = port
                    encrypted = useTls
                    state.value = ServerStatus.Running(
                        port = port,
                        urls = LocalAddresses.urls(port, useTls),
                        fingerprint = certificate?.fingerprint,
                    )
                } catch (failure: Throwable) {
                    runCatching { starting.stop() }
                    proxy?.let { runCatching { it.stop() } }
                    proxy = null
                    state.value = ServerStatus.Failed(failure.describe(port))
                }
            }
        }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                shutdown()
                state.value = ServerStatus.Stopped
            }
        }
    }

    /** Re-reads the network interfaces, e.g. after the device changes Wi-Fi. */
    fun refreshAddresses() {
        val running = state.value as? ServerStatus.Running ?: return
        state.value = running.copy(urls = LocalAddresses.urls(running.port, encrypted))
    }

    /**
     * Generates or reloads the certificate for the current addresses, then puts
     * the proxy in front of the loopback server.
     */
    private fun startProxy(listenOn: Int, forwardTo: Int): DeviceCertificate {
        val certificate = DeviceCertificate.loadOrCreate(certificateDirectory, LocalAddresses.candidates())
        val started = TlsProxy(certificate, listenPort = listenOn, forwardToPort = forwardTo)
        started.start()
        proxy = started
        return certificate
    }

    private suspend fun shutdown() {
        proxy?.let { runCatching { it.stop() } }
        proxy = null
        server?.let { runCatching { it.stop() } }
        server = null
        listeningPort = null
        encrypted = false
    }

    private fun Throwable.describe(port: Int): String = when {
        this is BindException -> "Port $port is already in use. Try another one."
        this is SecurityException -> "The system blocked opening port $port."
        else -> message ?: this::class.java.simpleName
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
