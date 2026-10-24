package dev.hatch.floating

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure window-geometry rules for floating windows (unit-tested). */
data class Bounds(val x: Int, val y: Int, val w: Int, val h: Int)

object FloatGeometry {
    /** Keep the window at least partly on screen: the whole handle bar stays reachable, size within limits. */
    fun clamp(b: Bounds, screenW: Int, screenH: Int, minW: Int, minH: Int, keep: Int): Bounds {
        val w = b.w.coerceIn(min(minW, screenW), screenW)
        val h = b.h.coerceIn(min(minH, screenH), screenH)
        val x = b.x.coerceIn(-(w - keep), screenW - keep)
        val y = b.y.coerceIn(0, max(0, screenH - keep))
        return Bounds(x, y, w, h)
    }

    /** Drag a corner: [left] = bottom-left corner (x moves with the finger), otherwise bottom-right. */
    fun resizeCorner(start: Bounds, dx: Int, dy: Int, left: Boolean, minW: Int, minH: Int): Bounds =
        if (left) {
            val w = max(minW, start.w - dx)
            Bounds(start.x + (start.w - w), start.y, w, max(minH, start.h + dy))
        } else Bounds(start.x, start.y, max(minW, start.w + dx), max(minH, start.h + dy))

    /** Pinch on the handle: scale around the window centre. */
    fun pinch(start: Bounds, scale: Float, minW: Int, minH: Int): Bounds {
        val w = max(minW, (start.w * scale).roundToInt()); val h = max(minH, (start.h * scale).roundToInt())
        return Bounds(start.x + (start.w - w) / 2, start.y + (start.h - h) / 2, w, h)
    }

    /** Initial placement: 88% wide (phone) / 70% (unfolded), 62% tall, cascaded by 6% per open window. */
    fun initial(screenW: Int, screenH: Int, cascade: Int): Bounds {
        val wide = screenW > screenH * 0.8f
        // ~70% of a tablet/unfolded screen (88% wide on a portrait phone), centred, each new window cascaded down-right.
        val w = (screenW * if (wide) 0.70f else 0.88f).roundToInt()
        val h = (screenH * if (wide) 0.70f else 0.60f).roundToInt()
        val step = (min(screenW, screenH) * 0.05f).roundToInt() * (cascade % 5)
        return Bounds((screenW - w) / 2 + step, (screenH - h) / 2 + step, w, h)
    }

    /** Bubble snaps to the nearest vertical screen edge. */
    fun snapX(x: Int, size: Int, screenW: Int, margin: Int) = if (x + size / 2 < screenW / 2) margin else screenW - size - margin
}
