package dev.hatch.wayland

import java.nio.ByteBuffer

/** JNI bindings to cpp/hatchnative.c. Negative int results are -errno. */
object Native {
    init {
        val hostLib = System.getProperty("hatch.native")
        if (hostLib != null) System.load(hostLib) else System.loadLibrary("hatchnative")
        // SIGBUS guard is installed only on Android (memfd truncation); on the JVM host it fights HotSpot.
        if (hostLib == null) installSigbusGuard()
    }

    @JvmStatic external fun listen(path: String): Int
    @JvmStatic external fun accept(fd: Int): Int
    @JvmStatic external fun recv(fd: Int, buf: ByteBuffer, off: Int, cap: Int, fds: IntArray): Int
    @JvmStatic external fun send(fd: Int, buf: ByteBuffer, len: Int, fds: IntArray, nfds: Int): Int
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun poll(fds: IntArray, n: Int, revents: IntArray, timeoutMs: Int): Int
    @JvmStatic external fun eventfd(): Int
    @JvmStatic external fun eventfdSignal(fd: Int)
    @JvmStatic external fun eventfdDrain(fd: Int)
    @JvmStatic external fun memfdWith(data: ByteArray, tmpDir: String): Int
    @JvmStatic external fun pipe(): IntArray?
    @JvmStatic external fun writeAll(fd: Int, data: ByteArray): Int
    @JvmStatic external fun readAll(fd: Int, max: Int): ByteArray
    @JvmStatic private external fun installSigbusGuard()
    @JvmStatic external fun mmap(fd: Int, size: Int): Long
    @JvmStatic external fun munmap(ptr: Long, size: Int)
    @JvmStatic external fun storeAlloc(w: Int, h: Int): Long
    @JvmStatic external fun storeFree(store: Long)
    @JvmStatic external fun storeCopy(shm: Long, offset: Int, stride: Int, format: Int, store: Long, rects: IntArray, count: Int, poolSize: Long): Int
    @JvmStatic external fun storeCopyFd(fd: Int, offset: Int, stride: Int, format: Int, store: Long, w: Int, h: Int): Int
    /** (luma mean << 8) | luma stddev over a sample grid of a store; -1 if empty. */
    @JvmStatic external fun storeStats(store: Long): Int
    @JvmStatic external fun targetForSurface(surface: Any, w: Int, h: Int): Long
    @JvmStatic external fun targetMemory(w: Int, h: Int): Long
    @JvmStatic external fun targetRelease(target: Long)
    @JvmStatic external fun targetBegin(target: Long, background: Int): Boolean
    @JvmStatic external fun targetBlit(target: Long, store: Long, dx: Int, dy: Int, dw: Int, dh: Int, sx: Int, sy: Int, sw: Int, sh: Int, opaque: Boolean)
    @JvmStatic external fun targetEnd(target: Long)
    @JvmStatic external fun targetRead(target: Long, out: IntArray)
    @JvmStatic external fun spawn(cmd: String, argv: Array<String>, envp: Array<String>, cwd: String, usePty: Boolean): IntArray?
    @JvmStatic external fun waitPid(pid: Int, block: Boolean): Int
    @JvmStatic external fun killGroup(pid: Int, sig: Int)
    @JvmStatic external fun readSome(fd: Int, out: ByteArray): Int
}
