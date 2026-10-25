package dev.hatch.store

import android.content.Context
import android.util.Log
import dev.hatch.guest.LinuxRuntime
import dev.hatch.guest.RootfsInstaller
import dev.hatch.rootfs.Wipe
import dev.hatch.runtime.RuntimeControl
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings → Storage & reset. */
object Storage {
    private const val TAG = "HatchStorage"

    data class Report(val debian: Long, val cache: Long, val apps: List<StoreApp>)

    private fun aptArchives(ctx: Context) = File(LinuxRuntime.rootfsDir(ctx), "var/cache/apt/archives")

    /** Downloaded things that are safe to delete any time. */
    private fun cacheDirs(ctx: Context) = listOf(ctx.cacheDir, ctx.noBackupFilesDir, File(ctx.filesDir, "logs"))

    suspend fun report(ctx: Context): Report = withContext(Dispatchers.IO) {
        val archives = Wipe.size(aptArchives(ctx))
        val ids = InstalledApps.list(ctx).map { it.id }.toSet()
        Report(
            debian = Wipe.size(LinuxRuntime.rootfsDir(ctx)) - archives,
            cache = archives + cacheDirs(ctx).sumOf { Wipe.size(it) },
            apps = Catalogue.load(ctx).filter { it.id in ids }.sortedBy { it.name.lowercase() },
        )
    }

    suspend fun clearCache(ctx: Context): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        aptArchives(ctx).listFiles()?.filter { it.name.endsWith(".deb") }?.forEach { freed += Wipe.deleteTree(it) }
        File(aptArchives(ctx), "partial").listFiles()?.forEach { freed += Wipe.deleteTree(it) }
        cacheDirs(ctx).forEach { d -> d.listFiles()?.forEach { freed += Wipe.deleteTree(it) } }
        Log.i(TAG, "cleared cache: $freed bytes"); freed
    }

    /** Uninstall every app (Debian stays). Returns how many were removed. */
    suspend fun removeAllApps(ctx: Context): Int {
        var n = 0
        for (app in report(ctx).apps) if (InstallManager.uninstall(ctx, app)) n++
        Log.i(TAG, "removed $n apps"); return n
    }

    /**
     * Remove Linux and all apps: stop everything (sessions, processes, overlays, the runtime service), drop shortcuts,
     * delete filesDir / caches / downloads (rootfs incl. Wine prefixes + Flatpak data, link2symlink files, read-only
     * dirs), keep only SharedPreferences, and go back to onboarding. Legacy stub APKs are handed to the UI.
     */
    suspend fun removeEverything(ctx: Context): Long = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        RuntimeControl.stopAllAndWait(app)
        val records = InstalledApps.list(app)
        val stubs = records.map { it.stubPackage }.filter { it.isNotEmpty() && runCatching { app.packageManager.getPackageInfo(it, 0) }.isSuccess }
        runCatching { dev.hatch.launcher.Launchers.removeAll(app, records.map { it.id }) }
        RootfsInstaller.reset()
        val freed = Wipe.wipeAppData(app.filesDir, listOf(app.cacheDir), app.noBackupFilesDir)
        runCatching { dev.hatch.HatchApp.instance.runtime.reopenSockets() }
        InstallManager.queueLegacyStubs(stubs)
        LinuxRuntime.notifyInstallChanged()
        Log.i(TAG, "removed Linux and all apps: $freed bytes freed; legacy stubs: $stubs")
        freed
    }
}
