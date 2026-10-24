package dev.hatch.store

import android.content.Context
import android.util.Log
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
        data class Failed(val appId: String, val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress = _progress.asStateFlow()

    suspend fun install(context: Context, app: StoreApp, waylandHostDir: String?): Boolean =
        withContext(Dispatchers.IO) {
            try {
                _progress.value = Progress.Working(app.id, "Installing packages…")
                if (!Apt.install(context, app.packages, { Log.i(TAG, it) }, waylandHostDir)) {
                    _progress.value = Progress.Failed(app.id, "apt install failed")
                    return@withContext false
                }
                if (app.wine) {
                    _progress.value = Progress.Working(app.id, "Configuring Wine…")
                    GuestConfig.configureWineWayland(context, waylandHostDir)
                    val payload = app.windowsPayload
                    if (payload != null && payload.kind == "zip") {
                        _progress.value = Progress.Working(app.id, "Downloading ${app.name}…")
                        val zip = File(context.noBackupFilesDir, "${app.id}.zip")
                        if (payload.sha256.isNotEmpty()) {
                            download(payload.url, zip, payload.sha256)
                        } else {
                            download(payload.url, zip, sha256HexAllowEmpty(zip, payload.url))
                        }
                        val guestZip = File(LinuxRuntime.guestTmpDir(context), "${app.id}.zip")
                        zip.copyTo(guestZip, overwrite = true)
                        _progress.value = Progress.Working(app.id, "Extracting…")
                        LinuxRuntime.run(
                            context,
                            "mkdir -p ${payload.extractTo} && unzip -o /tmp/${app.id}.zip -d ${payload.extractTo} && rm -f /tmp/${app.id}.zip",
                            timeoutMs = 300_000,
                            waylandHostDir = waylandHostDir,
                        )
                    }
                }
                _progress.value = Progress.Working(app.id, "Creating launcher…")
                val apk = StubInstaller.buildAndStage(context, app.id, app.name)
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

    /** When sha is empty, download without verify (used only as last resort). */
    private fun sha256HexAllowEmpty(dest: File, url: String): String {
        // download() requires sha — compute after fetch by downloading to temp without check
        // Simpler: fetch into dest ignoring verify by using a two-phase helper
        throw IllegalStateException("sha256 required for $url")
    }
}
