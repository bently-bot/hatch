package dev.hatch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hatch.store.Storage
import dev.hatch.store.StoreApp
import dev.hatch.store.Uninstall
import kotlinx.coroutines.launch

/** Settings → Storage & reset: sizes, cache, and the destructive actions (double-confirmed). */
data class StorageActions(
    val report: suspend () -> Storage.Report,
    val appSize: suspend (StoreApp) -> Long?,
    val clearCache: suspend () -> Long,
    val removeAllApps: suspend () -> Int,
    val removeEverything: suspend () -> Long,
)

/** What the full reset removes, for its confirmation. Pure; unit-tested. */
val FULL_RESET_ITEMS = listOf(
    "Debian and every app in it",
    "Windows (Wine) and Flatpak data",
    "Files saved in Hatch's home folder",
    "Home screen icons Hatch added",
    "Downloads and caches",
)
const val CONFIRM_WORD = "DELETE"

private enum class Ask { NONE, CACHE, APPS1, APPS2, ALL1, ALL2, PICK }

@Composable
fun StorageSection(storage: StorageActions, onUninstall: (StoreApp) -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<Storage.Report?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf(Ask.NONE) }
    LaunchedEffect(refresh) { report = runCatching { storage.report() }.getOrNull() }
    val r = report

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Storage", fontWeight = FontWeight.SemiBold, color = HatchPurple)
                if (r == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Measuring…")
                } else {
                    SizeRow("Linux (Debian) and apps", r.debian, Modifier.testTag("storage-debian"))
                    SizeRow("Downloads and cache", r.cache)
                    Text("${r.apps.size} app${if (r.apps.size == 1) "" else "s"} installed", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ ask = Ask.PICK }, enabled = r != null && r.apps.isNotEmpty() && busy == null) { Text("Uninstall an app") }
                    OutlinedButton({ ask = Ask.CACHE }, enabled = r != null && busy == null) { Text("Clear cache") }
                }
                busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it) } }
                done?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = HatchBlue, modifier = Modifier.testTag("storage-done")) }
            }
        }
        // Danger zone
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reset", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text("Remove all apps keeps Linux, so new installs stay quick. Remove Linux deletes everything Hatch has set up and takes you back to the start.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val danger = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                OutlinedButton({ ask = Ask.APPS1 }, Modifier.fillMaxWidth(), enabled = r != null && r.apps.isNotEmpty() && busy == null, colors = danger,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) { Text("Remove all apps") }
                Button({ ask = Ask.ALL1 }, Modifier.fillMaxWidth().testTag("reset-all"), enabled = busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                    Text("Remove Linux and all apps")
                }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("How to fully uninstall Hatch", fontWeight = FontWeight.SemiBold)
                Text("Everything Hatch sets up lives in its own app storage. Uninstall Hatch from Android Settings → Apps → Hatch and it's all gone, including its Home screen icons. If you used “Separate app per icon”, uninstall those small apps too (or use Remove Linux first: it asks Android to remove them).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    fun run(label: String, block: suspend () -> String) {
        ask = Ask.NONE; busy = label; done = null
        scope.launch { done = runCatching { block() }.getOrElse { "That didn't finish. Try again." }; busy = null; refresh++ }
    }
    val total = r?.let { it.debian + it.cache } ?: 0L
    when (ask) {
        Ask.NONE -> {}
        Ask.PICK -> AppPicker(r?.apps.orEmpty(), storage, onPick = { ask = Ask.NONE; onUninstall(it) }, onDismiss = { ask = Ask.NONE })
        Ask.CACHE -> Confirm("Clear downloads and cache?", "Frees about ${Uninstall.human(r?.cache ?: 0)}. Apps and settings stay.", "Clear",
            danger = false, onOk = { run("Clearing…") { "Freed ${Uninstall.human(storage.clearCache())}" } }, onDismiss = { ask = Ask.NONE })
        Ask.APPS1 -> Confirm("Remove all apps?", "Uninstalls all ${r?.apps?.size ?: 0} apps and their Home screen icons. Linux stays installed.", "Continue",
            onOk = { ask = Ask.APPS2 }, onDismiss = { ask = Ask.NONE })
        Ask.APPS2 -> Confirm("Are you sure?", "This can't be undone. Files you saved in your Hatch home folder are kept.", "Remove all apps",
            onOk = { run("Removing apps…") { "Removed ${storage.removeAllApps()} apps" } }, onDismiss = { ask = Ask.NONE })
        Ask.ALL1 -> Confirm("Remove Linux and all apps?",
            "This deletes:\n" + FULL_RESET_ITEMS.joinToString("\n") { "•  $it" } + "\n\nFrees about ${Uninstall.human(total)}.", "Continue",
            onOk = { ask = Ask.ALL2 }, onDismiss = { ask = Ask.NONE })
        Ask.ALL2 -> TypeToConfirm(onOk = { run("Removing everything…") { "Freed ${Uninstall.human(storage.removeEverything())}" } }, onDismiss = { ask = Ask.NONE })
    }
}

@Composable
private fun SizeRow(label: String, bytes: Long, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Text(Uninstall.human(bytes), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
}

@Composable
private fun Confirm(title: String, body: String, ok: String, danger: Boolean = true, onOk: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
    confirmButton = {
        TextButton(onOk, colors = ButtonDefaults.textButtonColors(contentColor = if (danger) MaterialTheme.colorScheme.error else HatchBlue)) { Text(ok) }
    },
    dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
)

@Composable
private fun TypeToConfirm(onOk: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type $CONFIRM_WORD to confirm") },
        text = {
            Column {
                Text("Hatch will stop all apps, delete Linux and everything in it, and start over.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(typed, { typed = it }, singleLine = true, placeholder = { Text(CONFIRM_WORD) }, modifier = Modifier.testTag("confirm-word"))
            }
        },
        confirmButton = {
            TextButton(onOk, enabled = typed.trim().equals(CONFIRM_WORD, ignoreCase = false), modifier = Modifier.testTag("confirm-reset"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove everything") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppPicker(apps: List<StoreApp>, storage: StorageActions, onPick: (StoreApp) -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text("Uninstall an app") },
    text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(apps, key = { it.id }) { a ->
                var size by remember(a.id) { mutableStateOf<Long?>(null) }
                LaunchedEffect(a.id) { size = runCatching { storage.appSize(a) }.getOrNull() }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIconImage(a, 36, circle = true); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name); Text(size?.let { Uninstall.human(it) } ?: "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ onPick(a) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Uninstall") }
                }
            }
        }
    },
    confirmButton = { TextButton(onDismiss) { Text("Done") } },
)
