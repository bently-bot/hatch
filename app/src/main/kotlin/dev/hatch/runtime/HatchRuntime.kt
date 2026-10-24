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

/**
 * Owns the Wayland compositor and (later) the proot guest.
 * Lives in the process; [HatchRuntimeService] just keeps us off the phantom-process killer.
 * Activities bind/unbind targets on fold/unfold without tearing this down.
 */
class HatchRuntime(private val app: Context) : WindowHost {
    @Volatile var server: WaylandServer? = null
        private set
    private val sockDir: File = File(app.filesDir, "wayland").apply { mkdirs() }
    private val windows = ConcurrentHashMap<String, Toplevel>() // appId -> primary toplevel for now
    private val listeners = ConcurrentHashMap<String, (Toplevel) -> Unit>()

    fun ensureStarted() {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            Native.eventfd().also { Native.close(it) } // force JNI load
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

    fun onWindowAttached(appId: String, cb: (Toplevel) -> Unit) { listeners[appId] = cb }
    fun onWindowDetached(appId: String) { listeners.remove(appId) }

    override fun windowCreated(w: Toplevel) {
        val id = w.appId.ifEmpty { "unknown-${System.identityHashCode(w)}" }
        windows[id] = w
        listeners[id]?.invoke(w)
        log("windowCreated id=$id")
    }

    override fun windowChanged(w: Toplevel) {
        if (w.appId.isNotEmpty()) windows[w.appId] = w
    }

    override fun windowDestroyed(w: Toplevel) {
        windows.entries.removeIf { it.value === w }
        log("windowDestroyed")
    }

    override fun log(msg: String) { Log.i(TAG, msg) }

    companion object { private const val TAG = "HatchRuntime" }
}
