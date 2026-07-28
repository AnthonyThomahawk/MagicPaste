package com.tonyt.magicpaste

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tonyt.magicpaste.ui.theme.MagicPasteTheme

private const val WEBSITE_URL = "https://thomakos.net"
private const val SOURCE_URL = "https://github.com/AnthonyThomahawk/MagicPaste"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

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
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Text(
                    text = stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppIcon()
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.about_version, context.versionName()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.about_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column {
                    AboutRow(
                        label = stringResource(R.string.about_developer),
                        value = stringResource(R.string.about_developer_name),
                    )
                    HorizontalDivider()
                    AboutRow(
                        label = stringResource(R.string.about_website),
                        value = "thomakos.net",
                        onClick = { context.openUrl(WEBSITE_URL) },
                    )
                    HorizontalDivider()
                    AboutRow(
                        label = stringResource(R.string.about_source),
                        value = "github.com/AnthonyThomahawk/MagicPaste",
                        onClick = { context.openUrl(SOURCE_URL) },
                    )
                }
            }
        }
    }
}

/**
 * The launcher icon, composed the way the system composes it: the adaptive
 * foreground over the adaptive background, clipped to a rounded square.
 *
 * The foreground is drawn at 1.5× the tile so the visible 72dp of its 108dp
 * canvas fills the tile — the same crop a launcher applies, rather than the
 * artwork floating in its own margins.
 */
@Composable
private fun AppIcon() {
    val tile = 96.dp
    Box(
        modifier = Modifier
            .size(tile)
            .clip(RoundedCornerShape(24.dp))
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(tile * 108f / 72f),
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * Read from the package manager rather than `BuildConfig`, which would mean
 * turning on another build feature for one string.
 */
private fun Context.versionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull() ?: "—"

/**
 * Tries to open [url], and does nothing if the device has no browser.
 *
 * `resolveActivity` would be the usual check, but from Android 11 it reports
 * nothing without a `<queries>` declaration, so attempting the launch and
 * catching the failure is the honest test.
 */
private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Preview(showBackground = true)
@Composable
private fun AboutPreview() {
    MagicPasteTheme {
        AboutScreen(onBack = {})
    }
}
