package dev.hatch.store

import android.content.Context
import android.util.Log
import dev.hatch.guest.GuestScripts
import dev.hatch.guest.Apt
import dev.hatch.guest.GuestConfig
import dev.hatch.guest.LinuxRuntime
import dev.hatch.guest.download
import dev.hatch.stub.StubInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

object InstallManager {
    private const val TAG = "HatchInstall"

    sealed interface Progress {
        data object Idle : Progress
        /** [fraction] null = indeterminate; [sizes] e.g. "201 MB to download - 1.0 GB on disk". */
        data class Working(val appId: String, val message: String, val fraction: Float? = null, val sizes: String? = null) : Progress
        data class Done(val appId: String) : Progress
        data class Failed(val appId: String, val message: String, val log: List<String> = emptyList(), val title: String = "") : Progress
    }

    /** Human-readable cause for a failed install, from the tail of its log. Pure; unit-tested. */
    fun failureReason(log: List<String>): String {
        val text = log.joinToString("\n")
        return when {
            listOf("Temporary failure resolving", "Could not resolve", "Network is unreachable", "Could not connect")
                .any { it in text } -> "Install failed: no connection to the download servers — check your internet and retry"
            "No space left on device" in text -> "Install failed: not enough storage space"
            "Unable to locate package" in text || "has no installation candidate" in text ->
                "Install failed: package not available for this device"
            "checksum mismatch" in text || "sha256" in text.lowercase() && "FAILED" in text ->
                "Install failed: download was corrupted — retry"
            "Could not get lock" in text -> "Install failed: another install is running — retry in a moment"
            else -> "Install failed"
        }
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress = _progress.asStateFlow()

    @Volatile private var current: kotlinx.coroutines.Job? = null
    @Volatile private var currentId: String? = null

    /** Stop the running install (apt/dpkg are killed; the next install repairs dpkg state). */
    fun cancel() { current?.cancel() }

    /** Clear a shown failure. */
    fun dismissFailure() { if (_progress.value is Progress.Failed) _progress.value = Progress.Idle }

    suspend fun install(context: Context, app: StoreApp, waylandHostDir: String?): Boolean =
        withContext(Dispatchers.IO) {
            current = coroutineContext[kotlinx.coroutines.Job]; currentId = app.id
            try {
                _progress.value = Progress.Working(app.id, "Installing packages…")
                GuestConfig.refreshDns(context)
                GuestScripts.install(context, LinuxRuntime.rootfsDir(context))
                LinuxRuntime.run(context, "dpkg --configure -a 2>&1", timeoutMs = 600_000, waylandHostDir = waylandHostDir)
                val payload = app.payloadFor(dev.hatch.guest.RootfsCatalog.lxcArch())
                val cmd = GuestScripts.installCommand(
                    packages = app.packages,
                    wine = app.wine,
                    payloadUrl = payload?.url,
                    payloadSha = payload?.sha256,
                    payloadDest = payload?.extractTo,
                    desktopId = app.desktopId,
                    repo = app.repo,
                    flatpak = app.flatpak,
                )
                val tail = ArrayDeque<String>()
                val apt = AptProgress()
                val res = LinuxRuntime.run(
                    context, cmd, timeoutMs = 1_800_000, waylandHostDir = waylandHostDir,
                    onLine = { line ->
                        val t = line.trim()
                        val status = t.startsWith("dlstatus:") || t.startsWith("pmstatus:")
                        if (!status) {
                            Log.i(TAG, line)
                            synchronized(tail) { if (line.isNotBlank()) { tail.addLast(line.trimEnd()); if (tail.size > 60) tail.removeFirst() } }
                        }
                        if (apt.feed(t))
                            _progress.value = Progress.Working(app.id, apt.headline(), apt.fraction.takeIf { it > 0f }, apt.sizes())
                    },
                )
                if (res?.ok != true) {
                    val log = synchronized(tail) { tail.toList() }
                    _progress.value = Progress.Failed(app.id, failureReason(log), log)
                    return@withContext false
                }
                _progress.value = Progress.Working(app.id, "Creating launcher…")
                val stub = dev.hatch.launcher.Launchers.stubsEnabled(context)
                if (stub) {
                    // Advanced, opt-in: a real per-app APK (Play Protect scans it; needs "Install unknown apps").
                    val apk = StubInstaller.buildAndStage(context, app.id, app.name, AppIcons.png(context, app))
                    StubInstaller.install(context, apk)
                } else {
                    // Default: a home-screen shortcut, pinned by the UI while Hatch is in front (no APK, no scan).
                    dev.hatch.launcher.Launchers.reportUsed(context, app.id, app.name, AppIcons.png(context, app))
                    dev.hatch.launcher.Launchers.queuePin(context, app.id)
                }
                InstalledApps.put(
                    context,
                    InstalledRecord(
                        id = app.id,
                        name = app.name,
                        source = app.source,
                        stubPackage = if (stub) StubInstaller.stubPackageFor(app.id) else "",
                        command = app.command,
                        wine = app.wine,
                        x11 = app.x11,
                    ),
                )
                _progress.value = Progress.Done(app.id)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "install of ${app.id} cancelled")
                _progress.value = Progress.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                _progress.value = Progress.Failed(app.id, e.message ?: "install failed")
                false
            }
        }

