package dev.hatch.wayland

fun registerGlobals(s: WaylandServer) {
    s.addGlobal("wl_compositor", 4) { c, id, v -> CompositorRes(c, id, v) }
    s.addGlobal("wl_subcompositor", 1) { c, id, v -> SubcompositorRes(c, id, v) }
    s.addGlobal("wl_shm", 1) { c, id, v -> ShmRes(c, id, v) }
    s.addGlobal("wl_seat", 8) { c, id, v -> SeatRes(c, id, v) }
    s.addGlobal("wl_data_device_manager", 3) { c, id, v -> DataDeviceManagerRes(c, id, v) }
    s.addGlobal("wl_output", 2) { c, id, v -> OutputRes(c, id, v) }
    s.addGlobal("xdg_wm_base", 3) { c, id, v -> XdgWmBaseRes(c, id, v) }
    s.addGlobal("wp_viewporter", 1) { c, id, v -> ViewporterRes(c, id, v) }
    s.addGlobal("zxdg_decoration_manager_v1", 1) { c, id, v -> DecorationManagerRes(c, id, v) }
    s.addGlobal("zwp_text_input_manager_v3", 1) { c, id, v -> TextInputManagerRes(c, id, v) }
}

class CompositorRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wl_compositor"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> client.register(SurfaceRes(client, a.newId()))
            1 -> client.register(RegionRes(client, a.newId()))
            else -> super.request(op, a)
        }
    }
}

class RegionRes(c: Client, id: Int) : Res(c, id, 1) {
    override val iface = "wl_region"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { a.int(); a.int(); a.int(); a.int() } // add
            2 -> { a.int(); a.int(); a.int(); a.int() } // subtract
            else -> super.request(op, a)
        }
    }
}

class SubcompositorRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wl_subcompositor"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> {
                val nid = a.newId(); val surface = client.get<SurfaceRes>(a.obj())!!; val parent = client.get<SurfaceRes>(a.obj())!!
                client.register(SubsurfaceRes(client, nid, surface, parent))
            }
            else -> super.request(op, a)
        }
    }
}

class SubsurfaceRes(c: Client, id: Int, val surface: SurfaceRes, val parent: SurfaceRes) : Res(c, id, 1) {
    override val iface = "wl_subsurface"
    var x = 0; var y = 0; var sync = true
    init {
        surface.role = SurfaceRes.Role.Subsurface(this)
        parent.subsurfaces.add(this)
    }
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { x = a.int(); y = a.int(); parent.markDirty() }
            2 -> { a.obj() /* sibling */; /* place above */ }
            3 -> { a.obj() }
            4 -> sync = false
            5 -> sync = true
            else -> super.request(op, a)
        }
    }
    override fun onDestroy() {
        parent.subsurfaces.remove(this)
        if (surface.role is SurfaceRes.Role.Subsurface) surface.role = null
        parent.markDirty()
    }
}

class ShmRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wl_shm"
    init {
        // formats: ARGB8888=0, XRGB8888=1, ABGR8888, XBGR8888
        event(0).uint(0).end(); event(0).uint(1).end()
        event(0).uint(0x34324241).end(); event(0).uint(0x34324258).end()
    }
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> {
                val nid = a.newId(); val fd = a.fd(); val size = a.int()
                client.register(ShmPoolRes(client, nid, fd, size))
            }
            1 -> destroy()
            else -> super.request(op, a)
        }
    }
}

class ShmPoolRes(c: Client, id: Int, rawFd: Int, val size: Int) : Res(c, id, 1) {
    override val iface = "wl_shm_pool"
    val fd: Int = rawFd
    val ptr: Long = Native.mmap(fd, size)
    private var refs = 1 // the pool object itself
    private var destroyed = false
    fun retain() { refs++ }
    fun release() {
        refs--
        if (refs <= 0) {
            if (ptr != 0L) Native.munmap(ptr, size)
            Native.close(fd)
        }
    }
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> {
                val nid = a.newId(); val offset = a.int(); val w = a.int(); val h = a.int(); val stride = a.int(); val format = a.int()
                if (w <= 0 || h <= 0 || stride < w * 4 || offset < 0 || offset.toLong() + stride.toLong() * h > size || ptr == 0L)
                    throw ProtocolError(id, 1, "invalid buffer")
                retain()
                client.register(BufferRes(client, nid, this, offset, w, h, stride, format))
            }
            1 -> destroy()
            2 -> { a.int() /* resize ignored */ }
            else -> super.request(op, a)
        }
    }
    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        release() // drop the pool object's own ref; buffers may still hold some
    }
}

class BufferRes(c: Client, id: Int, val pool: ShmPoolRes, val offset: Int, val w: Int, val h: Int, val stride: Int, val format: Int) : Res(c, id, 1) {
    override val iface = "wl_buffer"
    var busy = false
    override fun request(op: Int, a: Args) { if (op == 0) destroy() else super.request(op, a) }
    fun release() { busy = false; if (alive) event(0).end() }
    override fun onDestroy() { pool.release() }
}

class OutputRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wl_output"
    init {
        val s = client.server
        event(0).int(0).int(0).int(s.outputWidth * 254 / (96 * s.scale)).int(s.outputHeight * 254 / (96 * s.scale))
            .int(0).string("Hatch").string("Display").int(0).end() // geometry
        event(1).uint(3 /* current|preferred */).int(s.outputWidth).int(s.outputHeight).int(60000).end() // mode
        if (version >= 2) event(2).int(s.scale).end()
        event(3).end() // done
    }
    override fun request(op: Int, a: Args) { if (op == 0) destroy() else super.request(op, a) }
}

class ViewporterRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "wp_viewporter"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { val nid = a.newId(); val surface = client.get<SurfaceRes>(a.obj())!!; client.register(ViewportRes(client, nid, surface)) }
            else -> super.request(op, a)
        }
    }
}

class ViewportRes(c: Client, id: Int, val surface: SurfaceRes) : Res(c, id, 1) {
    override val iface = "wp_viewport"
    init { surface.viewport = this }
    var srcX = -1.0; var srcY = -1.0; var srcW = -1.0; var srcH = -1.0
    var dstW = -1; var dstH = -1
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { srcX = a.fixed(); srcY = a.fixed(); srcW = a.fixed(); srcH = a.fixed() }
            2 -> { dstW = a.int(); dstH = a.int() }
            else -> super.request(op, a)
        }
    }
    override fun onDestroy() { if (surface.viewport === this) surface.viewport = null }
}

class DecorationManagerRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "zxdg_decoration_manager_v1"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { val nid = a.newId(); val tl = client.get<XdgToplevelRes>(a.obj())!!; client.register(DecorationRes(client, nid, tl)) }
            else -> super.request(op, a)
        }
    }
}

class DecorationRes(c: Client, id: Int, val toplevel: XdgToplevelRes) : Res(c, id, 1) {
    override val iface = "zxdg_toplevel_decoration_v1"
    init { event(0).uint(1 /* server_side */).end() } // configure
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { a.uint() /* mode */ }
            2 -> {}
            else -> super.request(op, a)
        }
    }
}
