package dev.hatch.ui

import androidx.compose.material.icons.filled.Add
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hatch.guest.RootfsCatalog
import dev.hatch.store.CatalogueCategory
import dev.hatch.store.InstallManager
import dev.hatch.store.StoreApp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

// ---------- theme (dark Material 3, purple accents, blue primary action — as in the Burrow demo) ----------
val HatchPurple = Color(0xFFD0BCFF)
val HatchPurpleContainer = Color(0xFF4F378B)
val HatchBlue = Color(0xFFA8C7FA)
val HatchOnBlue = Color(0xFF062E6F)

private val HatchDark = darkColorScheme(
    primary = HatchBlue, onPrimary = HatchOnBlue,
    primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = HatchPurple, onSecondary = Color(0xFF381E72),
    secondaryContainer = HatchPurpleContainer, onSecondaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF131316), surface = Color(0xFF131316),
    surfaceVariant = Color(0xFF2A2A2F), onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainer = Color(0xFF1F1F23), surfaceContainerHigh = Color(0xFF29292E), surfaceContainerHighest = Color(0xFF34343A),
    outline = Color(0xFF918F9A), outlineVariant = Color(0xFF46464F),
)

@Composable
fun HatchTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = HatchDark, content = content)

// ---------- helpers ----------
val deviceArch: String get() = if (RootfsCatalog.lxcArch() == "amd64") "x86-64" else "ARM64"

data class SourceInfo(val label: String, val colour: Color, val subtitle: String, val buildTitle: String, val buildDesc: String)

fun sourceOf(app: StoreApp): SourceInfo = when (app.source) {
    "flathub" -> SourceInfo("Flathub", Color(0xFF7FB7FF), "Flathub", "Flathub",
        "Sandboxed build from Flathub, built for this device. Updates with Update all.")
    "vendor" -> if (app.repo == null) SourceInfo("Vendor", Color(0xFF6FD6C2), app.vendorName.ifEmpty { "Vendor" },
        "${app.vendorName.ifEmpty { "Vendor" }} client",
        "Native $deviceArch build downloaded from ${app.vendorName.ifEmpty { "the vendor" }}'s own update servers (checksum-verified); it keeps itself up to date.")
    else SourceInfo("Vendor", Color(0xFF6FD6C2), app.vendorName.ifEmpty { "Vendor repository" },
        "${app.vendorName.ifEmpty { "Vendor" }} repository",
        "Native $deviceArch build from ${app.vendorName.ifEmpty { "the vendor" }}'s signed repository: the vendor's own build, which updates with Update all.")
    "release" -> if (app.wine) SourceInfo("Release", Color(0xFFFFB68A), "Windows · Wine", "Official release",
        "The developer's own $deviceArch Windows release, run with Wine. Updates with Update all.")
        else SourceInfo("Release", Color(0xFFFFB68A), "GitHub release", "Developer's release",
        "Native $deviceArch build from the developer's GitHub release (AppImage, verified by checksum).")
    "winget" -> SourceInfo("Release", Color(0xFFFFB68A), "Windows · Wine", "Official release",
        "The developer's own $deviceArch Windows release, run with Wine. Updates with Update all.")
    else -> SourceInfo("Debian", HatchPurple, "Debian 13", "Debian 13",
        "Native $deviceArch build from Debian's signed archive. Updates with Update all.")
}

/** Same app from other sources (Debian / Flathub / vendor / release): matched by normalised name. */
fun buildsOf(app: StoreApp, all: List<StoreApp>): List<StoreApp> {
    fun key(a: StoreApp) = a.name.lowercase().replace(Regex("[^a-z0-9]"), "")
    val k = key(app)
    return all.filter { it.id == app.id || (key(it) == k && k.isNotEmpty()) }.sortedBy { sourceRank(it) }
}

/** Recommended build order: the vendor's own build, then Flathub (newest upstream), Debian, then Windows releases. */
fun sourceRank(a: StoreApp): Int = when (a.source) { "vendor" -> 0; "flathub" -> 1; "debian" -> 2; else -> 3 }

private val CHIPS = listOf(
    "All" to emptySet(), "Develop" to setOf("dev", "editors"), "Productivity" to setOf("office", "internet", "utilities", "education", "science", "system"),
    "Create" to setOf("graphics"), "Media" to setOf("media"), "Games" to setOf("games"), "Windows" to setOf("windows"),
)
private val CLASSICS = listOf("doom", "emacs", "geany", "dosbox", "scummvm", "kate", "qtcreator", "thonny", "bluefish", "xterm")

