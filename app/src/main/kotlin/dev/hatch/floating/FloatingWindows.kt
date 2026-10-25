package dev.hatch.floating

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.hatch.HatchApp
import dev.hatch.store.InstalledApps
import dev.hatch.ui.AppLauncher
import dev.hatch.ui.WindowActivity

/**
 * Floating window mode (default): every program window is an overlay above the home screen and other apps, owned by
 * the runtime foreground service — like the Burrow demo (one Recents card, windows float over everything).
 * Task-per-window mode (each window its own Activity) stays available in Settings and is the fallback when the
 * "Display over other apps" permission hasn't been granted.
 */
object FloatingWindows {
    private const val TAG = "HatchFloat"
    const val PREF = "window_mode"
    const val MODE_FLOATING = "floating"
    const val MODE_TASKS = "tasks"

    private val main = Handler(Looper.getMainLooper())
    private val windows = mutableListOf<FloatingWindow>()   // main thread only; last = top

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences("hatch", Context.MODE_PRIVATE).getString(PREF, MODE_FLOATING) ?: MODE_FLOATING
    fun setMode(ctx: Context, m: String) = ctx.getSharedPreferences("hatch", Context.MODE_PRIVATE).edit().putString(PREF, m).apply()
    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun enabled(ctx: Context) = mode(ctx) == MODE_FLOATING && canOverlay(ctx)
    fun overlayPermissionIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val all: List<FloatingWindow> get() = windows.toList()
    val minimised: List<FloatingWindow> get() = windows.filter { it.isMinimised }

    private fun onMain(block: () -> Unit) = if (Looper.myLooper() == Looper.getMainLooper()) block() else { main.post(block); Unit }

    /** Open (or bring back) the program's window(s). */
    fun launch(ctx: Context, appId: String, label: String, fallbackCommand: List<String>? = null) = onMain {
        val app = ctx.applicationContext
        val rt = HatchApp.instance.runtime
        rt.ensureStarted()
        rt.secondaryLauncher = { id -> openSecondary(app, id) }
        val existing = windows.filter { it.appId == appId }
        if (existing.isNotEmpty() && dev.hatch.guest.AppSession.isRunning(appId)) {
            existing.forEach { it.restore(); raise(it) }
            return@onMain
        }
        val fw = FloatingWindow(app, appId, label, primary = true, cascade = windows.size)
        windows += fw
        fw.show()
        rt.register(appId, fw)
        when (val r = AppLauncher.startGuest(app, appId, label, fallbackCommand)) {
            is AppLauncher.Result.Error -> fw.status(r.message)
            else -> if (AppLauncher.isWine(app, appId)) fw.status("Starting $label…\n(first launch prepares Windows support — this can take a minute)")
        }
        Bubble.sync(app)
        Log.i(TAG, "floating window for $appId (${windows.size} open)")
    }

    // ---- ✕ on an app: one tap, closed from the user's view ----
    private val closing = HashMap<String, Long>()   // appId → close token (main thread)
    /** Main thread only. */
    fun isClosing(appId: String) = appId in closing

