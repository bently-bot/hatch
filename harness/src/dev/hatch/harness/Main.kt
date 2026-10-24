package dev.hatch.harness

import dev.hatch.wayland.*
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    if (args.size < 4) { System.err.println("usage: Main <socket-dir> <png> <seconds> -- <cmd...>"); System.exit(2) }
    val sockDir = File(args[0]).absoluteFile; sockDir.mkdirs()
    val png = File(args[1]); png.parentFile?.mkdirs()
    val seconds = args[2].toInt()
    val dash = args.indexOf("--"); require(dash > 0); val cmd = args.drop(dash + 1)

    val keymap = File("app/src/main/assets/keymap.xkb").readText()
    val windows = ArrayList<Toplevel>()
    val host = object : WindowHost {
        override fun windowCreated(w: Toplevel) {
            log("window created")
            w.attachTarget(Native.targetMemory(1280, 800), 1280, 800)
            synchronized(windows) { windows.add(w) }
        }
        override fun windowDestroyed(w: Toplevel) {
            log("window destroyed"); synchronized(windows) { windows.remove(w) }
        }
        override fun log(msg: String) { println("[host] $msg"); System.out.flush() }
    }
    val server = WaylandServer(host, sockDir.absolutePath, keymap)
    server.scale = 1; server.outputWidth = 1280; server.outputHeight = 800
    val sock = File(sockDir, "wayland-0").absolutePath; File(sock).delete()
    server.start()
    check(server.addSocket(sock, "harness")) { "listen failed" }
    println("[host] listening on $sock"); System.out.flush()

    // Give the listen socket a moment, then spawn the client as a child of this JVM's env
    Thread.sleep(200)
    val pb = ProcessBuilder(cmd)
    pb.environment()["XDG_RUNTIME_DIR"] = sockDir.absolutePath
    pb.environment()["WAYLAND_DISPLAY"] = "wayland-0"
    // pb.environment()["WAYLAND_DEBUG"] = "1"
    pb.redirectErrorStream(true)
    val logFile = File(png.parent, "client.log")
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
    val proc = pb.start()
    println("[host] spawned client pid=${proc.pid()} cmd=$cmd"); System.out.flush()

    val deadline = System.currentTimeMillis() + seconds * 1000L
    var captured = false
    while (System.currentTimeMillis() < deadline && !captured) {
        Thread.sleep(200)
        if (!proc.isAlive) {
            println("[host] client exited code=${proc.exitValue()}")
            // keep waiting a bit in case last frame landed
        }
        val w = synchronized(windows) { windows.firstOrNull { it.surface.store != 0L && it.surface.storeW > 8 } } ?: continue
        val img = BufferedImage(1280, 800, BufferedImage.TYPE_INT_ARGB)
        val px = IntArray(1280 * 800)
        val ok = server.call(2000) {
            val t = Native.targetMemory(1280, 800)
            Native.targetBegin(t, 0xff2a2a2a.toInt())
            if (w.surface.store != 0L)
                Native.targetBlit(t, w.surface.store, 0, 0, 1280, 800, 0, 0, w.surface.storeW, w.surface.storeH, w.surface.opaque)
            Native.targetEnd(t)
            Native.targetRead(t, px)
            Native.targetRelease(t)
            true
        } == true
        if (!ok) { println("[host] blit call failed"); continue }
        var coloured = 0
        for (i in px.indices step 8) if ((px[i] and 0xffffff) != 0x2a2a2a) coloured++
        println("[host] coloured samples=$coloured store=${w.surface.storeW}x${w.surface.storeH}")
        if (coloured < 10) continue
        for (i in px.indices) {
            val v = px[i]
            val r = v and 0xff; val g = (v shr 8) and 0xff; val b = (v shr 16) and 0xff; val a = (v shr 24) and 0xff
            img.setRGB(i % 1280, i / 1280, (a shl 24) or (r shl 16) or (g shl 8) or b)
        }
        ImageIO.write(img, "png", png)
        println("[host] wrote $png (${png.length()} bytes)")
        captured = true
    }
    proc.destroyForcibly()
    server.stop()
    if (!captured) { System.err.println("[host] timed out"); System.exit(1) }
}
