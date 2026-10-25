package dev.hatch

import dev.hatch.floating.Bounds
import dev.hatch.floating.FloatGeometry
import org.junit.Assert.*
import org.junit.Test

class FloatGeometryTest {
    @Test fun clampKeepsHandleOnScreen() {
        val b = FloatGeometry.clamp(Bounds(5000, -300, 800, 600), 1080, 2400, 300, 200, 120)
        assertEquals(1080 - 120, b.x); assertEquals(0, b.y)
        val c = FloatGeometry.clamp(Bounds(-5000, 9000, 800, 600), 1080, 2400, 300, 200, 120)
        assertEquals(-(800 - 120), c.x); assertEquals(2400 - 120, c.y)
    }
    @Test fun clampSize() {
        val b = FloatGeometry.clamp(Bounds(0, 0, 50, 5000), 1080, 2400, 300, 200, 120)
        assertEquals(300, b.w); assertEquals(2400, b.h)
    }
    @Test fun resizeRightAndLeftCorners() {
        val s = Bounds(100, 100, 600, 400)
        assertEquals(Bounds(100, 100, 700, 450), FloatGeometry.resizeCorner(s, 100, 50, left = false, minW = 300, minH = 200))
        assertEquals(Bounds(0, 100, 700, 450), FloatGeometry.resizeCorner(s, -100, 50, left = true, minW = 300, minH = 200))
        assertEquals(Bounds(400, 100, 300, 200), FloatGeometry.resizeCorner(s, 900, -900, left = true, minW = 300, minH = 200))
    }
    @Test fun pinchScalesAroundCentre() {
        val p = FloatGeometry.pinch(Bounds(100, 100, 600, 400), 1.5f, 300, 200)
        assertEquals(Bounds(-50, 0, 900, 600), p)
    }
    @Test fun initialFitsAndCascades() {
        val a = FloatGeometry.initial(1080, 2400, 0); val b = FloatGeometry.initial(1080, 2400, 1)
        assertTrue(a.x >= 0 && a.x + a.w <= 1080 && a.y + a.h <= 2400)
        assertTrue(b.x > a.x && b.y > a.y)
    }
    @Test fun bubbleSnaps() {
        assertEquals(8, FloatGeometry.snapX(100, 150, 1080, 8))
        assertEquals(1080 - 150 - 8, FloatGeometry.snapX(700, 150, 1080, 8))
    }

    @Test fun fitContentShrinksAroundCentre() {
        val cur = Bounds(397, 342, 1598, 1429)
        val f = FloatGeometry.fitContent(cur, 952, 294, 2208, 1840)!!
        assertEquals(952, f.w); assertEquals(294, f.h)
        assertEquals(cur.x + cur.w / 2, f.x + f.w / 2); assertEquals(cur.y + cur.h / 2, f.y + f.h / 2)
    }

    @Test fun fitContentLeavesFittingWindowsAlone() {
        assertNull(FloatGeometry.fitContent(Bounds(0, 0, 800, 600), 800, 600, 2208, 1840))
        assertNull(FloatGeometry.fitContent(Bounds(0, 0, 800, 600), 0, 0, 2208, 1840))
    }

    @Test fun fitContentStaysOnScreen() {
        val f = FloatGeometry.fitContent(Bounds(2000, 1700, 200, 140), 100, 100, 2208, 1840)!!
        assert(f.x + f.w <= 2208 && f.y + f.h <= 1840 && f.x >= 0 && f.y >= 0)
    }
}
