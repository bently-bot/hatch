package dev.hatch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.hatch.HatchApp
import dev.hatch.store.Catalogue
import dev.hatch.store.StoreApp

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        HatchApp.instance.runtime.ensureStarted()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    StoreScreen(
                        apps = Catalogue.seed,
                        onLaunch = { app -> launchApp(app) },
                        onInstallSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    private fun launchApp(app: StoreApp) {
        // Vertical-slice: open a WindowActivity that will host the Wayland toplevel.
        // Real install path wires stub APK → this same intent.
        startActivity(
            Intent(this, WindowActivity::class.java).apply {
                action = "dev.hatch.action.LAUNCH_APP"
                data = Uri.parse("hatch://app/${app.id}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(WindowActivity.EXTRA_APP_ID, app.id)
                putExtra(WindowActivity.EXTRA_LABEL, app.name)
                putExtra(WindowActivity.EXTRA_COMMAND, app.command)
            }
        )
    }
}

@Composable
private fun StoreScreen(
    apps: List<StoreApp>,
    onLaunch: (StoreApp) -> Unit,
    onInstallSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Hatch", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Linux + Windows apps as real Android apps. Galaxy Z Fold friendly.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        TextButton(onClick = onInstallSettings) { Text("Allow installing stubs") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(apps) { app ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { onLaunch(app) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(app.name, style = MaterialTheme.typography.titleMedium)
                        Text("${app.source} · ${app.summary}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
