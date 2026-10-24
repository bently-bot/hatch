package dev.hatch.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.hatch.wayland.Native
import dev.hatch.wayland.Toplevel
import dev.hatch.wayland.WaylandServer
import dev.hatch.wayland.WindowHost
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class HatchRuntime(private val app: Context) : WindowHost {
    @Volatile var server: WaylandServer? = null
        private set
    private val sockDir: File = File(app.filesDir, "wayland").apply { mkdirs() }
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(Toplevel) -> Unit>>()
    private val anyListeners = CopyOnWriteArrayList<(Toplevel) -> Unit>()

    fun ensureStarted() {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            // Force JNI load
            Native.eventfd().also { Native.close(it) }
            val keymap = app.assets.open("keymap.xkb").bufferedReader().readText()
            val s = WaylandServer(this, sockDir.absolutePath, keymap)
            s.scale = 2
            s.outputWidth = 1920
            s.outputHeight = 1080
            s.start()
            val sock = File(sockDir, "wayland-0").absolutePath
            File(sock).delete()
            check(s.addSocket(sock, "hatch")) { "wayland listen failed" }
            server = s
            Log.i(TAG, "Wayland listening on $sock")
            val svc = Intent(app, HatchRuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc) else app.startService(svc)
        }
    }

    fun socketDir(): File = sockDir

    fun onWindowAttached(appId: String, cb: (Toplevel) -> Unit) {
        if (appId.isEmpty()) anyListeners.add(cb)
        else listeners.getOrPut(appId) { CopyOnWriteArrayList() }.add(cb)
    }

    fun onWindowDetached(appId: String) {
        if (appId.isEmpty()) anyListeners.clear()
        else listeners.remove(appId)
    }

    override fun windowCreated(w: Toplevel) {
        val id = w.appId
        log("windowCreated appId='$id' title='${w.title}'")
        if (id.isNotEmpty()) listeners[id]?.forEach { it(w) }
        anyListeners.forEach { it(w) }
    }

    override fun windowChanged(w: Toplevel) {
        if (w.appId.isNotEmpty()) listeners[w.appId]?.forEach { it(w) }
    }

    override fun windowDestroyed(w: Toplevel) { log("windowDestroyed") }
    override fun log(msg: String) { Log.i(TAG, msg) }

    companion object { private const val TAG = "HatchRuntime" }
}
