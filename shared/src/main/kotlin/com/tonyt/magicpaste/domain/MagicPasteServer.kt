package com.tonyt.magicpaste.domain

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP server that shares one device's clipboard with every browser on the same
 * Wi-Fi network.
 *
 * Endpoints:
 * - `GET  /`                      the web UI
 * - `GET  /api/clipboard`         current snapshot as JSON
 * - `GET  /api/clipboard?since=N` long-polls, returning once the revision passes N
 * - `POST /api/clipboard`         `{"text": "..."}` — replaces the device clipboard
 * - `GET  /raw`                   clipboard as `text/plain`, for `curl`
 * - `POST /raw`                   request body becomes the clipboard, for `curl -d`
 * - `GET  /health`                liveness probe
 */
class MagicPasteServer(
    private val clipboard: ClipboardAccess,
    val port: Int = DEFAULT_PORT,
    private val host: String = ALL_INTERFACES,
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * Binds the port and starts serving. Returns once the socket is accepting
     * connections; throws if the port is already taken.
     */
    suspend fun start() {
        check(engine == null) { "Server is already running on port $port" }
        val server = embeddedServer(CIO, port = port, host = host) { magicPasteModule(clipboard) }
        engine = server
        try {
            withContext(Dispatchers.IO) { server.start(wait = false) }
        } catch (failure: Throwable) {
            engine = null
            withContext(Dispatchers.IO) { runCatching { server.stop(0, 0) } }
            throw failure
        }
    }

    /** Stops the server. Safe to call when it was never started. */
    suspend fun stop() {
        val server = engine ?: return
        engine = null
        withContext(Dispatchers.IO) { server.stop(GRACE_MILLIS, GRACE_MILLIS) }
    }

    companion object {
        const val DEFAULT_PORT = 8123
        const val ALL_INTERFACES = "0.0.0.0"

        /** How long a `?since=` request waits for a change before replying anyway. */
        const val LONG_POLL_TIMEOUT_MILLIS = 25_000L

        /** Upper bound on a single paste, so a stray upload cannot exhaust memory. */
        const val MAX_TEXT_LENGTH = 1_000_000

        private const val GRACE_MILLIS = 500L
    }
}

private val json = Json { encodeDefaults = true }

@Serializable
private data class WriteRequest(val text: String)

/** Wires up the routes; separated from [MagicPasteServer] so tests can host them directly. */
fun Application.magicPasteModule(clipboard: ClipboardAccess) {
    routing {
        get("/") { call.respondText(WEB_UI_HTML, ContentType.Text.Html) }

        get("/health") { call.respondText("ok", ContentType.Text.Plain) }

        get("/api/clipboard") {
            val since = call.request.queryParameters["since"]?.toLongOrNull()
            call.respondSnapshot(clipboard.await(since))
        }

        // PUT and POST do the same thing here; both spellings are common in clients.
        post("/api/clipboard") { call.writeFromJson(clipboard) }
        put("/api/clipboard") { call.writeFromJson(clipboard) }

        get("/raw") { call.respondText(clipboard.snapshot.value.text, ContentType.Text.Plain) }
        post("/raw") { call.writeFromPlainText(clipboard) }
        put("/raw") { call.writeFromPlainText(clipboard) }
    }
}

/**
 * The current snapshot, or — when [since] names a revision the clipboard has
 * already reached — a suspend until it moves past it. Times out into the current
 * snapshot so a client can simply ask again; that also keeps proxies from
 * killing an idle connection.
 */
private suspend fun ClipboardAccess.await(since: Long?): ClipboardSnapshot {
    if (since == null || snapshot.value.revision > since) return snapshot.value
    return withTimeoutOrNull(MagicPasteServer.LONG_POLL_TIMEOUT_MILLIS) {
        snapshot.first { it.revision > since }
    } ?: snapshot.value
}

private suspend fun ApplicationCall.writeFromJson(clipboard: ClipboardAccess) {
    if (rejectOversizedBody()) return
    val text = runCatching { json.decodeFromString(WriteRequest.serializer(), receiveText()).text }
        .getOrElse {
            respondText(
                """Expected a JSON body of the form {"text": "..."}""",
                status = HttpStatusCode.BadRequest,
            )
            return
        }
    write(clipboard, text)
}

private suspend fun ApplicationCall.writeFromPlainText(clipboard: ClipboardAccess) {
    if (rejectOversizedBody()) return
    write(clipboard, receiveText())
}

private suspend fun ApplicationCall.write(clipboard: ClipboardAccess, text: String) {
    if (text.length > MagicPasteServer.MAX_TEXT_LENGTH) {
        respondTooLarge()
        return
    }
    clipboard.write(text)
    respondSnapshot(clipboard.snapshot.value)
}

/** Refuses a body by its declared length, before it is read into memory. */
private suspend fun ApplicationCall.rejectOversizedBody(): Boolean {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return false
    if (declared <= MagicPasteServer.MAX_TEXT_LENGTH) return false
    respondTooLarge()
    return true
}

private suspend fun ApplicationCall.respondTooLarge() = respondText(
    "Text is longer than the ${MagicPasteServer.MAX_TEXT_LENGTH} character limit",
    status = HttpStatusCode.PayloadTooLarge,
)

private suspend fun ApplicationCall.respondSnapshot(snapshot: ClipboardSnapshot) = respondText(
    json.encodeToString(ClipboardSnapshot.serializer(), snapshot),
    ContentType.Application.Json,
)
