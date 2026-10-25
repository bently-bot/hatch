package dev.hatch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hatch.store.InstallManager
import dev.hatch.store.StoreApp
import dev.hatch.ui.HatchHome
import dev.hatch.ui.HatchTheme
import dev.hatch.ui.HatchUiState
import dev.hatch.ui.StoreActions
import dev.hatch.ui.UpdateActions
import dev.hatch.ui.UpdateBanner
import dev.hatch.ui.UpdatesSettings
import dev.hatch.update.GhAsset
import dev.hatch.update.SelfUpdater.State
import dev.hatch.update.SelfUpdates
import dev.hatch.update.UpdateOffer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The self-update card in each state + Settings → Updates → app/build/outputs/roborazzi/update-*.png */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class UpdateScreenshotBase(private val form: String) {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { HatchTheme { Surface { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/update-$name-$form.png")
    }

    private val notes = SelfUpdates.shortenNotes("""
        ## What's new
        - **In-app updates**: Hatch now updates itself from GitHub Releases
        - Downloads are checked against their `sha256` and Hatch's signing key
        - Running Linux apps are closed cleanly before an update
        ## Fixes
        - Uninstalling Steam (and other big apps) no longer fails
        **Full Changelog**: https://github.com/bently-bot/hatch/compare/v0.2.1...v0.3.1
    """.trimIndent())
    private val offer = UpdateOffer("0.3.1", "Hatch 0.3.1", notes,
        GhAsset("hatch-0.3.1.apk", 31_400_000, "https://example.invalid/hatch-0.3.1.apk"), "https://example.invalid/s", null)
    private val actions = UpdateActions(currentVersion = "0.3.0")

    @Test fun available() = shot("1-available") { UpdateBanner(State.Available(offer), actions) }
    @Test fun downloading() = shot("2-downloading") { UpdateBanner(State.Downloading(offer, 13_600_000, 31_400_000), actions) }
    @Test fun permission() = shot("3-permission") { UpdateBanner(State.NeedsPermission(offer, java.io.File("x.apk")), actions) }
    @Test fun confirmStop() = shot("4-confirm-stop") {
        UpdateBanner(State.ConfirmStop(offer, java.io.File("x.apk"), listOf("Galculator", "GIMP")), actions)
    }
    @Test fun installing() = shot("5-installing") { UpdateBanner(State.Installing(offer, "Closing Linux apps and installing…"), actions) }
    @Test fun failed() = shot("6-failed") {
        UpdateBanner(State.Failed("The download doesn't match its checksum, so it wasn't installed.", offer), actions)
    }
    @Test fun updated() = shot("7-updated") { UpdateBanner(State.Updated("0.3.1"), actions) }
    @Test fun settings() = shot("8-settings") {
        Column(Modifier.padding(16.dp)) {
            UpdatesSettings(State.UpToDate("0.3.0"), actions) { t, c ->
                Card { Column(Modifier.padding(16.dp)) { Text(t, style = MaterialTheme.typography.titleMedium); c() } }
            }
        }
    }
    @Test fun home() = shot("9-home") {
        val app = StoreApp(id = "code", name = "Visual Studio Code", summary = "Code editing. Redefined.", source = "vendor",
            packages = listOf("code"), command = listOf("code"))
        HatchHome(HatchUiState(listOf(app), emptyList(), setOf("code"), InstallManager.Progress.Idle, State.Available(offer)),
            StoreActions(install = {}, uninstall = {}, update = {}, launch = {}, installSettings = {}, updates = actions))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class PhoneUpdateScreenshotTest : UpdateScreenshotBase("phone")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w841dp-h701dp-xhdpi", application = android.app.Application::class)
class UnfoldedUpdateScreenshotTest : UpdateScreenshotBase("unfolded")
