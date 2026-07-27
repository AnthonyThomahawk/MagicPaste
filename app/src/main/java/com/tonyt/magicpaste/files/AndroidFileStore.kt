package com.tonyt.magicpaste.files

import com.tonyt.magicpaste.domain.DirectoryListing
import com.tonyt.magicpaste.domain.FileEntry
import com.tonyt.magicpaste.domain.FileStore
import com.tonyt.magicpaste.domain.FileStoreException
import com.tonyt.magicpaste.domain.VirtualPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * [FileStore] over the device's shared storage.
 *
 * Every path from a request passes through [resolve], which is the only place
 * that turns a virtual path into a real [File]. That funnel is the point: string
 * normalization in `VirtualPath` decides what a path means, and this decides
 * where it may land — checked against the canonical path, so a symlink pointing
 * out of the root is caught even though its virtual path looked innocent.
 */
class AndroidFileStore(root: File) : FileStore {

    private val root: File = root.canonicalFile

    override suspend fun list(path: String): DirectoryListing = onIo {
        val directory = resolve(path)
        if (!directory.exists()) notFound(path)
        if (!directory.isDirectory) {
            throw FileStoreException(FileStoreException.Kind.Invalid, "Not a folder")
        }
        val children = directory.listFiles()
            ?: throw FileStoreException(
                FileStoreException.Kind.NotPermitted,
                "Android will not let MagicPaste read this folder",
            )
        DirectoryListing(
            path = path,
            entries = children
                .map { it.toEntry() }
                // Folders first, then case-insensitive by name — what a file
                // manager is expected to do, and listFiles promises no order.
                .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() }),
            parent = VirtualPath.parentOf(path),
        )
    }

    override suspend fun stat(path: String): FileEntry = onIo {
        val file = resolve(path)
        if (!file.exists()) notFound(path)
        file.toEntry()
    }

    override fun read(path: String): Flow<ByteArray> = flow {
        val file = resolve(path)
        if (!file.exists()) notFound(path)
        file.inputStream().use { stream ->
            val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                if (read > 0) emit(buffer.copyOf(read))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(directory: String, name: String, chunks: Flow<ByteArray>) {
        val parent = resolve(directory)
        if (!parent.isDirectory) notFound(directory)
        val target = resolve(VirtualPath.join(directory, name))
        // Written under a temporary name and moved into place, so an upload that
        // drops halfway cannot leave a truncated file wearing the real name.
        val staging = File(parent, ".magicpaste-upload-${System.nanoTime()}")
        try {
            withContext(Dispatchers.IO) {
                staging.outputStream().use { stream ->
                    chunks.collect { chunk -> stream.write(chunk) }
                }
                if (target.exists() && !target.delete()) failed("Could not replace ${target.name}")
                if (!staging.renameTo(target)) failed("Could not save ${target.name}")
            }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    override suspend fun createDirectory(directory: String, name: String) = onIo {
        val target = resolve(VirtualPath.join(directory, name))
        if (target.exists()) {
            throw FileStoreException(FileStoreException.Kind.AlreadyExists, "'$name' already exists")
        }
        if (!target.mkdir()) failed("Could not create '$name'")
    }

    override suspend fun delete(path: String) = onIo {
        val file = resolve(path)
        if (!file.exists()) notFound(path)
        if (!file.deleteRecursively()) failed("Could not delete ${file.name}")
    }

    override suspend fun rename(path: String, newName: String) = onIo {
        val file = resolve(path)
        if (!file.exists()) notFound(path)
        val parent = VirtualPath.parentOf(path) ?: VirtualPath.ROOT
        val target = resolve(VirtualPath.join(parent, newName))
        if (target.exists()) {
            throw FileStoreException(FileStoreException.Kind.AlreadyExists, "'$newName' already exists")
        }
        if (!file.renameTo(target)) failed("Could not rename ${file.name}")
    }

    override suspend fun move(path: String, destination: String) = onIo {
        val file = resolve(path)
        if (!file.exists()) notFound(path)
        val directory = resolve(destination)
        if (!directory.isDirectory) notFound(destination)
        val target = File(directory, file.name).contained()
        if (target.exists()) {
            throw FileStoreException(FileStoreException.Kind.AlreadyExists, "'${file.name}' already exists there")
        }
        if (!file.renameTo(target)) {
            // renameTo fails across filesystems, which on Android means moving
            // between internal storage and an SD card.
            failed("Could not move ${file.name} — try downloading and re-uploading it")
        }
    }

    /**
     * The single door between a request and the filesystem: joins [path] onto the
     * root and refuses anything that does not land inside it.
     */
    private fun resolve(path: String): File {
        val normalized = VirtualPath.normalize(path)
        val candidate = if (normalized == VirtualPath.ROOT) root else File(root, normalized.removePrefix("/"))
        return candidate.contained()
    }

    /**
     * Canonicalizes and confirms containment. Canonical form is what resolves
     * symlinks, which is the case plain string checks miss.
     */
    private fun File.contained(): File {
        val canonical = try {
            canonicalFile
        } catch (failure: IOException) {
            throw FileStoreException(FileStoreException.Kind.Invalid, "Unusable path")
        }
        val inside = canonical == root || canonical.path.startsWith(root.path + File.separator)
        if (!inside) {
            throw FileStoreException(FileStoreException.Kind.NotPermitted, "Outside the shared storage folder")
        }
        return canonical
    }

    private fun File.toEntry() = FileEntry(
        name = name,
        isDirectory = isDirectory,
        size = if (isDirectory) 0L else length(),
        modified = lastModified(),
    )

    private fun notFound(path: String): Nothing =
        throw FileStoreException(FileStoreException.Kind.NotFound, "$path does not exist")

    private fun failed(message: String): Nothing =
        throw FileStoreException(FileStoreException.Kind.NotPermitted, message)

    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val TRANSFER_CHUNK_BYTES = 64 * 1024
    }
}
