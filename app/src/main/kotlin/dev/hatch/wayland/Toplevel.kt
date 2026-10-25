package dev.hatch.wayland

/** An xdg_toplevel window we composite into an Android Activity (or a memory target). */
class Toplevel(val server: WaylandServer, val client: Client, val surface: SurfaceRes, val xdgSurface: XdgSurfaceRes) {
    var xdg: XdgToplevelRes? = null
    var title = ""; var appId = ""
    /** Content tracking for black-window detection: frames committed, and whether any had real pixels. */
    @Volatile var hasContent = false
    @Volatile var framesCommitted = 0
    @Volatile var lastStats = -1
    var minW = 0; var minH = 0; var maxW = 0; var maxH = 0
    /** Desired logical size (from the Android window). 0 = let the client choose. */
    var configuredW = 0; var configuredH = 0
    var activated = true
    var dirty = true
    val pendingFrames = ArrayList<CallbackRes>()
    val popups = ArrayList<Popup>()
    private var target: Long = 0
    private var targetW = 0; private var targetH = 0
    private var lastSentW = -1; private var lastSentH = -1

    fun hasTarget() = target != 0L

    /** Re-render and read back the target (memory targets; used by the test harness). Server thread only. */
    fun readPixels(out: IntArray): Boolean {
        if (target == 0L) return false
        dirty = true; render()
        Native.targetRead(target, out)
        return true
    }

    /** Call from any thread; hops onto the server thread. */
    fun attachTarget(t: Long, w: Int, h: Int) {
        val apply = {
            if (target != 0L && target != t) Native.targetRelease(target)
            target = t; targetW = w; targetH = h; dirty = true
            val logicalW = w / server.scale; val logicalH = h / server.scale
            if (logicalW != configuredW || logicalH != configuredH) {
                configuredW = logicalW; configuredH = logicalH
                sendConfigure()
            }
        }
        if (Thread.currentThread().name == "hatch-wayland") apply() else server.post(apply)
    }

    fun detachTarget() = server.post {
        if (target != 0L) { Native.targetRelease(target); target = 0 }
    }

    fun sendConfigure() {
        val w = if (configuredW > 0) configuredW else 0
        val h = if (configuredH > 0) configuredH else 0
        if (w == lastSentW && h == lastSentH) return
        lastSentW = w; lastSentH = h
        // Each window fills its own Android window: tell clients they're maximized so GTK/Qt drop
        // client-side shadows and rounded corners (the Android task frame provides the border).
        val states = if (activated) intArrayOf(1 /* maximized */, 4 /* activated */) else intArrayOf(1)
        xdg?.sendConfigure(w, h, states)
    }

    fun render() {
        xdgSurface.applyPending()
        if (target != 0L) {
            if (Native.targetBegin(target, 0xff2a2a2a.toInt())) {
                val sc = server.scale
                // Surface pixels at 1:1 (×scale), shifted so the window geometry (no CSD shadow) starts at 0,0.
                blitSurface(surface, -geomX() * sc, -geomY() * sc, surface.contentW() * sc, surface.contentH() * sc)
                for (p in popups.toTypedArray()) {
                    if (!p.surface.alive || p.surface.store == 0L) continue
                    val (ox, oy) = surfaceOrigin(p.xdgSurface)
                    blitSurface(p.surface, (ox - geomX()) * sc, (oy - geomY()) * sc,
                        p.surface.contentW() * sc, p.surface.contentH() * sc)
                }
                Native.targetEnd(target)
            }
        }
        dirty = false
        // Frame callbacks must fire even if we have no target yet, or clients stall forever.
        val frames = ArrayList(pendingFrames); pendingFrames.clear()
        for (f in frames) f.done(server.timeMs())
    }

    private fun blitSurface(s: SurfaceRes, dx: Int, dy: Int, dw: Int, dh: Int) {
        if (s.store == 0L || s.storeW == 0) return
        val vp = s.viewport
        val sx: Int; val sy: Int; val sw: Int; val sh: Int
        if (vp != null && vp.srcW > 0) {
            sx = Math.round(vp.srcX).toInt(); sy = Math.round(vp.srcY).toInt()
            sw = Math.round(vp.srcW).toInt(); sh = Math.round(vp.srcH).toInt()
        } else { sx = 0; sy = 0; sw = s.storeW; sh = s.storeH }
        Native.targetBlit(target, s.store, dx, dy, dw, dh, sx, sy, sw, sh, s.opaque)
        for (sub in s.subsurfaces) {
            if (sub.surface.store == 0L) continue
            val sdx = dx + sub.x * server.scale; val sdy = dy + sub.y * server.scale
            val sdw = sub.surface.contentW() * server.scale; val sdh = sub.surface.contentH() * server.scale
            blitSurface(sub.surface, sdx, sdy, sdw, sdh)
        }
    }

    fun destroyWindow() {
        if (!server.windows.remove(this)) return
        for (p in popups.toTypedArray()) p.destroy()
        detachTarget()
        if (surface.role is SurfaceRes.Role.ToplevelRole) surface.role = null
        server.host.windowDestroyed(this)
        xdg?.let { if (it.alive) it.client.destroyRes(it) }
    }

    fun geomX() = xdgSurface.windowGeometry?.get(0) ?: 0
    fun geomY() = xdgSurface.windowGeometry?.get(1) ?: 0

