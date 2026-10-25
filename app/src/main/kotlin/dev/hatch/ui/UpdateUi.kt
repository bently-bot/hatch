package dev.hatch.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.hatch.R
import dev.hatch.update.SelfUpdater.State
import dev.hatch.update.SelfUpdates
import dev.hatch.update.UpdateOffer

/** What the update card and Settings can do (wired to SelfUpdater in MainActivity; no-ops in previews/tests). */
data class UpdateActions(
    val update: (UpdateOffer) -> Unit = {},
    val later: () -> Unit = {},
    val skip: (UpdateOffer) -> Unit = {},
    val openPermission: () -> Unit = {},
    val stopAppsAndUpdate: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val checkNow: () -> Unit = {},
    val autoCheck: () -> Boolean = { true },
    val setAutoCheck: (Boolean) -> Unit = {},
    val currentVersion: String = "",
)

private val UpdateGradient = Brush.linearGradient(listOf(Color(0xFF4F378B), Color(0xFF2B4C7E)))

/** Release notes: a small Markdown subset (headings, bullets, **bold**, `code`, links as text). */
@Composable
fun ReleaseNotes(md: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SelfUpdates.markdownBlocks(md).forEachIndexed { i, b -> when (b) {
            is SelfUpdates.Block.Heading -> Text(inline(b.text), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = HatchPurple, modifier = Modifier.padding(top = if (i == 0) 0.dp else 6.dp))
            is SelfUpdates.Block.Bullet -> Row(Modifier.padding(start = (b.level * 14).dp)) {
                Text("•", Modifier.width(14.dp), color = HatchBlue, style = MaterialTheme.typography.bodyMedium)
                Text(inline(b.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            is SelfUpdates.Block.Para -> Text(inline(b.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
private fun inline(text: String) = buildAnnotatedString {
    for ((t, s) in SelfUpdates.inlineSpans(text)) when (s) {
        SelfUpdates.Style.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(t) }
        SelfUpdates.Style.CODE -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33A8C7FA))) { append(t) }
        SelfUpdates.Style.PLAIN -> append(t)
    }
}

@Composable
private fun HatchBadge() = Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B1B1F)), contentAlignment = Alignment.Center) {
    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(48.dp))
}

/** The update card at the top of Hatch's home (every tab). Nothing for Idle / Checking / UpToDate. */
@Composable
fun UpdateBanner(state: State, actions: UpdateActions, modifier: Modifier = Modifier) {
    val offer = when (state) {
        is State.Available -> state.offer; is State.Downloading -> state.offer; is State.Verifying -> state.offer
        is State.NeedsPermission -> state.offer; is State.ConfirmStop -> state.offer; is State.Installing -> state.offer
        is State.Failed -> state.offer
        is State.Updated -> null
        else -> return
    }
    if (state is State.Failed && offer == null) return
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("update-card"), shape = RoundedCornerShape(24.dp),
        color = Color.Transparent) {
        Column(Modifier.background(UpdateGradient).padding(18.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HatchBadge(); Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (state is State.Updated) {
                        Text("Hatch is up to date", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Updated to ${state.version}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE8DEF8))
                    } else {
                        Text(when (state) {
                            is State.Failed -> "Update didn't finish"
                            is State.Available -> "Hatch ${offer!!.version} is available"
                            else -> "Updating to Hatch ${offer!!.version}"
                        },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("You have ${actions.currentVersion}" + (offer!!.apk.size.takeIf { it > 0 }?.let { " · ${SelfUpdates.human(it)} download" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE8DEF8))
                    }
                }
            }
            if (state is State.Updated) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(actions.dismiss) { Text("OK", color = Color.White) } }
                return@Column
            }
            offer!!
            when (state) {
                is State.Available -> {
                    if (offer.notes.isNotBlank()) Surface(shape = RoundedCornerShape(16.dp), color = Color(0x26000000)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp).testTag("update-notes")) {
                            if (!offer.notes.trimStart().startsWith("#"))
                                Text("What's new", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = HatchPurple)
                            ReleaseNotes(offer.notes)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ actions.skip(offer) }, Modifier.testTag("update-skip")) { Text("Skip this version", color = Color(0xFFCAC4D0)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(actions.later, Modifier.testTag("update-later")) { Text("Later", color = Color.White) }
                        Button({ actions.update(offer) }, Modifier.testTag("update-now"),
                            colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Text("Update") }
                    }
                }
                is State.Downloading -> {
                    SquigglyProgress(state.fraction, color = HatchBlue)
                    Text(buildString {
                        append("Downloading… ${SelfUpdates.human(state.done)}")
                        if (state.total > 0) append(" of ${SelfUpdates.human(state.total)} · ${((state.fraction ?: 0f) * 100).toInt()}%")
                    }, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE8DEF8), modifier = Modifier.testTag("update-progress"))
                }
                is State.Verifying -> {
                    SquigglyProgress(null, color = HatchBlue)
                    Text("Checking the download (checksum and signature)…", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE8DEF8))
                }
                is State.Installing -> {
                    SquigglyProgress(null, color = HatchBlue)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE8DEF8), modifier = Modifier.testTag("update-progress"))
                }
                is State.NeedsPermission -> {
                    Text("Android needs your OK once to let Hatch install its own updates. Turn on “Allow from this source”, then come back: the update carries on by itself.",
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE8DEF8))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(actions.later) { Text("Later", color = Color.White) }
                        Button(actions.openPermission, Modifier.testTag("update-permission"),
                            colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Text("Allow installs") }
                    }
                }
                is State.ConfirmStop -> {
                    Text("Updating restarts Hatch, so ${if (state.running.size == 1) "this app closes" else "these apps close"}: ${state.running.joinToString(", ")}. Save your work first.",
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE8DEF8), modifier = Modifier.testTag("update-running"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(actions.later) { Text("Not now", color = Color.White) }
                        Button(actions.stopAppsAndUpdate, Modifier.testTag("update-stop-apps"),
                            colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Text("Close apps and update") }
                    }
                }
                is State.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFDAD6))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(actions.dismiss) { Text("Close", color = Color.White) }
                        Button({ actions.update(offer) }, colors = ButtonDefaults.buttonColors(containerColor = HatchBlue, contentColor = HatchOnBlue)) { Text("Try again") }
                    }
                }
                else -> {}
            }
        }
    }
}

/** Settings → Updates: current version, the auto-check toggle, Check for updates + its result. */
@Composable
fun UpdatesSettings(state: State, actions: UpdateActions, card: @Composable (String, @Composable ColumnScope.() -> Unit) -> Unit) {
    var auto by remember { mutableStateOf(actions.autoCheck()) }
    card("Updates") {
        Text("Hatch ${actions.currentVersion}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("current-version"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatically check for updates", Modifier.weight(1f))
            Switch(auto, { auto = it; actions.setAutoCheck(it) }, Modifier.testTag("auto-update"),
                colors = SwitchDefaults.colors(checkedTrackColor = HatchBlue, checkedThumbColor = HatchOnBlue))
        }
        Text("Looks for a new Hatch on GitHub when the store opens (at most every 6 hours). Updates download only when you tap Update.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(actions.checkNow, enabled = state !is State.Checking && state !is State.Downloading && state !is State.Installing,
                modifier = Modifier.testTag("check-updates")) { Text("Check for updates") }
            Text(when (state) {
                is State.Checking -> "Checking…"
                is State.UpToDate -> "You're on the latest version."
                is State.Available -> "Hatch ${state.offer.version} is available."
                is State.Failed -> if (state.offer == null) state.message else ""
                else -> ""
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("update-status"))
        }
    }
}