@Composable
fun SourcePill(app: StoreApp) {
    val s = sourceOf(app)
    Text(s.label, color = Color(0xFF1B1B1F), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(s.colour).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun ArchTag() = Text(deviceArch, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Animated "squiggly" progress (Android 13+ media style): wavy filled part, flat remaining track. */
@Composable
fun SquigglyProgress(fraction: Float?, modifier: Modifier = Modifier, color: Color = HatchBlue) {
    val t = rememberInfiniteTransition(label = "squiggle")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Canvas(modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = "Progress ${((fraction ?: 0f) * 100).toInt()}%" }) {
        val mid = size.height / 2; val amp = size.height * 0.28f; val wl = 22.dp.toPx(); val sw = 3.5.dp.toPx()
        val f = fraction?.coerceIn(0f, 1f)
        val (a, b) = if (f == null) { val s0 = sweep * size.width * 1.3f - size.width * 0.3f; s0.coerceAtLeast(0f) to (s0 + size.width * 0.3f).coerceAtMost(size.width) } else 0f to f * size.width
        drawLine(color.copy(alpha = 0.25f), Offset(if (f == null) 0f else b + 6.dp.toPx(), mid), Offset(size.width, mid), strokeWidth = sw, cap = StrokeCap.Round)
        if (b > a) {
            val p = Path(); var x = a; p.moveTo(x, mid + amp * sin((x / wl) * 2 * PI.toFloat() - phase))
            while (x < b) { x = (x + 2f).coerceAtMost(b); p.lineTo(x, mid + amp * sin((x / wl) * 2 * PI.toFloat() - phase)) }
            drawPath(p, color, style = Stroke(width = sw, cap = StrokeCap.Round))
        }
    }
}

// ---------- root ----------
data class HatchUiState(
    val apps: List<StoreApp>,
    val categories: List<CatalogueCategory>,
    val installed: Set<String>,
    val progress: InstallManager.Progress,
    /** Hatch's own update (SelfUpdater): the card at the top and Settings → Updates. */
    val update: dev.hatch.update.SelfUpdater.State = dev.hatch.update.SelfUpdater.State.Idle,
)

@Composable
fun HatchHome(state: HatchUiState, actions: StoreActions) {
    var tab by rememberSaveable { mutableIntStateOf(1) }       // 0 Apps, 1 Get apps, 2 Settings
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = state.apps.firstOrNull { it.id == detailId }
    if (detail != null) {
        BackHandler { detailId = null }
        AppDetailScreen(detail, state, actions, onBack = { detailId = null }, onSelect = { detailId = it })
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                val c = NavigationBarItemDefaults.colors(indicatorColor = HatchPurpleContainer, selectedIconColor = Color(0xFFEADDFF))
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Apps") }, colors = c)
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.ShoppingCart, null) }, label = { Text("Get apps") }, colors = c)
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") }, colors = c)
            }
        },
    ) { pad ->
        // Re-checked whenever Hatch comes back to the front (e.g. returning from the permission screen).
        var overlayMissing by remember { mutableStateOf(actions.overlayMissing()) }
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(owner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) overlayMissing = actions.overlayMissing()
            }
            owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
        }
        Column(Modifier.padding(pad).fillMaxSize()) {
        UpdateBanner(state.update, actions.updates)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> AppsTab(state, actions, onDetail = { detailId = it })
                1 -> GetAppsTab(state, actions, onDetail = { detailId = it })
                else -> SettingsTab(actions, state.update)
            }
        }
        }
    }
}

