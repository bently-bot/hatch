package dev.hatch.runtime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import dev.hatch.HatchApp
import dev.hatch.floating.Bubble
import dev.hatch.floating.FloatingWindows
import dev.hatch.guest.AppSession
import dev.hatch.store.InstallManager
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lifecycle of the runtime foreground service: "Stop all", auto-stop once nothing runs, and the notification text.
 * The in-process Wayland compositor stays up (a thread, no processes); only guests, windows and the FGS go.
 */
object RuntimeControl {
    private const val TAG = "HatchRuntime"
    const val ACTION_STOP_ALL = "dev.hatch.action.STOP_ALL"
    const val AUTO_STOP_GRACE_MS = 10_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val _stopped = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted by Stop all: full-screen WindowActivity tasks close themselves. */
    val stopped = _stopped.asSharedFlow()

    data class Proc(val pid: Int, val uid: Int, val cmdline: String)

    /**
     * Processes Stop all must kill: everything of our uid except our own Android process(es) (cmdline = package
     * name, optionally ":suffix"). Guests run under proot as our uid, so this catches wineserver / Xwayland /
     * daemonised children that left proot's process group. Pure; unit-tested.
     */
    fun orphans(procs: List<Proc>, myPid: Int, myUid: Int, packageName: String): List<Int> =
        procs.filter { it.uid == myUid && it.pid != myPid && it.cmdline != packageName && !it.cmdline.startsWith("$packageName:") }.map { it.pid }

    private fun readProcs(): List<Proc> = File("/proc").listFiles().orEmpty().mapNotNull { d ->
        val pid = d.name.toIntOrNull() ?: return@mapNotNull null
        val uid = runCatching { File(d, "status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt() }.getOrNull() ?: return@mapNotNull null
        val cmd = runCatching { File(d, "cmdline").readText().substringBefore('\u0000') }.getOrDefault("")
        Proc(pid, uid, cmd)
    }

    /** Pids of guest processes still alive (for tests/logs). */
    fun guestPids(ctx: Context): List<Int> = orphans(readProcs(), Process.myPid(), Process.myUid(), ctx.packageName)

    private fun busyInstalling(): Boolean =
        InstallManager.progress.value is InstallManager.Progress.Working || dev.hatch.guest.RootfsInstaller.isRunning

    /** Close every app, kill leftover guest processes, remove overlays and the notification. */
    fun stopAll(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        Log.i(TAG, "stop all ($reason)")
        AppSession.stopAll()
        main.post {
            FloatingWindows.closeAll(app)
            Bubble.sync(app)
            _stopped.tryEmit(Unit)
        }
        Thread({
            // An install/setup runs proot too; leave it alone (its own processes end with it).
            if (!busyInstalling()) {
                repeat(3) { round ->
                    val left = guestPids(app)
                    if (left.isEmpty()) return@repeat
                    Log.i(TAG, "killing ${left.size} leftover guest process(es) (round $round)")
                    left.forEach { runCatching { Process.sendSignal(it, if (round == 0) 15 else 9) } }
                    Thread.sleep(300)
                }
            }
            stopService(app)
        }, "hatch-stop-all").start()
    }

    /** Stop all and wait until no guest process is left (Storage reset). */
    fun stopAllAndWait(ctx: Context, timeoutMs: Long = 8_000) {
        stopAll(ctx, "storage reset")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && (guestPids(ctx).isNotEmpty() || HatchRuntimeService.running)) Thread.sleep(200)
    }

    private fun stopService(app: Context) {
        HatchRuntimeService.running = false
        app.stopService(Intent(app, HatchRuntimeService::class.java))
        Log.i(TAG, "runtime service stopped")
    }

    /** Nothing open, nothing running: guests, floating windows, full-screen windows. */
    fun idle(): Boolean =
        AppSession.runningIds().isEmpty() && FloatingWindows.all.isEmpty() && !HatchApp.instance.runtime.anySlots()

    private val autoStop = Runnable {
        val app = HatchApp.instance
        if (HatchRuntimeService.running && idle() && !busyInstalling()) {
            Log.i(TAG, "auto-stop: no apps running")
            stopService(app)
        }
    }

    /** Called whenever an app session or window goes away / appears; refreshes the notification too. */
    fun changed(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            HatchRuntimeService.refresh(app)
            main.removeCallbacks(autoStop)
            if (idle()) main.postDelayed(autoStop, AUTO_STOP_GRACE_MS)
        }
    }

    /** "2 apps running: Blender, VS Code". Pure; unit-tested. */
    fun summary(names: List<String>): String = when (names.size) {
        0 -> "No apps running"
        1 -> "1 app running: ${names[0]}"
        else -> "${names.size} apps running: ${names.joinToString(", ")}"
    }
}
