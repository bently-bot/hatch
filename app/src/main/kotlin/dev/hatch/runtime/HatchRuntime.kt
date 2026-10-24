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

/** Integer Wayland output scale from Android density (2.625 on Fold/Pixel → 2), or a user override 1..3. */
fun outputScale(density: Float, override: Int = 0): Int =
    if (override in 1..3) override else Math.round(density * 0.85f).coerceIn(1, 3)

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
            val dm = app.resources.displayMetrics
            s.scale = outputScale(dm.density, app.getSharedPreferences("hatch", Context.MODE_PRIVATE).getInt("ui_scale", 0))
            s.outputWidth = maxOf(dm.widthPixels, dm.heightPixels)
            s.outputHeight = minOf(dm.widthPixels, dm.heightPixels)
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

    /**
     * One Wayland socket per app session (filesDir/wayland/<appId>/wayland-0, bound to the guest's /run/hatch).
     * Every client on it is tagged with the appId, so windows are routed to that app's Android windows.
     */
    fun ensureAppSocket(appId: String): File {
        ensureStarted()
        val dir = File(sockDir, "app-" + appId.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { mkdirs() }
        synchronized(appSockets) {
            if (appId !in appSockets) {
                val sock = File(dir, "wayland-0"); sock.delete()
                if (server?.addSocket(sock.absolutePath, appId) == true) appSockets.add(appId)
            }
        }
        return dir
    }
    private val appSockets = HashSet<String>()

    /** An Android window (WindowActivity) that can show one toplevel. */
    interface WindowSlot {
        val boundWindow: Toplevel?
        fun bind(w: Toplevel)
        fun windowGone(w: Toplevel)
        fun guestExited() {}
        fun textInput(enabled: Boolean) {}
    }

    private val slots = ConcurrentHashMap<String, CopyOnWriteArrayList<WindowSlot>>()
    private val unclaimed = ConcurrentHashMap<String, CopyOnWriteArrayList<Toplevel>>()
    @Volatile var focusedSlot: WindowSlot? = null

    fun register(appId: String, slot: WindowSlot) {
        slots.getOrPut(appId) { CopyOnWriteArrayList() }.add(slot)
        claimFor(appId, slot)
    }

    fun unregister(appId: String, slot: WindowSlot) {
        slots[appId]?.remove(slot)
        if (focusedSlot === slot) focusedSlot = null
    }

    fun hasSlots(appId: String) = slots[appId]?.isNotEmpty() == true

    fun claimFor(appId: String, slot: WindowSlot): Boolean {
        if (slot.boundWindow != null) return true
        val q = unclaimed[appId] ?: return false
        val w = q.firstOrNull { it.surface.alive } ?: return false
        q.remove(w); slot.bind(w); return true
    }

    fun windowsOf(appId: String): List<Toplevel> =
        server?.call(1000) { server!!.windows.filter { it.client.appTag == appId } } ?: emptyList()

    var secondaryLauncher: ((appId: String) -> Unit)? = null

    fun guestExited(appId: String) { slots[appId]?.forEach { it.guestExited() } }

    override fun windowCreated(w: Toplevel) {
        val tag = w.client.appTag
        log("windowCreated tag='$tag' title='${w.title}'")
        val free = slots[tag]?.firstOrNull { it.boundWindow == null }
        if (free != null) { free.bind(w); return }
        unclaimed.getOrPut(tag) { CopyOnWriteArrayList() }.add(w)
        if (tag.isNotEmpty() && tag != "hatch") secondaryLauncher?.invoke(tag)
        // Legacy listeners (global socket)
        anyListeners.forEach { it(w) }
    }

    override fun windowChanged(w: Toplevel) {
        if (w.appId.isNotEmpty()) listeners[w.appId]?.forEach { it(w) }
    }

    override fun textInputChanged(enabled: Boolean) { focusedSlot?.textInput(enabled) }

    override fun windowDestroyed(w: Toplevel) {
        log("windowDestroyed '${w.title}'")
        unclaimed[w.client.appTag]?.remove(w)
        slots[w.client.appTag]?.forEach { if (it.boundWindow === w) it.windowGone(w) }
    }

    override fun log(msg: String) { Log.i(TAG, msg) }

    companion object { private const val TAG = "HatchRuntime" }
}
