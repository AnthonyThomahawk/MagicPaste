package com.tonyt.magicpaste.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.tonyt.magicpaste.domain.ClipboardAccess
import com.tonyt.magicpaste.domain.ClipboardSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * [ClipboardAccess] backed by the system clipboard.
 *
 * Reads are the awkward half. Since Android 10 an app may only read the
 * clipboard while it holds window focus, so [refresh] is driven by MainActivity's
 * lifecycle and the last value read is cached here for the server to hand out
 * while the app sits in the background. Writes have no such restriction and work
 * whenever the service is alive.
 */
class AndroidClipboard(context: Context) : ClipboardAccess {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val state = MutableStateFlow(ClipboardSnapshot.Empty)
    override val snapshot: StateFlow<ClipboardSnapshot> = state.asStateFlow()

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { refresh() }

    init {
        manager.addPrimaryClipChangedListener(listener)
    }

    /**
     * Re-reads the system clipboard. Call whenever the app gains focus: the
     * change listener also only fires while focused, so this is what closes the
     * gap for anything copied while MagicPaste was away.
     *
     * A read the OS refuses returns null rather than throwing, and is ignored so
     * the cached value survives.
     */
    fun refresh() {
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(appContext).toString()
        publish(text)
    }

    override suspend fun write(text: String) {
        // ClipboardManager expects the main thread on some OEM builds.
        withContext(Dispatchers.Main.immediate) {
            manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            publish(text)
        }
    }

    private fun publish(text: String) = state.update { current ->
        if (current.text == text) current else ClipboardSnapshot(text, current.revision + 1)
    }

    private companion object {
        const val CLIP_LABEL = "MagicPaste"
    }
}
