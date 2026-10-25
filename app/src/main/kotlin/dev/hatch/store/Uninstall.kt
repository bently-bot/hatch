package dev.hatch.store

/** Packages in Hatch's prebuilt rootfs (rootfs/base-packages.txt): shared by every app, never purged on uninstall. */
object BasePackages {
    val LIST: Set<String> = setOf(
        "ca-certificates", "curl", "xz-utils", "zstd", "unzip", "lsof", "procps", "dbus",
        "libwayland-client0", "libwayland-cursor0", "libwayland-egl1", "libdecor-0-0", "libxkbcommon0", "fontconfig", "fonts-dejavu-core",
        "xwayland", "xfonts-base", "matchbox-window-manager", "wmctrl", "xterm",
        "libegl1", "libgl1", "libgl1-mesa-dri", "libegl-mesa0", "libgles2", "libvulkan1", "vulkan-tools", "libdrm2", "libx11-xcb1",
        "libxcb-dri3-0", "libxcb-present0", "libxshmfence1", "libzstd1",
        "pulseaudio", "pulseaudio-utils", "libasound2-plugins",
    )
}

/** Pure helpers for uninstalling a guest app (unit-tested). The work itself is guest-bin/hatch-uninstall-app. */
object Uninstall {
    /** Wine runtime shared by every Windows app (hatch-install-app --wine adds it). */
    private val WINE = listOf("wine", "wine64", "libwine", "fonts-wine")

    /**
     * App data directories outside its packages that go with it, keyed by launch command: Steam's client
     * (downloaded by hatch-steam, ~1 GB) and its library/login data. "~/" is the guest user's home.
     */
    private val DATA_DIRS: Map<String, List<String>> = mapOf(
        "hatch-steam" to listOf("~/.local/share/Steam", "~/.steam"),
    )

    /**
     * Packages the guest must not remove for [app]: everything the other installed apps list (two apps can list the
     * same package without one depending on the other), plus Wine while another Windows app remains. The base system
     * and Essential/required/important packages are protected by hatch-uninstall-app itself.
     */
    fun keepPackages(app: StoreApp, otherInstalled: List<StoreApp>): List<String> {
        val others = otherInstalled.filter { it.id != app.id }
        return (others.flatMap { it.packages } + (if (others.any { it.wine }) WINE else emptyList())).distinct().sorted()
    }

    /**
     * What the app may have added, for apps installed before install records existed (Hatch 0.2.0 or older):
     * its catalogue packages (+ Wine for a Windows app). The guest only ever removes those of them that aren't base
     * packages and that apt's autoremove says nothing else needs.
     */
    fun fallbackPackages(app: StoreApp): List<String> =
        if (app.flatpak.isNotEmpty()) emptyList() else (app.packages + (if (app.wine) WINE else emptyList())).distinct()

    /** Payload directories (AppImage / Windows release extracts) to delete for [app]. */
    fun payloadDirs(app: StoreApp): List<String> =
        ((listOfNotNull(app.windowsPayload) + app.payloads.values).map { it.extractTo } + app.extras.map { it.dest } +
            listOfNotNull(app.import?.dest)).filter { it.startsWith("/opt/hatch/") }.distinct()

    /** Every directory that goes with [app]: payloads + app data (e.g. Steam's client and data). */
    fun dataDirs(app: StoreApp): List<String> =
        (payloadDirs(app) + (DATA_DIRS[app.command.firstOrNull()] ?: emptyList())).distinct()

    /** Shell command line for hatch-uninstall-app (non-Flatpak apps). */
    fun command(app: StoreApp, otherInstalled: List<StoreApp>, dryRun: Boolean = false): String = buildList {
        add("${dev.hatch.guest.GuestScripts.BIN}/hatch-uninstall-app"); add("--id"); add(dev.hatch.guest.sq(app.id))
        fallbackPackages(app).takeIf { it.isNotEmpty() }?.let { add("--packages"); add(dev.hatch.guest.sq(it.joinToString(" "))) }
        keepPackages(app, otherInstalled).takeIf { it.isNotEmpty() }?.let { add("--keep"); add(dev.hatch.guest.sq(it.joinToString(" "))) }
        dataDirs(app).takeIf { it.isNotEmpty() }?.let { add("--dirs"); add(dev.hatch.guest.sq(it.joinToString(" "))) }
        if (dryRun) add("--dry-run")
    }.joinToString(" ") + " 2>&1"

    enum class Outcome { OK, WARNING, FAILED }

    /** hatch-uninstall-app's exit code: 0 removed, 10 removed with a warning (apt left packages), else failed. */
    fun outcome(exitCode: Int?): Outcome = when (exitCode) {
        0 -> Outcome.OK
        10 -> Outcome.WARNING
        else -> Outcome.FAILED
    }

    fun warningText(name: String) =
        "$name is uninstalled, but some of the Linux packages it used couldn't be removed. They don't affect other apps; Hatch tidies them up later."

    private val FREED_KB = Regex("""hatch: frees-kb (\d+)""")

    /** Bytes hatch-uninstall-app --dry-run says uninstalling frees; null if it didn't say. */
    fun parseFreedKb(out: String): Long? = FREED_KB.find(out)?.groupValues?.get(1)?.toLongOrNull()?.times(1024)

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