    /**
     * Hide every window of [appId] now, ask the app politely to close (xdg_toplevel.close to each of its toplevels;
     * X11 apps in rootful Xwayland also get WM_DELETE_WINDOW through the WM: hatch-x11-close), and end its session
     * if it is still running after [FloatingWindow.CLOSE_GRACE_MS]. If the app answers with a new window (a
     * "Save changes?" dialog), the close is called off and its windows come back.
     *
     * Root cause of the old "second tap" bug: apps did close their window on the first request, but proot only exits
     * when every guest process has, and session helpers (dbus-daemon, D-Bus-activated dconf/gvfs/at-spi) outlive
     * the app, so the window sat on "Waiting for…" until a second ✕ killed the session.
     */
    fun closeApp(ctx: Context, appId: String) = onMain {
        val app = ctx.applicationContext
        val mine = windows.filter { it.appId == appId }
        if (appId in closing) { finishClose(app, appId, closing[appId]!!, "second ✕"); return@onMain }
        val token = android.os.SystemClock.uptimeMillis(); closing[appId] = token
        mine.forEach { it.hideForClose() }
        Bubble.sync(app)
        val rt = HatchApp.instance.runtime
        rt.server?.post { rt.server?.windows?.filter { it.client.appTag == appId }?.forEach { it.xdg?.sendClose() } }
        dev.hatch.runtime.RuntimeControl.changed(app)
        if (!dev.hatch.guest.AppSession.isRunning(appId)) { finishClose(app, appId, token, "not running"); return@onMain }
        if (AppLauncher.isX11(app, appId)) {
            Log.i(TAG, "close $appId: xdg close + WM_DELETE_WINDOW (X11)")
            Thread({
                val r = runCatching {
                    kotlinx.coroutines.runBlocking {
                        dev.hatch.guest.LinuxRuntime.run(app, "/usr/local/bin/hatch-x11-close", timeoutMs = 6_000,
                            waylandHostDir = rt.ensureAppSocket(appId).absolutePath)
                    }?.stdout?.trim()?.lines()?.lastOrNull()
                }.getOrNull() ?: "?"
                main.post {
                    Log.i(TAG, "close $appId: X11 says $r")
                    when (r) {
                        "DIALOG" -> cancelClose(app, appId, "X11 dialog")
                        else -> main.postDelayed({ finishClose(app, appId, token, "X11 $r") }, if (r == "GONE") 600 else 0)
                    }
                }
            }, "hatch-close-$appId").apply { isDaemon = true; start() }
            main.postDelayed({ finishClose(app, appId, token, "X11 timeout") }, 7_000)
        } else {
            Log.i(TAG, "close $appId: xdg close sent")
            main.postDelayed({ finishClose(app, appId, token, "grace") }, FloatingWindow.CLOSE_GRACE_MS)
        }
    }

    private fun cancelClose(ctx: Context, appId: String, why: String) {
        if (closing.remove(appId) == null) return
        Log.i(TAG, "close $appId called off: $why")
        windows.filter { it.appId == appId }.forEach { it.unhideAfterClose() }
        Bubble.sync(ctx)
    }

    private fun finishClose(ctx: Context, appId: String, token: Long, why: String) {
        if (closing[appId] != token) return
        closing.remove(appId)
        val running = dev.hatch.guest.AppSession.isRunning(appId)
        Log.i(TAG, "close $appId done ($why): ${if (running) "session still running → ending it" else "app exited"}")
        if (running) dev.hatch.guest.AppSession.stop(appId)
        windows.filter { it.appId == appId }.forEach { it.forceDismiss() }
        Bubble.sync(ctx)
    }

    /** Stop all: tear down every floating window now (guests are already being killed). */
    fun closeAll(ctx: Context) = onMain { closing.clear(); windows.toList().forEach { it.forceDismiss() } }

    /** A further toplevel of a running program (dialog, 2nd document). */
    fun openSecondary(ctx: Context, appId: String) = onMain {
        if (!enabled(ctx)) { WindowActivity.launchSecondary(ctx, appId); return@onMain }
        // A new window while the app is being closed is its answer (e.g. "Save changes?"): show it, call off the close.
        if (appId in closing) cancelClose(ctx, appId, "new window")
        val label = InstalledApps.get(ctx, appId)?.name ?: appId
        val fw = FloatingWindow(ctx.applicationContext, appId, label, primary = false, cascade = windows.size)
        windows += fw
        fw.show()
        HatchApp.instance.runtime.register(appId, fw)
        Bubble.sync(ctx)
    }

    fun raise(fw: FloatingWindow) = onMain {
        if (windows.lastOrNull() === fw) return@onMain
        windows.remove(fw); windows += fw
        fw.bringToFront()
        Bubble.bringToFront()
    }

    internal fun removed(fw: FloatingWindow, ctx: Context) = onMain {
        windows.remove(fw)
        Bubble.sync(ctx)
    }

    /** Hide all (e.g. bubble long-press) / restore all. */
    fun minimiseAll() = onMain { windows.forEach { it.minimise() } }
    fun restoreAll() = onMain { windows.forEach { it.restore() } }
}
