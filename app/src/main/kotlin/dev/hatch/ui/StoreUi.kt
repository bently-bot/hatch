package dev.hatch.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hatch.store.AppIcons
import dev.hatch.store.CatalogueCategory
import dev.hatch.store.InstallManager
import dev.hatch.store.StoreApp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Accent = Color(0xFFFF7A3D)

@Composable
fun AppIconImage(app: StoreApp, size: Int = 52, circle: Boolean = false) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(AppIcons.cached(app.id), app.id) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIcons.bitmap(ctx, app) }
    }
    Box(
        Modifier.size(size.dp).clip(if (circle) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape((size / 4).dp))
            .background(if (bmp != null) Color(0xFFF1F1F4) else categoryColour(app)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.padding((size / 8).dp).fillMaxSize())
        else Text(app.name.take(1).uppercase(), color = Color.White, fontSize = (size / 2.6).sp, fontWeight = FontWeight.Bold)
    }
}

private fun categoryColour(app: StoreApp) = when (app.category) {
    "editors" -> Color(0xFF3B82F6); "office" -> Color(0xFF10B981); "graphics" -> Color(0xFFF59E0B)
    "games" -> Color(0xFFEF4444); "system" -> Color(0xFF8B5CF6); "internet" -> Color(0xFF06B6D4)
    "dev" -> Color(0xFF64748B); "windows" -> Color(0xFF6366F1); "media" -> Color(0xFFEC4899)
    else -> Color(0xFF475569)
}

/** What a remote image is doing; [Failed] is final for this visit (never an endless spinner). */
sealed interface ImageState {
    data object Loading : ImageState
    data class Loaded(val bitmap: Bitmap) : ImageState
    data object Failed : ImageState
}

/** Loader used by [RemoteImage]; tests swap it via [LocalImageLoader]. */
fun interface ImageLoader { suspend fun load(url: String, maxPx: Int): Bitmap? }

val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

private class DiskImageLoader(private val cacheDir: File) : ImageLoader {
    override suspend fun load(url: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = dev.hatch.store.ImageCache.fetch(cacheDir, url) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
        if (bmp == null) dev.hatch.store.ImageCache.dropCorrupt(cacheDir, url)
        bmp
    }
}

@Composable
private fun rememberImageLoader(): ImageLoader {
    LocalImageLoader.current?.let { return it }
    val ctx = LocalContext.current
    return remember(ctx) {
        dev.hatch.store.ImageCache.userAgent = "Hatch/${dev.hatch.BuildConfig.VERSION_NAME} (+https://github.com/bently-bot/hatch)"
        DiskImageLoader(ctx.cacheDir)
    }
}

@Composable
fun rememberImageState(url: String, maxPx: Int = 900): ImageState {
    val loader = rememberImageLoader()
    val st by produceState<ImageState>(ImageState.Loading, url, maxPx) {
        value = runCatching { loader.load(url, maxPx) }.getOrNull()?.let { ImageState.Loaded(it) } ?: ImageState.Failed
    }
    return st
}

/** Stateless frame: spinner while loading, the image, or a "broken image" placeholder. */
@Composable
fun ImageFrame(state: ImageState, modifier: Modifier, onClick: (() -> Unit)? = null, fit: ContentScale = ContentScale.Fit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF26262B))
            .then(if (onClick != null && state is ImageState.Loaded) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is ImageState.Loaded -> Image(state.bitmap.asImageBitmap(), "Screenshot", contentScale = fit, modifier = Modifier.fillMaxSize())
            ImageState.Loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            ImageState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { contentDescription = "Image unavailable" }) {
                Text("🖼", fontSize = 26.sp, color = Color(0xFF8A8A92), modifier = Modifier.alpha(0.45f))
                Text("Image unavailable", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A8A92))
            }
        }
    }
}

@Composable
internal fun RemoteImage(url: String, modifier: Modifier, maxPx: Int = 900, onClick: (() -> Unit)? = null) =
    ImageFrame(rememberImageState(url, maxPx), modifier, onClick)

/**
 * Screenshot strip for the detail page: shots that fail to load drop out, the whole strip disappears when none work,
 * and tapping one opens a full-screen, swipeable viewer.
 */
@Composable
fun ScreenshotCarousel(urls: List<String>, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 200.dp) {
    if (urls.isEmpty()) return
    val failed = remember(urls) { mutableStateListOf<String>() }
    val visible = urls.filter { it !in failed }
    if (visible.isEmpty()) return
    var full by remember(urls) { mutableStateOf<String?>(null) }
    LazyRow(modifier.testTag("screenshots"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(visible, key = { it }) { url ->
            val st = rememberImageState(url)
            LaunchedEffect(st) { if (st is ImageState.Failed) failed.add(url) }
            ImageFrame(st, Modifier.height(height).width(height * 1.5f), onClick = { full = url })
        }
    }
    full?.let { start ->
        ScreenshotViewer(visible, visible.indexOf(start).coerceAtLeast(0)) { full = null }
    }
}

@Composable
private fun ScreenshotViewer(urls: List<String>, start: Int, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(start) { urls.size }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("screenshot-viewer")) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                ImageFrame(rememberImageState(urls[page], maxPx = 2400), Modifier.fillMaxSize().clickable(onClick = onClose))
            }
            Text("${pager.currentPage + 1} / ${urls.size}", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
                Text("✕", color = Color.White, fontSize = 22.sp)
            }
        }
    }
}


