package dev.hatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hatch.R
import dev.hatch.guest.RootfsInstaller
import dev.hatch.guest.RootfsInstaller.Stage

/** Onboarding copy helpers (pure; unit-tested). */
object OnboardingText {
    fun mb(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${(bytes + 500_000) / 1_000_000} MB"

    fun eta(seconds: Long?): String? = when {
        seconds == null -> null
        seconds < 50 -> "less than a minute left"
        else -> "about ${(seconds + 30) / 60} min left"
    }

    /** "34% · 45 of 132 MB · about 1 min left" */
    fun progressLine(s: Stage.Running): String? {
        val f = s.fraction ?: return null
        return listOfNotNull("${(f * 100).toInt()}%", "${mb(s.bytes)} of ${mb(s.total)}", eta(s.etaSeconds)).joinToString(" · ")
    }

    fun estimate(download: Long, installed: Long): String =
        "About ${mb(download)} to download and ${mb(installed)} on your phone. Usually 1–3 minutes on Wi‑Fi."

    /** Friendly line under the title for each step. */
    fun stepHint(title: String): String = when {
        title.startsWith("Download") -> "Getting Debian 13, the Linux system your apps run on."
        title.startsWith("Unpack") -> "Putting the files in place."
        title.startsWith("Setting up") -> "Connecting Linux to your phone: network, fonts, windows."
        title.startsWith("Installing") -> "A few shared pieces every app needs."
        else -> ""
    }
}

enum class OnboardingPage { WELCOME, NOTIFICATIONS, OVERLAY, PROGRESS }

data class OnboardingPerms(val notificationsNeeded: Boolean, val overlayNeeded: Boolean)

@Composable
fun HatchLogo(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF2B2140), Color(0xFF15121C)))),
        contentAlignment = Alignment.Center,
    ) { Image(painterResource(R.drawable.ic_launcher_foreground), "Hatch", Modifier.size(size * 1.5f)) }
}

/** Full-screen frame: edge-to-edge background, content inside the safe area, width capped for unfolded/tablet screens. */
@Composable
private fun OnboardingFrame(bottom: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(32.dp)); content(); Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(bottom = 16.dp)) { bottom() }
        }
    }
}

@Composable
fun Onboarding(
    stage: Stage,
    log: List<String>,
    estimate: Pair<Long, Long>,
    perms: OnboardingPerms,
    onStart: () -> Unit,
    onAllowNotifications: () -> Unit,
    onAllowOverlay: () -> Unit,
    initialPage: OnboardingPage = if (stage is Stage.Idle) OnboardingPage.WELCOME else OnboardingPage.PROGRESS,
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    fun after(p: OnboardingPage): OnboardingPage = when {
        p == OnboardingPage.WELCOME && perms.notificationsNeeded -> OnboardingPage.NOTIFICATIONS
        p.ordinal < OnboardingPage.OVERLAY.ordinal && perms.overlayNeeded -> OnboardingPage.OVERLAY
        else -> OnboardingPage.PROGRESS
    }
    val totalSteps = 1 + (if (perms.notificationsNeeded) 1 else 0) + (if (perms.overlayNeeded) 1 else 0) + 1
    when (page) {
        OnboardingPage.WELCOME -> Welcome(estimate) { onStart(); page = after(OnboardingPage.WELCOME) }
        OnboardingPage.NOTIFICATIONS -> PermissionStep(
            icon = Icons.Default.Notifications, step = 2, of = totalSteps, stage = stage,
            title = "Know when it's ready",
            body = "Hatch shows one quiet notification while your apps run, with Stop all and Open Hatch, so nothing keeps running without you knowing.",
            allow = "Allow notifications",
            onAllow = { onAllowNotifications(); page = after(OnboardingPage.NOTIFICATIONS) },
            onSkip = { page = after(OnboardingPage.NOTIFICATIONS) },
        )
        OnboardingPage.OVERLAY -> PermissionStep(
            icon = Icons.Default.Home, step = totalSteps - 1, of = totalSteps, stage = stage,
            title = "Floating windows",
            body = "Let apps open in windows you can move and resize over anything, like a desktop. Android calls this “Display over other apps”: turn it on for Hatch, then come back.",
            allow = "Open settings",
            onAllow = { onAllowOverlay(); page = OnboardingPage.PROGRESS },
            onSkip = { page = OnboardingPage.PROGRESS },
            skip = "Use full-screen apps",
        )
        OnboardingPage.PROGRESS -> Progress(stage, log, onRetry = onStart)
    }
}

