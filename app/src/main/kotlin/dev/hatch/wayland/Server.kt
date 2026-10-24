package dev.hatch.wayland

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the compositor needs from the platform (Android activities, or the test harness). */
interface WindowHost {
    /** A new toplevel appeared. The host should give it a target via [Toplevel.attachTarget]. */
    fun windowCreated(w: Toplevel)
    fun windowChanged(w: Toplevel) {}
    fun windowDestroyed(w: Toplevel)
    fun clipboardChanged(text: String) {}
    fun clipboardText(): String? = null
    /** A client enabled/disabled text input (show/hide the soft keyboard). */
    fun textInputChanged(enabled: Boolean) {}
    fun log(msg: String) { println("[wayland] $msg") }
}

class Global(val name: Int, val iface: String, val version: Int, val bind: (Client, Int, Int) -> Res)

/**
 * A single-threaded Wayland server. All protocol state is touched only on the server thread;
 * other threads hand work over with [post] / [call].
 */
class WaylandServer(val host: WindowHost, val tmpDir: String, keymapText: String) {
    /** Output scale: logical units -> target pixels. */
    @Volatile var scale: Int = 2
    var outputWidth = 1920; var outputHeight = 1080
    val keymap: ByteArray = (keymapText + "\u0000").toByteArray()
    private val listeners = java.util.concurrent.ConcurrentHashMap<Int, String>() // fd -> app tag
    val clients = ArrayList<Client>()
    val globals = ArrayList<Global>()
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val wake = Native.eventfd()
    private var serialCounter = 1
    fun nextSerial(): Int = serialCounter++
    val startNanos = System.nanoTime()
    fun timeMs(): Int = ((System.nanoTime() - startNanos) / 1_000_000).toInt()
    val windows = ArrayList<Toplevel>()
    val textInputs = ArrayList<TextInputRes>()
    val seat = Seat(this)
    val clipboard = Clipboard(this)
    @Volatile var running = false
    private var thread: Thread? = null

    init { registerGlobals(this) }

    fun addGlobal(iface: String, version: Int, bind: (Client, Int, Int) -> Res) {
        globals.add(Global(globals.size + 1, iface, version, bind))
    }

    /** Listen on a socket whose clients all belong to [appTag]. Callable from any thread. */
    fun addSocket(path: String, appTag: String): Boolean {
        val fd = Native.listen(path)
        if (fd < 0) { host.log("listen $path failed: $fd"); return false }
        listeners[fd] = appTag
        Native.eventfdSignal(wake) // wake poll so it rebuilds the fd set
        return true
    }

    fun removeSocket(appTag: String) = post {
        val it = listeners.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.value == appTag) { Native.close(e.key); it.remove() } }
    }

    fun post(task: () -> Unit) { tasks.add(task); Native.eventfdSignal(wake) }

    /** Runs [task] on the server thread and waits for it (bounded). */
    fun <T> call(timeoutMs: Long = 2000, task: () -> T): T? {
        if (Thread.currentThread() === thread) return task()
        var result: T? = null
        val latch = CountDownLatch(1)
        post { try { result = task() } finally { latch.countDown() } }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    fun start() {
        running = true
        thread = Thread({ loop() }, "hatch-wayland").apply { isDaemon = true; start() }
    }

    fun stop() { running = false; Native.eventfdSignal(wake) }

    private fun loop() {
        val fds = IntArray(1024); val rev = IntArray(1024)
        while (running) {
            var n = 0
            fds[n++] = wake
            val lfds = listeners.keys.toIntArray()
            for (l in lfds) fds[n++] = l
            val cl = clients.toTypedArray()
            for (c in cl) fds[n++] = c.fd
            val r = Native.poll(fds, n, rev, if (anyDirty()) 4 else 1000)
            if (r < 0) { host.log("poll error $r"); Thread.sleep(10) }
            if (rev[0] != 0) Native.eventfdDrain(wake)
            while (true) { val task = tasks.poll() ?: break; try { task() } catch (e: Throwable) { host.log("task: ${e.message}") } }
            for ((i, l) in lfds.withIndex()) if (rev[1 + i] != 0) {
                val cfd = Native.accept(l)
                if (cfd >= 0) { host.log("accept fd=$cfd"); clients.add(Client(this, cfd, listeners[l] ?: "unknown")) }
            }
            for ((i, c) in cl.withIndex()) {
                val e = rev[1 + lfds.size + i]
                if (e != 0) c.readAndDispatch()
            }
            renderAll()
            for (c in clients.toTypedArray()) c.flush()
            clients.removeAll { it.dead }
        }
    }

    private fun anyDirty() = windows.any { it.dirty }

    private fun renderAll() { for (w in windows.toTypedArray()) if (w.dirty) w.render() }

    fun clientGone(c: Client) {
        for (w in windows.filter { it.client === c }) w.destroyWindow()
        seat.clientGone(c)
        clipboard.clientGone(c)
        textInputs.removeAll { it.client === c }
    }
}

/** Base for every protocol object. */
abstract class Res(val client: Client, val id: Int, val version: Int) {
    abstract val iface: String
    open fun request(op: Int, a: Args) { throw ProtocolError(id, 0, "$iface: unknown request $op") }
    open fun onDestroy() {}
    var alive = true
    fun event(op: Int): Out = client.out.begin(id, op)
    /** Client asked to destroy us. */
    fun destroy() { client.destroyRes(this) }
}