// ---------- Apps tab ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsTab(state: HatchUiState, actions: StoreActions, onDetail: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val busy = (state.progress as? InstallManager.Progress.Working)
    val mine = state.apps.filter { it.id in state.installed || it.id == busy?.appId }
        .filter { q.isBlank() || it.name.contains(q, true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        PillSearch(q, { q = it }, "Search your apps")
        Spacer(Modifier.height(12.dp))
        if (mine.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (q.isBlank()) "No apps yet — find some in Get apps" else "No matches", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(88.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(mine, key = { it.id }) { a ->
                val installing = busy?.appId == a.id
                var menu by remember { mutableStateOf(false) }
                Column(
                    Modifier.combinedClickable(onClick = { if (a.id in state.installed && !installing) actions.launch(a) else onDetail(a.id) },
                        onLongClick = { if (a.id in state.installed && !installing) menu = true else onDetail(a.id) })
                        .semantics { contentDescription = "installed-${a.id}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Open") }, { menu = false; actions.launch(a) })
                        DropdownMenuItem({ Text("Add to home screen") }, { menu = false; actions.addToHome(a) })
                        DropdownMenuItem({ Text("App details") }, { menu = false; onDetail(a.id) })
                    }
                    AppIconImage(a, 60, circle = true)
                    Spacer(Modifier.height(6.dp))
                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    if (installing) Text("Installing : ${((busy?.fraction ?: 0f) * 100).toInt()}%", fontSize = 11.sp, color = HatchBlue, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PillSearch(value: String, onChange: (String) -> Unit, hint: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(HatchBlue),
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "Clear") }
        trailing?.invoke()
    }
}

// ---------- Get apps ----------
@Composable
private fun GetAppsTab(state: HatchUiState, actions: StoreActions, onDetail: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var sub by rememberSaveable { mutableIntStateOf(0) }     // Discover / Installed / Updates
    var chip by rememberSaveable { mutableIntStateOf(0) }
    val cats = CHIPS[chip].second
    val pool = state.apps.filter { cats.isEmpty() || it.category in cats || (it.wine && "windows" in cats) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            PillSearch(q, { q = it }, "Search apps") {
                Box(Modifier.size(32.dp).clip(CircleShape).background(HatchPurpleContainer), contentAlignment = Alignment.Center) {
                    Text("H", color = Color(0xFFEADDFF), fontWeight = FontWeight.Bold)
                }
            }
        }
        TabRow(selectedTabIndex = sub, containerColor = Color.Transparent, contentColor = HatchPurple,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[sub]), color = HatchPurple) }) {
            listOf("Discover", "Installed", "Updates").forEachIndexed { i, t ->
                Tab(sub == i, { sub = i }, text = { Text(t, color = if (sub == i) HatchPurple else MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
        if (sub == 0 || q.isNotBlank()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CHIPS.size) { i ->
                    FilterChip(chip == i, { chip = i }, label = { Text(CHIPS[i].first) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = HatchPurpleContainer, selectedLabelColor = Color(0xFFEADDFF)))
                }
            }
        }
        ProgressStrip(state)
        when {
            q.isNotBlank() -> ResultsList("Results", searchApps(pool, q), state, onDetail)
            sub == 1 -> ResultsList("Installed", state.apps.filter { it.id in state.installed }, state, onDetail)
            sub == 2 -> UpdatesPane(state, actions, onDetail)
            else -> DiscoverPane(pool, state, chip == 0, onDetail)
        }
    }
}

private fun searchApps(pool: List<StoreApp>, q: String): List<StoreApp> {
    val s = q.trim().lowercase()
    return pool.filter { a -> a.name.lowercase().contains(s) || a.summary.lowercase().contains(s) || a.id.contains(s) || a.packages.any { it.contains(s) } }
        .sortedWith(compareBy({ !it.name.lowercase().startsWith(s) }, { !it.featured }, { it.name.lowercase() }))
}

@Composable
private fun ProgressStrip(state: HatchUiState) {
    val p = state.progress
    if (p is InstallManager.Progress.Failed) {
        val name = state.apps.firstOrNull { it.id == p.appId }?.name ?: p.appId
        val (t, body) = failureText(p, name)
        ErrorCard(t, body, p.log, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onDismiss = { InstallManager.dismissFailure() })
    }
}

@Composable
private fun DiscoverPane(pool: List<StoreApp>, state: HatchUiState, all: Boolean, onDetail: (String) -> Unit) {
    val featured = pool.filter { it.featured }.take(16)
    val windows = pool.filter { it.wine }
    val flathub = pool.filter { it.source == "flathub" }.take(16)
    val vendor = pool.filter { it.source == "vendor" }
    val classics = CLASSICS.mapNotNull { id -> pool.firstOrNull { it.id == id } }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (featured.isNotEmpty()) item { Section("Featured", "Popular apps, each from its best source", featured + vendor.filter { it !in featured }, state, onDetail) }
        if (flathub.isNotEmpty()) item { Section("Popular on Flathub", "What people install most from Flathub this month, built for this device", flathub, state, onDetail) }
        if (windows.isNotEmpty() && all) item { Section("Windows apps", "Windows programs running with Wine, natively on $deviceArch", windows, state, onDetail) }
        if (classics.isNotEmpty() && all) item { Section("Classics", "Programming environments and tools that shaped computing", classics, state, onDetail) }
        item { Text("All apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp)) }
        items(pool.sortedBy { it.name.lowercase() }.take(300), key = { "all-" + it.id }) { a -> ResultRow(a, a.id in state.installed, state, onDetail) }
    }
}

@Composable
private fun Section(title: String, subtitle: String, apps: List<StoreApp>, state: HatchUiState, onDetail: (String) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(apps, key = { title + it.id }) { a -> DiscoverCard(a, a.id in state.installed) { onDetail(a.id) } }
        }
    }
}

