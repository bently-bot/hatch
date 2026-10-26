package dev.hatch.wayland

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * zwp_linux_dmabuf_v1 (v4, with feedback) for GPU clients: Mesa's Vulkan WSI (Turnip/Zink), EGL, Chromium.
 *
 * Android gives an app no DRM render node and no public way to wrap a raw dma-buf fd in an AHardwareBuffer, so
 * Hatch imports what it can map: single-plane 32-bit RGB buffers with the LINEAR modifier (the only ones whose
 * bytes are meaningful to a CPU; Adreno's tiled/UBWC layouts are not advertised). Each commit maps the dma-buf,
 * brackets the read with DMA_BUF_IOCTL_SYNC and copies it into the window store like wl_shm. That keeps the guest
 * on the GPU (no readback into shm inside the guest) and costs one copy on Hatch's side. See docs/GPU-DMABUF.md.
 */
object DmabufFormats {
    const val MOD_LINEAR = 0L
    const val MOD_INVALID = 0x00ffffffffffffffL
    private fun fourcc(s: String) = s[0].code or (s[1].code shl 8) or (s[2].code shl 16) or (s[3].code shl 24)
    val ARGB8888 = fourcc("AR24"); val XRGB8888 = fourcc("XR24"); val ABGR8888 = fourcc("AB24"); val XBGR8888 = fourcc("XB24")
    val formats = listOf(ARGB8888, XRGB8888, ABGR8888, XBGR8888)

    /** DRM fourcc → the store copy's format code (wl_shm codes 0/1 for (A|X)RGB, fourcc for (A|X)BGR); -1 unsupported. */
    fun copyFormat(drm: Int): Int = when (drm) { ARGB8888 -> 0; XRGB8888 -> 1; ABGR8888 -> ABGR8888; XBGR8888 -> XBGR8888; else -> -1 }

    /** Feedback format table: 16-byte entries (u32 format, u32 pad, u64 modifier), one per format, LINEAR only. */
    fun table(): ByteArray {
        val b = ByteBuffer.allocate(formats.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (f in formats) { b.putInt(f); b.putInt(0); b.putLong(MOD_LINEAR) }
        return b.array()
    }
    /** tranche_formats: u16 indices into [table]. */
    fun indices(): ByteArray {
        val b = ByteBuffer.allocate(formats.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        formats.indices.forEach { b.putShort(it.toShort()) }
        return b.array()
    }
    fun devT(dev: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(dev).array()

    /** Validation of zwp_linux_buffer_params_v1.create; null when OK, else the reason (→ failed / protocol error). */
    fun check(w: Int, h: Int, format: Int, planes: Int, stride: Int, offset: Int, modifier: Long): String? = when {
        w <= 0 || h <= 0 -> "bad size ${w}x$h"
        copyFormat(format) < 0 -> "format 0x${format.toString(16)} not supported"
        planes != 1 -> "$planes planes (only single-plane RGB is supported)"
        modifier != MOD_LINEAR -> "modifier 0x${modifier.toString(16)} (only LINEAR is advertised)"
        stride < w * 4 || offset < 0 -> "bad stride/offset $stride/$offset"
        else -> null
    }
}

class LinuxDmabufRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "zwp_linux_dmabuf_v1"
    init {
        if (version < 4) for (f in DmabufFormats.formats) {
            event(0).uint(f).end()                                   // format (deprecated, still read by v1-2 clients)
            if (version >= 3) event(1).uint(f).uint(0).uint(0).end() // modifier: LINEAR
        }
    }
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> client.register(BufferParamsRes(client, a.newId()))
            2 -> client.register(DmabufFeedbackRes(client, a.newId(), version))
            3 -> { val nid = a.newId(); a.obj(); client.register(DmabufFeedbackRes(client, nid, version)) }
            else -> super.request(op, a)
        }
    }
}

class DmabufFeedbackRes(c: Client, id: Int, v: Int) : Res(c, id, v) {
    override val iface = "zwp_linux_dmabuf_feedback_v1"
    init {
        val table = DmabufFormats.table()
        val fd = Native.memfdWith(table, client.server.tmpDir)
        val dev = DmabufFormats.devT(client.server.mainDevice)
        event(1).fd(fd).uint(table.size).end()      // format_table
        event(2).array(dev).end()                    // main_device
        event(4).array(dev).end()                    // tranche_target_device
        event(5).array(DmabufFormats.indices()).end() // tranche_formats
        event(6).uint(0).end()                       // tranche_flags
        event(3).end()                               // tranche_done
        event(0).end()                               // done
    }
    override fun request(op: Int, a: Args) { if (op == 0) destroy() else super.request(op, a) }
}

class BufferParamsRes(c: Client, id: Int) : Res(c, id, 1) {
    override val iface = "zwp_linux_buffer_params_v1"
    private class Plane(val fd: Int, val offset: Int, val stride: Int, val modifier: Long)
    private val planes = HashMap<Int, Plane>()
    private var used = false

    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { // add(fd, plane_idx, offset, stride, modifier_hi, modifier_lo)
                val fd = a.fd(); val idx = a.uint(); val off = a.uint(); val stride = a.uint()
                val mod = (a.uint().toLong() shl 32) or (a.uint().toLong() and 0xffffffffL)
                if (idx in planes) { Native.close(fd); throw ProtocolError(id, 1 /* PLANE_SET */, "plane $idx already set") }
                planes[idx] = Plane(fd, off, stride, mod)
            }
            2 -> { val w = a.int(); val h = a.int(); val f = a.uint(); a.uint(); create(0, w, h, f) }
            3 -> { val nid = a.newId(); val w = a.int(); val h = a.int(); val f = a.uint(); a.uint(); create(nid, w, h, f) }
            else -> super.request(op, a)
        }
    }

    /** [immedId] 0 = create (answer created/failed), else create_immed with that client id. */
    private fun create(immedId: Int, w: Int, h: Int, format: Int) {
        if (used) throw ProtocolError(id, 0 /* ALREADY_USED */, "params already used")
        used = true
        val p = planes[0]
        val why = DmabufFormats.check(w, h, format, planes.size, p?.stride ?: 0, p?.offset ?: 0, p?.modifier ?: -1)
        if (why != null || p == null) {
            client.server.host.log("dmabuf rejected: ${why ?: "no plane 0"}")
            planes.values.forEach { Native.close(it.fd) }; planes.clear()
            if (immedId != 0) throw ProtocolError(id, 7 /* INVALID_WL_BUFFER */, why ?: "no plane 0")
            event(1).end(); return   // failed
        }
        planes.remove(0)
        val bid = if (immedId != 0) immedId else client.newServerId()
        client.register(BufferRes(client, bid, null, p.offset, w, h, p.stride, DmabufFormats.copyFormat(format), dmaFd = p.fd))
        if (immedId == 0) event(0).uint(bid).end()   // created(new wl_buffer)
    }
    override fun onDestroy() { planes.values.forEach { Native.close(it.fd) }; planes.clear() }
}
