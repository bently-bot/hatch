package dev.hatch.wayland

class SurfaceRes(c: Client, id: Int) : Res(c, id, 4) {
    override val iface = "wl_surface"
    sealed class Role {
        class ToplevelRole(val t: dev.hatch.wayland.Toplevel) : Role()
        class PopupRole(val p: dev.hatch.wayland.Popup) : Role()
        class Subsurface(val s: SubsurfaceRes) : Role()
        object Cursor : Role()
    }
    var role: Role? = null
    var viewport: ViewportRes? = null
    val subsurfaces = ArrayList<SubsurfaceRes>()

    /** Current (committed) buffer content. */
    var store: Long = 0
    var storeW = 0; var storeH = 0
    var opaque  = true
    var bufferScale = 1

    // pending
    private var pendingBuffer: BufferRes? = null
    private var pendingOffsetX = 0; private var pendingOffsetY = 0
    private var hasAttach = false
    private var pendingDamage = IntArray(0); private var pendingDamageCount = 0
    private var pendingDamageBuf = IntArray(0); private var pendingDamageBufCount = 0
    private var pendingScale = -1
    private val pendingFrame = ArrayList<CallbackRes>()
    private var pendingOpaqueRegion: Any? = null

    fun markDirty() {
        when (val r = role) {
            is Role.ToplevelRole -> r.t.dirty  = true
            is Role.PopupRole -> r.p.parentXdg.toplevel()?.dirty  = true
            is Role.Subsurface -> r.s.parent.markDirty()
            else -> {}
        }
    }

    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> destroy()
            1 -> { // attach
                val b = client.get<BufferRes>(a.obj()); val x = a.int(); val y = a.int()
                pendingBuffer = b; pendingOffsetX = x; pendingOffsetY = y; hasAttach  = true
            }
            2 -> { // damage
                ensureDamage(); pendingDamage[pendingDamageCount * 4] = a.int()
                pendingDamage[pendingDamageCount * 4 + 1] = a.int()
                pendingDamage[pendingDamageCount * 4 + 2] = a.int()
                pendingDamage[pendingDamageCount * 4 + 3] = a.int()
                pendingDamageCount++
            }
            3 -> { // frame
                val cb = CallbackRes(client, a.newId()); client.register(cb); pendingFrame.add(cb)
            }
            4 -> { a.obj() /* set_opaque_region */ ; pendingOpaqueRegion = Any() }
            5 -> { a.obj() /* set_input_region */ }
            6 -> commit()
            7 -> { /* set_buffer_transform */ a.int() }
            8 -> pendingScale = a.int()
            9 -> { // damage_buffer
                ensureDamageBuf(); pendingDamageBuf[pendingDamageBufCount * 4] = a.int()
                pendingDamageBuf[pendingDamageBufCount * 4 + 1] = a.int()
                pendingDamageBuf[pendingDamageBufCount * 4 + 2] = a.int()
                pendingDamageBuf[pendingDamageBufCount * 4 + 3] = a.int()
                pendingDamageBufCount++
            }
            10 -> { a.int(); a.int() /* offset */ }
            else -> super.request(op, a)
        }
    }

    private fun ensureDamage() {
        if (pendingDamageCount * 4 + 4 > pendingDamage.size) pendingDamage = pendingDamage.copyOf(maxOf(64, pendingDamage.size * 2))
    }
    private fun ensureDamageBuf() {
        if (pendingDamageBufCount * 4 + 4 > pendingDamageBuf.size) pendingDamageBuf = pendingDamageBuf.copyOf(maxOf(64, pendingDamageBuf.size * 2))
    }

    private fun commit() {
        if (pendingScale > 0) bufferScale = pendingScale
        if (hasAttach) {
            val buf = pendingBuffer
            if (buf == null) {
                if (store != 0L) { Native.storeFree(store); store = 0 }
                storeW = 0; storeH = 0
            } else {
                if (store == 0L || storeW != buf.w || storeH != buf.h) {
                    if (store != 0L) Native.storeFree(store)
                    store = Native.storeAlloc(buf.w, buf.h)
                    storeW = buf.w; storeH = buf.h
                }
                val rects: IntArray; val count: Int
                if (pendingDamageBufCount > 0) {
                    rects = pendingDamageBuf; count = pendingDamageBufCount
                } else if (pendingDamageCount > 0) {
                    // surface coords -> buffer coords
                    val s = bufferScale; rects = IntArray(pendingDamageCount * 4)
                    for (i in 0 until pendingDamageCount) {
                        rects[i * 4] = pendingDamage[i * 4] * s
                        rects[i * 4 + 1] = pendingDamage[i * 4 + 1] * s
                        rects[i * 4 + 2] = pendingDamage[i * 4 + 2] * s
                        rects[i * 4 + 3] = pendingDamage[i * 4 + 3] * s
                    }
                    count = pendingDamageCount
                } else {
                    rects = intArrayOf(0, 0, buf.w, buf.h); count = 1
                }
                val r = if (buf.isDmabuf) {
                    if (client.dmabufCommits++ == 0L) { client.server.presented(client.appTag, "dma-buf"); client.server.host.log("client ${client.appTag}: first dma-buf frame ${buf.w}x${buf.h}") }
                    Native.storeCopyDmabuf(buf.dmaFd, buf.offset, buf.stride, buf.format, store, buf.w, buf.h)
                } else {
                    if (client.shmCommits++ == 0L) { client.server.presented(client.appTag, "wl_shm"); client.server.host.log("client ${client.appTag}: first wl_shm frame ${buf.w}x${buf.h}") }
                    Native.storeCopyFd(buf.pool!!.fd, buf.offset, buf.stride, buf.format, store, buf.w, buf.h)
                }
                if (r < 0) client.server.host.log("${if (buf.isDmabuf) "storeCopyDmabuf" else "storeCopyFd"} failed $r")
                else (role as? Role.ToplevelRole)?.t?.let { t ->
                    t.framesCommitted++
                    if (!t.hasContent || !t.varied) {
                        val st = Native.storeStats(store); t.lastStats = st
                        val varied = st >= 0 && (st and 0xff) >= 2
                        if (!t.hasContent && (varied || (st >= 0 && (st shr 8) >= 10))) {
                            t.hasContent = true; t.varied = varied
                            client.server.host.log("content '${t.title}' after ${t.framesCommitted} frame(s) (luma ${st shr 8}±${st and 0xff})")
                            client.server.host.windowContent(t)
                        } else if (t.hasContent && varied) {
                            t.varied = true
                            client.server.host.log("detail '${t.title}' after ${t.framesCommitted} frame(s) (luma ${st shr 8}±${st and 0xff})")
                            client.server.host.windowContent(t)
                        }
                    }
                }
                opaque = buf.format == 1 || buf.format == 0x34324258 || pendingOpaqueRegion != null
                if (buf.busy) { /* previous still held */ }
                buf.busy  = true
                // release the previous buffer after copy so the client can reuse it
                buf.release()
            }
            hasAttach = false; pendingBuffer = null
        }
        pendingDamageCount = 0; pendingDamageBufCount = 0; pendingOpaqueRegion = null
        if (pendingFrame.isNotEmpty()) {
            val frames = ArrayList(pendingFrame); pendingFrame.clear()
            // fire after the next render of our role; for now fire immediately after markDirty
            when (val r = role) {
                is Role.ToplevelRole -> r.t.pendingFrames.addAll(frames)
                is Role.PopupRole -> r.p.parentXdg.toplevel()?.pendingFrames?.addAll(frames)
                else -> for (f in frames) f.done(client.server.timeMs())
            }
        }
        markDirty()
    }

    override fun onDestroy() {
        if (store != 0L) { Native.storeFree(store); store = 0 }
        when (val r = role) {
            is Role.ToplevelRole -> r.t.destroyWindow()
            is Role.PopupRole -> r.p.destroy()
            else -> {}
        }
        role = null
    }

    /** Logical size of the surface content (after viewport / bufferScale). */
    fun contentW(): Int {
        val vp = viewport
        if (vp != null && vp.dstW > 0) return vp.dstW
        return if (bufferScale > 0) storeW / bufferScale else storeW
    }
    fun contentH(): Int {
        val vp = viewport
        if (vp != null && vp.dstH > 0) return vp.dstH
        return if (bufferScale > 0) storeH / bufferScale else storeH
    }
}