class Client(val server: WaylandServer, val fd: Int, val appTag: String) {
    val objects = HashMap<Int, Res>()
    val out = Out()
    private val inBuf: ByteBuffer = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private var inLen = 0
    private val inFds = ArrayDeque<Int>()
    private val fdScratch = IntArray(29)
    var dead = false
    private var nextServerId = 0xff000000.toInt()
    var display: DisplayRes = DisplayRes(this)

    init { objects[1] = display }

    fun newServerId(): Int = nextServerId++

    fun register(r: Res): Res {
        if (objects.containsKey(r.id) && objects[r.id]!!.alive) throw ProtocolError(1, 0, "id ${r.id} in use")
        objects[r.id] = r; return r
    }

    inline fun <reified T : Res> get(id: Int): T? {
        if (id == 0) return null
        val o = objects[id] ?: return null
        return o as? T ?: throw ProtocolError(id, 0, "object $id is ${o.iface}, wanted ${T::class.simpleName}")
    }

    fun destroyRes(r: Res) {
        if (!r.alive) return
        r.alive = false
        r.onDestroy()
        if (objects[r.id] === r) objects.remove(r.id)
        if (r.id.toUInt() < 0xff000000u) display.event(1).uint(r.id).end() // delete_id
    }

    fun readAndDispatch() {
        if (dead) return
        while (true) {
            val n = Native.recv(fd, inBuf, inLen, inBuf.capacity() - inLen, fdScratch)
            if (n == 0) break
            if (n < 0) { disconnect(); return }
            for (i in 0 until fdScratch[0]) { val f = fdScratch[1 + i]; inFds.addLast(f) }
            inLen += n
            dispatchBuffered()
            if (dead) return
            if (inLen == inBuf.capacity()) { disconnect(); return }
        }
    }

    private fun dispatchBuffered() {
        var pos = 0
        while (inLen - pos >= 8) {
            val obj = inBuf.getInt(pos)
            val w = inBuf.getInt(pos + 4)
            val size = (w ushr 16) and 0xffff
            val op = w and 0xffff
            if (size < 8) { postError(1, 1, "bad size"); return }
            if (inLen - pos < size) break
            val target = objects[obj]
            if (target == null || !target.alive) {
            } else {
                try {
                    target.request(op, Args(inBuf, pos + 8, pos + size, inFds))
                } catch (e: ProtocolError) {
                    postError(e.objectId.takeIf { it != 1 } ?: obj, e.code, e.message ?: "error"); return
                } catch (e: Throwable) {
                    postError(obj, 0, "internal error: $e"); return
                }
            }
            pos += size
        }
        // compact
        if (pos > 0) {
            for (i in 0 until inLen - pos) inBuf.put(i, inBuf.get(pos + i))
            inLen -= pos
        }
    }

    fun postError(obj: Int, code: Int, msg: String) {
        server.host.log("protocol error to client: obj=$obj code=$code $msg")
        display.event(0).obj(obj).uint(code).string(msg).end()
        flush(); disconnect()
    }

    fun flush() {
        if (dead) return
        val b = out.buf
        val len = b.position()
        if (len == 0 && out.fds.isEmpty()) return
        val fds = out.fds.toIntArray()
        val r = Native.send(fd, b, len, fds, fds.size)
        for (f in fds) Native.close(f) // our copies; the client got its own
        out.fds.clear()
        if (r < 0) { disconnect(); return }
        if (r < len) {
            // partial write: keep the rest (rare; sockets have big buffers)
            val rest = ByteArray(len - r); for (i in 0 until len - r) rest[i] = b.get(r + i)
            b.clear(); b.put(rest)
        } else b.clear()
    }

    fun disconnect() {
        if (dead) return
        dead = true
        for (r in objects.values.toList()) { if (r.alive) { r.alive = false; try { r.onDestroy() } catch (_: Throwable) {} } }
        objects.clear()
        while (inFds.isNotEmpty()) Native.close(inFds.removeFirst())
        Native.close(fd)
        server.clientGone(this)
    }
}

class DisplayRes(c: Client) : Res(c, 1, 1) {
    override val iface = "wl_display"
    override fun request(op: Int, a: Args) {
        when (op) {
            0 -> { val cb = a.newId(); client.out.begin(cb, 0).uint(client.server.nextSerial()).end(); client.display.event(1).uint(cb).end() }
            1 -> client.register(RegistryRes(client, a.newId()))
            else -> super.request(op, a)
        }
    }
}

class RegistryRes(c: Client, id: Int) : Res(c, id, 1) {
    override val iface = "wl_registry"
    init { for (g in c.server.globals) event(0).uint(g.name).string(g.iface).uint(g.version).end() }
    override fun request(op: Int, a: Args) {
        if (op != 0) return super.request(op, a)
        val name = a.uint(); val ifc = a.string(); val ver = a.uint(); val nid = a.newId()
        val g = client.server.globals.firstOrNull { it.name == name } ?: throw ProtocolError(id, 0, "no global $name")
        if (g.iface != ifc) throw ProtocolError(id, 0, "global $name is ${g.iface}")
        client.register(g.bind(client, nid, minOf(ver, g.version)))
    }
}

class CallbackRes(c: Client, id: Int) : Res(c, id, 1) {
    override val iface = "wl_callback"
    fun done(data: Int) { if (!alive) return; event(0).uint(data).end(); client.destroyRes(this) }
}
