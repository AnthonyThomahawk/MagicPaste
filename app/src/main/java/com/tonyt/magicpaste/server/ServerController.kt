package com.tonyt.magicpaste.server

import com.tonyt.magicpaste.domain.ClipboardAccess
import com.tonyt.magicpaste.domain.MagicPasteServer
import com.tonyt.magicpaste.net.LocalAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.BindException

/** What the UI and the notification need to know about the server. */
sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data object Starting : ServerStatus

    /** Serving. [urls] is every address other devices can try, Wi-Fi first. */
    data class Running(val port: Int, val urls: List<String>) : ServerStatus

    data class Failed(val reason: String) : ServerStatus
}

/**
 * Owns the single [MagicPasteServer] instance for the process and exposes its
 * state as a flow, so the activity and the foreground service can both drive and
 * observe it without holding a reference to each other.
 */
class ServerController(private val clipboard: ClipboardAccess) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val state = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val status: StateFlow<ServerStatus> = state.asStateFlow()

    private var server: MagicPasteServer? = null

    /** Starts serving on [port]; a no-op if it is already up on that port. */
    fun start(port: Int) {
        scope.launch {
            lock.withLock {
                val running = server
                if (running != null) {
                    if (running.port == port) return@withLock
                    running.stop()
                    server = null
                }
                state.value = ServerStatus.Starting
                val starting = MagicPasteServer(clipboard, port)
                try {
                    starting.start()
                    server = starting
                    state.value = ServerStatus.Running(port, LocalAddresses.urls(port))
                } catch (failure: Throwable) {
                    state.value = ServerStatus.Failed(failure.describe(port))
                }
            }
        }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                server?.stop()
                server = null
                state.value = ServerStatus.Stopped
            }
        }
    }

    /** Re-reads the network interfaces, e.g. after the device changes Wi-Fi. */
    fun refreshAddresses() {
        val running = state.value as? ServerStatus.Running ?: return
        state.value = running.copy(urls = LocalAddresses.urls(running.port))
    }

    private fun Throwable.describe(port: Int): String = when {
        this is BindException -> "Port $port is already in use. Try another one."
        this is SecurityException -> "The system blocked opening port $port."
        else -> message ?: this::class.java.simpleName
    }
}
