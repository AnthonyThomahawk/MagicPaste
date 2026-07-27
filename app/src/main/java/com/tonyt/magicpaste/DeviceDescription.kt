package com.tonyt.magicpaste

import android.os.Build

/**
 * How this device introduces itself on the web page — "OnePlus CPH2747 -
 * Android 16".
 *
 * Deliberately built from [Build] constants rather than the user's device name:
 * the model says which phone you are looking at without putting whatever someone
 * called their phone onto every browser on the network.
 */
fun deviceDescription(): String {
    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar(Char::uppercase)
    val model = Build.MODEL.orEmpty()
    // Some makers already prefix the model — "Google Pixel 9" — and repeating it
    // reads badly.
    val hardware = when {
        model.isEmpty() -> maker
        maker.isEmpty() || model.startsWith(maker, ignoreCase = true) -> model
        else -> "$maker $model"
    }
    val release = Build.VERSION.RELEASE.orEmpty()
    return if (release.isEmpty()) hardware else "$hardware - Android $release"
}
