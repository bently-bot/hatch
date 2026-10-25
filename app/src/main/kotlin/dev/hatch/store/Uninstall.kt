package dev.hatch.store

/** Packages in Hatch's prebuilt rootfs (rootfs/base-packages.txt): shared by every app, never purged on uninstall. */
object BasePackages {
    val LIST: Set<String> = setOf(
        "ca-certificates", "curl", "xz-utils", "zstd", "unzip", "lsof", "procps", "dbus",
        "libwayland-client0", "libwayland-cursor0", "libwayland-egl1", "libdecor-0-0", "libxkbcommon0", "fontconfig", "fonts-dejavu-core",
        "xwayland", "xfonts-base", "matchbox-window-manager", "xterm",
        "libegl1", "libgl1", "libgl1-mesa-dri", "libegl-mesa0", "libgles2", "libvulkan1", "vulkan-tools", "libdrm2", "libx11-xcb1",
        "libxcb-dri3-0", "libxcb-present0", "libxshmfence1", "libzstd1",
        "pulseaudio", "pulseaudio-utils", "libasound2-plugins",
    )
}

/** Pure helpers for uninstalling a guest app (unit-tested). */
object Uninstall {
    /** Wine runtime shared by every Windows app. */
    private val WINE = setOf("wine", "wine64", "libwine", "fonts-wine")

    /**
     * Debian packages to purge for [app]: its own packages minus anything another installed app also lists
     * (e.g. Blender lists xwayland), the base system, and Wine while another Windows app remains. The rest of its
     * dependencies go with `--autoremove` (apt only removes auto-installed packages nothing else needs).
     */
    fun aptTargets(app: StoreApp, otherInstalled: List<StoreApp>, base: Set<String> = BasePackages.LIST): List<String> {
        if (app.flatpak.isNotEmpty()) return emptyList()
        val shared = otherInstalled.filter { it.id != app.id }.flatMap { it.packages }.toSet()
        val wineShared = otherInstalled.any { it.id != app.id && it.wine }
        return app.packages.filter { it !in shared && it !in base && !(wineShared && it in WINE) }.distinct()
    }

    /** Payload directories (AppImage / Windows release extracts) to delete for [app]. */
    fun payloadDirs(app: StoreApp): List<String> =
        (listOfNotNull(app.windowsPayload) + app.payloads.values).map { it.extractTo }.filter { it.startsWith("/opt/hatch/") }.distinct()

    private val FREED = Regex("""After this operation, ([\d.,]+) ([kKMG]?B) disk space will be freed""")

    /** Bytes `apt-get -s purge --autoremove` says it would free; null if apt didn't say. */
    fun parseAptFreed(out: String): Long? {
        val m = FREED.find(out) ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val mul = when (m.groupValues[2].uppercase()) { "KB" -> 1e3; "MB" -> 1e6; "GB" -> 1e9; else -> 1.0 }
        return (n * mul).toLong()
    }

    fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    /** Friendly message for a failed uninstall (never a raw exception). */
    fun failureReason(log: List<String>): String {
        val t = log.joinToString("\n")
        return when {
            "Could not get lock" in t -> "Another install or update is running. Try again in a moment."
            "No space left" in t -> "Your phone is out of storage space."
            else -> "Something went wrong while removing it. Tap Details for the log, or try again."
        }
    }
}
