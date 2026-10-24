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
        data class Working(val appId: String, val message: String) : Progress
        data class Done(val appId: String) : Progress
        data class Failed(val appId: String, val message: String, val log: List<String> = emptyList()) : Progress
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

    suspend fun install(context: Context, app: StoreApp, waylandHostDir: String?): Boolean =
        withContext(Dispatchers.IO) {
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
                )
                val tail = ArrayDeque<String>()
                val res = LinuxRuntime.run(
                    context, cmd, timeoutMs = 1_800_000, waylandHostDir = waylandHostDir,
                    onLine = { line ->
                        Log.i(TAG, line)
                        synchronized(tail) { if (line.isNotBlank()) { tail.addLast(line.trimEnd()); if (tail.size > 60) tail.removeFirst() } }
                        val t = line.trim()
                        if (t.startsWith("hatch:") || t.startsWith("Get:") || t.startsWith("Unpacking") || t.startsWith("Setting up"))
                            _progress.value = Progress.Working(app.id, t.removePrefix("hatch: ").take(80))
                    },
                )
                if (res?.ok != true) {
                    val log = synchronized(tail) { tail.toList() }
                    _progress.value = Progress.Failed(app.id, failureReason(log), log)
                    return@withContext false
                }
                _progress.value = Progress.Working(app.id, "Creating launcher…")
                val apk = StubInstaller.buildAndStage(context, app.id, app.name, AppIcons.png(context, app))
                StubInstaller.install(context, apk)
                InstalledApps.put(
                    context,
                    InstalledRecord(
                        id = app.id,
                        name = app.name,
                        source = app.source,
                        stubPackage = StubInstaller.stubPackageFor(app.id),
                        command = app.command,
                        wine = app.wine,
                        x11 = app.x11,
                    ),
                )
                _progress.value = Progress.Done(app.id)
                true
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                _progress.value = Progress.Failed(app.id, e.message ?: "install failed")
                false
            }
        }

    /** Re-run install (apt refresh + stub rebuild). */
    suspend fun update(context: Context, app: StoreApp, waylandHostDir: String?): Boolean =
        install(context, app, waylandHostDir)

    suspend fun uninstall(context: Context, app: StoreApp): Boolean = withContext(Dispatchers.IO) {
        try {
            _progress.value = Progress.Working(app.id, "Removing…")
            val rec = InstalledApps.get(context, app.id)
            if (rec != null) StubInstaller.uninstall(context, rec.stubPackage)
            if (app.packages.isNotEmpty() && !app.wine) {
                Apt.remove(context, app.packages)
            }
            InstalledApps.remove(context, app.id)
            _progress.value = Progress.Done(app.id)
            true
        } catch (e: Exception) {
            _progress.value = Progress.Failed(app.id, e.message ?: "uninstall failed")
            false
        }
    }

}
