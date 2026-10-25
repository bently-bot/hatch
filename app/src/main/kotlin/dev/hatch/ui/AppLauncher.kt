package dev.hatch.ui

import android.content.Context
import android.util.Log
import dev.hatch.HatchApp
import dev.hatch.gpu.Gpu
import dev.hatch.gpu.Renderers
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
        val gpuOk = Gpu.useGpu(Gpu.mode(context), Gpu.info(context, LinuxRuntime.rootfsDir(context)))
        val kind = Renderers.kind(cmd)
        val ladder = Renderers.ladder(kind, gpuOk)
        val cfg = Renderers.current(context, appId, ladder)
        Renderers.active[appId] = cfg
        val flatpak = cmd.firstOrNull() == dev.hatch.guest.FlatpakSpec.RUN
        val full = if (rec?.wine == true) cmd else cmd + cfg.args.filter { it !in cmd }
        val argv = if (flatpak) full
            else GuestScripts.launchArgv(full, wine = rec?.wine == true, x11 = cfg.x11 ?: (rec?.x11 == true))
        val env = Gpu.env(cfg.gpu) + cfg.env
        val ok = AppSession.start(context, appId, argv, waylandDir, extraEnv = env, onExit = { rt.guestExited(appId) },
            header = "renderer: ${cfg.label} [${cfg.id}] (${kind.name.lowercase()}, ladder ${ladder.joinToString(" → ") { it.id }})\n" +
                "gpu: ${Gpu.describe(Gpu.mode(context), Gpu.info(context, LinuxRuntime.rootfsDir(context)))}\n" +
                "env: ${env.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
        Log.i(TAG, "renderer for $appId: ${cfg.label} [${cfg.id}] (ladder ${ladder.joinToString(" → ") { it.id }})")
        Log.i(TAG, "spawn $appId ok=$ok argv=$argv")
        return if (ok) Result.Started else Result.Error("Couldn't start $label.")
    }

    fun isWine(context: Context, appId: String) = InstalledApps.get(context, appId)?.wine == true

    /** The app's guest runs in rootful Xwayland (its own X11 app, or a Chromium app on the X11 GPU rung). */
    fun isX11(context: Context, appId: String): Boolean =
        Renderers.active[appId]?.x11 ?: (InstalledApps.get(context, appId)?.x11 == true)

    fun ladderFor(context: Context, appId: String): List<Renderers.Config> {
        val cmd = InstalledApps.get(context, appId)?.command ?: return emptyList()
        val gpuOk = Gpu.useGpu(Gpu.mode(context), Gpu.info(context, LinuxRuntime.rootfsDir(context)))
        return Renderers.ladder(Renderers.kind(cmd), gpuOk)
    }
}
