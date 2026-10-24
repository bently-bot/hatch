package dev.hatch.guest

import android.content.Context
import android.util.Log
import dev.hatch.wayland.Native
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream

/**
 * First-run: download Debian 13 arm64 rootfs, unpack through proot+toybox tar, configure apt.
 */
object RootfsInstaller {
    private const val TAG = "HatchSetup"

    sealed interface Stage {
        data object Idle : Stage
        data class Downloading(val what: String, val bytes: Long, val total: Long) : Stage {
            val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
        }
        data class Working(val what: String) : Stage
        data object Done : Stage
        data class Failed(val message: String) : Stage
    }

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage = _stage.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    private fun logLine(line: String) {
        val t = line.trim(); if (t.isEmpty()) return
        if (t.startsWith("(Reading database")) return
        _log.value = (_log.value + t).takeLast(300)
    }

    fun start(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        job = scope.launch { install(app) }
    }

    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!LinuxRuntime.isInstalled(context)) {
                val xz = File(context.noBackupFilesDir, RootfsCatalog.ROOTFS_NAME)
                if (!xz.exists()) {
                    _stage.value = Stage.Downloading("Debian ${RootfsCatalog.DEBIAN_RELEASE}", 0, 0)
                    logLine("Downloading Debian ${RootfsCatalog.DEBIAN_CODENAME} arm64 rootfs")
                    download(RootfsCatalog.ROOTFS_URL, xz, RootfsCatalog.ROOTFS_SHA256) { got, total ->
                        _stage.value = Stage.Downloading("Debian ${RootfsCatalog.DEBIAN_RELEASE}", got, total)
                    }
                }
                _stage.value = Stage.Working("Decompressing")
                logLine("Decompressing xz → tar")
                val tar = File(context.noBackupFilesDir, "debian-rootfs.tar")
                if (!tar.exists() || tar.length() < 1_000_000) decompressXz(xz, tar)
                _stage.value = Stage.Working("Unpacking")
                logLine("Unpacking through proot + /system/bin/tar")
                if (!extract(context, tar)) return@withContext fail("Could not unpack rootfs")
                runCatching { tar.delete() }
            }
            _stage.value = Stage.Working("Configuring")
            GuestConfig.apply(context)
            logLine("Configured apt, DNS, dpkg")
            File(LinuxRuntime.rootfsDir(context), "run/hatch").mkdirs()
            _stage.value = Stage.Working("Updating package lists")
            LinuxRuntime.run(context, "apt-get update 2>&1", timeoutMs = 300_000, onLine = ::logLine)
            _stage.value = Stage.Working("Installing base packages")
            Apt.install(
                context,
                listOf("ca-certificates", "curl", "xz-utils", "libwayland-client0", "libwayland-cursor0", "libxkbcommon0", "fontconfig", "fonts-dejavu-core"),
                ::logLine,
            )
            LinuxRuntime.markInstalled(context)
            LinuxRuntime.notifyInstallChanged()
            logLine("Runtime ready")
            _stage.value = Stage.Done
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(msg: String): Boolean { _stage.value = Stage.Failed(msg); return false }

    private fun decompressXz(xz: File, tar: File) {
        tar.parentFile?.mkdirs()
        val part = File(tar.path + ".part")
        BufferedInputStream(FileInputStream(xz)).use { raw ->
            XZInputStream(raw).use { inn ->
                BufferedOutputStream(FileOutputStream(part)).use { out ->
                    inn.copyTo(out, 64 * 1024)
                }
            }
        }
        part.renameTo(tar)
    }

    private fun extract(context: Context, archive: File): Boolean {
        val target = LinuxRuntime.rootfsDir(context).apply { mkdirs() }
        val proot = LinuxRuntime.prootBinary(context)
        // Extract with -r / so Android's tar is visible; -l rewrites hard links.
        val argv = arrayOf(
            proot.absolutePath, "-0", "-l", "-r", "/",
            "-b", "${target.absolutePath}:${target.absolutePath}",
            "-b", "${archive.parent}:${archive.parent}",
            "-w", target.absolutePath,
            "/system/bin/tar", "-xf", archive.absolutePath, "-C", target.absolutePath,
            "--exclude=./dev", "--exclude=./dev/*",
        )
        val r = Native.spawn(argv[0], argv, LinuxRuntime.prootEnv(context), context.filesDir.absolutePath, true)
            ?: return false
        val pid = r[0]; val fd = r[1]
        try {
            // Drain pty
            val buf = ByteArray(4096)
            while (true) {
                val n = Native.readSome(fd, buf)
                if (n <= 0) break
                val s = String(buf, 0, n)
                s.lineSequence().forEach { if (it.isNotBlank()) logLine(it) }
            }
            Native.waitPid(pid, true)
        } finally {
            Native.killGroup(pid, 9)
            Native.close(fd)
        }
        if (!LinuxRuntime.rootfsLooksComplete(context)) return false
        LinuxRuntime.markInstalled(context)
        return true
    }
}