@Composable
private fun Welcome(estimate: Pair<Long, Long>, onStart: () -> Unit) = OnboardingFrame(bottom = {
    Button(onStart, Modifier.fillMaxWidth().height(56.dp).testTag("setup-start"), shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) {
        Text("Set up Hatch", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
    Spacer(Modifier.height(10.dp))
    Text("You can remove everything later in Settings → Storage.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}) {
    HatchLogo(88.dp)
    Spacer(Modifier.height(28.dp))
    Text("Welcome to Hatch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text("Desktop Linux and Windows apps on your phone, in real windows.", style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(28.dp))
    Feature(Icons.Default.Build, "Real desktop apps", "Blender, VS Code, GIMP, Notepad++ and more, each installed in a tap.")
    Feature(Icons.Default.Home, "Windows that float", "Move and resize apps over anything, folded or unfolded, with a keyboard and mouse.")
    Feature(Icons.Default.Lock, "Stays on your phone", "Apps run in their own Debian space. No account, no cloud.")
    Spacer(Modifier.height(20.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("One-time setup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(OnboardingText.estimate(estimate.first, estimate.second), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("setup-estimate"))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(HatchPurpleContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = HatchPurple, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionStep(
    icon: ImageVector, step: Int, of: Int, stage: Stage, title: String, body: String, allow: String,
    onAllow: () -> Unit, onSkip: () -> Unit, skip: String = "Not now",
) = OnboardingFrame(bottom = {
    MiniProgress(stage)
    Spacer(Modifier.height(12.dp))
    Button(onAllow, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Text(allow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
    TextButton(onSkip, Modifier.fillMaxWidth().height(48.dp)) { Text(skip) }
}) {
    Text("Step $step of $of", style = MaterialTheme.typography.labelLarge, color = HatchPurple)
    Spacer(Modifier.height(24.dp))
    Box(Modifier.size(72.dp).clip(CircleShape).background(HatchPurpleContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = HatchPurple, modifier = Modifier.size(36.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One line of setup progress under the permission steps (setup keeps going meanwhile). */
@Composable
private fun MiniProgress(stage: Stage) {
    val r = stage as? Stage.Running
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(if (r != null) "${r.title} in the background… ${r.fraction?.let { "${(it * 100).toInt()}%" } ?: ""}" else "Setting up Linux in the background…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val f = r?.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth(), color = HatchBlue)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = HatchBlue)
        }
    }
}

@Composable
private fun Progress(stage: Stage, log: List<String>, onRetry: () -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    val running = stage as? Stage.Running
    val steps = running?.steps ?: RootfsInstaller.FAST_STEPS
    val current = when (stage) { is Stage.Running -> stage.step; Stage.Done -> steps.size; else -> 0 }
    OnboardingFrame(bottom = {
        if (log.isNotEmpty()) TextButton({ details = !details }, Modifier.fillMaxWidth()) { Text(if (details) "Hide details" else "Details") }
    }) {
        HatchLogo(56.dp)
        Spacer(Modifier.height(24.dp))
        Text(if (stage is Stage.Done) "Ready" else "Setting up Linux", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("This happens once. You can leave Hatch, it keeps going.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        steps.forEachIndexed { i, name ->
            StepRow(i, name, done = i < current, active = i == current && stage !is Stage.Failed, failed = i == current && stage is Stage.Failed,
                running = running?.takeIf { i == current })
        }
        if (stage is Stage.Failed) {
            Spacer(Modifier.height(16.dp))
            ErrorCard("Setup didn't finish", stage.message, log, onRetry = onRetry)
        }
        AnimatedVisibility(details) {
            val listState = rememberLazyListState()
            LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }
            Surface(Modifier.padding(top = 16.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(12.dp), state = listState) {
                    items(log.takeLast(200)) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(index: Int, name: String, done: Boolean, active: Boolean, failed: Boolean, running: Stage.Running?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Box(Modifier.size(28.dp).clip(CircleShape).background(HatchBlue), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, "done", tint = HatchOnBlue, modifier = Modifier.size(18.dp))
                }
                active -> CircularProgressIndicator(Modifier.size(24.dp), color = HatchBlue, strokeWidth = 3.dp)
                else -> Box(Modifier.size(28.dp).border(BorderStroke(2.dp, if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant), CircleShape),
                    contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium,
                color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            if (active && running != null) {
                Text(OnboardingText.stepHint(running.title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val f = running.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = HatchBlue)
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = HatchBlue)
                OnboardingText.progressLine(running)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("setup-progress"))
                }
            }
        }
    }
}

// ---- Previews (also rendered by the Roborazzi screenshot tests) ----
internal val PREVIEW_LOG = listOf("Downloading prebuilt Debian 13 (130 MB)", "Unpacked 21873 files, 3104 links, 12 hard links (612 MB), checksum OK")
internal val PREVIEW_RUNNING = Stage.Running(0, RootfsInstaller.FAST_STEPS, "Downloading Linux", 43_000_000, 130_000_000, 2_400_000)

@Preview(name = "Welcome · phone", widthDp = 411, heightDp = 914)
@Preview(name = "Welcome · unfolded", widthDp = 841, heightDp = 701)
@Composable
fun OnboardingWelcomePreview() = HatchTheme { Onboarding(Stage.Idle, emptyList(), 130_000_000L to 486_000_000L, OnboardingPerms(true, true), {}, {}, {}) }

@Preview(name = "Notifications step", widthDp = 411, heightDp = 914)
@Composable
fun OnboardingNotificationsPreview() = HatchTheme {
    Onboarding(PREVIEW_RUNNING, PREVIEW_LOG, 0L to 0L, OnboardingPerms(true, true), {}, {}, {}, initialPage = OnboardingPage.NOTIFICATIONS)
}

@Preview(name = "Progress · phone", widthDp = 411, heightDp = 914)
@Preview(name = "Progress · unfolded", widthDp = 841, heightDp = 701)
@Composable
fun OnboardingProgressPreview() = HatchTheme { Onboarding(PREVIEW_RUNNING, PREVIEW_LOG, 0L to 0L, OnboardingPerms(false, false), {}, {}, {}) }
