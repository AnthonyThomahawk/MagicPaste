package com.tonyt.magicpaste.domain

import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileRoutesTest {

    @Test
    fun `lists a directory, folders first`() = testApplication {
        val store = fakeStore()
        serve(store)

        val listing = files().get("/api/files?path=/").decodeListing()

        assertEquals("/", listing.path)
        assertEquals(listOf("DCIM", "notes.txt"), listing.entries.map { it.name })
        assertEquals(null, listing.parent)
    }

    @Test
    fun `downloads a file with a filename attached`() = testApplication {
        serve(fakeStore())

        val response = files().get("/api/files/download?path=/notes.txt")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello from the phone", response.bodyAsText())
        assertTrue(
            response.headers[HttpHeaders.ContentDisposition]?.contains("notes.txt") == true,
            "expected a filename in Content-Disposition, got ${response.headers[HttpHeaders.ContentDisposition]}",
        )
    }

    @Test
    fun `refuses to download a folder`() = testApplication {
        serve(fakeStore())

        assertEquals(HttpStatusCode.BadRequest, files().get("/api/files/download?path=/DCIM").status)
    }

    @Test
    fun `uploads a file into the current folder`() = testApplication {
        val store = fakeStore()
        serve(store)

        val response = files().post("/api/files/upload?path=/DCIM") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            "picture-bytes".toByteArray(),
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"holiday.jpg\"")
                            },
                        )
                    }
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("picture-bytes", store.contents["/DCIM/holiday.jpg"])
    }

    @Test
    fun `creates, renames and deletes`() = testApplication {
        val store = fakeStore()
        serve(store)
        val client = files()

        client.postJson("/api/files/folder", """{"path":"/","name":"Reports"}""")
        assertTrue(store.directories.contains("/Reports"))

        client.postJson("/api/files/rename", """{"path":"/notes.txt","name":"todo.txt"}""")
        assertTrue(store.contents.containsKey("/todo.txt"))

        client.postJson("/api/files/delete", """{"path":"/todo.txt"}""")
        assertTrue(!store.contents.containsKey("/todo.txt"))
    }

    @Test
    fun `the root cannot be deleted`() = testApplication {
        val store = fakeStore()
        serve(store)

        val response = files().postJson("/api/files/delete", """{"path":"/"}""")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(store.contents.isNotEmpty(), "the store was touched")
    }

    @Test
    fun `traversal is refused before it reaches the store`() = testApplication {
        val store = fakeStore()
        serve(store)
        val client = files()

        val attempts = listOf(
            "/api/files?path=/../etc",
            "/api/files?path=..",
            "/api/files/download?path=/DCIM/../../etc/passwd",
        )
        for (attempt in attempts) {
            assertEquals(HttpStatusCode.BadRequest, client.get(attempt).status, "allowed: $attempt")
        }

        // A separator smuggled through a name would turn a rename into a move.
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postJson("/api/files/rename", """{"path":"/notes.txt","name":"../escaped.txt"}""").status,
        )
        assertTrue(store.reachedWith.none { it.contains("..") }, "a '..' path reached the store: ${store.reachedWith}")
    }

    @Test
    fun `file endpoints need the pin like everything else`() = testApplication {
        serve(fakeStore())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/files?path=/").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/files/download?path=/notes.txt").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/files/delete").status)
    }

    @Test
    fun `the files page shows the pin prompt rather than the file manager`() = testApplication {
        serve(fakeStore())

        // A page answers with the prompt instead of a bare 401, the same way `/`
        // does — but it must not carry the file manager along with it.
        val body = client.get("/files").bodyAsText()

        assertTrue(body.contains("Enter the PIN"), "expected the PIN prompt, got: ${body.take(200)}")
        assertTrue(!body.contains("New folder"), "the file manager leaked to a locked-out client")
        assertTrue(!body.contains("notes.txt"), "a file name leaked to a locked-out client")
    }

    @Test
    fun `a missing file is a 404`() = testApplication {
        serve(fakeStore())

        assertEquals(HttpStatusCode.NotFound, files().get("/api/files/download?path=/nope.txt").status)
    }

    @Test
    fun `the clipboard page only offers files when a store is present`() = testApplication {
        application { magicPasteModule(InMemoryClipboard(), PinGate(FILE_PIN) { "t" }) }

        val body = files().get("/").bodyAsText()

        assertTrue(!body.contains("""href="/files""""), "offered a file manager that is not mounted")
        assertEquals(HttpStatusCode.NotFound, files().get("/files").status)
    }

    // --- sharing the clipboard, the files, or both ---

    @Test
    fun `sharing both mounts both, and each page links to the other`() = testApplication {
        serve(fakeStore())
        val client = files()

        assertEquals(HttpStatusCode.OK, client.get("/api/clipboard").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/files?path=/").status)
        assertTrue(client.get("/").bodyAsText().contains("""href="/files""""), "no link to the files page")
        assertTrue(client.get("/files").bodyAsText().contains("""href="/""""), "no link back to the clipboard")
    }

    @Test
    fun `files only leaves the clipboard unreachable and lands on the file manager`() = testApplication {
        application {
            magicPasteModule(clipboard = null, gate = PinGate(FILE_PIN) { "t" }, files = fakeStore())
        }
        val client = files()

        assertEquals(HttpStatusCode.NotFound, client.get("/api/clipboard").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/raw").status)

        // The bare address has to land somewhere useful, so it serves the only page there is.
        val landing = client.get("/").bodyAsText()
        assertTrue(landing.contains("New folder"), "expected the file manager at /, got: ${landing.take(200)}")
        assertTrue(!landing.contains("""href="/""""), "linked to a clipboard page that is not mounted")
    }

    @Test
    fun `clipboard only leaves the file manager unreachable`() = testApplication {
        application { magicPasteModule(InMemoryClipboard("still here"), PinGate(FILE_PIN) { "t" }) }
        val client = files()

        assertEquals(HttpStatusCode.NotFound, client.get("/files").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/files?path=/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/files/download?path=/notes.txt").status)
        assertEquals("still here", client.get("/raw").bodyAsText())
    }

    @Test
    fun `sharing nothing serves nothing`() = testApplication {
        application { magicPasteModule(clipboard = null, gate = PinGate(FILE_PIN) { "t" }) }

        assertEquals(HttpStatusCode.ServiceUnavailable, files().get("/").status)
        // Still answers the liveness probe, so the app can tell the port is bound.
        assertEquals("ok", client.get("/health").bodyAsText())
    }

    private fun ApplicationTestBuilder.serve(store: FileStore) {
        application {
            magicPasteModule(InMemoryClipboard(), PinGate(FILE_PIN) { "test-token" }, "Test Device", store)
        }
    }

    private fun ApplicationTestBuilder.files() = createClient {
        install(DefaultRequest) { header(PinGate.PIN_HEADER, FILE_PIN) }
    }
}

private const val FILE_PIN = "4242"

private suspend fun io.ktor.client.HttpClient.postJson(url: String, body: String) = post(url) {
    header(HttpHeaders.ContentType, "application/json")
    setBody(body)
}

private suspend fun io.ktor.client.statement.HttpResponse.decodeListing(): DirectoryListing =
    Json.decodeFromString(DirectoryListing.serializer(), bodyAsText())

private fun fakeStore() = FakeFileStore().apply {
    directories += "/"
    directories += "/DCIM"
    contents["/notes.txt"] = "hello from the phone"
}

/**
 * A [FileStore] over two maps. Records every path it is asked about so a test can
 * assert that a refused request never got this far.
 */
private class FakeFileStore : FileStore {

    val directories = mutableSetOf<String>()
    val contents = mutableMapOf<String, String>()
    val reachedWith = mutableListOf<String>()

    override suspend fun list(path: String): DirectoryListing {
        reachedWith += path
        if (path !in directories) throw missing(path)
        val prefix = if (path == "/") "/" else "$path/"
        val childDirectories = directories
            .filter { it != path && it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { FileEntry(it.removePrefix(prefix), isDirectory = true, size = 0, modified = 1) }
        val childFiles = contents.keys
            .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { FileEntry(it.removePrefix(prefix), false, contents.getValue(it).length.toLong(), 2) }
        return DirectoryListing(
            path = path,
            entries = (childDirectories + childFiles)
                .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name }),
            parent = VirtualPath.parentOf(path),
        )
    }

    override suspend fun stat(path: String): FileEntry {
        reachedWith += path
        val name = path.substringAfterLast('/')
        return when {
            path in directories -> FileEntry(name, isDirectory = true, size = 0, modified = 1)
            path in contents -> FileEntry(name, false, contents.getValue(path).length.toLong(), 2)
            else -> throw missing(path)
        }
    }

    override fun read(path: String): Flow<ByteArray> {
        reachedWith += path
        val text = contents[path] ?: throw missing(path)
        return flowOf(text.toByteArray())
    }

    override suspend fun write(directory: String, name: String, chunks: Flow<ByteArray>) {
        reachedWith += directory
        val joined = chunks.toList().fold(ByteArray(0)) { all, chunk -> all + chunk }
        contents[VirtualPath.join(directory, name)] = joined.decodeToString()
    }

    override suspend fun createDirectory(directory: String, name: String) {
        reachedWith += directory
        directories += VirtualPath.join(directory, name)
    }

    override suspend fun delete(path: String) {
        reachedWith += path
        if (contents.remove(path) == null && !directories.remove(path)) throw missing(path)
    }

    override suspend fun rename(path: String, newName: String) {
        reachedWith += path
        val text = contents.remove(path) ?: throw missing(path)
        contents[VirtualPath.join(VirtualPath.parentOf(path) ?: "/", newName)] = text
    }

    override suspend fun move(path: String, destination: String) {
        reachedWith += path
        val text = contents.remove(path) ?: throw missing(path)
        contents[VirtualPath.join(destination, path.substringAfterLast('/'))] = text
    }

    private fun missing(path: String) =
        FileStoreException(FileStoreException.Kind.NotFound, "$path does not exist")
}

class FakeFileStoreSanityTest {

    @Test
    fun `the fake refuses what it does not have, so 404 tests mean something`() {
        val store = fakeStore()
        assertFailsWith<FileStoreException> {
            kotlinx.coroutines.runBlocking { store.stat("/missing") }
        }
    }
}
