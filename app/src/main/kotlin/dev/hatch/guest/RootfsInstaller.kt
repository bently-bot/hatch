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
import dev.hatch.rootfs.TarExtractor
import dev.hatch.rootfs.Wipe

/**
 * First-run Debian setup.
 * Fast path: Hatch's prebuilt rootfs (RootfsCatalog.prebaked) streamed in one pass: download → sha256 → zstd →
 * TarExtractor (symlinks, link2symlink hard links, modes) — no temp archive, no proot tar, no apt.
 * Fallback (no prebuilt image for this arch, or it fails): linuxcontainers/Docker Hub tarball → proot tar → apt.
 */
object RootfsInstaller {
    private const val TAG = "HatchSetup"

    val FAST_STEPS = listOf("Download Linux", "Set up your desktop")
    val LEGACY_STEPS = listOf("Download Linux", "Unpack", "Set up your desktop", "Install the basics")

    sealed interface Stage {
        data object Idle : Stage
        /** [step] indexes [steps]; [bytes]/[total] are real byte counts when known; [bytesPerSec] for the ETA. */
        data class Running(
            val step: Int, val steps: List<String>, val title: String,
            val bytes: Long = 0, val total: Long = 0, val bytesPerSec: Long = 0,
        ) : Stage {
            val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
            val etaSeconds: Long? get() = if (total > 0 && bytesPerSec > 0) (total - bytes) / bytesPerSec else null
        }
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
        android.util.Log.i(TAG, t)
        if (t.startsWith("(Reading database")) return
        _log.value = (_log.value + t).takeLast(300)
    }

    /** Back to the first-run state (after Settings → Remove Linux). */
    fun reset() { job?.cancel(); _stage.value = Stage.Idle; _log.value = emptyList() }

    fun start(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        job = scope.launch { install(app) }
    }

    /** Download + installed size for the pre-start screen (prebuilt image if there is one). */
    fun estimate(): Pair<Long, Long> = RootfsCatalog.prebaked()?.let { it.size to it.installedSize } ?: (130_000_000L to 1_100_000_000L)

