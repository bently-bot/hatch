package dev.hatch

import dev.hatch.gpu.Gpu
import org.junit.Assert.*
import org.junit.Test

class GpuTest {
    private fun info(kgsl: Boolean = true, bundled: Boolean = true, check: String = "") = Gpu.Info(kgsl, "Adreno (TM) 830", bundled, check)

    @Test fun gpuOnlyAfterSelfTestPasses() {
        assertFalse(Gpu.useGpu(Gpu.Mode.AUTO, info(check = "")))
        assertFalse(Gpu.useGpu(Gpu.Mode.AUTO, info(check = "fail missing libs")))
        assertFalse(Gpu.useGpu(Gpu.Mode.AUTO, info(kgsl = false, check = "ok Turnip Adreno (TM) 830")))
        assertFalse(Gpu.useGpu(Gpu.Mode.SOFTWARE, info(check = "ok Turnip Adreno (TM) 830")))
        assertTrue(Gpu.useGpu(Gpu.Mode.AUTO, info(check = "ok Turnip Adreno (TM) 830")))
    }

    @Test fun envSoftwareVsGpu() {
        assertEquals("llvmpipe", Gpu.env(false)["GALLIUM_DRIVER"])
        assertNull(Gpu.env(false)["VK_ICD_FILENAMES"])
        val g = Gpu.env(true)
        assertEquals("zink", g["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals(Gpu.ICD, g["VK_ICD_FILENAMES"])
        assertEquals("sw", g["MESA_VK_WSI_DEBUG"])   // wl_shm-only compositor: Mesa's software WSI copy path
        assertEquals("1", g["LIBGL_KOPPER_DRI2"])  // Xwayland has no DRI3: GLX → drisw + Zink (kopper)
        assertEquals("0", g["LIBGL_ALWAYS_SOFTWARE"])
    }

    @Test fun describeExplainsEveryState() {
        assertTrue(Gpu.describe(Gpu.Mode.SOFTWARE, info()).contains("chosen in settings"))
        assertTrue(Gpu.describe(Gpu.Mode.AUTO, info(kgsl = false)).contains("no Adreno"))
        assertTrue(Gpu.describe(Gpu.Mode.AUTO, info(bundled = false)).contains("not included"))
        assertTrue(Gpu.describe(Gpu.Mode.AUTO, info(check = "ok Turnip Adreno (TM) 830")).startsWith("GPU accelerated"))
        assertTrue(Gpu.describe(Gpu.Mode.AUTO, info(check = "fail x")).contains("self-test failed"))
    }

    @Test fun llvmpipeUsesEveryCore() {
        assertEquals("8", dev.hatch.gpu.Gpu.env(false, cores = 8)["LP_NUM_THREADS"])
        assertEquals("16", dev.hatch.gpu.Gpu.env(false, cores = 64)["LP_NUM_THREADS"])
        assertEquals(null, dev.hatch.gpu.Gpu.env(true, cores = 8)["LP_NUM_THREADS"])
    }
}
