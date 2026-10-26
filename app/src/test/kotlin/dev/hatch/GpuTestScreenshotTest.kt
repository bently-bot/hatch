package dev.hatch

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hatch.gpu.GpuDiag
import dev.hatch.ui.DiagnosticsCard
import dev.hatch.ui.HatchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Diagnostics › GPU test → app/build/outputs/roborazzi/gpu-test-*.png */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class GpuTestScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val fold = """
        HATCH-GPU kgsl=visible
        HATCH-GPU dri=
        HATCH-GPU dma_heap=system
        HATCH-GPU vk_device=Turnip Adreno (TM) 830
        HATCH-GPU vk_driver=turnip Mesa driver
        HATCH-GPU chromium_gl_renderer=ANGLE (Mesa, zink Vulkan 1.4(Turnip Adreno (TM) 830 (MESA_TURNIP)), OpenGL 4.6 (Core Profile) Mesa 25.2)
        HATCH-GPU chromium_gl_implementation=(gl=egl-angle,angle=opengl)
        HATCH-GPU feature_gpu_compositing=enabled
        HATCH-GPU feature_rasterization=enabled
        HATCH-GPU feature_webgl=enabled
        HATCH-GPU chromium_renderer_is_cpu=no
    """.trimIndent()

    @Test fun result() {
        val r = GpuDiag.report(GpuDiag.parse(fold), "Brave", "GPU (Zink, X11 + ANGLE/GL)", setOf("wl_shm"))
        compose.setContent { HatchTheme { Surface(Modifier.padding(8.dp)) { DiagnosticsCard({ r }) } } }
        compose.onNodeWithTag("gpu-test").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("gpu-test-title").assertIsDisplayed() }.isSuccess }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/gpu-test-result-phone.png")
    }

    @Test fun software() {
        val r = GpuDiag.report(GpuDiag.parse("HATCH-GPU kgsl=visible\nHATCH-GPU feature_gpu_compositing=disabled_software\nHATCH-GPU feature_rasterization=enabled\nHATCH-GPU chromium_gl_renderer=ANGLE (Mesa, Vulkan 1.4 (Turnip Adreno (TM) 830))"),
            "Cursor", "GPU raster (Turnip, Wayland + ANGLE/Vulkan)", setOf("wl_shm"))
        compose.setContent { HatchTheme { Surface(Modifier.padding(8.dp)) { DiagnosticsCard({ r }, initial = r) } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/gpu-test-software-phone.png")
    }
}