data class StoreActions(
    val install: (StoreApp) -> Unit,
    val uninstall: (StoreApp) -> Unit,
    val update: (StoreApp) -> Unit,
    val launch: (StoreApp) -> Unit,
    val installSettings: () -> Unit,
    /** Graphics status line for the settings sheet (see Gpu.describe). */
    val gpuStatus: () -> String = { "Software rendering" },
    val gpuEnabled: () -> Boolean = { false },
    val setGpuEnabled: (Boolean) -> Unit = {},
    val floatingEnabled: () -> Boolean = { false },
    /** Floating mode is selected but "Display over other apps" isn't granted yet (programs would open full screen). */
    val overlayMissing: () -> Boolean = { false },
    val grantOverlay: () -> Unit = {},
    /** Returns false when the overlay permission must be granted first (the permission screen was opened). */
    val setFloatingEnabled: (Boolean) -> Boolean = { true },
    val cancel: () -> Unit = {},
    /** Refresh package lists; returns ids of installed apps with updates. */
    val checkUpdates: suspend () -> List<String> = { emptyList() },
    val updateAll: (List<StoreApp>) -> Unit = {},
    val bubbleEnabled: () -> Boolean = { true },
    val setBubbleEnabled: (Boolean) -> Unit = {},
    val uiScale: () -> Int = { 0 },
    val setUiScale: (Int) -> Unit = {},
    val storageUsed: suspend () -> String = { "" },
    val versionName: String = "",
    /** Pin the app's icon to the home screen (launcher shortcut; no APK). */
    val addToHome: (StoreApp) -> Unit = {},
    /** Settings → Storage & reset (null in previews/tests). */
    val storage: StorageActions? = null,
    /** Per-app "Use software rendering" (GPU-first otherwise) and the renderer that last worked. */
    val softwareRendering: (StoreApp) -> Boolean = { false },
    val setSoftwareRendering: (StoreApp, Boolean) -> Unit = { _, _ -> },
    val rendererInfo: (StoreApp) -> String = { "" },
    val shareLogs: () -> Unit = {},
    /** Catalogue `import` spec (e.g. DOOM's "Import WAD"): pick files in Android storage and copy them into the guest. */
    val importFiles: (StoreApp) -> Unit = {},
    val isPinned: (StoreApp) -> Boolean = { false },
    /** Advanced: per-app stub APKs instead of shortcuts (off by default). */
    val stubsEnabled: () -> Boolean = { false },
    val setStubsEnabled: (Boolean) -> Unit = {},
)

@Composable
private fun SettingsDialog(actions: StoreActions, onDismiss: () -> Unit) {
    var gpuOn by remember { mutableStateOf(actions.gpuEnabled()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Windows", fontWeight = FontWeight.SemiBold)
                var floatOn by remember { mutableStateOf(actions.floatingEnabled()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Floating windows over other apps", Modifier.weight(1f))
                    Switch(checked = floatOn, onCheckedChange = { floatOn = actions.setFloatingEnabled(it) && it })
                }
                Text(if (floatOn) "Programs open as movable, resizable windows above everything, with a bubble to reach Hatch."
                    else "Each program window opens as its own Android app window (Recents card, split screen, DeX).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text("Graphics", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use the GPU when available", Modifier.weight(1f))
                    Switch(checked = gpuOn, onCheckedChange = { gpuOn = it; actions.setGpuEnabled(it) })
                }
                Text(actions.gpuStatus(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Applies to apps opened after the change.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                HorizontalDivider()
                Text("App installs", fontWeight = FontWeight.SemiBold)
                Text("Hatch adds each app to your launcher as its own icon. Android asks once for permission to do this.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = actions.installSettings) { Text("Install permission") }
            }
        },
    )
}

@Composable
fun StoreScreen(
    apps: List<StoreApp>,
    categories: List<CatalogueCategory>,
    installed: Set<String>,
    progress: InstallManager.Progress,
    actions: StoreActions,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val busyId = (progress as? InstallManager.Progress.Working)?.appId

    val detail = detailId?.let { id -> apps.firstOrNull { it.id == id } }
    if (detail != null) {
        BackHandler { detailId = null }
        AppDetail(detail, detail.id in installed, busyId, progress, actions) { detailId = null }
        return
    }

    val q = query.trim().lowercase()
    val filtered = remember(apps, q, category, tab, installed) {
        apps.filter { a ->
            (tab == 0 || a.id in installed) &&
                (category == null || a.category == category) &&
                (q.isEmpty() || a.name.lowercase().contains(q) || a.summary.lowercase().contains(q) ||
                    a.id.contains(q) || a.packages.any { it.contains(q) })
        }.let { list -> if (q.isEmpty()) list else list.sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 } }
    }
    val showFeatured = tab == 0 && q.isEmpty() && category == null

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Hatch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("${apps.size} Linux & Windows apps · each in its own window",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var showSettings by remember { mutableStateOf(false) }
                TextButton(onClick = { showSettings = true }) { Text("Settings") }
                if (showSettings) SettingsDialog(actions) { showSettings = false }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search apps") }, shape = RoundedCornerShape(16.dp),
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } },
            )
            Spacer(Modifier.height(6.dp))
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Discover") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Library (${installed.size})") })
            }
        }
        ProgressBanner(progress, apps)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") }) }
                    items(categories, key = { it.id }) { c ->
                        FilterChip(selected = category == c.id, onClick = { category = if (category == c.id) null else c.id },
                            label = { Text(c.name) })
                    }
                }
            }
            if (showFeatured) {
                item { SectionTitle("Featured") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(apps.filter { it.featured }, key = { "f-" + it.id }) { a ->
                            FeaturedCard(a, a.id in installed) { detailId = a.id }
                        }
                    }
                }
                item { SectionTitle("All apps") }
            }
            if (filtered.isEmpty()) item {
                Text(if (tab == 1) "Nothing installed yet — find something in Discover." else "No matches.",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(filtered, key = { it.id }) { a ->
                AppRow(a, a.id in installed, busyId, onClick = { detailId = a.id }, actions = actions)
            }
        }
    }
}