    /** Top-left of [xdg]'s surface in this toplevel's surface-local logical coordinates. */
    fun surfaceOrigin(xdg: XdgSurfaceRes, depth: Int = 0): Pair<Int, Int> {
        if (xdg === xdgSurface || depth > 16) return 0 to 0
        val p = popups.firstOrNull { it.xdgSurface === xdg } ?: return 0 to 0
        val (px, py) = surfaceOrigin(p.parentXdg, depth + 1)
        val pg = p.parentXdg.windowGeometry; val g = p.xdgSurface.windowGeometry
        return (px + (pg?.get(0) ?: 0) + p.x - (g?.get(0) ?: 0)) to (py + (pg?.get(1) ?: 0) + p.y - (g?.get(1) ?: 0))
    }

    class Hit(val surface: SurfaceRes, val x: Double, val y: Double, val popup: Popup?)

    /** Which surface is under target pixel (x,y)? Topmost popup first, else the toplevel. */
    fun hitTest(xPx: Int, yPx: Int): Hit {
        val lx = xPx.toDouble() / server.scale + geomX(); val ly = yPx.toDouble() / server.scale + geomY()
        for (p in popups.asReversed()) {
            if (!p.surface.alive) continue
            val (ox, oy) = surfaceOrigin(p.xdgSurface)
            val w = p.surface.contentW(); val h = p.surface.contentH()
            if (lx >= ox && ly >= oy && lx < ox + w && ly < oy + h) return Hit(p.surface, lx - ox, ly - oy, p)
        }
        return Hit(surface, lx, ly, null)
    }

    /** Map target-pixel (x,y) to surface-local logical coords of the toplevel surface. */
    fun toLocal(x: Int, y: Int): Pair<Double, Double> =
        Pair(x.toDouble() / server.scale + geomX(), y.toDouble() / server.scale + geomY())

    /** Dismiss every popup (outside click / focus loss). */
    fun dismissPopups() {
        for (p in popups.toTypedArray().reversed()) (p.xdgSurface.role as? XdgPopupRes)?.sendPopupDone()
    }
}

class Popup(
    val server: WaylandServer, val client: Client, val surface: SurfaceRes,
    val xdgSurface: XdgSurfaceRes, val parentXdg: XdgSurfaceRes, pos: XdgPositionerRes,
    val root: Toplevel?,
) {
    var x = 0; var y = 0
    var w = pos.w; var h = pos.h
    init { place(pos) }

    /** xdg_positioner placement: anchor point on the anchor rect, gravity, offset, then keep it on-window. */
    fun place(pos: XdgPositionerRes) {
        w = pos.w; h = pos.h
        val r = PopupPlacement.place(pos.ax, pos.ay, pos.aw, pos.ah, pos.anchor, pos.gravity, pos.ox, pos.oy, w, h)
        x = r.first; y = r.second
        val tl = root ?: return
        val bw = tl.xdgSurface.windowGeometry?.get(2)?.takeIf { it > 0 } ?: tl.configuredW
        val bh = tl.xdgSurface.windowGeometry?.get(3)?.takeIf { it > 0 } ?: tl.configuredH
        if (bw <= 0 || bh <= 0) return
        // parent geometry origin, in root-geometry coordinates
        val (po, poy) = tl.surfaceOrigin(parentXdg)
        val pg = parentXdg.windowGeometry
        val baseX = po + (pg?.get(0) ?: 0) - tl.geomX(); val baseY = poy + (pg?.get(1) ?: 0) - tl.geomY()
        val c = PopupPlacement.constrain(baseX + x, baseY + y, w, h, bw, bh, pos.ax + baseX, pos.ay + baseY, pos.aw, pos.ah, pos.constraint)
        x = c.first - baseX; y = c.second - baseY
    }
    fun sendConfigure() {
        (xdgSurface.role as? XdgPopupRes)?.sendConfigure(x, y, w, h)
        root?.dirty = true
    }
    fun destroy() {
        root?.popups?.remove(this)
        root?.dirty = true
        if (surface.role is SurfaceRes.Role.PopupRole) surface.role = null
        (xdgSurface.role as? XdgPopupRes)?.let { if (it.alive) it.client.destroyRes(it) }
    }
}

/** Pure placement maths (unit-tested). */
object PopupPlacement {
    private fun hx(e: Int) = when (e) { 3, 5, 6 -> -1; 4, 7, 8 -> 1; else -> 0 }  // left / right
    private fun vy(e: Int) = when (e) { 1, 5, 7 -> -1; 2, 6, 8 -> 1; else -> 0 }  // top / bottom

    fun place(ax: Int, ay: Int, aw: Int, ah: Int, anchor: Int, gravity: Int, ox: Int, oy: Int, w: Int, h: Int): Pair<Int, Int> {
        val px = when (hx(anchor)) { -1 -> ax; 1 -> ax + aw; else -> ax + aw / 2 }
        val py = when (vy(anchor)) { -1 -> ay; 1 -> ay + ah; else -> ay + ah / 2 }
        val x = when (hx(gravity)) { -1 -> px - w; 1 -> px; else -> px - w / 2 }
        val y = when (vy(gravity)) { -1 -> py - h; 1 -> py; else -> py - h / 2 }
        return (x + ox) to (y + oy)
    }

    /** Flip (if allowed and it helps) then slide so the popup stays inside a bw×bh window. */
    fun constrain(x0: Int, y0: Int, w: Int, h: Int, bw: Int, bh: Int, ax: Int, ay: Int, aw: Int, ah: Int, constraint: Int): Pair<Int, Int> {
        var x = x0; var y = y0
        if (y + h > bh && constraint and 8 != 0 && ay - h >= 0) y = ay - h          // flip_y above the anchor
        if (x + w > bw && constraint and 4 != 0 && ax + aw - w >= 0) x = ax + aw - w // flip_x
        // slide (always: a popup off a phone screen is useless)
        if (x + w > bw) x = bw - w
        if (y + h > bh) y = bh - h
        if (x < 0) x = 0
        if (y < 0) y = 0
        return x to y
    }
}