@Composable
private fun DiscoverCard(app: StoreApp, installed: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(168.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        AppIconImage(app, 48, circle = true)
        Spacer(Modifier.height(10.dp))
        Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(app.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(34.dp))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArchTag(); SourcePill(app)
            if (installed) Icon(Icons.Default.CheckCircle, "Installed", tint = HatchBlue, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ResultsList(title: String, apps: List<StoreApp>, state: HatchUiState, onDetail: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp, 8.dp)) }
        if (apps.isEmpty()) item { Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        items(apps.take(400), key = { it.id }) { a -> ResultRow(a, a.id in state.installed, state, onDetail) }
    }
}

@Composable
private fun ResultRow(app: StoreApp, installed: Boolean, state: HatchUiState, onDetail: (String) -> Unit) {
    val busy = (state.progress as? InstallManager.Progress.Working)?.takeIf { it.appId == app.id }
    Row(Modifier.fillMaxWidth().clickable { onDetail(app.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIconImage(app, 56, circle = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ArchTag(); SourcePill(app)
                if (busy != null) Text("Installing : ${((busy.fraction ?: 0f) * 100).toInt()}%", fontSize = 11.sp, color = HatchBlue)
                else if (installed) Text("Installed", fontSize = 11.sp, color = HatchBlue)
            }
        }
    }
}

@Composable
private fun UpdatesPane(state: HatchUiState, actions: StoreActions, onDetail: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var ids by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { checking = true; ids = runCatching { actions.checkUpdates() }.getOrDefault(emptyList()); checking = false }
    val apps = state.apps.filter { it.id in (ids ?: emptyList()) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(when { checking -> "Checking for updates…"; apps.isEmpty() -> "Everything is up to date"; else -> "${apps.size} update${if (apps.size > 1) "s" else ""} available" },
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (apps.isNotEmpty()) Button(onClick = { actions.updateAll(apps) }, colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Text("Update all") }
            else IconButton(onClick = { scope.launch { checking = true; ids = runCatching { actions.checkUpdates() }.getOrDefault(emptyList()); checking = false } }) { Icon(Icons.Default.Refresh, "Check again") }
        }
        if (checking) SquigglyProgress(null)
        LazyColumn { items(apps, key = { it.id }) { a -> ResultRow(a, true, state, onDetail) } }
    }
}

@Composable
private fun OverlayBanner(onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp).clip(RoundedCornerShape(16.dp)).background(HatchPurpleContainer).padding(14.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Floating windows", fontWeight = FontWeight.SemiBold, color = Color(0xFFEADDFF))
            Text("Allow \u201cDisplay over other apps\u201d so programs open as movable windows over your home screen and other apps.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFEADDFF))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onAllow, colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Text("Allow") }
    }
}

// ---------- App details ----------
@Composable
internal fun AppDetailScreen(app: StoreApp, state: HatchUiState, actions: StoreActions, onBack: () -> Unit, onSelect: (String) -> Unit = {}) {
    val src = sourceOf(app)
    val installed = app.id in state.installed
    val working = (state.progress as? InstallManager.Progress.Working)?.takeIf { it.appId == app.id }
    val otherBusy = state.progress is InstallManager.Progress.Working && working == null
    var menu by remember { mutableStateOf(false) }
    // Edge-to-edge: the background fills behind the status bar; content stays inside the safe area (status bar,
    // camera cutout, gesture bar), and is width-capped on the unfolded Fold / tablets.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(4.dp, 8.dp).testTag("detail-topbar"), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (app.experimental) "${src.subtitle} · Experimental" else src.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SourcePill(app); Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconImage(app, 88, circle = true)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(app.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            val failed = (state.progress as? InstallManager.Progress.Failed)?.takeIf { it.appId == app.id }
            when {
                working != null -> {
                    Text(working.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SquigglyProgress(working.fraction, Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = actions.cancel) { Text("Cancel") }
                    }
                    working.sizes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                installed -> Row(Modifier.fillMaxWidth()) {
                    Button(onClick = { actions.launch(app) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 6.dp, bottomEnd = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("Open", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(3.dp))
                    Box {
                        Button(onClick = { menu = true }, Modifier.height(52.dp).width(56.dp), contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 26.dp, bottomEnd = 26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Icon(Icons.Default.ArrowDropDown, "More actions") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Add to home screen") }, { menu = false; actions.addToHome(app) })
                            var sw by remember(app.id) { mutableStateOf(actions.softwareRendering(app)) }
                            DropdownMenuItem({ Text("Use software rendering") }, { sw = !sw; actions.setSoftwareRendering(app, sw) },
                                trailingIcon = { Checkbox(sw, null) })
                            DropdownMenuItem({ Text("Update") }, { menu = false; actions.update(app) }, enabled = !otherBusy)
                            DropdownMenuItem({ Text("Uninstall") }, { menu = false; actions.uninstall(app) }, enabled = !otherBusy)
                        }
                    }
                }
                else -> Button(onClick = { actions.install(app) }, Modifier.fillMaxWidth().height(52.dp), enabled = !otherBusy, shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Text("Install", fontWeight = FontWeight.SemiBold) }
            }
            if (installed && working == null) {
                // Re-checked on resume: the launcher's own confirmation sheet (One UI / Pixel) may have just closed.
                var pinned by remember(app.id) { mutableStateOf(actions.isPinned(app)) }
                val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(owner, app.id) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) pinned = actions.isPinned(app) }
                    owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
                }
                Spacer(Modifier.height(10.dp))
                if (pinned) Text("On your Home screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else OutlinedButton(onClick = { actions.addToHome(app) }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add to home screen")
                }
                app.import?.let { spec ->
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { actions.importFiles(app) }, Modifier.fillMaxWidth().height(48.dp).testTag("import-files"), shape = RoundedCornerShape(24.dp)) {
                        Text(spec.label)
                    }
                    if (app.controls == "doom") Text(
                        "Plays the shareware episode (or Freedoom) out of the box. Own DOOM or DOOM II? Import DOOM.WAD / DOOM2.WAD from your phone. " +
                            "Keyboard, gamepad, or the on-screen pad (window menu → Touch controls).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (failed != null) {
                Spacer(Modifier.height(12.dp))
                val (t, body) = failureText(failed, app.name)
                ErrorCard(t, body, failed.log, onDismiss = { InstallManager.dismissFailure() })
            }
            ScreenshotCarousel(app.screenshots, Modifier.padding(top = 20.dp))
            Spacer(Modifier.height(24.dp))
            Text("Build", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val builds = buildsOf(app, state.apps)
            builds.forEachIndexed { i, b ->
                val bs = sourceOf(b)
                val selected = b.id == app.id
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
                    .selectable(selected, onClick = { if (!selected) onSelect(b.id) }).padding(12.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(selected, null, colors = RadioButtonDefaults.colors(selectedColor = HatchBlue))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bs.buildTitle + if (i == 0) " • Recommended" else "", fontWeight = FontWeight.SemiBold)
                        Text("${b.flatpak.ifEmpty { b.packages.firstOrNull() ?: b.id }} : $deviceArch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(bs.buildDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (b.id in state.installed) Text("Installed", fontSize = 11.sp, color = HatchBlue)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("About this app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(app.description.ifBlank { app.summary }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Text(buildString {
                append(if (app.wine) "Windows program · runs with Wine" else "Linux app · ${if (app.x11) "X11 via Xwayland" else "Wayland"}")
                if (app.packages.isNotEmpty()) append(" · package ${app.packages.first()}")
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.height(24.dp)); Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

// ---------- Settings tab ----------
@Composable
private fun SettingsTab(actions: StoreActions, update: dev.hatch.update.SelfUpdater.State = dev.hatch.update.SelfUpdater.State.Idle) {
    var floatOn by remember { mutableStateOf(actions.floatingEnabled()) }
    var bubble by remember { mutableStateOf(actions.bubbleEnabled()) }
    var gpu by remember { mutableStateOf(actions.gpuEnabled()) }
    var scale by remember { mutableIntStateOf(actions.uiScale()) }
    val storage by produceState("…") { value = runCatching { actions.storageUsed() }.getOrDefault("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        UpdatesSettings(update, actions.updates) { t, c -> SettingCard(t, c) }
        SettingCard("Windows") {
            SwitchRow("Floating windows over other apps", floatOn) { floatOn = actions.setFloatingEnabled(it) && it }
            Hint(if (floatOn) "Programs open as movable, resizable windows above everything." else "Each program window opens as its own Android app window (Recents, split screen, DeX).")
            SwitchRow("Hatch bubble at the screen edge", bubble) { bubble = it; actions.setBubbleEnabled(it) }
            Text("Interface scale", Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Auto", 1 to "1×", 2 to "2×", 3 to "3×").forEach { (v, l) ->
                    FilterChip(scale == v, { scale = v; actions.setUiScale(v) }, label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = HatchPurpleContainer))
                }
            }
            Hint("Applies after Hatch restarts its display server (close all programs).")
        }
        SettingCard("Graphics") {
            SwitchRow("Use the GPU when available", gpu) { gpu = it; actions.setGpuEnabled(it) }
            Hint(actions.gpuStatus())
            Hint("Apps start on the GPU. If an app's window stays black or it quits before drawing, Hatch retries it with the next renderer and falls back to software last, then remembers what worked. To force software for one app, use its menu (⋮ on the window, or the ▾ next to Open).")
        }
        actions.gpuTest?.let { test -> DiagnosticsCard(test) }
        SettingCard("Help") {
            Hint("Each app launch is logged on this phone. Share logs bundles them with Hatch's own log and device info and opens the share sheet. Nothing is sent until you pick where it goes.")
            OutlinedButton(onClick = actions.shareLogs, Modifier.testTag("share-logs")) { Text("Share logs") }
        }
        SettingCard("Launcher icons") {
            var stubs by remember { mutableStateOf(actions.stubsEnabled()) }
            Hint("Installed apps go on your Home screen as shortcuts: nothing extra is installed, so there's no Play Protect scan.")
            SwitchRow("Separate app per icon (advanced)", stubs) { stubs = it; actions.setStubsEnabled(it) }
            Hint("Installs a tiny launcher APK per app instead, so programs also show in the app drawer. Android scans each one and needs \u201cInstall unknown apps\u201d for Hatch.")
            if (stubs) OutlinedButton(onClick = actions.installSettings) { Text("Install permission") }
        }
        actions.storage?.let { StorageSection(it, onUninstall = actions.uninstall) }
            ?: SettingCard("Storage") { Hint(if (storage.isEmpty()) "Linux system: unknown" else "Linux system and apps: $storage") }
        SettingCard("About") { Hint("Hatch ${actions.versionName} · Debian 13 ($deviceArch) under proot · Wayland compositor · Wine") }
    }
}

/** Settings › Diagnostics: "GPU test" (GpuTest.kt) — which renderer a Chromium/Electron app really ends up on. */
@Composable
fun DiagnosticsCard(test: suspend () -> dev.hatch.gpu.GpuDiag.Report, initial: dev.hatch.gpu.GpuDiag.Report? = null) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(initial) }
    SettingCard("Diagnostics") {
        Hint("GPU test starts a throwaway copy of an installed Chromium or Electron app (Brave, VS Code, Cursor…) on the renderer it currently uses and asks it which GPU and compositing path it really got. Takes up to a minute; a test window may flash up.")
        OutlinedButton(onClick = {
            running = true
            scope.launch { report = runCatching { test() }.getOrElse { dev.hatch.gpu.GpuDiag.Report("GPU test failed: ${it.message}", emptyList(), null) }; running = false }
        }, enabled = !running, modifier = Modifier.testTag("gpu-test")) { Text(if (running) "Testing…" else "GPU test") }
        report?.let { r ->
            Text(r.title, fontWeight = FontWeight.SemiBold, color = when (r.gpuCompositing) { true -> HatchBlue; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurface },
                modifier = Modifier.testTag("gpu-test-title"))
            r.lines.forEach { Hint(it) }
        }
    }
}

@Composable private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, color = HatchPurple); content()
    }

@Composable private fun SwitchRow(text: String, on: Boolean, set: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f)); Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = HatchBlue, checkedThumbColor = HatchOnBlue))
    }

@Composable private fun Hint(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