@Composable private fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))

@Composable
private fun ProgressBanner(progress: InstallManager.Progress, apps: List<StoreApp>) {
    when (progress) {
        is InstallManager.Progress.Working -> Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            val name = apps.firstOrNull { it.id == progress.appId }?.name ?: progress.appId
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            Text("$name — ${progress.message}", style = MaterialTheme.typography.bodySmall, color = Accent, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        is InstallManager.Progress.Failed -> {
            val (t, body) = failureText(progress, apps.firstOrNull { it.id == progress.appId }?.name ?: progress.appId)
            ErrorCard(t, body, progress.log, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onDismiss = { InstallManager.dismissFailure() })
        }
        else -> {}
    }
}

@Composable
private fun FeaturedCard(app: StoreApp, installed: Boolean, onClick: () -> Unit) {
    ElevatedCard(Modifier.width(150.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIconImage(app, 64)
            Spacer(Modifier.height(8.dp))
            Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (installed) "Installed" else badge(app), style = MaterialTheme.typography.labelSmall,
                color = if (installed) Accent else MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}

private fun badge(app: StoreApp) = buildString {
    append(if (app.wine) "Windows" else "Linux")
    if (app.experimental) append(" · Experimental")
    else if (app.verified) append(" · ✓ tested")
}

@Composable
private fun AppRow(app: StoreApp, installed: Boolean, busyId: String?, onClick: () -> Unit, actions: StoreActions) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "app-${app.id}" }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIconImage(app)
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(badge(app), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            ActionButton(app, installed, busyId, actions, compact = true)
        }
    }
}

@Composable
private fun ActionButton(app: StoreApp, installed: Boolean, busyId: String?, actions: StoreActions, compact: Boolean) {
    when {
        busyId == app.id -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Accent)
        installed -> Button(onClick = { actions.launch(app) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Open") }
        else -> Button(onClick = { actions.install(app) }, enabled = busyId == null) { Text(if (compact) "Install" else "Install") }
    }
}

@Composable
private fun AppDetail(
    app: StoreApp, installed: Boolean, busyId: String?, progress: InstallManager.Progress,
    actions: StoreActions, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIconImage(app, 84)
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(app.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(badge(app) + (if (app.license.isNotEmpty()) " · ${app.license}" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(app, installed, busyId, actions, compact = false)
            if (installed && busyId == null) {
                OutlinedButton(onClick = { actions.update(app) }) { Text("Update") }
                OutlinedButton(onClick = { actions.uninstall(app) }) { Text("Remove") }
            }
        }
        if (progress is InstallManager.Progress.Working && progress.appId == app.id) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            Text(progress.message, style = MaterialTheme.typography.bodySmall, color = Accent)
        }
        ScreenshotCarousel(app.screenshots, Modifier.padding(top = 18.dp), height = 220.dp)
        Spacer(Modifier.height(18.dp))
        Text(app.description.ifBlank { app.summary }, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            buildString {
                append(if (app.wine) "Runs with Wine (ARM64) · " else "Debian 13 · ")
                if (app.packages.isNotEmpty()) append("package: ${app.packages.joinToString()}")
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}
