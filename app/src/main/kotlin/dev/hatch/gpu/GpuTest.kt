package dev.hatch.gpu

import android.content.Context
import android.util.Log
import dev.hatch.HatchApp
import dev.hatch.guest.AppSession
import dev.hatch.guest.GuestScripts
import dev.hatch.guest.LinuxRuntime
import dev.hatch.store.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings › Diagnostics › GPU test: launches a throwaway instance of an installed Chromium/Electron app on the
 * renderer rung it currently uses (own profile dir, so a running copy is untouched), asks it over DevTools which
 * GL renderer and compositing path it really got (guest-bin/hatch-gpu-diag), and reads back from the compositor
 * whether its frames arrived as dma-bufs or wl_shm.
 */
object GpuTest {
    private const val TAG = "HatchGpuTest"
    private val preference = listOf("brave", "code", "cursor", "chromium")

    suspend fun run(context: Context): GpuDiag.Report = withContext(Dispatchers.IO) {
        val rootfs = LinuxRuntime.rootfsDir(context)
        val gpuOk = Gpu.useGpu(Gpu.mode(context), Gpu.info(context, rootfs))
        val apps = InstalledApps.list(context).filter { !it.wine && Renderers.kind(it.command) == Renderers.Kind.CHROMIUM }
        val app = apps.minByOrNull { a -> preference.indexOfFirst { it in a.id.lowercase() }.let { if (it < 0) 99 else it } }
        if (app == null) {
            val env = Gpu.env(gpuOk).entries.joinToString(" ") { "${it.key}='${it.value}'" }
            val r = LinuxRuntime.run(context, "env $env ${GuestScripts.BIN}/hatch-gpu-diag 2>&1", timeoutMs = 90_000)
            return@withContext GpuDiag.report(GpuDiag.parse(r?.stdout.orEmpty()), null, null, emptySet())
        }
        val ladder = Renderers.ladder(Renderers.kind(app.command), gpuOk)
        val cfg = Renderers.current(context, app.id, ladder)
        val rt = HatchApp.instance.runtime
        rt.ensureStarted()
        val dir = rt.ensureAppSocket(GpuDiag.TAG).absolutePath
        rt.server?.let { s -> s.post { s.presentKinds.remove(GpuDiag.TAG) } }
        val argv = GuestScripts.launchArgv(listOf("${GuestScripts.BIN}/hatch-gpu-diag", "--chromium") + app.command + cfg.args.filter { it !in app.command },
            wine = false, x11 = cfg.x11 ?: app.x11)
        val env = Gpu.env(cfg.gpu) + cfg.env
        Log.i(TAG, "GPU test with ${app.id} on ${cfg.id}: $argv")
        if (!AppSession.start(context, GpuDiag.TAG, argv, dir, extraEnv = env, header = "GPU test: ${app.name} on ${cfg.label} [${cfg.id}]"))
            return@withContext GpuDiag.report(mapOf("chromium_error" to "could not start the guest"), app.name, cfg.label, emptySet())
        val deadline = System.currentTimeMillis() + 180_000
        while (AppSession.isRunning(GpuDiag.TAG) && System.currentTimeMillis() < deadline) delay(500)
        if (AppSession.isRunning(GpuDiag.TAG)) AppSession.stop(GpuDiag.TAG)
        delay(300)   // the log thread flushes after EOF
        val out = runCatching { AppSession.logFile(context, GpuDiag.TAG).readText() }.getOrDefault("")
        val kinds = rt.server?.presentKinds?.get(GpuDiag.TAG)?.toSet().orEmpty()
        GpuDiag.report(GpuDiag.parse(out), app.name, cfg.label, kinds).also { Log.i(TAG, "${it.title}: ${it.lines}") }
    }
}
