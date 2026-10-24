package dev.hatch.wayland

class XdgWmBaseRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "xdg_wm_base"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> client.register(XdgPositionerRes(client, a.newId()))
            2 -> {
                val nid = a.newId(); val surface = client.get<SurfaceRes>(a.obj())!!
                if (surface.role != null) throw ProtocolError(id, 0, "surface already has a role")
                client.register(XdgSurfaceRes(client, nid, surface))
            }
            3 -> { /* pong */ a.uint() }
            else -> super.request(op, a)
        }
    }
}

class XdgPositionerRes(c: Client, id: Int) : Res(c, id, 1) {
    override val iface = "xdg_positioner"
    var w = 0; var h = 0; var ax = 0; var ay = 0; var aw = 0; var ah = 0
    var gravity = 0; var constraint = 0; var ox = 0; var oy = 0
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { w = a.int(); h = a.int() }
            2 -> { ax = a.int(); ay = a.int(); aw = a.int(); ah = a.int() }
            3 -> a.uint() // anchor
            4 -> gravity = a.uint()
            5 -> constraint = a.uint()
            6 -> { ox = a.int(); oy = a.int() }
            7 -> {}
            8 -> a.uint()
            else -> super.request(op, a)
        }
    }
}

class XdgSurfaceRes(c: Client, id: Int, val surface: SurfaceRes) : Res(c, id, 1) {
    override val iface = "xdg_surface"
    var role: Any? = null
    private var pendingAck = 0; private var lastConfigure = 0
    private var pendingWindowGeometry: IntArray? = null
    var windowGeometry: IntArray? = null

    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> {
                val nid = a.newId()
                if (surface.role != null) throw ProtocolError(id, 0, "surface already has a role")
                val tl = Toplevel(client.server, client, surface, this)
                val res = XdgToplevelRes(client, nid, tl)
                client.register(res); role = res
                surface.role = SurfaceRes.Role.ToplevelRole(tl)
                client.server.windows.add(tl)
                client.server.host.windowCreated(tl)
                tl.sendConfigure()
            }
            2 -> {
                val nid = a.newId(); val parent = client.get<XdgSurfaceRes>(a.obj())!!; val pos = client.get<XdgPositionerRes>(a.obj())!!
                if (surface.role != null) throw ProtocolError(id, 0, "surface already has a role")
                val popup = Popup(client.server, client, surface, this, parent, pos)
                val res = XdgPopupRes(client, nid, popup)
                client.register(res); role = res
                surface.role = SurfaceRes.Role.PopupRole(popup)
                parent.toplevel()?.popups?.add(popup)
                popup.sendConfigure()
            }
            3 -> { // set_window_geometry
                pendingWindowGeometry = intArrayOf(a.int(), a.int(), a.int(), a.int())
            }
            4 -> { // ack_configure
                pendingAck = a.uint()
            }
            else -> super.request(op, a)
        }
    }

    fun sendConfigure() {
        lastConfigure = client.server.nextSerial()
        event(0).uint(lastConfigure).end()
    }

    fun applyPending() {
        if (pendingWindowGeometry != null) { windowGeometry = pendingWindowGeometry; pendingWindowGeometry = null }
    }

    fun toplevel(): Toplevel? = (role as? XdgToplevelRes)?.tl
    override fun onDestroy() {
        when (val r = role) {
            is XdgToplevelRes -> r.tl.destroyWindow()
            is XdgPopupRes -> r.popup.destroy()
        }
        role = null
        if (surface.role is SurfaceRes.Role.ToplevelRole || surface.role is SurfaceRes.Role.PopupRole) surface.role = null
    }
}

class XdgToplevelRes(c: Client, id: Int, val tl: Toplevel) : Res(c, id, 1) {
    override val iface = "xdg_toplevel"
    init { tl.xdg = this }
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { /* set_parent */ a.obj() }
            2 -> tl.title = a.string() ?: ""
            3 -> tl.appId = a.string() ?: ""
            4 -> { a.obj() /* seat */; a.uint(); a.int(); a.int() /* show_window_menu */ }
            5 -> { a.obj(); a.uint() /* move */ }
            6 -> { a.obj(); a.uint(); a.uint() /* resize */ }
            7 -> { tl.minW = a.int(); tl.minH = a.int() }
            8 -> { tl.maxW = a.int(); tl.maxH = a.int() }
            9 -> {} // maximize
            10 -> {}
            11 -> {} // fullscreen
            12 -> {}
            13 -> {} // minimize
            else -> super.request(op, a)
        }
        client.server.host.windowChanged(tl)
    }
    override fun onDestroy() { tl.destroyWindow() }
    fun sendConfigure(w: Int, h: Int, states: IntArray) {
        event(0).int(w).int(h).intArray(states).end()
        tl.xdgSurface.sendConfigure()
    }
}

class XdgPopupRes(c: Client, id: Int, val popup: Popup) : Res(c, id, 1) {
    override val iface = "xdg_popup"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { a.obj(); a.obj() /* grab */ }
            2 -> { a.obj() /* reposition */ }
            else -> super.request(op, a)
        }
    }
    override fun onDestroy() { popup.destroy() }
    fun sendConfigure(x: Int, y: Int, w: Int, h: Int) {
        event(0).int(x).int(y).int(w).int(h).end()
        popup.xdgSurface.sendConfigure()
    }
}
