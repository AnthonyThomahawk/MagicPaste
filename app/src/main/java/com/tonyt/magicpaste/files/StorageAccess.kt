package com.tonyt.magicpaste.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Whether MagicPaste may read the device's shared storage, and how to ask.
 *
 * Android changed this twice. Up to API 28 a runtime permission covered it; API
 * 29 kept that working through legacy storage; from API 30 broad access needs
 * `MANAGE_EXTERNAL_STORAGE`, which is not a runtime dialog at all — the user has
 * to grant it on a system settings screen, and Google Play restricts which apps
 * may ask.
 */
object StorageAccess {

    /** The tree the file manager exposes: the shared volume, `/sdcard`. */
    fun root(): File = Environment.getExternalStorageDirectory()

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** True when asking means sending the user to Settings rather than a dialog. */
    fun needsSettingsScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * The settings screen for All files access, aimed at this app. Some builds
     * ship without the per-app screen, so the caller should be ready for the
     * general list as a fallback.
     */
    fun settingsIntents(context: Context): List<Intent> = listOf(
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )

    /** The runtime permissions to request on API 29 and below. */
    val legacyPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
}
