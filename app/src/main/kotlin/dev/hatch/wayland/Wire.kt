package dev.hatch.wayland

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolError(val objectId: Int, val code: Int, message: String) : Exception(message)

/** Reads the arguments of one request. */
class Args(private val b: ByteBuffer, private var pos: Int, private val end: Int, private val fds: ArrayDeque<Int>) {
    fun int(): Int { check(pos + 4 <= end) { "short message" }; val v = b.getInt(pos); pos += 4; return v }
    fun uint(): Int = int()
    fun fixed(): Double = int() / 256.0
    fun obj(): Int = int()
    fun newId(): Int = int()
    fun string(): String? {
        val len = int()
        if (len == 0) return null
        check(pos + len <= end) { "bad string" }
        val bytes = ByteArray(len - 1)
        for (i in 0 until len - 1) bytes[i] = b.get(pos + i)
        pos += (len + 3) and 3.inv()
        return String(bytes, Charsets.UTF_8)
    }
    fun array(): ByteArray {
        val len = int()
        check(pos + len <= end) { "bad array" }
        val out = ByteArray(len)
        for (i in 0 until len) out[i] = b.get(pos + i)
        pos += (len + 3) and 3.inv()
        return out
    }
    fun fd(): Int = fds.removeFirstOrNull() ?: throw ProtocolError(1, 0, "missing fd")
}

/** Accumulates events for one client. */
class Out {
    var buf: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    val fds = ArrayList<Int>()
    private var start = 0

    private fun ensure(n: Int) {
        if (buf.position() + n <= buf.capacity()) return
        val nb = ByteBuffer.allocateDirect(maxOf(buf.capacity() * 2, buf.position() + n + 1024)).order(ByteOrder.LITTLE_ENDIAN)
        buf.flip(); nb.put(buf); buf = nb
    }
    fun begin(obj: Int, opcode: Int): Out { ensure(8); start = buf.position(); buf.putInt(obj); buf.putInt(opcode); return this }
    fun int(v: Int): Out { ensure(4); buf.putInt(v); return this }
    fun uint(v: Int): Out = int(v)
    fun obj(v: Int): Out = int(v)
    fun fixed(v: Double): Out = int(Math.round(v * 256.0).toInt())
    fun string(s: String?): Out {
        if (s == null) return int(0)
        val bytes = s.toByteArray(Charsets.UTF_8)
        val len = bytes.size + 1
        ensure(4 + len + 4)
        buf.putInt(len); buf.put(bytes); buf.put(0)
        repeat(((len + 3) and 3.inv()) - len) { buf.put(0) }
        return this
    }
    fun array(a: ByteArray): Out {
        ensure(4 + a.size + 4)
        buf.putInt(a.size); buf.put(a)
        repeat(((a.size + 3) and 3.inv()) - a.size) { buf.put(0) }
        return this
    }
    fun intArray(vs: IntArray): Out {
        val a = ByteBuffer.allocate(vs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vs.forEach { a.putInt(it) }
        return array(a.array())
    }
    fun fd(fd: Int): Out { fds.add(fd); return this }
    fun end() {
        val size = buf.position() - start
        val opcode = buf.getInt(start + 4)
        buf.putInt(start + 4, (size shl 16) or (opcode and 0xffff))
    }
}
