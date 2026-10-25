package dev.hatch

import androidx.test.core.app.ApplicationProvider
import dev.hatch.gpu.Renderers
import dev.hatch.gpu.Renderers.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RenderersTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun kinds() {
        assertEquals(Kind.CHROMIUM, Renderers.kind(listOf("/usr/share/code/code", "--no-sandbox")))
        assertEquals(Kind.CHROMIUM, Renderers.kind(listOf("/usr/share/cursor/cursor", "--no-sandbox")))
        assertEquals(Kind.CHROMIUM, Renderers.kind(listOf("brave-browser", "--no-sandbox", "--test-type")))
        assertEquals(Kind.STEAM, Renderers.kind(listOf("/usr/local/bin/hatch-steam")))
        assertEquals(Kind.GENERIC, Renderers.kind(listOf("mousepad")))
        assertEquals(Kind.GENERIC, Renderers.kind(listOf("blender")))
    }

    @Test fun gpuFirstSoftwareLast() {
        for (k in Kind.values()) {
            val l = Renderers.ladder(k, gpuAvailable = true)
            assertTrue("$k starts on the GPU", l.first().gpu)
            assertEquals("$k ends on software", "software", l.last().id)
            assertTrue("$k: only the last rung is software", l.dropLast(1).all { it.gpu })
            assertEquals(listOf("software"), Renderers.ladder(k, gpuAvailable = false).map { it.id })
        }
        val c = Renderers.ladder(Kind.CHROMIUM, true)
        assertEquals(listOf("gpu-x11", "gpu-wayland", "gpu-raster", "software"), c.map { it.id })
        assertTrue(c.first().x11 == true && "--ozone-platform=x11" in c.first().args)
        assertTrue(c.all { "--no-sandbox" in it.args && "--disable-gpu-sandbox" in it.args && "--disable-dev-shm-usage" in it.args })
        assertTrue("--disable-gpu" in c.last().args)
        val s = Renderers.ladder(Kind.STEAM, true)
        assertTrue("default Steam rung keeps CEF GPU compositing", s.first().args.isEmpty())
        assertEquals(listOf("-cef-disable-gpu-compositing"), s[1].args)
    }

    @Test fun advanceRememberAndSoftwareOverride() {
        val id = "code"; val l = Renderers.ladder(Kind.CHROMIUM, true)
        Renderers.forget(ctx, id)
        assertEquals("gpu-x11", Renderers.current(ctx, id, l).id)
        assertEquals("gpu-wayland", Renderers.advance(ctx, id, l, l[0])!!.id)
        assertEquals("gpu-wayland", Renderers.current(ctx, id, l).id)
        Renderers.markWorked(ctx, id, l[1])
        assertEquals("gpu-wayland", Renderers.worked(ctx, id))
        assertNull(Renderers.advance(ctx, id, l, l.last()))
        Renderers.setSoftwareForced(ctx, id, true)
        assertEquals("software", Renderers.current(ctx, id, l).id)
        Renderers.setSoftwareForced(ctx, id, false)
        assertEquals("back to GPU-first", "gpu-x11", Renderers.current(ctx, id, l).id)
    }
}
