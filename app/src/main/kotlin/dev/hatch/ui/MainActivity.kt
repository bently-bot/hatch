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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hatch.HatchApp
import dev.hatch.guest.LinuxRuntime
import dev.hatch.guest.RootfsInstaller
import dev.hatch.store.Catalogue
import dev.hatch.store.InstallManager
import dev.hatch.store.InstalledApps
import dev.hatch.store.StoreApp
import kotlinx.coroutines.launch

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
                    val installedTick by LinuxRuntime.installChanges.collectAsStateWithLifecycle()
                    val setupStage by RootfsInstaller.stage.collectAsStateWithLifecycle()
                    val setupLog by RootfsInstaller.log.collectAsStateWithLifecycle()
                    val installProg by InstallManager.progress.collectAsStateWithLifecycle()
                    val ready = remember(installedTick) { LinuxRuntime.isInstalled(this@MainActivity) }
                    val apps = remember { Catalogue.load(this@MainActivity) }
                    val installed = remember(installProg, installedTick) {
                        InstalledApps.list(this@MainActivity).map { it.id }.toSet()
                    }
                    val scope = rememberCoroutineScope()

                    if (!ready) {
                        SetupScreen(
                            stage = setupStage,
                            log = setupLog,
                            onStart = { RootfsInstaller.start(this@MainActivity) },
                        )
                    } else {
                        StoreScreen(
                            apps = apps,
                            installed = installed,
                            progress = installProg,
                            onInstall = { app ->
                                scope.launch {
                                    InstallManager.install(
                                        this@MainActivity, app,
                                        HatchApp.instance.runtime.socketDir().absolutePath,
                                    )
                                }
                            },
                            onUninstall = { app ->
                                scope.launch { InstallManager.uninstall(this@MainActivity, app) }
                            },
                            onLaunch = { app -> launchApp(app) },
                            onInstallSettings = {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:$packageName"),
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    private fun launchApp(app: StoreApp) {
        startActivity(
            Intent(this, WindowActivity::class.java).apply {
                action = "dev.hatch.action.LAUNCH_APP"
                data = Uri.parse("hatch://app/${app.id}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(WindowActivity.EXTRA_APP_ID, app.id)
                putExtra(WindowActivity.EXTRA_LABEL, app.name)
            }
        )
    }
}

@Composable
private fun SetupScreen(stage: RootfsInstaller.Stage, log: List<String>, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Welcome to Hatch", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("First run downloads Debian 13 arm64 (~120 MB compressed) and sets up the Linux runtime. Fold-friendly; sessions survive fold/unfold.")
        Spacer(Modifier.height(16.dp))
        when (stage) {
            is RootfsInstaller.Stage.Idle -> Button(onClick = onStart) { Text("Download & set up") }
            is RootfsInstaller.Stage.Downloading -> {
                Text("Downloading ${stage.what}…")
                LinearProgressIndicator(progress = { stage.fraction }, modifier = Modifier.fillMaxWidth())
                Text("${stage.bytes / 1_000_000} / ${stage.total / 1_000_000} MB")
            }
            is RootfsInstaller.Stage.Working -> {
                Text(stage.what)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is RootfsInstaller.Stage.Done -> Text("Ready.")
            is RootfsInstaller.Stage.Failed -> {
                Text("Failed: ${stage.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = onStart) { Text("Retry") }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(log.takeLast(80)) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun StoreScreen(
    apps: List<StoreApp>,
    installed: Set<String>,
    progress: InstallManager.Progress,
    onInstall: (StoreApp) -> Unit,
    onUninstall: (StoreApp) -> Unit,
    onLaunch: (StoreApp) -> Unit,
    onInstallSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Hatch", style = MaterialTheme.typography.headlineMedium)
        Text("Linux + Windows apps as real Android apps", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onInstallSettings) { Text("Allow installing stubs") }
        when (progress) {
            is InstallManager.Progress.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is InstallManager.Progress.Failed -> Text(progress.message, color = MaterialTheme.colorScheme.error)
            else -> {}
        }
        if (progress is InstallManager.Progress.Working) {
            Text("${progress.appId}: ${progress.message}", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(apps) { app ->
                val isInst = app.id in installed
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(app.name, style = MaterialTheme.typography.titleMedium)
                        Text("${app.source} · ${app.summary}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isInst) {
                                Button(onClick = { onLaunch(app) }) { Text("Open") }
                                TextButton(onClick = { onUninstall(app) }) { Text("Remove") }
                            } else {
                                Button(onClick = { onInstall(app) }) { Text("Install") }
                            }
                        }
                    }
                }
            }
        }
    }
}
