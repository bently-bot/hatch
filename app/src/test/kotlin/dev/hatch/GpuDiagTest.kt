package dev.hatch

import dev.hatch.gpu.GpuDiag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuDiagTest {
    // Real output of hatch-gpu-diag (Chrome 156 on Hatch's compositor, X11 rung, Zink over lavapipe).
    private val x11 = """
        HATCH-GPU kgsl=absent
        HATCH-GPU dri=
        HATCH-GPU dma_heap=
        HATCH-GPU gl_renderer=zink Vulkan 1.4(llvmpipe (LLVM 19.1.7, 256 bits) (MESA_LLVMPIPE))
        launch: google-chrome --no-sandbox ...
        HATCH-GPU chromium_gl_renderer=ANGLE (Mesa, zink Vulkan 1.4(llvmpipe (LLVM 19.1.7 256 bits) (MESA_LLVMPIPE)), OpenGL 4.6 (Core Profile) Mesa 25.0.7-2+deb13u1)
        HATCH-GPU chromium_gl_implementation=(gl=egl-angle,angle=opengl)
        HATCH-GPU feature_gpu_compositing=enabled
        HATCH-GPU feature_rasterization=enabled
        HATCH-GPU feature_webgl=enabled
        HATCH-GPU chromium_renderer_is_cpu=yes
        HATCH-GPU verdict=gpu-compositing
    """.trimIndent()

    @Test fun parsesKeyValueLinesOnly() {
        val m = GpuDiag.parse(x11)
        assertEquals("enabled", m["feature_gpu_compositing"])
        assertEquals("", m["dri"])
        assertTrue(m["chromium_gl_renderer"]!!.startsWith("ANGLE (Mesa, zink"))
        assertFalse(m.containsKey("launch: google-chrome --no-sandbox ..."))
    }

    @Test fun cpuDriverIsCalledOut() {
        val r = GpuDiag.report(GpuDiag.parse(x11), "Brave", "GPU (Zink, X11 + ANGLE/GL)", setOf("wl_shm"))
        assertEquals(true, r.gpuCompositing)
        assertEquals("GPU test: Brave composites with GL, but on a CPU driver", r.title)
        assertTrue(r.lines.any { it == "Presented via: wl_shm" })
    }

    @Test fun adrenoIsGpu() {
        val out = x11.replace("zink Vulkan 1.4(llvmpipe (LLVM 19.1.7 256 bits) (MESA_LLVMPIPE))", "zink Vulkan 1.4(Turnip Adreno (TM) 830 (MESA_TURNIP))")
            .replace("chromium_renderer_is_cpu=yes", "chromium_renderer_is_cpu=no")
        val r = GpuDiag.report(GpuDiag.parse(out), "Brave", null, emptySet())
        assertEquals("GPU test: Brave composites on the GPU", r.title)
    }

    @Test fun waylandSoftwareCompositing() {
        val r = GpuDiag.report(GpuDiag.parse("HATCH-GPU feature_gpu_compositing=disabled_software\nHATCH-GPU feature_rasterization=enabled"), "Cursor", null, setOf("wl_shm"))
        assertEquals(false, r.gpuCompositing)
        assertEquals("GPU test: Cursor uses software compositing", r.title)
    }

    @Test fun errorsAndNoApp() {
        assertNull(GpuDiag.report(GpuDiag.parse("HATCH-GPU chromium_error=DevTools did not come up"), "VS Code", null, emptySet()).gpuCompositing)
        assertEquals("GPU test: no Chromium app installed", GpuDiag.report(emptyMap(), null, null, emptySet()).title)
    }
}

class GpuVkWsiTest {
    @Test fun lines() {
        assertEquals("dma-buf (GPU buffer, no guest-side readback)", GpuDiag.vkWsiLine(emptyMap(), setOf("dma-buf")))
        assertEquals("wl_shm (driver copied to shared memory)", GpuDiag.vkWsiLine(mapOf("vk_wsi_rc" to "124"), setOf("wl_shm")))
        assertEquals("no frames (rc 1)", GpuDiag.vkWsiLine(mapOf("vk_wsi_rc" to "1"), emptySet()))
        assertEquals("not tested (vkcube missing)", GpuDiag.vkWsiLine(mapOf("vk_wsi" to "vkcube-missing"), emptySet()))
        assertTrue(GpuDiag.report(emptyMap(), null, null, emptySet(), "dma-buf (GPU buffer, no guest-side readback)").lines.first().startsWith("Vulkan Wayland present"))
    }
}
