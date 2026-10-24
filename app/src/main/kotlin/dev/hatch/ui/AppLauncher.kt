package dev.hatch.ui

import android.content.Context
import android.util.Log
import dev.hatch.HatchApp
import dev.hatch.gpu.Gpu
import dev.hatch.guest.AppSession
import dev.hatch.guest.GuestScripts
import dev.hatch.guest.LinuxRuntime
import dev.hatch.store.InstalledApps

/** Starts an installed program's guest session (shared by window-per-task mode and floating overlay mode). */
object AppLauncher {
    private const val TAG = "HatchWindow"

    sealed interface Result {
        data object Started : Result
        data object AlreadyRunning : Result
        data class Error(val message: String) : Result
    }

    fun startGuest(context: Context, appId: String, label: String, fallbackCommand: List<String>? = null): Result {
        if (AppSession.isRunning(appId)) return Result.AlreadyRunning
        if (!LinuxRuntime.isInstalled(context)) return Result.Error("Hatch isn't set up yet — open Hatch first.")
        val rec = InstalledApps.get(context, appId)
        val cmd = rec?.command ?: fallbackCommand
        if (cmd.isNullOrEmpty()) return Result.Error("$label isn't installed.")
        val rt = HatchApp.instance.runtime
        val waylandDir = rt.ensureAppSocket(appId).absolutePath
        val argv = if (cmd.firstOrNull() == dev.hatch.guest.FlatpakSpec.RUN) cmd
            else GuestScripts.launchArgv(cmd, wine = rec?.wine == true, x11 = rec?.x11 == true)
        val gpu = Gpu.useGpu(Gpu.mode(context), Gpu.info(context, LinuxRuntime.rootfsDir(context)))
        val ok = AppSession.start(context, appId, argv, waylandDir, extraEnv = Gpu.env(gpu), onExit = { rt.guestExited(appId) })
        Log.i(TAG, "rendering for $appId: ${if (gpu) "GPU (Turnip/KGSL + Zink)" else "software"}")
        Log.i(TAG, "spawn $appId ok=$ok argv=$argv")
        return if (ok) Result.Started else Result.Error("Couldn't start $label.")
    }

    fun isWine(context: Context, appId: String) = InstalledApps.get(context, appId)?.wine == true
}
