package com.tonyt.magicpaste.domain

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MagicPasteServerTest {

    @Test
    fun `serves the current clipboard as json`() = testApplication {
        val clipboard = InMemoryClipboard("hello from the phone")
        application { magicPasteModule(clipboard) }

        val snapshot = client.get("/api/clipboard").decode()

        assertEquals("hello from the phone", snapshot.text)
        assertEquals(1L, snapshot.revision)
    }

    @Test
    fun `serves the clipboard as plain text for curl`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("plain")) }

        assertEquals("plain", client.get("/raw").bodyAsText())
    }

    @Test
    fun `posting json replaces the device clipboard`() = testApplication {
        val clipboard = InMemoryClipboard()
        application { magicPasteModule(clipboard) }

        val response = client.post("/api/clipboard") {
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
        application { magicPasteModule(clipboard) }

        client.post("/raw") { setBody("from curl") }

        assertEquals("from curl", clipboard.snapshot.value.text)
    }

    @Test
    fun `malformed json is rejected without touching the clipboard`() = testApplication {
        val clipboard = InMemoryClipboard("untouched")
        application { magicPasteModule(clipboard) }

        val response = client.post("/api/clipboard") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("untouched", clipboard.snapshot.value.text)
    }

    @Test
    fun `oversized bodies are refused by their declared length`() = testApplication {
        val clipboard = InMemoryClipboard("untouched")
        application { magicPasteModule(clipboard) }

        val response = client.post("/raw") {
            setBody("x".repeat(MagicPasteServer.MAX_TEXT_LENGTH + 1))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("untouched", clipboard.snapshot.value.text)
    }

    @Test
    fun `a since request returns immediately when the clipboard is already newer`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("already copied")) }

        assertEquals("already copied", client.get("/api/clipboard?since=0").decode().text)
    }

    @Test
    fun `a since request waits for the next copy`() = testApplication {
        val clipboard = InMemoryClipboard("first")
        application { magicPasteModule(clipboard) }
        startApplication()

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
    fun `serves the web ui at the root`() = testApplication {
        application { magicPasteModule(InMemoryClipboard()) }

        val body = client.get("/").bodyAsText()

        assertTrue(body.contains("<title>MagicPaste</title>"), "expected the web UI, got: ${body.take(120)}")
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.decode(): ClipboardSnapshot =
    Json.decodeFromString(ClipboardSnapshot.serializer(), bodyAsText())