    /** apt-get update + `apt list --upgradable`, mapped to installed catalogue apps. */
    suspend fun checkUpdates(context: Context, apps: List<StoreApp>): List<String> = withContext(Dispatchers.IO) {
        GuestConfig.refreshDns(context)
        val r = LinuxRuntime.run(context, "apt-get update -qq >/dev/null 2>&1; apt list --upgradable 2>/dev/null", timeoutMs = 300_000)
            ?: return@withContext emptyList()
        val upgradable = upgradablePackages(r.stdout)
        val installed = InstalledApps.list(context).map { it.id }.toSet()
        apps.filter { it.id in installed && it.packages.any { p -> p in upgradable } }.map { it.id }
    }

    /** Pure: package names from `apt list --upgradable` ("mousepad/stable 0.6.3-2 arm64 [upgradable from: 0.6.3-1]"). */
    fun upgradablePackages(out: String): Set<String> =
        out.lineSequence().filter { "/" in it && "upgradable" in it }.map { it.substringBefore('/') }.toSet()

    /** Size of the Linux system (rootfs) on disk, e.g. "2.4 GB". */
    suspend fun storageUsed(context: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("du", "-sk", LinuxRuntime.rootfsDir(context).absolutePath).redirectErrorStream(true).start()
            val kb = p.inputStream.bufferedReader().readText().trim().substringBefore('\t').substringBefore(' ').toLong()
            AptProgress.human(kb * 1024)
        }.getOrDefault("")
    }

    /** Re-run install (apt refresh + stub rebuild). */
    suspend fun update(context: Context, app: StoreApp, waylandHostDir: String?): Boolean =
        install(context, app, waylandHostDir)

    /** Legacy per-app stub APKs (v0.1.0) waiting for the UI to ask Android to remove them (ACTION_DELETE). */
    private val _legacyStubs = MutableStateFlow<List<String>>(emptyList())
    val legacyStubs = _legacyStubs.asStateFlow()
    fun legacyStubHandled(pkg: String) { _legacyStubs.value = _legacyStubs.value - pkg }

    private fun installedStoreApps(context: Context): List<StoreApp> {
        val ids = InstalledApps.list(context).map { it.id }.toSet()
        return Catalogue.load(context).filter { it.id in ids }
    }

    /** Space uninstalling [app] frees: apt's own simulation + Flatpak / payload directory sizes. Null if unknown. */
    suspend fun estimateUninstall(context: Context, app: StoreApp): Long? = withContext(Dispatchers.IO) {
        var total = 0L; var known = false
        val targets = Uninstall.aptTargets(app, installedStoreApps(context))
        if (targets.isNotEmpty()) {
            val r = LinuxRuntime.run(context, "apt-get -s purge --autoremove ${targets.joinToString(" ") { dev.hatch.guest.sq(it) }} 2>&1", timeoutMs = 120_000)
            Uninstall.parseAptFreed(r?.stdout.orEmpty())?.let { total += it; known = true }
        }
        val dirs = Uninstall.payloadDirs(app) + listOfNotNull(app.flatpak.takeIf { it.isNotEmpty() }?.let { "/var/lib/flatpak/app/$it" })
        if (dirs.isNotEmpty()) {
            val r = LinuxRuntime.run(context, "du -sk ${dirs.joinToString(" ") { dev.hatch.guest.sq(it) }} 2>/dev/null", timeoutMs = 120_000)
            r?.stdout?.lineSequence()?.mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() }?.forEach { total += it * 1024; known = true }
        }
        if (known) total else null
    }

    /**
     * Uninstall: stop the app, remove it from the guest (apt purge --autoremove of only what nothing else needs,
     * flatpak uninstall, payload delete), drop its shortcuts. A legacy stub APK is handed to the UI to remove with
     * Android's own confirmation; it can never fail the guest uninstall.
     */
    suspend fun uninstall(context: Context, app: StoreApp): Boolean = withContext(Dispatchers.IO) {
        val tail = ArrayDeque<String>()
        val onLine: (String) -> Unit = { l -> Log.i(TAG, l); synchronized(tail) { if (l.isNotBlank()) { tail.addLast(l.trimEnd()); if (tail.size > 60) tail.removeFirst() } } }
        try {
            _progress.value = Progress.Working(app.id, "Removing ${app.name}…")
            runCatching { dev.hatch.guest.AppSession.stop(app.id) }
            val rec = InstalledApps.get(context, app.id)
            val targets = Uninstall.aptTargets(app, installedStoreApps(context))
            var ok = true
            if (app.flatpak.isNotEmpty()) {
                ok = LinuxRuntime.run(context, "${GuestScripts.BIN}/hatch-flatpak uninstall ${dev.hatch.guest.sq(app.flatpak)} 2>&1", timeoutMs = 600_000, onLine = onLine)?.ok == true
            }
            if (ok && targets.isNotEmpty()) {
                LinuxRuntime.run(context, "dpkg --configure -a 2>&1", timeoutMs = 600_000, onLine = onLine)
                ok = LinuxRuntime.run(context, "DEBIAN_FRONTEND=noninteractive apt-get purge -y --autoremove ${targets.joinToString(" ") { dev.hatch.guest.sq(it) }} 2>&1",
                    timeoutMs = 600_000, onLine = onLine)?.ok == true
            }
            val dirs = Uninstall.payloadDirs(app)
            if (ok && dirs.isNotEmpty()) {
                ok = LinuxRuntime.run(context, "rm -rf ${dirs.joinToString(" ") { dev.hatch.guest.sq(it) }} 2>&1", timeoutMs = 300_000, onLine = onLine)?.ok == true
            }
            if (!ok) {
                val log = synchronized(tail) { tail.toList() }
                _progress.value = Progress.Failed(app.id, Uninstall.failureReason(log), log, "Couldn't uninstall ${app.name}")
                return@withContext false
            }
            InstalledApps.remove(context, app.id)
            runCatching { dev.hatch.launcher.Launchers.remove(context, app.id) }
            val stub = rec?.stubPackage.orEmpty()
            if (stub.isNotEmpty() && runCatching { context.packageManager.getPackageInfo(stub, 0) }.isSuccess)
                _legacyStubs.value = _legacyStubs.value + stub
            _progress.value = Progress.Done(app.id)
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "uninstall failed", e)
            val log = synchronized(tail) { tail.toList() } + listOf("${e.javaClass.simpleName}: ${e.message}")
            _progress.value = Progress.Failed(app.id, Uninstall.failureReason(log), log, "Couldn't uninstall ${app.name}")
            false
        }
    }
}
