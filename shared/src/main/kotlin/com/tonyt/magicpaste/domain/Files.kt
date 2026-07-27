package com.tonyt.magicpaste.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One entry in a directory listing. */
@Serializable
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Epoch milliseconds, supplied by the platform — this module has no clock. */
    val modified: Long,
)

/** The contents of one directory, plus where "up" goes. */
@Serializable
data class DirectoryListing(
    val path: String,
    val entries: List<FileEntry>,
    val parent: String?,
)

/**
 * Why a file operation could not be carried out. Kept as a small closed set so
 * routes can map it to status codes without inspecting messages.
 */
class FileStoreException(
    val kind: Kind,
    message: String,
) : Exception(message) {
    enum class Kind { NotFound, NotPermitted, AlreadyExists, Invalid }
}

/**
 * Access to a tree of files, addressed by [VirtualPath]-normalized paths that are
 * always relative to a root the implementation chooses.
 *
 * Paths crossing that root are the implementation's problem to refuse — string
 * normalization happens before this interface is reached, but it is not
 * sufficient on its own, because symlinks can escape a path that looks clean.
 */
interface FileStore {

    suspend fun list(path: String): DirectoryListing

    suspend fun stat(path: String): FileEntry

    /** Streams a file's bytes. Chunked so a large video never lands in memory whole. */
    fun read(path: String): Flow<ByteArray>

    /** Writes [chunks] to [directory]/[name], replacing any existing file. */
    suspend fun write(directory: String, name: String, chunks: Flow<ByteArray>)

    suspend fun createDirectory(directory: String, name: String)

    /** Deletes a file, or a directory and everything under it. */
    suspend fun delete(path: String)

    suspend fun rename(path: String, newName: String)

    /** Moves [path] into the directory [destination], keeping its name. */
    suspend fun move(path: String, destination: String)
}

/**
 * Turns a path from an HTTP request into a canonical `/a/b` form, or refuses it.
 *
 * This is the first of two defences against escaping the shared root — it settles
 * what the path *means*, before [FileStore] settles where it *lands*. Doing it in
 * shared code keeps the rule identical on every platform and, being pure string
 * work, makes it cheap to test exhaustively.
 */
object VirtualPath {

    const val ROOT = "/"

    /**
     * Normalizes [raw] to `/a/b`, with no trailing slash, no empty or `.`
     * segments, and no `..` at all.
     *
     * `..` is rejected rather than resolved. Resolving is the usual approach and
     * the usual source of traversal bugs — a rejected request is one that cannot
     * be smuggled through a second decoding pass.
     */
    fun normalize(raw: String): String {
        val segments = raw.split('/', '\\')
        val kept = ArrayList<String>(segments.size)
        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> throw FileStoreException(
                    FileStoreException.Kind.Invalid,
                    "Paths may not contain '..'",
                )

                segment.any { it.isForbidden() } -> throw FileStoreException(
                    FileStoreException.Kind.Invalid,
                    "Path contains an unsupported character",
                )

                else -> kept += segment
            }
        }
        return if (kept.isEmpty()) ROOT else kept.joinToString(separator = "/", prefix = "/")
    }

    /**
     * Checks a single name — for creating and renaming — where a separator would
     * silently turn one operation into another.
     */
    fun requireSimpleName(name: String): String {
        val invalid = name.isEmpty() ||
            name == "." ||
            name == ".." ||
            name.any { it == '/' || it == '\\' || it.isForbidden() }
        if (invalid) {
            throw FileStoreException(FileStoreException.Kind.Invalid, "'$name' is not a usable name")
        }
        return name
    }

    /** The parent of [path], or null at the root. */
    fun parentOf(path: String): String? {
        if (path == ROOT) return null
        val cut = path.lastIndexOf('/')
        return if (cut <= 0) ROOT else path.substring(0, cut)
    }

    /** Appends [name] to [directory]; [name] must already be a simple name. */
    fun join(directory: String, name: String): String =
        if (directory == ROOT) "/$name" else "$directory/$name"

    /**
     * NUL terminates a path in the C APIs underneath every filesystem, and the
     * control characters have no business in a file name either.
     */
    private fun Char.isForbidden(): Boolean = code < 0x20 || code == 0x7F
}
