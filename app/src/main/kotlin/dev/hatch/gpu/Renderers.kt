package dev.hatch.gpu

import android.content.Context

/**
 * Per-app renderer ladder: GPU first, software last (Bently's policy: always GPU when it works).
 *
 * Why Chromium apps need their own ladder: Hatch's compositor is wl_shm only (no zwp_linux_dmabuf / wl_drm). On a
 * real Adreno, Chromium's Ozone-Wayland GPU process wants GBM/dmabuf presentation, so VS Code, Cursor, Brave
 * (Electron/Chromium) and Steam's CEF web helper either lose their GPU process or present solid black frames.
 * Blender works because X11 GL reaches Zink through Xwayland via drisw/kopper (software present, GPU render), so the
 * first rung puts Chromium on that same proven path.
 *
 * A rung "worked" once its window showed real pixels (WaylandServer black-frame check); that rung is remembered.
 * If a window stays black / the app dies before drawing, the launcher moves to the next rung automatically.
 */
object Renderers {
    enum class Kind { CHROMIUM, STEAM, GENERIC }

    data class Config(
        val id: String,
        val label: String,
        val gpu: Boolean,
        /** null = the app's own setting; true/false = force X11 (rootful Xwayland) or native Wayland. */
        val x11: Boolean? = null,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
    )

    // Chromium under proot: no setuid/namespace sandbox, no shared /dev/shm guarantee.
    private val CHROMIUM_BASE = listOf("--no-sandbox", "--disable-gpu-sandbox", "--disable-dev-shm-usage")

    fun kind(command: List<String>): Kind {
        val c = command.joinToString(" ").lowercase()
        return when {
            "hatch-steam" in c -> Kind.STEAM
            listOf("/usr/share/code/", "/usr/share/cursor/", "brave", "chromium", "chrome", "electron", "--no-sandbox",
                "obsidian", "discord", "signal-desktop", "slack", "teams", "spotify", "vivaldi", "microsoft-edge")
                .any { it in c } -> Kind.CHROMIUM
            else -> Kind.GENERIC
        }
    }

    /** Pure: the ordered rungs for an app. [gpuAvailable] = this device has a working GPU path (Gpu.useGpu). */
    fun ladder(kind: Kind, gpuAvailable: Boolean): List<Config> {
        val sw = when (kind) {
            Kind.CHROMIUM -> Config("software", "Software (llvmpipe)", gpu = false, x11 = false,
                args = CHROMIUM_BASE + listOf("--ozone-platform=wayland", "--disable-gpu", "--disable-gpu-compositing"),
                env = mapOf("ELECTRON_OZONE_PLATFORM_HINT" to "wayland"))
            Kind.STEAM -> Config("software", "Software (llvmpipe)", gpu = false, args = listOf("-cef-disable-gpu", "-cef-disable-gpu-compositing"))
            Kind.GENERIC -> Config("software", "Software (llvmpipe)", gpu = false)
        }
        if (!gpuAvailable) return listOf(sw)
        return when (kind) {
            Kind.CHROMIUM -> listOf(
                Config("gpu-x11", "GPU (Zink, X11 + ANGLE/GL)", gpu = true, x11 = true,
                    args = CHROMIUM_BASE + listOf("--ozone-platform=x11", "--use-gl=angle", "--use-angle=gl", "--ignore-gpu-blocklist"),
                    env = mapOf("ELECTRON_OZONE_PLATFORM_HINT" to "x11", "XDG_SESSION_TYPE" to "x11")),
                Config("gpu-wayland", "GPU (Turnip, Wayland + ANGLE/Vulkan)", gpu = true, x11 = false,
                    args = CHROMIUM_BASE + listOf("--ozone-platform=wayland", "--use-gl=angle", "--use-angle=vulkan",
                        "--enable-features=Vulkan,VulkanFromANGLE,DefaultANGLEVulkan", "--ignore-gpu-blocklist"),
                    env = mapOf("ELECTRON_OZONE_PLATFORM_HINT" to "wayland")),
                Config("gpu-raster", "GPU raster, software compositing (Wayland)", gpu = true, x11 = false,
                    args = CHROMIUM_BASE + listOf("--ozone-platform=wayland", "--use-gl=angle", "--use-angle=gl",
                        "--disable-gpu-compositing", "--ignore-gpu-blocklist"),
                    env = mapOf("ELECTRON_OZONE_PLATFORM_HINT" to "wayland")),
                sw,
            )
            Kind.STEAM -> listOf(
                Config("gpu", "GPU (Zink, CEF GPU compositing)", gpu = true),
                Config("gpu-nocomp", "GPU, CEF software compositing", gpu = true, args = listOf("-cef-disable-gpu-compositing")),
                sw,
            )
            Kind.GENERIC -> listOf(Config("gpu", "GPU (Turnip + Zink)", gpu = true), sw)
        }
    }

    /** Seconds a window may stay black before the next rung is tried. */
    fun blackTimeoutSec(kind: Kind) = when (kind) { Kind.STEAM -> 60; Kind.CHROMIUM -> 25; Kind.GENERIC -> 20 }

    // ---- per-app state (prefs "renderers") ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("renderers", Context.MODE_PRIVATE)

    /** The rung to launch with: the user's software override, else the remembered/next rung. */
    fun current(ctx: Context, appId: String, ladder: List<Config>): Config {
        if (softwareForced(ctx, appId)) return ladder.last()
        val id = prefs(ctx).getString("rung_$appId", null)
        return ladder.firstOrNull { it.id == id } ?: ladder.first()
    }
    fun worked(ctx: Context, appId: String) = prefs(ctx).getString("worked_$appId", null)
    fun markWorked(ctx: Context, appId: String, cfg: Config) {
        if (worked(ctx, appId) != cfg.id) prefs(ctx).edit().putString("worked_$appId", cfg.id).putString("rung_$appId", cfg.id).apply()
    }
    /** Move to the next rung; null when already on the last (software) one. */
    fun advance(ctx: Context, appId: String, ladder: List<Config>, from: Config): Config? {
        val i = ladder.indexOfFirst { it.id == from.id }
        val next = ladder.getOrNull(i + 1) ?: return null
        prefs(ctx).edit().putString("rung_$appId", next.id).remove("worked_$appId").apply()
        return next
    }
    fun softwareForced(ctx: Context, appId: String) = prefs(ctx).getBoolean("sw_$appId", false)
    fun setSoftwareForced(ctx: Context, appId: String, on: Boolean) {
        val e = prefs(ctx).edit().putBoolean("sw_$appId", on)
        if (!on) e.remove("rung_$appId").remove("worked_$appId")   // back to GPU-first
        e.apply()
    }
    fun forget(ctx: Context, appId: String) =
        prefs(ctx).edit().remove("rung_$appId").remove("worked_$appId").remove("sw_$appId").apply()

    /** Active rung per running app (for the window menu / info). */
    val active = java.util.concurrent.ConcurrentHashMap<String, Config>()
}
