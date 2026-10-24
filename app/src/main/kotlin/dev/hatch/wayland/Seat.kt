package dev.hatch.wayland

class Seat(val server: WaylandServer) {
    val seats = ArrayList<SeatRes>()
    var focus: Toplevel? = null
    var pointerSurface: SurfaceRes? = null
    var keyboardSurface: SurfaceRes? = null
    var pointerX = 0.0; var pointerY = 0.0
    private var buttonsDown = 0
    private val mods = IntArray(4) // depressed, latched, locked, group

    fun clientGone(c: Client) {
        seats.removeAll { it.client === c }
        if (focus?.client === c) focus = null
        if (pointerSurface?.client === c) pointerSurface = null
        if (keyboardSurface?.client === c) keyboardSurface = null
    }

    fun focusWindow(w: Toplevel?) {
        if (focus === w) return
        val old = focus
        focus = w
        if (old != null) { old.activated = false; old.sendConfigure(); leaveKeyboard(old.surface) }
        if (w != null) {
            w.activated = true; w.sendConfigure()
            enterKeyboard(w.surface)
        }
    }

    private fun enterKeyboard(s: SurfaceRes) {
        keyboardSurface = s
        for (seat in seats) if (seat.client === s.client) seat.keyboard?.enter(s)
    }
    private fun leaveKeyboard(s: SurfaceRes) {
        for (seat in seats) if (seat.client === s.client) seat.keyboard?.leave(s)
        if (keyboardSurface === s) keyboardSurface = null
    }

    /** Pointer motion in target pixels of [w]; routes to the popup under the pointer, if any. */
    fun pointerMotion(w: Toplevel, xPx: Int, yPx: Int) {
        focusWindow(w)
        val hit = w.hitTest(xPx, yPx)
        pointerX = hit.x; pointerY = hit.y
        if (pointerSurface !== hit.surface) {
            pointerSurface?.let { old -> if (old.alive) for (seat in seats) if (seat.client === old.client) seat.pointer?.leave(old) }
            pointerSurface = hit.surface
            for (seat in seats) if (seat.client === hit.surface.client) seat.pointer?.enter(hit.surface, hit.x, hit.y)
        } else {
            for (seat in seats) if (seat.client === hit.surface.client) seat.pointer?.motion(hit.x, hit.y)
        }
    }

    fun pointerButton(w: Toplevel, button: Int, pressed: Boolean) {
        focusWindow(w)
        // Accept either index (0/1/2) or Linux BTN_* (0x110+)
        val code = when {
            button >= 0x110 -> button
            button == 0 -> 0x110
            button == 1 -> 0x111
            button == 2 -> 0x112
            else -> 0x110 + button
        }
        // Press outside every open popup dismisses them (xdg_popup grab semantics).
        if (pressed && w.popups.isNotEmpty() && w.popups.none { it.surface === pointerSurface }) {
            w.dismissPopups()
            return
        }
        val bit = code and 0xff
        if (pressed) buttonsDown = buttonsDown or (1 shl bit) else buttonsDown = buttonsDown and (1 shl bit).inv()
        val target = pointerSurface?.takeIf { it.alive } ?: w.surface
        for (seat in seats) if (seat.client === target.client)
            seat.pointer?.button(code, if (pressed) 1 else 0)
    }

    fun pointerScroll(w: Toplevel, dx: Double, dy: Double) {
        for (seat in seats) if (seat.client === w.client) seat.pointer?.axis(dx, dy)
    }

    fun key(w: Toplevel, keycode: Int, pressed: Boolean) {
        focusWindow(w)
        for (seat in seats) if (seat.client === w.client)
            seat.keyboard?.key(keycode, if (pressed) 1 else 0)
    }

    fun setMods(depressed: Int, latched: Int, locked: Int, group: Int) {
        mods[0] = depressed; mods[1] = latched; mods[2] = locked; mods[3] = group
        for (seat in seats) seat.keyboard?.modifiers()
    }

    fun mods(): IntArray = mods
}

class SeatRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wl_seat"
    var pointer: PointerRes? = null
    var keyboard: KeyboardRes? = null
    var dataDevice: DataDeviceRes? = null
    init {
        client.server.seat.seats.add(this)
        event(0).uint(3 /* pointer|keyboard */).end() // capabilities
        if (version >= 2) event(1).string("default").end()
    }
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> { val p = PointerRes(client, a.newId(), this, version); client.register(p); pointer = p }
            1 -> { val k = KeyboardRes(client, a.newId(), this, version); client.register(k); keyboard = k }
            2 -> { /* touch */ a.newId() }
            3 -> destroy()
            else -> super.request(op, a)
        }
    }
    override fun onDestroy() { client.server.seat.seats.remove(this) }
}

class PointerRes(c: Client, id: Int, val seat: SeatRes, v: Int) : Res(c, id, v) {
    override val iface = "wl_pointer"
    /** wl_pointer.frame is opcode **5** (an earlier build sent 6 = axis_source, which broke clients). */
    private fun frameIfNeeded() { if (version >= 5) event(5).end() }
    fun enter(s: SurfaceRes, x: Double, y: Double) {
        event(0).uint(client.server.nextSerial()).obj(s.id).fixed(x).fixed(y).end()
        frameIfNeeded()
    }
    fun leave(s: SurfaceRes) { event(1).uint(client.server.nextSerial()).obj(s.id).end(); frameIfNeeded() }
    fun motion(x: Double, y: Double) { event(2).uint(client.server.timeMs()).fixed(x).fixed(y).end(); frameIfNeeded() }
    fun button(button: Int, state: Int) {
        event(3).uint(client.server.nextSerial()).uint(client.server.timeMs()).uint(button).uint(state).end()
        frameIfNeeded()
    }
    fun axis(dx: Double, dy: Double) {
        if (dy != 0.0) event(4).uint(client.server.timeMs()).uint(0).fixed(dy).end()
        if (dx != 0.0) event(4).uint(client.server.timeMs()).uint(1).fixed(dx).end()
        frameIfNeeded()
    }
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> { // set_cursor(serial, surface?, hotspot_x, hotspot_y)
                a.uint(); a.obj(); a.int(); a.int()
            }
            1 -> destroy()
            else -> {}
        }
    }
}

class KeyboardRes(c: Client, id: Int, val seat: SeatRes, v: Int) : Res(c, id, v) {
    override val iface = "wl_keyboard"
    init {
        val km = client.server.keymap
        val fd = Native.memfdWith(km, client.server.tmpDir)
        event(0).uint(1 /* xkb_v1 */).fd(fd).uint(km.size).end() // keymap
        // modifiers: serial, depressed, latched, locked, group
        event(4).uint(0).uint(0).uint(0).uint(0).uint(0).end()
        if (version >= 4) event(5).int(1000).int(50).end() // repeat_info
    }
    fun enter(s: SurfaceRes) { event(1).uint(client.server.nextSerial()).obj(s.id).array(ByteArray(0)).end() }
    fun leave(s: SurfaceRes) { event(2).uint(client.server.nextSerial()).obj(s.id).end() }
    fun key(code: Int, state: Int) { event(3).uint(client.server.nextSerial()).uint(client.server.timeMs()).uint(code).uint(state).end() }
    fun modifiers() {
        val m = client.server.seat.mods()
        event(4).uint(client.server.nextSerial()).uint(m[0]).uint(m[1]).uint(m[2]).uint(m[3]).end()
    }
    override fun request(op: Int, a: Args) { if (op == 0) destroy() else super.request(op, a) }
}
