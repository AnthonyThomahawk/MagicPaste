package com.tonyt.magicpaste.domain

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PathRequest(val path: String)

@Serializable
private data class NameRequest(val path: String, val name: String)

@Serializable
private data class MoveRequest(val path: String, val destination: String)

private val fileJson = Json { encodeDefaults = true }

/**
 * The file manager's endpoints. Registered by the caller inside whatever guard
 * protects the rest of the server — none of these check the PIN themselves, so
 * they must never be mounted outside [guardedBy].
 */
internal fun Route.fileRoutes(store: FileStore, guardedBy: suspend ApplicationCall.(suspend () -> Unit) -> Unit) {

    get("/api/files") {
        call.guardedBy {
            val path = VirtualPath.normalize(call.parameter("path"))
            call.respondJson(DirectoryListing.serializer(), store.list(path))
        }
    }

    get("/api/files/download") {
        call.guardedBy {
            val path = VirtualPath.normalize(call.parameter("path"))
            val entry = store.stat(path)
            if (entry.isDirectory) {
                throw FileStoreException(FileStoreException.Kind.Invalid, "That is a folder, not a file")
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, entry.name)
                    .toString(),
            )
            call.respondBytesWriter(
                contentType = ContentType.Application.OctetStream,
                contentLength = entry.size,
            ) {
                store.read(path).collect { chunk -> writeFully(chunk, 0, chunk.size) }
            }
        }
    }

    post("/api/files/upload") {
        call.guardedBy {
            val directory = VirtualPath.normalize(call.parameter("path"))
            val multipart = call.receiveMultipart()
            var received = 0
            while (true) {
                val part = multipart.readPart() ?: break
                if (part is PartData.FileItem) {
                    val name = VirtualPath.requireSimpleName(part.originalFileName.orEmpty())
                    store.write(directory, name, part.chunks())
                    received++
                }
                part.dispose()
            }
            if (received == 0) {
                throw FileStoreException(FileStoreException.Kind.Invalid, "No file was included")
            }
            call.respondJson(DirectoryListing.serializer(), store.list(directory))
        }
    }

    post("/api/files/folder") {
        call.guardedBy {
            val request = call.decode(NameRequest.serializer())
            val directory = VirtualPath.normalize(request.path)
            store.createDirectory(directory, VirtualPath.requireSimpleName(request.name))
            call.respondJson(DirectoryListing.serializer(), store.list(directory))
        }
    }

    post("/api/files/rename") {
        call.guardedBy {
            val request = call.decode(NameRequest.serializer())
            val path = VirtualPath.normalize(request.path)
            store.rename(path, VirtualPath.requireSimpleName(request.name))
            call.respondListingOfParent(store, path)
        }
    }

    post("/api/files/move") {
        call.guardedBy {
            val request = call.decode(MoveRequest.serializer())
            val path = VirtualPath.normalize(request.path)
            store.move(path, VirtualPath.normalize(request.destination))
            call.respondListingOfParent(store, path)
        }
    }

    post("/api/files/delete") {
        call.guardedBy {
            val path = VirtualPath.normalize(call.decode(PathRequest.serializer()).path)
            if (path == VirtualPath.ROOT) {
                throw FileStoreException(FileStoreException.Kind.NotPermitted, "The root cannot be deleted")
            }
            store.delete(path)
            call.respondListingOfParent(store, path)
        }
    }
}

/**
 * Reads a multipart file part as a flow of chunks, so an upload streams to disk
 * instead of being assembled in memory first.
 *
 * Each chunk is copied out of the read buffer rather than handed over directly:
 * the buffer is reused on the next pass, and a [FileStore] that queued the array
 * would otherwise write the wrong bytes.
 */
private fun PartData.FileItem.chunks(): Flow<ByteArray> = flow {
    val channel = provider()
    val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read > 0) emit(buffer.copyOf(read))
    }
}

private const val UPLOAD_CHUNK_BYTES = 64 * 1024

private suspend fun ApplicationCall.respondListingOfParent(store: FileStore, path: String) {
    val parent = VirtualPath.parentOf(path) ?: VirtualPath.ROOT
    respondJson(DirectoryListing.serializer(), store.list(parent))
}

private fun ApplicationCall.parameter(name: String): String =
    request.queryParameters[name] ?: VirtualPath.ROOT

private suspend fun <T> ApplicationCall.decode(
    serializer: kotlinx.serialization.KSerializer<T>,
): T = runCatching { fileJson.decodeFromString(serializer, receiveText()) }.getOrElse {
    throw FileStoreException(FileStoreException.Kind.Invalid, "Malformed request")
}

private suspend fun <T> ApplicationCall.respondJson(
    serializer: kotlinx.serialization.KSerializer<T>,
    value: T,
) = respondText(fileJson.encodeToString(serializer, value), ContentType.Application.Json)

/** Maps a [FileStoreException] onto the status code that fits it. */
internal fun FileStoreException.status(): HttpStatusCode = when (kind) {
    FileStoreException.Kind.NotFound -> HttpStatusCode.NotFound
    FileStoreException.Kind.NotPermitted -> HttpStatusCode.Forbidden
    FileStoreException.Kind.AlreadyExists -> HttpStatusCode.Conflict
    FileStoreException.Kind.Invalid -> HttpStatusCode.BadRequest
}
