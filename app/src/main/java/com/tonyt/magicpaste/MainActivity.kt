package com.tonyt.magicpaste

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.tonyt.magicpaste.server.MagicPasteService
import com.tonyt.magicpaste.server.ServerStatus
import com.tonyt.magicpaste.ui.theme.MagicPasteTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MagicPasteTheme {
                val app = LocalContext.current.magicPaste
                val status by app.serverController.status.collectAsState()
                val clipboard by app.clipboard.snapshot.collectAsState()

                MagicPasteScreen(
                    status = status,
                    clipboard = clipboard,
                    initialPort = app.settings.port,
                    onStart = ::startSharing,
                    onStop = { MagicPasteService.stop(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The device may have joined a different network since we started.
        magicPaste.serverController.refreshAddresses()
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

    private fun startSharing(port: Int) {
        magicPaste.settings.port = port
        MagicPasteService.start(this, port)
    }
}

@Composable
fun MagicPasteScreen(
    status: ServerStatus,
    clipboard: ClipboardSnapshot,
    initialPort: Int,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var portText by rememberSaveable { mutableStateOf(initialPort.toString()) }
    val port = portText.toIntOrNull()
    val portIsValid = port != null && port in MIN_PORT..MAX_PORT
    val isStopped = status is ServerStatus.Stopped || status is ServerStatus.Failed

    // Declining notifications does not stop the service, it only makes it
    // invisible — so either answer leads to the same next step.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onStart(port ?: initialPort) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            StatusCard(status)

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

            if (isStopped) {
                Button(
                    onClick = {
                        val chosen = port ?: return@Button
                        if (context.needsNotificationPermission()) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onStart(chosen)
                        }
                    },
                    enabled = portIsValid,
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

            ClipboardCard(clipboard)

            Text(
                text = stringResource(R.string.focus_caveat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            onStart = {},
            onStop = {},
        )
    }
}
