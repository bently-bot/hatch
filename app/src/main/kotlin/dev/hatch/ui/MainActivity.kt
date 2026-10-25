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
        // Permissions are asked in onboarding; the runtime (and its notification) starts with the first app.

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
                    // Installs queue a home-screen pin; ask the launcher once Hatch is in front.
                    LaunchedEffect(installProg) { if (installProg is InstallManager.Progress.Done && resumed) flushPins() }
                    // A v0.1.0 stub APK: Android removes it with its own confirmation (needs REQUEST_DELETE_PACKAGES).
                    val stubs by InstallManager.legacyStubs.collectAsStateWithLifecycle()
                    LaunchedEffect(stubs) {
                        stubs.firstOrNull()?.let { pkg ->
                            InstallManager.legacyStubHandled(pkg)
                            runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
                                .onFailure { android.util.Log.w("HatchInstall", "legacy stub $pkg not removed: $it") }
                        }
                    }

                    if (!ready) {
                        val fw = dev.hatch.floating.FloatingWindows
                        Onboarding(
                            stage = setupStage,
                            log = setupLog,
                            estimate = remember { RootfsInstaller.estimate() },
                            perms = remember {
                                OnboardingPerms(
                                    notificationsNeeded = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
                                    overlayNeeded = fw.mode(this@MainActivity) == fw.MODE_FLOATING && !fw.canOverlay(this@MainActivity),
                                )
                            },
                            onStart = { RootfsInstaller.start(this@MainActivity) },
                            onAllowNotifications = { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            onAllowOverlay = { runCatching { startActivity(fw.overlayPermissionIntent(this@MainActivity)) } },
                        )
                    } else {
                        LaunchedEffect(ready) { dev.hatch.gpu.Gpu.ensureChecked(this@MainActivity) }
                        var confirmUninstall by remember { mutableStateOf<StoreApp?>(null) }
                        confirmUninstall?.let { app ->
                            UninstallDialog(app, estimate = { InstallManager.estimateUninstall(this@MainActivity, app) },
                                onConfirm = { confirmUninstall = null; scope.launch { InstallManager.uninstall(this@MainActivity, app) } },
                                onDismiss = { confirmUninstall = null })
                        }
                        HatchHome(
                            HatchUiState(catalogue.apps, catalogue.categories, installed, installProg),
                            StoreActions(
                                install = { app -> scope.launch { InstallManager.install(this@MainActivity, app, HatchApp.instance.runtime.socketDir().absolutePath) } },
                                uninstall = { app -> confirmUninstall = app },
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
                                storage = StorageActions(
                                    report = { dev.hatch.store.Storage.report(this@MainActivity) },
                                    appSize = { app -> InstallManager.estimateUninstall(this@MainActivity, app) },
                                    clearCache = { dev.hatch.store.Storage.clearCache(this@MainActivity) },
                                    // NonCancellable: Settings leaves the screen during these (back to onboarding); never stop a wipe halfway.
                                    removeAllApps = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { dev.hatch.store.Storage.removeAllApps(this@MainActivity) } },
                                    removeEverything = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { dev.hatch.store.Storage.removeEverything(this@MainActivity) } },
                                ),
                                addToHome = { app -> pin(app, explicit = true) },
                                isPinned = { app -> dev.hatch.launcher.Launchers.isPinned(this@MainActivity, app.id) },
                                softwareRendering = { app -> dev.hatch.gpu.Renderers.softwareForced(this@MainActivity, app.id) },
                                setSoftwareRendering = { app, on -> dev.hatch.gpu.Renderers.setSoftwareForced(this@MainActivity, app.id, on) },
                                shareLogs = { dev.hatch.runtime.LogShare.share(this@MainActivity) },
                                stubsEnabled = { dev.hatch.launcher.Launchers.stubsEnabled(this@MainActivity) },
                                setStubsEnabled = { on -> dev.hatch.launcher.Launchers.setStubsEnabled(this@MainActivity, on) },
                                versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "",
                            ),
                        )
                    }
                }
            }
        }
    }

    private var resumed = false
    override fun onResume() { super.onResume(); resumed = true; flushPins() }
    override fun onPause() { resumed = false; super.onPause() }

    private fun flushPins() {
        val ids = dev.hatch.launcher.Launchers.takePending(this); if (ids.isEmpty()) return
        val apps = Catalogue.load(this)
        for (id in ids) apps.firstOrNull { it.id == id }?.let { pin(it, explicit = false) }
    }

    /** Pin [app]'s icon. Quiet when automatic (after an install); explains itself when the user asked. */
    private fun pin(app: StoreApp, explicit: Boolean) {
        val icon = runCatching { dev.hatch.store.AppIcons.png(this, app) }.getOrNull()
        val r = dev.hatch.launcher.Launchers.requestPin(this, app.id, app.name, icon)
        android.util.Log.i("HatchLauncher", "pin ${app.id}: $r")
        val msg = when (r) {
            dev.hatch.launcher.Launchers.PinResult.ALREADY_PINNED -> if (explicit) "${app.name} is already on your Home screen" else null
            dev.hatch.launcher.Launchers.PinResult.UNSUPPORTED -> "Your launcher can't add shortcuts. Open ${app.name} from Hatch, or long-press Hatch's icon."
            dev.hatch.launcher.Launchers.PinResult.FAILED -> if (explicit) "Couldn't add ${app.name} to the Home screen" else null
            dev.hatch.launcher.Launchers.PinResult.REQUESTED -> null
        }
        msg?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show() }
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
