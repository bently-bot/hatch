package dev.hatch.guest

import android.content.Context
import android.util.Log
import dev.hatch.wayland.Native
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun sq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * Debian-in-app via bundled PRoot (jniLibs). Remade from the Kern approach (reference only):
 * libproot.so in nativeLibraryDir + loader stubs + -0 -l + L2S self-bind.
 */
object LinuxRuntime {
    private const val TAG = "HatchRuntime"
    private val commandCounter = AtomicLong(0)
    private val _installChanges = MutableStateFlow(0)
    val installChanges = _installChanges.asStateFlow()
    fun notifyInstallChanged() { _installChanges.value++ }

    fun rootfsDir(context: Context): File = File(context.filesDir, "linux")
    fun prootTmpDir(context: Context): File = File(context.filesDir, "tmp").apply { mkdirs() }
    fun guestTmpDir(context: Context): File = File(rootfsDir(context), "tmp").apply { mkdirs() }
    private fun l2sDir(context: Context): File = File(rootfsDir(context), ".l2s").apply { mkdirs() }
    private fun nativeLibDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)
    fun prootBinary(context: Context): File = File(nativeLibDir(context), "libproot.so")
    private fun installMarker(context: Context): File = File(rootfsDir(context), ".hatch-installed")

    fun rootfsLooksComplete(context: Context): Boolean {
        val root = rootfsDir(context)
        return File(root, "etc/os-release").exists() &&
            (File(root, "bin/bash").exists() || File(root, "usr/bin/bash").exists()) &&
            (File(root, "bin/apt-get").exists() || File(root, "usr/bin/apt-get").exists()) &&
            File(root, "var/lib/dpkg/status").exists()
    }

    fun markInstalled(context: Context) {
        runCatching { installMarker(context).writeText(RootfsCatalog.DEBIAN_CODENAME + "\n") }
    }

    fun isInstalled(context: Context): Boolean =
        rootfsLooksComplete(context) && installMarker(context).exists()

    fun prootEnv(context: Context, extra: Map<String, String> = emptyMap()): Array<String> {
        val nativeLib = nativeLibDir(context)
        val base = linkedMapOf(
            "LD_LIBRARY_PATH" to nativeLib.absolutePath,
            "PROOT_LOADER" to File(nativeLib, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nativeLib, "libproot_loader32.so").absolutePath,
            "PROOT_LOADER32" to File(nativeLib, "libproot_loader32.so").absolutePath,
            "PROOT_TMP_DIR" to prootTmpDir(context).absolutePath,
            "PROOT_L2S_DIR" to l2sDir(context).absolutePath,
            "TERM" to "xterm-256color",
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games",
            "TMPDIR" to "/tmp",
            "DEBIAN_FRONTEND" to "noninteractive",
            "XDG_RUNTIME_DIR" to "/run/hatch",
            "WAYLAND_DISPLAY" to "wayland-0",
            "GDK_BACKEND" to "wayland",
            "QT_QPA_PLATFORM" to "wayland",
            "SDL_VIDEODRIVER" to "wayland",
            "WINEPREFIX" to "/root/.wine",
            // Default: LLVMpipe/softpipe (correct). Set HATCH_GPU=1 after /dev/kgsl-3d0 verified on device
            // to prefer Turnip (venus/kgsl) + Zink for GLES→Vulkan.
            "LIBGL_ALWAYS_SOFTWARE" to "1",
            "GALLIUM_DRIVER" to "llvmpipe",
            "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
        )
        base.putAll(extra)
        if (extra["HATCH_GPU"] == "1" || System.getenv("HATCH_GPU") == "1") {
            base["LIBGL_ALWAYS_SOFTWARE"] = "0"
            base.remove("GALLIUM_DRIVER")
            base["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            base["MESA_VK_DEVICE_SELECT"] = "turnip"
            base["TU_DEBUG"] = "noconform"
        }
        return base.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    fun bindCandidates(context: Context, waylandHostDir: String?): List<String> {
        val l2s = l2sDir(context).absolutePath
        return buildList {
            add("$l2s:$l2s")
            add("/proc"); add("/sys"); add("/dev")
            if (File("/dev/pts").exists()) add("/dev/pts")
            add("/proc/self/fd:/dev/fd")
            if (File("/storage").exists()) add("/storage")
            if (File("/sdcard").exists()) add("/sdcard")
            // Bind host Wayland socket dir into guest /run/hatch
            if (waylandHostDir != null) {
                val host = File(waylandHostDir)
                if (host.exists()) add("${host.absolutePath}:/run/hatch")
            }
        }.filter { spec ->
            val host = spec.substringBefore(':')
            File(host).exists()
        }
    }

    fun prootArgv(
        context: Context,
        guestCommand: List<String>,
        workingDir: String = "/root",
        waylandHostDir: String? = null,
    ): Array<String> {
        val binds = bindCandidates(context, waylandHostDir)
        return buildList {
            add(prootBinary(context).absolutePath)
            add("-0"); add("-l")
            add("-r"); add(rootfsDir(context).absolutePath)
            for (b in binds) { add("-b"); add(b) }
            add("-w"); add(workingDir)
            addAll(guestCommand)
        }.toTypedArray()
    }

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    data class Session(val pid: Int, val outFd: Int) {
        fun kill() { Native.killGroup(pid, 15); Native.killGroup(pid, 9) }
    }

    /** Long-lived guest process (GUI app). Returns pid+stdout fd. */
    fun spawn(
        context: Context,
        guestCommand: List<String>,
        workingDir: String = "/root",
        waylandHostDir: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
        usePty: Boolean = false,
    ): Session? {
        val argv = prootArgv(context, guestCommand, workingDir, waylandHostDir)
        val env = prootEnv(context, extraEnv)
        val r = Native.spawn(argv[0], argv, env, context.filesDir.absolutePath, usePty) ?: return null
        return Session(r[0], r[1])
    }

    suspend fun run(
        context: Context,
        script: String,
        timeoutMs: Long = 60_000,
        workingDir: String = "/root",
        onLine: ((String) -> Unit)? = null,
        waylandHostDir: String? = null,
    ): Result? = withContext(Dispatchers.IO) {
        if (!isInstalled(context) && !rootfsLooksComplete(context)) return@withContext null
        val id = commandCounter.incrementAndGet()
        val guestTmp = guestTmpDir(context)
        val scriptFile = File(guestTmp, "fc-$id.sh")
        val outFile = File(guestTmp, "fc-$id.out")
        val errFile = File(guestTmp, "fc-$id.err")
        val rcFile = File(guestTmp, "fc-$id.rc")
        try {
            scriptFile.writeText(script)
            val wrapper =
                "bash /tmp/fc-$id.sh < /dev/null > /tmp/fc-$id.out 2> /tmp/fc-$id.err; echo \$? > /tmp/fc-$id.rc"
            val session = spawn(
                context,
                listOf("/bin/bash", "-lc", wrapper),
                workingDir,
                waylandHostDir,
            ) ?: return@withContext null
            try {
                val exit = withTimeoutOrNull(timeoutMs) {
                    var code: Int? = null
                    var seen = 0L
                    while (code == null) {
                        if (onLine != null) seen = tail(outFile, seen, onLine)
                        code = rcFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
                        if (code != null) break
                        delay(150)
                    }
                    if (onLine != null) tail(outFile, seen, onLine)
                    code
                } ?: return@withContext null
                Result(exit, outFile.readTextSafe(), errFile.readTextSafe())
            } finally {
                session.kill()
                Native.close(session.outFd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "run failed", e); null
        } finally {
            listOf(scriptFile, outFile, errFile, rcFile).forEach { runCatching { it.delete() } }
        }
    }

    private fun File.readTextSafe(): String = takeIf { exists() }?.readText().orEmpty()

    private fun tail(file: File, from: Long, onLine: (String) -> Unit): Long {
        if (!file.exists() || file.length() <= from) return from
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val n = (file.length() - from).toInt()
                val buf = ByteArray(n)
                val read = raf.read(buf)
                if (read <= 0) return@use from
                val text = String(buf, 0, read, Charsets.UTF_8)
                val lastNl = text.lastIndexOf('\n')
                if (lastNl < 0) return@use from
                text.substring(0, lastNl).split('\n', '\r').forEach { if (it.isNotBlank()) onLine(it) }
                from + lastNl + 1
            }
        }.getOrDefault(from)
    }
}
