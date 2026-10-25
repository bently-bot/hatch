package dev.hatch

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import dev.hatch.ui.ImageLoader
import dev.hatch.ui.LocalImageLoader
import dev.hatch.ui.ScreenshotCarousel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScreenshotCarouselTest {
    @get:Rule val compose = createComposeRule()
    private val bmp = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)

    private fun show(urls: List<String>, loader: ImageLoader) = compose.setContent {
        CompositionLocalProvider(LocalImageLoader provides loader) { ScreenshotCarousel(urls) }
    }

    @Test fun allBrokenHidesTheCarousel() {
        show(listOf("a", "b", "c"), ImageLoader { _, _ -> null })
        compose.waitForIdle()
        compose.onNodeWithTag("screenshots").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Image unavailable").assertCountEquals(0)
    }

    @Test fun brokenShotsDropOutAndTapOpensViewer() {
        show(listOf("ok1", "dead", "ok2"), ImageLoader { u, _ -> if (u.startsWith("ok")) bmp else null })
        compose.waitForIdle()
        compose.onNodeWithTag("screenshots").assertExists()
        compose.onAllNodesWithContentDescription("Screenshot").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Screenshot").onFirst().performClick()
        compose.onNodeWithTag("screenshot-viewer").assertExists()
    }
}
