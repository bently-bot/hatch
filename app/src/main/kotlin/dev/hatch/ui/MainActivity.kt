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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hatch.HatchApp
import dev.hatch.guest.LinuxRuntime
import dev.hatch.guest.RootfsInstaller
import dev.hatch.store.Catalogue
import dev.hatch.store.CatalogueCategory
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
            HatchTheme {
                Surface(Modifier.fillMaxSize()) {
                    val installedTick by LinuxRuntime.installChanges.collectAsStateWithLifecycle()
                    val setupStage by RootfsInstaller.stage.collectAsStateWithLifecycle()
                    val setupLog by RootfsInstaller.log.collectAsStateWithLifecycle()
                    val installProg by InstallManager.progress.collectAsStateWithLifecycle()
                    val ready = remember(installedTick) { LinuxRuntime.isInstalled(this@MainActivity) }
                    val catalogue = remember { Catalogue.loadFile(this@MainActivity) }
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
                        LaunchedEffect(ready) { dev.hatch.gpu.Gpu.ensureChecked(this@MainActivity) }
                        HatchHome(
                            HatchUiState(catalogue.apps, catalogue.categories, installed, installProg),
                            StoreActions(
                                install = { app -> scope.launch { InstallManager.install(this@MainActivity, app, HatchApp.instance.runtime.socketDir().absolutePath) } },
                                uninstall = { app -> scope.launch { InstallManager.uninstall(this@MainActivity, app) } },
                                update = { app -> scope.launch { InstallManager.update(this@MainActivity, app, HatchApp.instance.runtime.socketDir().absolutePath) } },
                                launch = { app -> launchApp(app) },
                                installSettings = {
                                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                                },
                                gpuStatus = {
                                    val g = dev.hatch.gpu.Gpu
                                    g.describe(g.mode(this@MainActivity), g.info(this@MainActivity, LinuxRuntime.rootfsDir(this@MainActivity)))
                                },
                                gpuEnabled = { dev.hatch.gpu.Gpu.mode(this@MainActivity) == dev.hatch.gpu.Gpu.Mode.AUTO },
                                setGpuEnabled = { on ->
                                    dev.hatch.gpu.Gpu.setMode(this@MainActivity, if (on) dev.hatch.gpu.Gpu.Mode.AUTO else dev.hatch.gpu.Gpu.Mode.SOFTWARE)
                                },
                                floatingEnabled = { dev.hatch.floating.FloatingWindows.enabled(this@MainActivity) },
                                overlayMissing = {
                                    val fw = dev.hatch.floating.FloatingWindows
                                    fw.mode(this@MainActivity) == fw.MODE_FLOATING && !fw.canOverlay(this@MainActivity)
                                },
                                grantOverlay = { startActivity(dev.hatch.floating.FloatingWindows.overlayPermissionIntent(this@MainActivity)) },
                                setFloatingEnabled = { on ->
                                    val fw = dev.hatch.floating.FloatingWindows
                                    fw.setMode(this@MainActivity, if (on) fw.MODE_FLOATING else fw.MODE_TASKS)
                                    if (on && !fw.canOverlay(this@MainActivity)) { startActivity(fw.overlayPermissionIntent(this@MainActivity)); false } else true
                                },
                                cancel = { InstallManager.cancel() },
                                checkUpdates = { InstallManager.checkUpdates(this@MainActivity, catalogue.apps) },
                                updateAll = { list -> scope.launch { for (a in list) InstallManager.update(this@MainActivity, a, HatchApp.instance.runtime.socketDir().absolutePath) } },
                                bubbleEnabled = { dev.hatch.floating.Bubble.enabled(this@MainActivity) },
                                setBubbleEnabled = { on -> dev.hatch.floating.Bubble.setEnabled(this@MainActivity, on) },
                                uiScale = { getSharedPreferences("hatch", MODE_PRIVATE).getInt("ui_scale", 0) },
                                setUiScale = { v -> getSharedPreferences("hatch", MODE_PRIVATE).edit().putInt("ui_scale", v).apply() },
                                storageUsed = { InstallManager.storageUsed(this@MainActivity) },
                                versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "",
                            ),
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
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Welcome to Hatch", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "First run downloads Debian 13 (${deviceArch}) and prepares a Linux desktop environment. " +
                "Apps install as their own launcher icons and open in real windows — Fold / DeX friendly.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        when (stage) {
            is RootfsInstaller.Stage.Idle ->
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Download & set up")
                }
            is RootfsInstaller.Stage.Downloading -> {
                Text("Downloading ${stage.what}…")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { stage.fraction }, modifier = Modifier.fillMaxWidth())
                Text("${stage.bytes / 1_000_000} / ${stage.total / 1_000_000} MB",
                    style = MaterialTheme.typography.bodySmall)
            }
            is RootfsInstaller.Stage.Working -> {
                Text(stage.what)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is RootfsInstaller.Stage.Done -> Text("Ready.")
            is RootfsInstaller.Stage.Failed -> {
                Text("Failed: ${stage.message}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onStart) { Text("Retry") }
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(log.takeLast(100)) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

