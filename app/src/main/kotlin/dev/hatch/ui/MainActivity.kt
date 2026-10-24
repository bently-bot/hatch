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
            MaterialTheme(colorScheme = darkColorScheme()) {
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
                        StoreScreen(
                            apps = catalogue.apps,
                            categories = catalogue.categories,
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
                            onUpdate = { app ->
                                scope.launch {
                                    InstallManager.update(
                                        this@MainActivity, app,
                                        HatchApp.instance.runtime.socketDir().absolutePath,
                                    )
                                }
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
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Welcome to Hatch", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "First run downloads Debian 13 arm64 and prepares a Linux desktop environment. " +
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

@Composable
private fun StoreScreen(
    apps: List<StoreApp>,
    categories: List<CatalogueCategory>,
    installed: Set<String>,
    progress: InstallManager.Progress,
    onInstall: (StoreApp) -> Unit,
    onUninstall: (StoreApp) -> Unit,
    onUpdate: (StoreApp) -> Unit,
    onLaunch: (StoreApp) -> Unit,
    onInstallSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) } // 0=Store 1=Library

    val cats = remember(categories, apps) {
        if (categories.isNotEmpty()) categories
        else apps.map { it.category }.distinct().sorted().map { CatalogueCategory(it, it.replaceFirstChar { c -> c.uppercase() }) }
    }

    val filtered = remember(apps, query, selectedCategory, tab, installed) {
        apps.filter { app ->
            val inTab = when (tab) {
                1 -> app.id in installed
                else -> true
            }
            val inCat = selectedCategory == null || app.category == selectedCategory
            val q = query.trim().lowercase()
            val inSearch = q.isEmpty() ||
                app.name.lowercase().contains(q) ||
                app.summary.lowercase().contains(q) ||
                app.id.lowercase().contains(q)
            inTab && inCat && inSearch
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Hatch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Linux + Windows apps · real windows",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onInstallSettings) { Text("Stub installs") }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search apps") },
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(8.dp))
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Store") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Library (${installed.size})") })
            }
        }

        when (progress) {
            is InstallManager.Progress.Working -> {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "${progress.appId}: ${progress.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            is InstallManager.Progress.Failed -> {
                Text(
                    progress.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {}
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All") },
                )
            }
            items(cats) { cat ->
                FilterChip(
                    selected = selectedCategory == cat.id,
                    onClick = { selectedCategory = if (selectedCategory == cat.id) null else cat.id },
                    label = { Text(cat.name) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (tab == 1) "No apps installed yet — switch to Store."
                        else "No matches.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { it.id }) { app ->
                AppCard(
                    app = app,
                    installed = app.id in installed,
                    busy = progress is InstallManager.Progress.Working &&
                        (progress as InstallManager.Progress.Working).appId == app.id,
                    onInstall = { onInstall(app) },
                    onUninstall = { onUninstall(app) },
                    onUpdate = { onUpdate(app) },
                    onLaunch = { onLaunch(app) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppCard(
    app: StoreApp,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
    onLaunch: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(app.icon, app.category)
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(app.category)
                        if (app.wine) append(" · Wine")
                        if (app.x11) append(" · X11")
                        append(" · "); append(app.source)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    installed -> {
                        Button(onClick = onLaunch, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                            Text("Open")
                        }
                        TextButton(onClick = onUpdate, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("Update")
                        }
                        TextButton(onClick = onUninstall, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("Remove")
                        }
                    }
                    else -> Button(onClick = onInstall, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        Text("Install")
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(icon: String, category: String) {
    val (bg, glyph) = when (icon.ifEmpty { category }) {
        "text", "editors" -> Color(0xFF3B82F6) to "Aa"
        "calc", "office" -> Color(0xFF10B981) to "∑"
        "image", "graphics" -> Color(0xFFF59E0B) to "◈"
        "game", "games" -> Color(0xFFEF4444) to "▶"
        "folder", "system" -> Color(0xFF8B5CF6) to "▣"
        "web" -> Color(0xFF06B6D4) to "◉"
        "term", "dev" -> Color(0xFF64748B) to ">_"
        "windows" -> Color(0xFF6366F1) to "W"
        "test" -> Color(0xFF94A3B8) to "·"
        else -> Color(0xFF475569) to "◆"
    }
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
