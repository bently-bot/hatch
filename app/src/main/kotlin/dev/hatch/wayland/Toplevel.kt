package dev.hatch.wayland

/** An xdg_toplevel window we composite into an Android Activity (or a memory target). */
class Toplevel(val server: WaylandServer, val client: Client, val surface: SurfaceRes, val xdgSurface: XdgSurfaceRes) {
    var xdg: XdgToplevelRes? = null
    var title = ""; var appId = ""
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
        val states = if (activated) intArrayOf(4 /* activated */) else intArrayOf()
        xdg?.sendConfigure(w, h, states)
    }

    fun render() {
        xdgSurface.applyPending()
        if (target != 0L) {
            if (Native.targetBegin(target, 0xff2a2a2a.toInt())) {
                blitSurface(surface, 0, 0, targetW, targetH)
                for (p in popups.toTypedArray()) {
                    if (!p.surface.alive || p.surface.store == 0L) continue
                    val px = p.x * server.scale; val py = p.y * server.scale
                    val pw = p.surface.contentW() * server.scale; val ph = p.surface.contentH() * server.scale
                    blitSurface(p.surface, px, py, pw, ph)
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

    /** Map target-pixel (x,y) to surface-local logical coords. */
    fun toLocal(x: Int, y: Int): Pair<Double, Double> =
        Pair(x.toDouble() / server.scale, y.toDouble() / server.scale)
}

class Popup(
    val server: WaylandServer, val client: Client, val surface: SurfaceRes,
    val xdgSurface: XdgSurfaceRes, val parentXdg: XdgSurfaceRes, pos: XdgPositionerRes
) {
    var x = pos.ax + pos.ox; var y = pos.ay + pos.oy
    var w = pos.w; var h = pos.h
    fun sendConfigure() {
        (xdgSurface.role as? XdgPopupRes)?.sendConfigure(x, y, w, h)
        parentXdg.toplevel()?.dirty = true
    }
    fun destroy() {
        parentXdg.toplevel()?.popups?.remove(this)
        parentXdg.toplevel()?.dirty = true
        if (surface.role is SurfaceRes.Role.PopupRole) surface.role = null
        (xdgSurface.role as? XdgPopupRes)?.let { if (it.alive) it.client.destroyRes(it) }
    }
}
