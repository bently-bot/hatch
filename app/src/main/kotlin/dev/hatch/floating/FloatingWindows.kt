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

    /** Stop all: tear down every floating window now (guests are already being killed). */
    fun closeAll(ctx: Context) = onMain { windows.toList().forEach { it.forceDismiss() } }

    /** A further toplevel of a running program (dialog, 2nd document). */
    fun openSecondary(ctx: Context, appId: String) = onMain {
        if (!enabled(ctx)) { WindowActivity.launchSecondary(ctx, appId); return@onMain }
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
