package dev.hatch.harness

import dev.hatch.wayland.*
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun savePng(px: IntArray, f: File) {
    val img = BufferedImage(1280, 800, BufferedImage.TYPE_INT_ARGB)
    for (i in px.indices) {
        val v = px[i]
        val r = v and 0xff; val g = (v shr 8) and 0xff; val b = (v shr 16) and 0xff; val a = (v shr 24) and 0xff
        img.setRGB(i % 1280, i / 1280, (a shl 24) or (r shl 16) or (g shl 8) or b)
    }
    ImageIO.write(img, "png", f)
    println("[host] wrote $f")
}

fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println("usage: Main <socket-dir> <png> <seconds> [--inject] -- <cmd...>")
        System.exit(2)
    }
    val sockDir = File(args[0]).absoluteFile; sockDir.mkdirs()
    val png = File(args[1]); png.parentFile?.mkdirs()
    val seconds = args[2].toInt()
    val inject = args.contains("--inject")
    fun opt(n: String) = args.firstOrNull { it.startsWith("--$n=") }?.substringAfter("=")
    val titleFilter = opt("title")            // only capture a window whose title contains this
    val settleMs = opt("settle")?.toLong() ?: 0L // wait after first good frame, then re-capture
    val waitExit = args.contains("--wait-exit") // succeed when client exits 0 (e.g. wineboot)
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

    Thread.sleep(200)
    val pb = ProcessBuilder(cmd)
    pb.environment()["XDG_RUNTIME_DIR"] = sockDir.absolutePath
    pb.environment()["WAYLAND_DISPLAY"] = "wayland-0"
    pb.environment()["GDK_BACKEND"] = "wayland"
    pb.environment()["QT_QPA_PLATFORM"] = "wayland"
    pb.environment()["SDL_VIDEODRIVER"] = "wayland"
    pb.environment()["NO_AT_BRIDGE"] = "1"
    pb.environment()["GTK_A11Y"] = "none"
    pb.environment()["GSETTINGS_BACKEND"] = "memory"
    pb.environment()["LC_ALL"] = "C.UTF-8"
    pb.environment().remove("DISPLAY")

    pb.redirectErrorStream(true)
    val logFile = File(png.parent, "client.log")
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
    val proc = pb.start()
    println("[host] spawned client pid=${proc.pid()} cmd=$cmd inject=$inject"); System.out.flush()

    val deadline = System.currentTimeMillis() + seconds * 1000L
    var captured = false
    var injected = false
    var settled = settleMs == 0L
    var lastTitles = ""
    while (System.currentTimeMillis() < deadline && !captured) {
        Thread.sleep(200)
        if (!proc.isAlive) {
            println("[host] client exited code=${proc.exitValue()}")
            if (waitExit) {
                server.stop()
                System.exit(if (proc.exitValue() == 0) 0 else 3)
            }
        }
        val titles = synchronized(windows) { windows.joinToString(" | ") { "'${it.title}'(${it.appId}) ${it.surface.storeW}x${it.surface.storeH}" } }
        if (titles != lastTitles) { println("[host] windows: $titles"); lastTitles = titles }
        if (waitExit) continue
        val w = synchronized(windows) {
            windows.lastOrNull { it.surface.store != 0L && it.surface.storeW > 8 &&
                (titleFilter == null || it.title.contains(titleFilter, ignoreCase = true)) }
        } ?: continue
        if (!settled) {
            println("[host] first frame for '${w.title}', settling ${settleMs}ms")
            Thread.sleep(settleMs); settled = true; continue
        }

        if (inject && !injected) {
            injected = true
            val mode = System.getProperty("hatch.inject", "all")
            if (mode == "script") {
                // -Dhatch.script="m:x,y;c:x,y;k:30,31;kd:103;ku:103;w:500;s:name" (move, click, tap/hold/release keys, wait ms, snapshot)
                for (step in System.getProperty("hatch.script", "").split(';').filter { it.isNotBlank() }) {
                    val op = step.substringBefore(':'); val arg = step.substringAfter(':')
                    when (op) {
                        "w" -> Thread.sleep(arg.toLong())
                        "t" -> { server.call(1000) { server.seat.typeText(w, arg.replace("\\n", "\n")); true }; Thread.sleep(200) }
                        // chord, e.g. kc:29+30 = Ctrl+A (press in order, release in reverse)
                        "kc" -> { val ks = arg.split('+').map { it.trim().toInt() }
                            server.call(1000) { ks.forEach { server.seat.key(w, it, true) }; ks.reversed().forEach { server.seat.key(w, it, false) }; true }
                            Thread.sleep(250) }
                        "p" -> { server.call(1000) { server.clipboard.setFromHost(arg); true }; Thread.sleep(200) } // Android → guest
                        "g" -> println("[host] clipboard[$arg]: ${server.call(1000) { server.clipboard.text } ?: "<none>"}")
                        "s" -> { // intermediate snapshot
                            val px0 = IntArray(1280 * 800)
                            if (server.call(2000) { w.readPixels(px0) } == true) savePng(px0, File(png.parentFile, "${png.nameWithoutExtension}-$arg.png"))
                        }
                        else -> {
                            val n = arg.split(',').map { it.trim().toInt() }
                            server.call(1000) {
                                when (op) {
                                    "m" -> server.seat.pointerMotion(w, n[0], n[1])
                                    "c" -> { server.seat.pointerMotion(w, n[0], n[1]); server.seat.pointerButton(w, 0, true); server.seat.pointerButton(w, 0, false) }
                                    "k" -> for (k in n) { server.seat.key(w, k, true); server.seat.key(w, k, false) }
                                    "kd" -> for (k in n) server.seat.key(w, k, true)    // hold (games sample keys per tic)
                                    "ku" -> for (k in n) server.seat.key(w, k, false)
                                }
                                true
                            }
                            Thread.sleep(150)
                        }
                    }
                }
            } else server.call(1000) {
                when (mode) {
                    "enter" -> server.seat.pointerMotion(w, 640, 400)
                    "click" -> {
                        server.seat.pointerMotion(w, 640, 400)
                        server.seat.pointerButton(w, 0, true)
                        server.seat.pointerButton(w, 0, false)
                    }
                    "keys" -> {
                        server.seat.focusWindow(w)
                        for (k in intArrayOf(43, 38, 28, 54, 43)) {
                            server.seat.key(w, k, true); server.seat.key(w, k, false)
                        }
                    }
                    else -> {
                        server.seat.pointerMotion(w, 640, 400)
                        server.seat.pointerButton(w, 0, true)
                        server.seat.pointerButton(w, 0, false)
                        for (k in intArrayOf(43, 38, 28, 54, 43)) {
                            server.seat.key(w, k, true); server.seat.key(w, k, false)
                        }
                    }
                }
                true
            }
            println("[host] injected mode=$mode")
            Thread.sleep(600)
        }

        val img = BufferedImage(1280, 800, BufferedImage.TYPE_INT_ARGB)
        val px = IntArray(1280 * 800)
        // Read back exactly what the app's render path (Toplevel.render) produced.
        val ok = server.call(2000) { w.readPixels(px) } == true
        if (!ok) { println("[host] blit call failed"); continue }
        var coloured = 0
        for (i in px.indices step 8) if ((px[i] and 0xffffff) != 0x2a2a2a) coloured++
        println("[host] coloured samples=$coloured store=${w.surface.storeW}x${w.surface.storeH} popups=${w.popups.size}")
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