    private fun elapsed(t0: Long) = "%.1f".format((System.nanoTime() - t0) / 1e9)

    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            if (!LinuxRuntime.isInstalled(context)) {
                val pre = RootfsCatalog.prebaked()
                var fast = false
                if (pre != null && !RootfsCatalog.forceLegacy(context)) {
                    fast = runCatching { installPrebaked(context, pre) }.getOrElse { e ->
                        logLine("Prebuilt Debian failed (${e.javaClass.simpleName}: ${e.message}); using the standard download")
                        false
                    }
                    if (!fast) Wipe.deleteTree(LinuxRuntime.rootfsDir(context))
                }
                if (fast) {
                    logLine("setup timing: prebuilt download+extract ${elapsed(t0)}s")
                    _stage.value = Stage.Running(1, FAST_STEPS, "Setting up your desktop")
                    GuestConfig.apply(context)
                    File(LinuxRuntime.rootfsDir(context), "run/hatch").mkdirs()
                    LinuxRuntime.markInstalled(context)
                    logLine("setup timing: total ${elapsed(t0)}s (prebuilt)")
                } else if (!installLegacy(context, t0)) return@withContext false
            }
            LinuxRuntime.notifyInstallChanged()
            logLine("Runtime ready")
            _stage.value = Stage.Done
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            fail("Setup stopped: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** One pass: HTTPS → sha256 → zstd → files. Verifies the hash at the end of the stream; false/throws on any problem. */
    private fun installPrebaked(context: Context, img: RootfsCatalog.Prebaked): Boolean {
        val root = LinuxRuntime.rootfsDir(context)
        Wipe.deleteTree(root); root.mkdirs()
        logLine("Downloading prebuilt Debian ${RootfsCatalog.DEBIAN_RELEASE} (${img.size / 1_000_000} MB) from ${img.url}")
        val (body, len) = openStream(img.url)
        val total = if (len > 0) len else img.size
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val start = System.nanoTime(); var got = 0L; var lastUi = 0L
        val counting = object : java.io.FilterInputStream(java.security.DigestInputStream(body.buffered(1 shl 20), md)) {
            override fun read(b: ByteArray, off: Int, n: Int): Int = super.read(b, off, n).also { r ->
                if (r > 0) {
                    got += r
                    val now = System.nanoTime()
                    if (now - lastUi > 250_000_000L) {
                        lastUi = now
                        val rate = (got * 1e9 / (now - start)).toLong()
                        _stage.value = Stage.Running(0, FAST_STEPS, "Downloading Linux", got, total, rate)
                    }
                }
            }
        }
        val stats = counting.use { c ->
            com.github.luben.zstd.ZstdInputStream(c).use { z ->
                val st = TarExtractor(root, LinuxRuntime.l2sDir(context), AndroidFs).extract(z.buffered(1 shl 20))
                val sink = ByteArray(1 shl 16); while (z.read(sink) >= 0) { }
                while (c.read(sink) >= 0) { }
                st
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(img.sha256, ignoreCase = true)) { logLine("Checksum mismatch: got $sha"); return false }
        logLine("Unpacked ${stats.files} files, ${stats.symlinks} links, ${stats.hardlinks} hard links (${stats.bytes / 1_000_000} MB), checksum OK")
        return LinuxRuntime.rootfsLooksComplete(context)
    }

    /** Android: chmod/symlink via libcore (no java.nio attribute views needed). */
    private object AndroidFs : TarExtractor.Fs {
        override fun chmod(f: File, mode: Int) = android.system.Os.chmod(f.path, mode)
        override fun symlink(target: String, link: File) = android.system.Os.symlink(target, link.path)
        override fun readlink(f: File): String? = try { android.system.Os.readlink(f.path) } catch (_: android.system.ErrnoException) { null }
    }

    private suspend fun installLegacy(context: Context, t0: Long): Boolean {
        val xz = File(context.noBackupFilesDir, RootfsCatalog.ROOTFS_NAME)
        if (!xz.exists()) {
            _stage.value = Stage.Running(0, LEGACY_STEPS, "Downloading Linux")
            val rf = RootfsCatalog.resolve()
            logLine("Downloading Debian ${RootfsCatalog.DEBIAN_CODENAME} ${RootfsCatalog.lxcArch()} rootfs (${rf.build})")
            val start = System.nanoTime()
            download(rf.url, xz, rf.sha256) { got, total ->
                _stage.value = Stage.Running(0, LEGACY_STEPS, "Downloading Linux", got, total, (got * 1e9 / (System.nanoTime() - start + 1)).toLong())
            }
        }
        logLine("setup timing: download ${elapsed(t0)}s")
        _stage.value = Stage.Running(1, LEGACY_STEPS, "Unpacking")
        logLine("Decompressing rootfs → tar")
        val tar = File(context.noBackupFilesDir, "debian-rootfs.tar")
        if (!tar.exists() || tar.length() < 1_000_000) decompressXz(xz, tar)
        logLine("Unpacking through proot + /system/bin/tar")
        if (!extract(context, tar)) return fail("Couldn't unpack Linux. Free up some space and try again.")
        runCatching { tar.delete() }; runCatching { xz.delete() }
        logLine("setup timing: unpacked ${elapsed(t0)}s")
        _stage.value = Stage.Running(2, LEGACY_STEPS, "Setting up your desktop")
        GuestConfig.apply(context)
        logLine("Configured apt, DNS, dpkg")
        File(LinuxRuntime.rootfsDir(context), "run/hatch").mkdirs()
        val upd = LinuxRuntime.run(context, "apt-get update 2>&1", timeoutMs = 300_000, onLine = ::logLine)
        if (upd == null || !upd.ok || upd.stdout.contains("Temporary failure resolving") || upd.stdout.contains("Could not resolve"))
            return fail("Couldn't reach the Debian servers. Check your internet connection and try again.")
        _stage.value = Stage.Running(3, LEGACY_STEPS, "Installing the basics")
        val base = Apt.install(
            context,
            listOf("ca-certificates", "curl", "xz-utils", "libwayland-client0", "libwayland-cursor0", "libxkbcommon0", "fontconfig", "fonts-dejavu-core"),
            ::logLine,
        )
        if (!base) return fail("Installing the basics didn't finish. Try again.")
        LinuxRuntime.markInstalled(context)
        logLine("setup timing: total ${elapsed(t0)}s (standard)")
        return true
    }

    private fun fail(msg: String): Boolean { _stage.value = Stage.Failed(msg); return false }

    private fun decompressXz(xz: File, tar: File) {
        tar.parentFile?.mkdirs()
        val part = File(tar.path + ".part")
        BufferedInputStream(FileInputStream(xz)).use { raw ->
            raw.mark(4); val gz = raw.read() == 0x1f && raw.read() == 0x8b; raw.reset()
            (if (gz) java.util.zip.GZIPInputStream(raw, 64 * 1024) else XZInputStream(raw)).use { inn ->
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
