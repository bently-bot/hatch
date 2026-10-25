package dev.hatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hatch.store.InstallManager

/** Friendly title + body for a failure; raw log text only behind "Details". Pure; unit-tested. */
fun failureText(p: InstallManager.Progress.Failed, appName: String): Pair<String, String> {
    val title = p.title.ifEmpty { "Couldn't install $appName" }
    val body = p.message.removePrefix("Install failed").trimStart(':', ' ', '—').ifBlank { "Something went wrong. Tap Details for the log, or try again." }
    return title to body.replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." }
}

@Composable
fun ErrorCard(title: String, body: String, log: List<String>, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    var open by remember(title, body) { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("error-title"))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (log.isNotEmpty()) TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
                if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
            }
            if (open) Text(
                log.takeLast(25).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(8.dp),
            )
        }
    }
}

/** "Uninstall X?" with the space it frees (computed in the background). */
@Composable
fun UninstallDialog(app: dev.hatch.store.StoreApp, estimate: suspend () -> Long?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var freed by remember(app.id) { mutableStateOf<Long?>(null) }
    var measured by remember(app.id) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(app.id) { freed = runCatching { estimate() }.getOrNull(); measured = true }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uninstall ${app.name}?") },
        text = {
            Column {
                Text(when {
                    !measured -> "Working out how much space this frees…"
                    freed != null && freed!! > 0 -> "Frees about ${dev.hatch.store.Uninstall.human(freed!!)}."
                    else -> "Removes the app and anything only it needed."
                }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("uninstall-freed"))
                Spacer(Modifier.size(8.dp))
                Text("Its Home screen icon goes too. Files you saved in your Hatch home folder are kept.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-uninstall"),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Uninstall") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
