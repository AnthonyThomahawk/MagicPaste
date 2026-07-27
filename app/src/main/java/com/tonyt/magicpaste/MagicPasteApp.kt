package com.tonyt.magicpaste

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.tonyt.magicpaste.clipboard.AndroidClipboard
import com.tonyt.magicpaste.domain.MagicPasteServer
import com.tonyt.magicpaste.server.ServerController

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
        serverController = ServerController(clipboard)
        settings = Settings(this)
    }
}

/** The one port setting, remembered across launches. */
class Settings(context: Context) {

    private val preferences = context.getSharedPreferences("magicpaste", Context.MODE_PRIVATE)

    var port: Int
        get() = preferences.getInt(KEY_PORT, MagicPasteServer.DEFAULT_PORT)
        set(value) = preferences.edit { putInt(KEY_PORT, value) }

    private companion object {
        const val KEY_PORT = "port"
    }
}

/** Shorthand for reaching the wiring above from an activity or service. */
val Context.magicPaste: MagicPasteApp
    get() = applicationContext as MagicPasteApp
