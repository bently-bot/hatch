package dev.hatch.wayland

class Clipboard(val server: WaylandServer) {
    var text: String? = null
    private var source: DataSourceRes? = null
    fun clientGone(c: Client) { if (source?.client === c) source = null }
    fun setFromClient(s: DataSourceRes) {
        source = s
        // Prefer text/plain
        val mime = s.mimes.firstOrNull { it.startsWith("text/plain") } ?: s.mimes.firstOrNull() ?: return
        val pipe = Native.pipe() ?: return
        s.send(mime, pipe[1]); Native.close(pipe[1])
        // Read asynchronously by posting a task that blocks briefly — fine for small clipboard.
        server.post {
            val bytes = Native.readAll(pipe[0], 1 shl 20)
            Native.close(pipe[0])
            text = bytes.toString(Charsets.UTF_8)
            server.host.clipboardChanged(text!!)
            for (seat in server.seat.seats) seat.dataDevice?.selection(s)
        }
    }
    fun setFromHost(t: String) {
        text = t; source = null
        // Clear client selection; host text remains in [text] for Android→guest paste bridge
        // (full wl_data_offer from host needs a synthetic DataSource — next pass).
        for (seat in server.seat.seats) seat.dataDevice?.selection(null)
    }
    fun hostText(): String? = text
}

class DataDeviceManagerRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wl_data_device_manager"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> client.register(DataSourceRes(client, a.newId()))
            1 -> {
                val nid = a.newId(); val seat = client.get<SeatRes>(a.obj())!!
                val d = DataDeviceRes(client, nid, seat); client.register(d); seat.dataDevice = d
            }
            else -> super.request(op, a)
        }
    }
}

class DataSourceRes(c: Client, id: Int) : Res(c, id, 3) {
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
    fun send(mime: String, fd: Int) { event(0).string(mime).fd(fd).end() }
}

class DataDeviceRes(c: Client, id: Int, val seat: SeatRes) : Res(c, id, 3) {
    override val iface = "wl_data_device"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> { /* start_drag */ a.obj(); a.obj(); a.obj(); a.uint() }
            1 -> { // set_selection
                val source = client.get<DataSourceRes>(a.obj()); a.uint()
                if (source != null) client.server.clipboard.setFromClient(source)
            }
            2 -> destroy()
            else -> super.request(op, a)
        }
    }
    fun selection(source: DataSourceRes?) {
        // Nullable object: 0 clears selection. Safe anytime after get_data_device.
        event(5).obj(source?.id ?: 0).end()
    }
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
