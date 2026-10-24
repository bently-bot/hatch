package dev.hatch.gpu

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.util.Log
import java.io.File

/**
 * GPU acceleration for guest apps on Qualcomm devices (Fold 8: Adreno via /dev/kgsl-3d0).
 *
 * Path: guest GL → Debian's Zink (gallium) → Vulkan → Turnip built with the KGSL kernel backend (bundled in the APK,
 * extracted to /opt/hatch/turnip) → /dev/kgsl-3d0. Our compositor speaks wl_shm only, so Mesa's software WSI copy
 * path is forced (MESA_VK_WSI_DEBUG=sw). "Auto" enables this only after the guest self-test (hatch-gpu-check) saw
 * Turnip enumerate the Adreno; anything else stays on llvmpipe, so a broken driver can never break an app.
 */
object Gpu {
    private const val TAG = "HatchGpu"
    const val PREF = "gpu_mode"
    enum class Mode { AUTO, SOFTWARE }

    const val TURNIP_DIR = "/opt/hatch/turnip"
    const val ICD = "$TURNIP_DIR/freedreno_icd.json"
    /** Written by guest hatch-gpu-check: first line "ok <deviceName>" or "fail <reason>". */
    const val CHECK_FILE = "/var/lib/hatch/gpu-check"

    data class Info(val kgsl: Boolean, val renderer: String, val turnipBundled: Boolean, val check: String)

    @Volatile private var cached: Info? = null

    fun kgslUsable(dev: File = File("/dev/kgsl-3d0")) = dev.exists() && dev.canRead() && dev.canWrite()

    fun mode(context: Context): Mode =
        if (context.getSharedPreferences("hatch", Context.MODE_PRIVATE).getString(PREF, "auto") == "software") Mode.SOFTWARE
        else Mode.AUTO

    fun setMode(context: Context, m: Mode) =
        context.getSharedPreferences("hatch", Context.MODE_PRIVATE).edit()
            .putString(PREF, if (m == Mode.SOFTWARE) "software" else "auto").apply()

    fun turnipBundled(context: Context) = runCatching { context.assets.list("gpu")?.contains("turnip.tar.xz") == true }.getOrDefault(false)

    fun info(context: Context, rootfs: File): Info = cached ?: Info(
        kgsl = kgslUsable(),
        renderer = hostRenderer(),
        turnipBundled = turnipBundled(context),
        check = File(rootfs, CHECK_FILE.removePrefix("/")).takeIf { it.exists() }?.readText()?.trim().orEmpty(),
    ).also { cached = it; Log.i(TAG, "gpu info: $it") }

    fun invalidate() { cached = null }

    /** Pure: should a launch use the GPU path? */
    fun useGpu(mode: Mode, info: Info) = mode == Mode.AUTO && info.kgsl && info.check.startsWith("ok")

    /** Pure: guest env overrides for a launch. */
    fun env(gpu: Boolean): Map<String, String> =
        if (!gpu) mapOf(
            "LIBGL_ALWAYS_SOFTWARE" to "1", "GALLIUM_DRIVER" to "llvmpipe", "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
        ) else mapOf(
            "LIBGL_ALWAYS_SOFTWARE" to "0",
            "GALLIUM_DRIVER" to "zink",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "VK_ICD_FILENAMES" to ICD,
            "VK_DRIVER_FILES" to ICD,
            "TU_DEBUG" to "noconform",
            "MESA_VK_WSI_DEBUG" to "sw",
            "HATCH_GPU" to "1",
        )

    /** Human summary for the store's settings sheet. */
    fun describe(mode: Mode, info: Info): String = when {
        mode == Mode.SOFTWARE -> "Software rendering (llvmpipe) — chosen in settings"
        !info.kgsl -> "Software rendering — no Adreno GPU access on this device (${info.renderer.ifEmpty { "unknown GPU" }})"
        !info.turnipBundled -> "Software rendering — GPU driver not included in this build"
        info.check.startsWith("ok") -> "GPU accelerated: ${info.check.removePrefix("ok").trim()} via Turnip + Zink"
        info.check.isEmpty() -> "${info.renderer}: GPU driver will be tested at next app launch"
        else -> "Software rendering — GPU self-test failed (${info.check.removePrefix("fail").trim()})"
    }

    /** Background, once: unpack the bundled Turnip into the guest and run hatch-gpu-check (Qualcomm devices only). */
    suspend fun ensureChecked(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!dev.hatch.guest.LinuxRuntime.isInstalled(context)) return@withContext
        val root = dev.hatch.guest.LinuxRuntime.rootfsDir(context)
        val i = info(context, root)
        if (!i.kgsl || !i.turnipBundled || i.check.isNotEmpty()) return@withContext
        runCatching {
            File(root, "opt/hatch").mkdirs()
            context.assets.open("gpu/turnip.tar.xz").use { inp ->
                File(root, "opt/hatch/turnip.tar.xz").outputStream().use { inp.copyTo(it) }
            }
            dev.hatch.guest.GuestScripts.install(context, root)
            val r = dev.hatch.guest.LinuxRuntime.run(context, "hatch-gpu-check 2>&1", timeoutMs = 600_000,
                onLine = { Log.i(TAG, it) })
            Log.i(TAG, "gpu-check exit ok=${r?.ok}")
        }.onFailure { Log.w(TAG, "gpu-check failed to run", it) }
        invalidate()
    }

    private fun hostRenderer(): String = runCatching {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); val v = IntArray(2)
        EGL14.eglInitialize(d, v, 0, v, 1)
        val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(d, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(d, surf, surf, ctx)
        val r = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
        EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(d, surf); EGL14.eglDestroyContext(d, ctx); EGL14.eglTerminate(d)
        r
    }.getOrDefault("")
}
