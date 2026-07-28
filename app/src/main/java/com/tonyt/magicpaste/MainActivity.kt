package com.tonyt.magicpaste

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tonyt.magicpaste.domain.ClipboardSnapshot
import com.tonyt.magicpaste.files.StorageAccess
import com.tonyt.magicpaste.server.MagicPasteService
import com.tonyt.magicpaste.server.ServerStatus
import com.tonyt.magicpaste.tls.Fingerprint
import com.tonyt.magicpaste.ui.theme.MagicPasteTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * Storage access is granted on a settings screen we do not control, so the
     * only way to learn the answer is to look again on the way back.
     */
    private val storageGranted = MutableStateFlow(false)

    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            storageGranted.value = StorageAccess.isGranted(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MagicPasteTheme {
                val app = LocalContext.current.magicPaste
                val status by app.serverController.status.collectAsState()
                val clipboard by app.clipboard.snapshot.collectAsState()
                val granted by storageGranted.collectAsState()
                var showAbout by rememberSaveable { mutableStateOf(false) }

                BackHandler(enabled = showAbout) { showAbout = false }

                if (showAbout) {
                    AboutScreen(onBack = { showAbout = false })
                    return@MagicPasteTheme
                }

                MagicPasteScreen(
                    status = status,
                    clipboard = clipboard,
                    initialPort = app.settings.port,
                    initialPin = app.settings.pin,
                    initialShareClipboard = app.settings.shareClipboard,
                    initialShareFiles = app.settings.shareFiles,
                    initialUseTls = app.settings.useTls,
                    storageGranted = granted,
                    onStart = ::startSharing,
                    onStop = { MagicPasteService.stop(this) },
                    onNewPin = app.settings::randomPin,
                    onGrantStorage = ::requestStorageAccess,
                    onAbout = { showAbout = true },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The device may have joined a different network since we started.
        magicPaste.serverController.refreshAddresses()
        storageGranted.value = StorageAccess.isGranted(this)
    }

    /**
     * From API 30 this leaves the app entirely — All files access lives in
     * Settings. Some builds lack the per-app screen, hence the fallback.
     */
    private fun requestStorageAccess() {
        if (!StorageAccess.needsSettingsScreen()) {
            legacyStoragePermission.launch(StorageAccess.legacyPermissions)
            return
        }
        for (intent in StorageAccess.settingsIntents(this)) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(this, R.string.files_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    /**
     * Android only hands the clipboard to an app that holds window focus, so
     * this — not the change listener — is what picks up whatever was copied
     * while MagicPaste was in the background.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) magicPaste.clipboard.refresh()
    }

    private fun startSharing(
        port: Int,
        pin: String,
        shareClipboard: Boolean,
        shareFiles: Boolean,
        useTls: Boolean,
    ) {
        // Saved before the service starts, because the service reads its
        // configuration back out of settings rather than off the intent.
        with(magicPaste.settings) {
            this.port = port
            this.pin = pin
            this.shareClipboard = shareClipboard
            this.shareFiles = shareFiles
            this.useTls = useTls
        }
        MagicPasteService.start(this, port)
    }
}

@Composable
fun MagicPasteScreen(
    status: ServerStatus,
    clipboard: ClipboardSnapshot,
    initialPort: Int,
    initialPin: String,
    initialShareClipboard: Boolean,
    initialShareFiles: Boolean,
    initialUseTls: Boolean,
    storageGranted: Boolean,
    onStart: (port: Int, pin: String, shareClipboard: Boolean, shareFiles: Boolean, useTls: Boolean) -> Unit,
    onStop: () -> Unit,
    onNewPin: () -> String,
    onGrantStorage: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    var portText by rememberSaveable { mutableStateOf(initialPort.toString()) }
    var pinText by rememberSaveable { mutableStateOf(initialPin) }
    var shareClipboard by rememberSaveable { mutableStateOf(initialShareClipboard) }
    var shareFiles by rememberSaveable { mutableStateOf(initialShareFiles) }
    var useTls by rememberSaveable { mutableStateOf(initialUseTls) }
    val port = portText.toIntOrNull()
    val portIsValid = port != null && port in MIN_PORT..MAX_PORT
    val pinIsValid = pinText.length == Settings.PIN_LENGTH
    val isStopped = status is ServerStatus.Stopped || status is ServerStatus.Failed
    // Files can only be shared once Android has granted access, so the effective
    // answer is the switch AND the permission.
    val filesShared = shareFiles && storageGranted
    val hasSomethingToShare = shareClipboard || filesShared

    // Declining notifications does not stop the service, it only makes it
    // invisible — so either answer leads to the same next step.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onStart(port ?: initialPort, pinText, shareClipboard, filesShared, useTls) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAbout) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.about_title),
                    )
                }
            }

            StatusCard(status)

            SharingCard(
                shareClipboard = shareClipboard,
                shareFiles = shareFiles,
                storageGranted = storageGranted,
                isStopped = isStopped,
                onShareClipboard = { shareClipboard = it },
                onShareFiles = { shareFiles = it },
                onGrant = onGrantStorage,
            )

            SecurityCard(
                useTls = useTls,
                isStopped = isStopped,
                fingerprint = (status as? ServerStatus.Running)?.fingerprint,
                onUseTls = { useTls = it },
            )

            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.port_label)) },
                singleLine = true,
                enabled = isStopped,
                isError = portText.isNotEmpty() && !portIsValid,
                supportingText = {
                    if (portText.isNotEmpty() && !portIsValid) {
                        Text(stringResource(R.string.port_error, MIN_PORT, MAX_PORT))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = pinText,
                onValueChange = { pinText = it.filter(Char::isDigit).take(Settings.PIN_LENGTH) },
                label = { Text(stringResource(R.string.pin_label)) },
                singleLine = true,
                enabled = isStopped,
                isError = pinText.isNotEmpty() && !pinIsValid,
                supportingText = {
                    if (!pinIsValid) {
                        stringResource(R.string.pin_error, Settings.PIN_LENGTH)
                    }
                },
                trailingIcon = {
                    if (isStopped) {
                        TextButton(onClick = { pinText = onNewPin() }) {
                            Text(stringResource(R.string.action_new_pin))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            if (isStopped) {
                Button(
                    onClick = {
                        val chosen = port ?: return@Button
                        if (context.needsNotificationPermission()) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onStart(chosen, pinText, shareClipboard, filesShared, useTls)
                        }
                    },
                    enabled = portIsValid && pinIsValid && hasSomethingToShare,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.action_start))
                }
            } else {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.action_stop))
                }
            }

            if (shareClipboard) {
                ClipboardCard(clipboard)
            }
        }
    }
}

@Composable
private fun StatusCard(status: ServerStatus) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(status.indicatorColor())
                )
                Text(text = status.headline(), style = MaterialTheme.typography.titleMedium)
            }

            when (status) {
                is ServerStatus.Running -> if (status.urls.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_network),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.open_on_any_device),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status.urls.forEach { url ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { context.shareText(url) }) {
                                Text(stringResource(R.string.action_share))
                            }
                        }
                    }
                }

                is ServerStatus.Failed -> Text(
                    text = status.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Unit
            }
        }
    }
}

/**
 * What gets shared: the clipboard, the files, or both.
 *
 * The choice is read when sharing starts, so the switches are only live while
 * stopped — the alternative is a UI that claims a change took effect when the
 * running server still has the old routes mounted.
 */
@Composable
private fun SharingCard(
    shareClipboard: Boolean,
    shareFiles: Boolean,
    storageGranted: Boolean,
    isStopped: Boolean,
    onShareClipboard: (Boolean) -> Unit,
    onShareFiles: (Boolean) -> Unit,
    onGrant: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.sharing_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            ToggleRow(
                label = stringResource(R.string.share_clipboard),
                description = stringResource(R.string.share_clipboard_detail),
                checked = shareClipboard,
                enabled = isStopped,
                onCheckedChange = onShareClipboard,
            )

            ToggleRow(
                label = stringResource(R.string.share_files),
                description = if (storageGranted) {
                    stringResource(R.string.share_files_detail)
                } else {
                    stringResource(R.string.files_needs_permission)
                },
                checked = shareFiles && storageGranted,
                enabled = isStopped && storageGranted,
                onCheckedChange = onShareFiles,
            )

            if (!storageGranted) {
                Button(
                    onClick = onGrant,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.action_grant_storage))
                }
            }

            if (!shareClipboard && !(shareFiles && storageGranted)) {
                Text(
                    text = stringResource(R.string.sharing_nothing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * The encryption switch and, once running, the fingerprint to check against.
 *
 * The fingerprint is the whole point of showing anything here: a self-signed
 * certificate encrypts the connection but proves nothing about who is on the
 * other end, and comparing this against what the browser reports is what turns
 * "encrypted" into "encrypted, to this device".
 */
@Composable
private fun SecurityCard(
    useTls: Boolean,
    isStopped: Boolean,
    fingerprint: Fingerprint?,
    onUseTls: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.security_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            ToggleRow(
                label = stringResource(R.string.use_tls),
                description = stringResource(R.string.use_tls_detail),
                checked = useTls,
                enabled = isStopped,
                onCheckedChange = onUseTls,
            )

            if (fingerprint != null) {
                FingerprintPanel(fingerprint)
            } else if (useTls && isStopped) {
                Text(
                    text = stringResource(R.string.tls_fingerprint_when_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * The fingerprint, in the two shapes that get used: the ends large enough to
 * compare across a room, and the full digest for anyone actually reading it off
 * a browser's certificate dialog.
 */
@Composable
private fun FingerprintPanel(fingerprint: Fingerprint) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.fingerprint_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = fingerprint.head,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "…",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = fingerprint.tail,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = stringResource(R.string.fingerprint_compare),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = fingerprint.grouped,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ClipboardCard(clipboard: ClipboardSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.shared_clipboard),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = clipboard.text.ifEmpty { stringResource(R.string.clipboard_empty) },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                modifier = Modifier.heightIn(max = 160.dp),
            )
        }
    }
}

@Composable
private fun ServerStatus.headline(): String = when (this) {
    is ServerStatus.Running -> stringResource(R.string.status_running, port)
    ServerStatus.Starting -> stringResource(R.string.status_starting)
    is ServerStatus.Failed -> stringResource(R.string.status_failed)
    ServerStatus.Stopped -> stringResource(R.string.status_stopped)
}

@Composable
private fun ServerStatus.indicatorColor(): Color = when (this) {
    is ServerStatus.Running -> Color(0xFF2E9E4F)
    ServerStatus.Starting -> Color(0xFFE0A63B)
    is ServerStatus.Failed -> MaterialTheme.colorScheme.error
    ServerStatus.Stopped -> MaterialTheme.colorScheme.outline
}

private fun Context.needsNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

private fun Context.shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(intent, null))
}

private const val MIN_PORT = 1024
private const val MAX_PORT = 65535

@Preview(showBackground = true)
@Composable
private fun RunningPreview() {
    MagicPasteTheme {
        MagicPasteScreen(
            status = ServerStatus.Running(8123, listOf("http://192.168.1.42:8123")),
            clipboard = ClipboardSnapshot("the quick brown fox", 3),
            initialPort = 8123,
            initialPin = "4183",
            initialShareClipboard = true,
            initialShareFiles = true,
            initialUseTls = true,
            storageGranted = true,
            onStart = { _, _, _, _, _ -> },
            onStop = {},
            onNewPin = { "0000" },
            onGrantStorage = {},
            onAbout = {},
        )
    }
}
