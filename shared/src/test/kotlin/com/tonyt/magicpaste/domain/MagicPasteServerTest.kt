package com.tonyt.magicpaste.domain

import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MagicPasteServerTest {

    @Test
    fun `serves the current clipboard as json`() = testApplication {
        val clipboard = InMemoryClipboard("hello from the phone")
        application { magicPasteModule(clipboard, gate()) }

        val snapshot = unlocked().get("/api/clipboard").decode()

        assertEquals("hello from the phone", snapshot.text)
        assertEquals(1L, snapshot.revision)
    }

    @Test
    fun `serves the clipboard as plain text for curl`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("plain"), gate()) }

        assertEquals("plain", unlocked().get("/raw").bodyAsText())
    }

    @Test
    fun `posting json replaces the device clipboard`() = testApplication {
        val clipboard = InMemoryClipboard()
        application { magicPasteModule(clipboard, gate()) }

        val response = unlocked().post("/api/clipboard") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"text":"from the browser"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("from the browser", clipboard.snapshot.value.text)
        assertEquals(1L, response.decode().revision)
    }

    @Test
    fun `posting raw text replaces the device clipboard`() = testApplication {
        val clipboard = InMemoryClipboard()
        application { magicPasteModule(clipboard, gate()) }

        unlocked().post("/raw") { setBody("from curl") }

        assertEquals("from curl", clipboard.snapshot.value.text)
    }

    @Test
    fun `malformed json is rejected without touching the clipboard`() = testApplication {
        val clipboard = InMemoryClipboard("untouched")
        application { magicPasteModule(clipboard, gate()) }

        val response = unlocked().post("/api/clipboard") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("untouched", clipboard.snapshot.value.text)
    }

    @Test
    fun `oversized bodies are refused by their declared length`() = testApplication {
        val clipboard = InMemoryClipboard("untouched")
        application { magicPasteModule(clipboard, gate()) }

        val response = unlocked().post("/raw") {
            setBody("x".repeat(MagicPasteServer.MAX_TEXT_LENGTH + 1))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("untouched", clipboard.snapshot.value.text)
    }

    @Test
    fun `a since request returns immediately when the clipboard is already newer`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("already copied"), gate()) }

        assertEquals("already copied", unlocked().get("/api/clipboard?since=0").decode().text)
    }

    @Test
    fun `a since request waits for the next copy`() = testApplication {
        val clipboard = InMemoryClipboard("first")
        application { magicPasteModule(clipboard, gate()) }
        startApplication()
        val client = unlocked()

        coroutineScope {
            val pending = async { client.get("/api/clipboard?since=1").decode() }
            delay(200)
            assertTrue(pending.isActive, "long poll should still be waiting for a change")

            clipboard.write("second")

            val snapshot = pending.await()
            assertEquals("second", snapshot.text)
            assertEquals(2L, snapshot.revision)
        }
    }

    @Test
    fun `serves the web ui once unlocked`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate()) }

        val body = unlocked().get("/").bodyAsText()

        assertTrue(body.contains("Device clipboard"), "expected the web UI, got: ${body.take(200)}")
    }

    @Test
    fun `the page names the device it is serving`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate(), "OnePlus CPH2747 - Android 16") }

        val body = unlocked().get("/").bodyAsText()

        assertTrue(body.contains("OnePlus CPH2747 - Android 16"), "device description missing")
        assertTrue(!body.contains(DEVICE_PLACEHOLDER), "placeholder was left in the page")
    }

    @Test
    fun `a device name cannot inject markup`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate(), "<script>alert(1)</script>") }

        val body = unlocked().get("/").bodyAsText()

        assertTrue(!body.contains("<script>alert"), "device description was not escaped")
        assertTrue(body.contains("&lt;script&gt;"), "expected the escaped form, got: ${body.take(400)}")
    }

    @Test
    fun `the pin prompt does not name the device`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate(), "OnePlus CPH2747 - Android 16") }

        val body = client.get("/").bodyAsText()

        assertTrue(!body.contains("OnePlus"), "the device model leaked to a locked-out client")
    }

    // --- the PIN ---

    @Test
    fun `the root serves the pin prompt, and no clipboard markup, without a session`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("secret"), gate()) }

        val body = client.get("/").bodyAsText()

        assertTrue(body.contains("Enter the PIN"), "expected the PIN prompt, got: ${body.take(200)}")
        assertTrue(!body.contains("Device clipboard"), "the clipboard page leaked to a locked-out client")
        assertTrue(!body.contains("secret"), "the clipboard contents leaked to a locked-out client")
    }

    @Test
    fun `clipboard endpoints reject callers without the pin`() = testApplication {
        val clipboard = InMemoryClipboard("private")
        application { magicPasteModule(clipboard, gate()) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/clipboard").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/raw").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/raw") { setBody("nope") }.status)
        assertEquals("private", clipboard.snapshot.value.text)
    }

    @Test
    fun `the wrong pin is rejected`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate()) }

        val response = client.post("/api/session") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"pin":"0000"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a wrong pin in the header is rejected`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("private"), gate()) }

        val response = client.get("/raw") { header(PinGate.PIN_HEADER, "0000") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `the right pin returns a session cookie that unlocks the clipboard`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("shared"), gate()) }
        val browser = createClient { install(HttpCookies) }

        assertEquals(HttpStatusCode.Unauthorized, browser.get("/raw").status)

        val login = browser.post("/api/session") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"pin":"$TEST_PIN"}""")
        }

        assertEquals(HttpStatusCode.OK, login.status)
        // The cookie is now held by the client, so no PIN header is involved here.
        assertEquals("shared", browser.get("/raw").bodyAsText())
    }

    @Test
    fun `wrong guesses do not lock out the real pin`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("shared"), gate()) }

        repeat(3) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/raw") { header(PinGate.PIN_HEADER, "1111") }.status,
            )
        }

        assertEquals("shared", unlocked().get("/raw").bodyAsText())
    }

    @Test
    fun `health stays open so the app can be found without the pin`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate()) }

        assertEquals("ok", client.get("/health").bodyAsText())
    }

    @Test
    fun `the session cookie is marked Secure only when something terminates TLS`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate(), secureCookies = true) }

        val cookie = login().orEmpty()

        assertTrue(cookie.contains("Secure", ignoreCase = true), "expected a Secure cookie, got: $cookie")
        assertTrue(cookie.contains("HttpOnly", ignoreCase = true), "expected HttpOnly, got: $cookie")
    }

    /**
     * Cookies are scoped to a host, not a port or a scheme. If both modes used one
     * name, the Secure cookie written over HTTPS could never be replaced by a
     * plain one over HTTP — browsers refuse that — and logging in over HTTP after
     * ever using HTTPS would silently do nothing.
     */
    @Test
    fun `the two modes use different cookie names so they cannot collide`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate(), secureCookies = true) }

        val overTls = login().orEmpty()

        assertTrue(
            overTls.startsWith("${PinGate.SECURE_SESSION_COOKIE}="),
            "expected the TLS cookie name, got: $overTls",
        )
        assertNotEquals(PinGate.SESSION_COOKIE, PinGate.SECURE_SESSION_COOKIE)
    }

    @Test
    fun `a session from the other mode does not unlock this one`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("private"), gate()) }

        // A cookie of the TLS name, presented to a plain-HTTP server. Even with a
        // valid-looking token it is the wrong door, and must not open.
        val response = client.get("/raw") {
            header(HttpHeaders.Cookie, "${PinGate.SECURE_SESSION_COOKIE}=test-token-0")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `the session cookie is not Secure over plain http, or browsers would drop it`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), gate()) }

        val cookie = login().orEmpty()

        assertTrue(!cookie.contains("Secure", ignoreCase = true), "unexpected Secure flag, got: $cookie")
    }

    @Test
    fun `session tokens are not reused between logins`() = testApplication {
        val tokens = ArrayDeque(listOf("token-one", "token-two"))
        application { magicPasteModule(InMemoryClipboard(), PinGate(TEST_PIN) { tokens.removeFirst() }) }

        val first = login()
        val second = login()

        assertNotEquals(first, second)
    }

    private suspend fun ApplicationTestBuilder.login(): String? = client.post("/api/session") {
        header(HttpHeaders.ContentType, "application/json")
        setBody("""{"pin":"$TEST_PIN"}""")
    }.headers[HttpHeaders.SetCookie]
}

private const val TEST_PIN = "4242"

private var tokenCounter = 0

private fun gate() = PinGate(TEST_PIN) { "test-token-${tokenCounter++}" }

/** A client that presents the PIN on every request, standing in for a paired visitor. */
private fun ApplicationTestBuilder.unlocked() = createClient {
    install(DefaultRequest) { header(PinGate.PIN_HEADER, TEST_PIN) }
}

private suspend fun HttpResponse.decode(): ClipboardSnapshot =
    Json.decodeFromString(ClipboardSnapshot.serializer(), bodyAsText())
