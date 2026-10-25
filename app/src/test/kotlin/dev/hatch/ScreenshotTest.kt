package dev.hatch

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hatch.guest.RootfsInstaller.Stage
import dev.hatch.store.InstallManager
import dev.hatch.store.StoreApp
import dev.hatch.ui.AppDetailScreen
import dev.hatch.ui.HatchTheme
import dev.hatch.ui.HatchUiState
import dev.hatch.ui.Onboarding
import dev.hatch.ui.OnboardingPage
import dev.hatch.ui.OnboardingPerms
import dev.hatch.ui.StoreActions
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of onboarding + the app detail page for both form factors, written to
 * app/build/outputs/roborazzi/<name>-<phone|unfolded>.png. Phone = folded Fold / normal phone (411×914 dp);
 * unfolded = Fold inner screen (841×701 dp).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotBase(private val form: String) {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { HatchTheme { androidx.compose.material3.Surface { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name-$form.png")
    }

    private val running = Stage.Running(0, dev.hatch.guest.RootfsInstaller.FAST_STEPS, "Downloading Linux", 43_000_000, 130_000_000, 2_400_000)
    private val log = listOf("Downloading prebuilt Debian 13 (130 MB)", "Unpacked 21873 files, checksum OK")

    @Test fun onboardingWelcome() = shot("onboarding-1-welcome") {
        Onboarding(Stage.Idle, emptyList(), 130_000_000L to 486_000_000L, OnboardingPerms(true, true), {}, {}, {})
    }
    @Test fun onboardingNotifications() = shot("onboarding-2-notifications") {
        Onboarding(running, log, 0L to 0L, OnboardingPerms(true, true), {}, {}, {}, initialPage = OnboardingPage.NOTIFICATIONS)
    }
    @Test fun onboardingOverlay() = shot("onboarding-3-floating-windows") {
        Onboarding(running, log, 0L to 0L, OnboardingPerms(true, true), {}, {}, {}, initialPage = OnboardingPage.OVERLAY)
    }
    @Test fun onboardingProgress() = shot("onboarding-4-progress") {
        Onboarding(running, log, 0L to 0L, OnboardingPerms(false, false), {}, {}, {})
    }
    @Test fun onboardingFailed() = shot("onboarding-5-failed") {
        Onboarding(Stage.Failed("Couldn't reach the Debian servers. Check your internet connection and try again."), log, 0L to 0L,
            OnboardingPerms(false, false), {}, {}, {})
    }

    private val vscode = StoreApp(id = "code", name = "Visual Studio Code", summary = "Code editing. Redefined.", source = "vendor",
        packages = listOf("code"), command = listOf("code"))
    private val actions = StoreActions(install = {}, uninstall = {}, update = {}, launch = {}, installSettings = {})

    @Test fun settingsStorage() = shot("settings-storage") {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
            dev.hatch.ui.StorageSection(dev.hatch.ui.StorageActions(
                report = { dev.hatch.store.Storage.Report(1_240_000_000, 186_000_000, listOf(vscode)) },
                appSize = { 420_000_000 }, clearCache = { 0 }, removeAllApps = { 0 }, removeEverything = { 0 },
            ), onUninstall = {})
        }
    }

    @Test fun detailInstalled() = shot("detail-installed") {
        AppDetailScreen(vscode, HatchUiState(listOf(vscode), emptyList(), setOf("code"), InstallManager.Progress.Idle), actions, onBack = {})
    }
    @Test fun detailUninstallFailed() = shot("detail-error") {
        AppDetailScreen(vscode, HatchUiState(listOf(vscode), emptyList(), setOf("code"),
            InstallManager.Progress.Failed("code", "Another install or update is running. Try again in a moment.", listOf("E: Could not get lock"), "Couldn't uninstall Visual Studio Code")),
            actions, onBack = {})
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class PhoneScreenshotTest : ScreenshotBase("phone")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w841dp-h701dp-xhdpi", application = android.app.Application::class)
class UnfoldedScreenshotTest : ScreenshotBase("unfolded")
