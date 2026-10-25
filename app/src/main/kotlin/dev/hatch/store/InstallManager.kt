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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object InstallManager {
    private const val TAG = "HatchInstall"

    sealed interface Progress {
        data object Idle : Progress
        /** [fraction] null = indeterminate; [sizes] e.g. "201 MB to download - 1.0 GB on disk". */
        data class Working(val appId: String, val message: String, val fraction: Float? = null, val sizes: String? = null) : Progress
        /** [warning]: it finished, but not cleanly (e.g. uninstall removed the app but apt left some packages). */
        data class Done(val appId: String, val warning: String? = null, val log: List<String> = emptyList()) : Progress
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

    /** One apt/dpkg user at a time: installs, uninstalls and the start-up repair. */
    private val aptLock = Mutex()

    @Volatile private var current: kotlinx.coroutines.Job? = null
    @Volatile private var currentId: String? = null

    /** Stop the running install (apt/dpkg are killed; the next install repairs dpkg state). */
    fun cancel() { current?.cancel() }

    /** Clear a shown failure. */
    fun dismissFailure() {
        val p = _progress.value
        if (p is Progress.Failed || (p is Progress.Done && p.warning != null)) _progress.value = Progress.Idle
    }

    suspend fun install(context: Context, app: StoreApp, waylandHostDir: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (aptLock.isLocked) _progress.value = Progress.Working(app.id, "Waiting for Linux to finish tidying up…")
            aptLock.withLock {
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
                    extras = app.extras,
                    id = app.id,
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
        } }

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
    fun queueLegacyStubs(pkgs: List<String>) { _legacyStubs.value = (_legacyStubs.value + pkgs).distinct() }

    private fun installedStoreApps(context: Context): List<StoreApp> {
        val ids = InstalledApps.list(context).map { it.id }.toSet()
        return Catalogue.load(context).filter { it.id in ids }
    }

    /** Space uninstalling [app] frees: hatch-uninstall-app --dry-run (its own packages apt would remove + its files). */
    suspend fun estimateUninstall(context: Context, app: StoreApp): Long? = withContext(Dispatchers.IO) {
        if (app.flatpak.isNotEmpty()) {
            val r = LinuxRuntime.run(context, "du -sk ${dev.hatch.guest.sq("/var/lib/flatpak/app/${app.flatpak}")} 2>/dev/null", timeoutMs = 120_000)
            return@withContext r?.stdout?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.times(1024)
        }
        GuestScripts.install(context, LinuxRuntime.rootfsDir(context))
        val r = LinuxRuntime.run(context, Uninstall.command(app, installedStoreApps(context), dryRun = true), timeoutMs = 180_000)
        Uninstall.parseFreedKb(r?.stdout.orEmpty())
    }

    /**
     * Repair the guest's package database if an interrupted install or an older Hatch's uninstall left it broken
     * (dpkg --configure -a, apt-get -f install, base packages put back). Quick no-op when healthy; run on store
     * start and before every uninstall. Never while an install or uninstall is running.
     */
    suspend fun repairIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!aptLock.tryLock()) return@withContext true   // an install/uninstall runs; it repairs first anyway
        try { runCatching {
            GuestScripts.install(context, LinuxRuntime.rootfsDir(context))
            val r = LinuxRuntime.run(context, "${GuestScripts.BIN}/hatch-uninstall-app --repair 2>&1", timeoutMs = 900_000,
                onLine = { Log.i(TAG, "repair: $it") })
            r?.ok == true
        }.getOrDefault(false) } finally { aptLock.unlock() }
    }

    /**
     * Uninstall: stop the app, then hatch-uninstall-app removes only what it added (its install record; the
     * catalogue list for apps installed by 0.2.0 or older), never the base system or what other apps use, through
     * apt's autoremove, plus its files (payloads, Steam's client + data). Flatpaks go through hatch-flatpak. If apt
     * fails, the app's files and launcher still go and it ends as Done with a warning, not a failure. A legacy stub
     * APK is handed to the UI to remove with Android's own confirmation; it can never fail the guest uninstall.
     */
    suspend fun uninstall(context: Context, app: StoreApp): Boolean = withContext(Dispatchers.IO) {
        if (aptLock.isLocked) _progress.value = Progress.Working(app.id, "Waiting for Linux to finish tidying up…")
        aptLock.withLock {
        val tail = ArrayDeque<String>()
        val onLine: (String) -> Unit = { l -> Log.i(TAG, l); synchronized(tail) { if (l.isNotBlank()) { tail.addLast(l.trimEnd()); if (tail.size > 80) tail.removeFirst() } } }
        try {
            _progress.value = Progress.Working(app.id, "Removing ${app.name}…")
            runCatching { dev.hatch.guest.AppSession.stop(app.id) }
            val rec = InstalledApps.get(context, app.id)
            GuestScripts.install(context, LinuxRuntime.rootfsDir(context))
            var warning: String? = null
            if (app.flatpak.isNotEmpty()) {
                val r = LinuxRuntime.run(context, "${GuestScripts.BIN}/hatch-flatpak uninstall ${dev.hatch.guest.sq(app.flatpak)} 2>&1", timeoutMs = 600_000, onLine = onLine)
                if (r == null) return@withContext failed(app, tail)
                if (!r.ok) warning = Uninstall.warningText(app.name)
            } else {
                val r = LinuxRuntime.run(context, Uninstall.command(app, installedStoreApps(context)), timeoutMs = 1_200_000, onLine = onLine)
                when (Uninstall.outcome(r?.exitCode)) {
                    Uninstall.Outcome.OK -> {}
                    Uninstall.Outcome.WARNING -> warning = Uninstall.warningText(app.name)
                    // The guest never ran (Linux missing / proot couldn't start): nothing was removed, say so.
                    Uninstall.Outcome.FAILED -> return@withContext failed(app, tail)
                }
            }
            InstalledApps.remove(context, app.id); dev.hatch.gpu.Renderers.forget(context, app.id)
            runCatching { dev.hatch.launcher.Launchers.remove(context, app.id) }
            val stub = rec?.stubPackage.orEmpty()
            if (stub.isNotEmpty() && runCatching { context.packageManager.getPackageInfo(stub, 0) }.isSuccess)
                _legacyStubs.value = _legacyStubs.value + stub
            _progress.value = Progress.Done(app.id, warning, if (warning != null) synchronized(tail) { tail.toList() } else emptyList())
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "uninstall failed", e)
            synchronized(tail) { tail.addLast("${e.javaClass.simpleName}: ${e.message}") }
            failed(app, tail)
        }
    } }

    private fun failed(app: StoreApp, tail: ArrayDeque<String>): Boolean {
        val log = synchronized(tail) { tail.toList() }
        _progress.value = Progress.Failed(app.id, Uninstall.failureReason(log), log, "Couldn't uninstall ${app.name}")
        return false
    }
}
