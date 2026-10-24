package dev.hatch.wayland

/**
 * Selection (copy/paste) between guest apps and Android.
 * Current selection is either a guest wl_data_source or host (Android) text; every data device gets a fresh
 * wl_data_offer for it. Guest text is mirrored to Android via [WindowHost.clipboardChanged].
 */
class Clipboard(val server: WaylandServer) {
    @Volatile var text: String? = null
        private set
    private var source: DataSourceRes? = null
    private var hostText: String? = null
    private val devices = ArrayList<DataDeviceRes>()

    fun clientGone(c: Client) {
        devices.removeAll { it.client === c }
        if (source?.client === c) { source = null; broadcast() }
    }

    /** Spec: selection goes only to the keyboard-focused client (and right before wl_keyboard.enter). */
    fun addDevice(d: DataDeviceRes) { devices.add(d); if (isFocused(d.client)) offerTo(d) }
    fun keyboardEntering(c: Client) { for (d in devices.toList()) if (d.alive && d.client === c) offerTo(d) }
    private fun isFocused(c: Client) = server.seat.keyboardSurface?.client === c
    fun removeDevice(d: DataDeviceRes) { devices.remove(d) }

    fun mimes(): List<String> = source?.mimes?.toList()
        ?: if (hostText != null) TEXT_MIMES else emptyList()

    fun setFromClient(s: DataSourceRes?) {
        val old = source
        if (old != null && old !== s && old.alive) old.sendCancelled()
        source = s; hostText = null
        broadcast()
        if (s == null) return
        // Mirror text to Android (read on a worker thread; never block the compositor thread).
        val mime = s.mimes.firstOrNull { it.startsWith("text/plain;charset=utf-8") }
            ?: s.mimes.firstOrNull { it == "UTF8_STRING" || it.startsWith("text/plain") } ?: return
        val pipe = Native.pipe() ?: return
        s.send(mime, pipe[1]) // flush() closes our copy of pipe[1]
        Thread({
            val bytes = Native.readAll(pipe[0], 1 shl 20); Native.close(pipe[0])
            val t = bytes.toString(Charsets.UTF_8)
            server.post { if (source === s) { text = t; server.host.clipboardChanged(t) } }
        }, "hatch-clip-read").apply { isDaemon = true; start() }
    }

    fun sourceDestroyed(s: DataSourceRes) { if (source === s) { source = null; broadcast() } }

    /** Android → guest. */
    fun setFromHost(t: String) {
        if (t == text && source == null) return
        source?.let { if (it.alive) it.sendCancelled() }
        source = null; hostText = t; text = t
        broadcast()
    }

    fun hostText(): String? = text

    /** Serve a paste request from an offer. Takes ownership of [fd]. */
    fun receive(mime: String, fd: Int) {
        val s = source
        if (s != null && s.alive) { s.send(mime, fd); return }
        val t = hostText
        if (t == null) { Native.close(fd); return }
        Thread({ Native.writeAll(fd, t.toByteArray(Charsets.UTF_8)); Native.close(fd) }, "hatch-clip-write")
            .apply { isDaemon = true; start() }
    }

    private fun broadcast() { for (d in devices.toList()) if (d.alive && isFocused(d.client)) offerTo(d) }

    private fun offerTo(d: DataDeviceRes) {
        val m = mimes()
        if (m.isEmpty()) { d.selection(0); return }
        val offer = DataOfferRes(d.client, d.client.newServerId(), d.version)
        d.client.register(offer)
        d.event(0).int(offer.id).end()                  // wl_data_device.data_offer(new_id)
        for (mime in m) offer.event(0).string(mime).end() // wl_data_offer.offer
        d.selection(offer.id)
    }

    companion object {
        val TEXT_MIMES = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "TEXT", "STRING")
    }
}

class DataDeviceManagerRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wl_data_device_manager"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> client.register(DataSourceRes(client, a.newId(), version))
            1 -> {
                val nid = a.newId(); val seat = client.get<SeatRes>(a.obj())!!
                val d = DataDeviceRes(client, nid, seat, version); client.register(d); seat.dataDevice = d
                client.server.clipboard.addDevice(d)
            }
            else -> super.request(op, a)
        }
    }
}

class DataSourceRes(c: Client, id: Int, v: Int = 3) : Res(c, id, v) {
    override val iface = "wl_data_source"
    val mimes = ArrayList<String>()
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> a.string()?.let { mimes.add(it) }
            1 -> destroy()
            2 -> { /* set_actions */ a.uint() }
            else -> super.request(op, a)
        }
    }
    /** wl_data_source.send(mime, fd) — the fd is closed by flush() after it is passed on. */
    fun send(mime: String, fd: Int) { event(1).string(mime).fd(fd).end() }
    fun sendCancelled() { event(2).end() }
    override fun onDestroy() { client.server.clipboard.sourceDestroyed(this) }
}

class DataOfferRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wl_data_offer"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> { a.uint(); a.string() }                         // accept
            1 -> { val mime = a.string() ?: ""; val fd = a.fd(); client.server.clipboard.receive(mime, fd) } // receive
            2 -> destroy()
            3 -> {}                                               // finish
            4 -> { a.uint(); a.uint() }                           // set_actions
            else -> super.request(op, a)
        }
    }
}

class DataDeviceRes(c: Client, id: Int, val seat: SeatRes, v: Int = 3) : Res(c, id, v) {
    override val iface = "wl_data_device"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> { /* start_drag */ a.obj(); a.obj(); a.obj(); a.uint() }
            1 -> { // set_selection(source, serial)
                val source = client.get<DataSourceRes>(a.obj()); a.uint()
                client.server.clipboard.setFromClient(source)
            }
            2 -> destroy()
            else -> super.request(op, a)
        }
    }
    fun selection(offerId: Int) { event(5).obj(offerId).end() }
    override fun onDestroy() { client.server.clipboard.removeDevice(this) }
}

class TextInputManagerRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "zwp_text_input_manager_v3"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> {
                val nid = a.newId(); a.obj() /* seat */
                val ti = TextInputRes(client, nid)
                client.register(ti); client.server.textInputs.add(ti)
                // If this client already has keyboard focus, tell the new text-input about it.
                client.server.seat.keyboardSurface?.let { if (it.client === client) ti.enter(it) }
            }
            else -> super.request(op, a)
        }
    }
}

/** zwp_text_input_v3. Events: enter=0 leave=1 preedit_string=2 commit_string=3 delete_surrounding_text=4 done=5. */
class TextInputRes(c: Client, id: Int) : Res(c, id, 1) {
    override val iface = "zwp_text_input_v3"
    private var commits = 0
    private var pendingEnable: Boolean? = null
    var enabled = false; private set
    private var focused: SurfaceRes? = null
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> pendingEnable = true            // enable
            2 -> pendingEnable = false           // disable
            3 -> { a.string(); a.int(); a.int() } // set_surrounding_text
            4 -> a.uint()                         // set_text_change_cause
            5 -> { a.uint(); a.uint() }           // set_content_type
            6 -> { a.int(); a.int(); a.int(); a.int() } // set_cursor_rectangle
            7 -> { commits++; pendingEnable?.let { enabled = it }; pendingEnable = null
                   client.server.host.textInputChanged(enabled) }
            else -> super.request(op, a)
        }
    }
    fun enter(s: SurfaceRes) { if (focused !== s) { focused = s; event(0).obj(s.id).end() } }
    fun leave(s: SurfaceRes) { if (focused === s) { focused = null; enabled = false; event(1).obj(s.id).end() } }
    fun commitString(text: String) {
        if (!alive) return
        event(3).string(text).end() // commit_string
        event(5).uint(commits).end() // done(serial = number of commit requests seen)
    }
    override fun onDestroy() { client.server.textInputs.remove(this) }
}
