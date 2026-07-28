package com.tonyt.magicpaste

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.tonyt.magicpaste.clipboard.AndroidClipboard
import com.tonyt.magicpaste.files.AndroidFileStore
import com.tonyt.magicpaste.files.StorageAccess
import com.tonyt.magicpaste.domain.MagicPasteServer
import com.tonyt.magicpaste.domain.TokenSource
import com.tonyt.magicpaste.server.ServerController
import java.security.SecureRandom

/**
 * Process-wide wiring. The clipboard and the server outlive any single activity
 * — the whole point is that they keep working while the app is in the background
 * — so they hang off the [Application] rather than a ViewModel.
 */
class MagicPasteApp : Application() {

    lateinit var clipboard: AndroidClipboard
        private set

    lateinit var serverController: ServerController
        private set

    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        clipboard = AndroidClipboard(this)
        settings = Settings(this)
        serverController = ServerController(
            clipboard = clipboard,
            tokens = SecureTokenSource,
            device = deviceDescription(),
            certificateDirectory = filesDir,
        ) {
            // Only offered once the user has granted storage access; until then
            // the server simply has no file manager.
            if (StorageAccess.isGranted(this)) AndroidFileStore(StorageAccess.root()) else null
        }
    }
}

/** Port and PIN, remembered across launches. */
class Settings(context: Context) {

    private val preferences = context.getSharedPreferences("magicpaste", Context.MODE_PRIVATE)

    var port: Int
        get() = preferences.getInt(KEY_PORT, MagicPasteServer.DEFAULT_PORT)
        set(value) = preferences.edit { putInt(KEY_PORT, value) }

    /**
     * The PIN visitors must enter. Generated on first read rather than defaulted
     * to something like `0000`, so the server is never briefly shareable with a
     * PIN an attacker would guess first.
     *
     * A stored PIN of the wrong length is replaced rather than kept: it predates
     * a change to [PIN_LENGTH], and the app would otherwise show a PIN its own
     * validation rejects.
     */
    var pin: String
        get() = preferences.getString(KEY_PIN, null)?.takeIf { it.length == PIN_LENGTH }
            ?: randomPin().also { pin = it }
        set(value) = preferences.edit { putString(KEY_PIN, value) }

    /** Whether the clipboard is offered at all. */
    var shareClipboard: Boolean
        get() = preferences.getBoolean(KEY_SHARE_CLIPBOARD, true)
        set(value) = preferences.edit { putBoolean(KEY_SHARE_CLIPBOARD, value) }

    /**
     * Whether files are offered. Off by default — file sharing exposes far more
     * than the clipboard does, so it is opted into rather than out of.
     */
    var shareFiles: Boolean
        get() = preferences.getBoolean(KEY_SHARE_FILES, false)
        set(value) = preferences.edit { putBoolean(KEY_SHARE_FILES, value) }

    /**
     * Whether to serve HTTPS. Off by default: the certificate is self-signed, so
     * turning it on means every visitor meets a browser warning the first time,
     * and that cost should be chosen rather than inherited.
     */
    var useTls: Boolean
        get() = preferences.getBoolean(KEY_USE_TLS, false)
        set(value) = preferences.edit { putBoolean(KEY_USE_TLS, value) }

    /** A fresh PIN, for the regenerate button. */
    fun randomPin(): String = buildString {
        repeat(PIN_LENGTH) { append(SecureTokenSource.random.nextInt(10)) }
    }

    companion object {
        const val PIN_LENGTH = 4

        private const val KEY_PORT = "port"
        private const val KEY_PIN = "pin"
        private const val KEY_SHARE_CLIPBOARD = "share_clipboard"
        private const val KEY_SHARE_FILES = "share_files"
        private const val KEY_USE_TLS = "use_tls"
    }
}

/** Session tokens from the platform CSPRNG — see [TokenSource] for why it matters. */
object SecureTokenSource : TokenSource {

    internal val random = SecureRandom()

    override fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private const val TOKEN_BYTES = 24
}

/** Shorthand for reaching the wiring above from an activity or service. */
val Context.magicPaste: MagicPasteApp
    get() = applicationContext as MagicPasteApp
