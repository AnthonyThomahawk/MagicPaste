package com.tonyt.magicpaste.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * The clipboard as the server sees it: the text plus a monotonically increasing
 * revision.
 *
 * The revision — rather than a timestamp — is what lets clients long-poll for
 * changes ("give me the clipboard once it is newer than revision N") and keeps
 * this module free of any clock dependency.
 */
@Serializable
data class ClipboardSnapshot(val text: String, val revision: Long) {
    companion object {
        val Empty = ClipboardSnapshot(text = "", revision = 0L)
    }
}

/**
 * Platform-agnostic view of a device clipboard.
 *
 * Implementations own the revision counter: they must publish a snapshot with a
 * higher revision whenever the text changes, and must not bump it when the text
 * is unchanged (browsers would otherwise spin on the long-poll endpoint).
 */
interface ClipboardAccess {
    val snapshot: StateFlow<ClipboardSnapshot>

    /** Places [text] on the device clipboard and publishes the new snapshot. */
    suspend fun write(text: String)
}

/**
 * In-memory [ClipboardAccess] for tests and for platforms without a system
 * clipboard.
 */
class InMemoryClipboard(initialText: String = "") : ClipboardAccess {
    private val state = MutableStateFlow(
        if (initialText.isEmpty()) ClipboardSnapshot.Empty else ClipboardSnapshot(initialText, 1L)
    )

    override val snapshot: StateFlow<ClipboardSnapshot> = state.asStateFlow()

    override suspend fun write(text: String) {
        state.update { current ->
            if (current.text == text) current else ClipboardSnapshot(text, current.revision + 1)
        }
    }
}
