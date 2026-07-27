package com.tonyt.magicpaste.domain

import io.ktor.http.ContentType
import io.ktor.http.Cookie
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
 * Wi-Fi network, behind the PIN shown in the app.
 *
 * Endpoints (everything but `/health` needs a session cookie or the PIN header):
 * - `GET  /`                      the web UI, or the PIN prompt when unauthenticated
 * - `GET  /files`                 the file manager, when a [FileStore] was supplied
 * - `GET  /api/files…`            listing, download, upload, rename, move, delete
 * - `POST /api/session`           `{"pin": "..."}` — exchanges the PIN for a session cookie
 * - `GET  /api/clipboard`         current snapshot as JSON
 * - `GET  /api/clipboard?since=N` long-polls, returning once the revision passes N
 * - `POST /api/clipboard`         `{"text": "..."}` — replaces the device clipboard
 * - `GET  /raw`                   clipboard as `text/plain`, for `curl`
 * - `POST /raw`                   request body becomes the clipboard, for `curl -d`
 * - `GET  /health`                liveness probe
 */
class MagicPasteServer(
    /** Null leaves the clipboard unshared — the routes are simply not mounted. */
    private val clipboard: ClipboardAccess?,
    private val gate: PinGate,
    private val device: String = "",
    /** Null leaves the file manager unshared. */
    private val files: FileStore? = null,
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
        val server = embeddedServer(CIO, port = port, host = host) {
            magicPasteModule(clipboard, gate, device, files)
        }
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

@Serializable
private data class SessionRequest(val pin: String)

/**
 * Wires up the routes; separated from [MagicPasteServer] so tests can host them
 * directly. [device] is what the page shows visitors — "OnePlus CPH2747 -
 * Android 16" — so they can tell whose clipboard they are looking at.
 */
fun Application.magicPasteModule(
    clipboard: ClipboardAccess? = null,
    gate: PinGate,
    device: String = "",
    files: FileStore? = null,
) {
    // Substituted once, not per request: none of this changes while the server
    // is up, and the cross-links depend on what is actually mounted — an offer
    // to visit a page that is not there is worse than no offer.
    val clipboardPage = WEB_UI_HTML
        .replace(DEVICE_PLACEHOLDER, device.escapeHtml())
        .replace(FILES_LINK_PLACEHOLDER, if (files != null) FILES_LINK_HTML else "")
    val filesPage = FILES_HTML
        .replace(DEVICE_PLACEHOLDER, device.escapeHtml())
        .replace(CLIPBOARD_LINK_PLACEHOLDER, if (clipboard != null) CLIPBOARD_LINK_HTML else "")

    // Whichever page exists is what the bare address should land on, so sharing
    // only files still means "open the address and you are there".
    val landingPage = clipboardPage.takeIf { clipboard != null } ?: filesPage.takeIf { files != null }

    routing {
        // The one unauthenticated read: it says whether a server is here, nothing
        // about what it holds, which is what makes it useful for "is it up?".
        get("/health") { call.respondText("ok", ContentType.Text.Plain) }

        get("/") {
            if (landingPage == null) {
                call.respondText("Nothing is being shared", status = HttpStatusCode.ServiceUnavailable)
                return@get
            }
            // The prompt stays anonymous: someone who cannot get past it learns
            // nothing about the device from us.
            val page = if (gate.admits(call)) landingPage else LOGIN_HTML
            call.respondText(page, ContentType.Text.Html)
        }

        post("/api/session") { call.openSession(gate) }

        if (clipboard != null) {
            get("/api/clipboard") {
                call.guarded(gate) {
                    val since = call.request.queryParameters["since"]?.toLongOrNull()
                    call.respondSnapshot(clipboard.await(since))
                }
            }

            // PUT and POST do the same thing here; both spellings are common in clients.
            post("/api/clipboard") { call.guarded(gate) { call.writeFromJson(clipboard) } }
            put("/api/clipboard") { call.guarded(gate) { call.writeFromJson(clipboard) } }

            get("/raw") {
                call.guarded(gate) {
                    call.respondText(clipboard.snapshot.value.text, ContentType.Text.Plain)
                }
            }
            post("/raw") { call.guarded(gate) { call.writeFromPlainText(clipboard) } }
            put("/raw") { call.guarded(gate) { call.writeFromPlainText(clipboard) } }
        }

        if (files != null) {
            get("/files") {
                val page = if (gate.admits(call)) filesPage else LOGIN_HTML
                call.respondText(page, ContentType.Text.Html)
            }
            // A refused file operation is an expected outcome, not a server
            // fault, so it becomes a status code here rather than a 500.
            fileRoutes(files) { block ->
                guarded(gate) {
                    try {
                        block()
                    } catch (refusal: FileStoreException) {
                        respondText(refusal.message ?: "Refused", status = refusal.status())
                    }
                }
            }
        }
    }
}

/**
 * Runs [block] only for a caller holding a session cookie or the PIN itself.
 *
 * Spelled out at each route rather than as a route-scoped plugin: with this few
 * endpoints, a reader can see at a glance which ones are guarded, and the one
 * that deliberately is not.
 */
private suspend fun ApplicationCall.guarded(gate: PinGate, block: suspend () -> Unit) {
    if (!gate.admits(this)) {
        respondText("Wrong or missing PIN", status = HttpStatusCode.Unauthorized)
        return
    }
    block()
}

/** True when the call carries a live session cookie, or the PIN itself. */
private suspend fun PinGate.admits(call: ApplicationCall): Boolean {
    if (isValidSession(call.request.cookies[PinGate.SESSION_COOKIE])) return true
    val offered = call.request.headers[PinGate.PIN_HEADER] ?: return false
    return verify(offered)
}

/** Exchanges a correct PIN for a session cookie. */
private suspend fun ApplicationCall.openSession(gate: PinGate) {
    val offered = runCatching {
        json.decodeFromString(SessionRequest.serializer(), receiveText()).pin
    }.getOrElse {
        respondText("""Expected a JSON body of the form {"pin": "..."}""", status = HttpStatusCode.BadRequest)
        return
    }

    val token = gate.authenticate(offered)
    if (token == null) {
        respondText("Wrong PIN", status = HttpStatusCode.Unauthorized)
        return
    }

    response.cookies.append(
        Cookie(
            name = PinGate.SESSION_COOKIE,
            value = token,
            path = "/",
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
        )
    )
    respondText("ok", ContentType.Text.Plain)
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
