package dev.hatch.guest

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Guest-side helper scripts shipped in assets/guest-bin and copied to /usr/local/bin.
 * CI runs the very same scripts inside a Debian 13 arm64 rootfs, so what CI proves is what ships.
 */
object GuestScripts {
    private const val TAG = "HatchScripts"
    const val ASSET_DIR = "guest-bin"
    const val BIN = "/usr/local/bin"

    fun install(context: Context, root: File) {
        val bin = File(root, "usr/local/bin").apply { mkdirs() }
        val names = context.assets.list(ASSET_DIR) ?: emptyArray()
        for (n in names) {
            runCatching {
                val dst = File(bin, n)
                context.assets.open("$ASSET_DIR/$n").use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                dst.setExecutable(true, false)
            }.onFailure { Log.w(TAG, "install $n: ${it.message}") }
        }
    }

    /** argv for launching an installed app through hatch-launch. */
    fun launchArgv(command: List<String>, wine: Boolean, x11: Boolean): List<String> = buildList {
        add("$BIN/hatch-launch")
        if (wine) add("--wine")
        if (x11) add("--x11")
        add("--")
        addAll(command)
    }

    /** Shell command line for hatch-install-app. */
    fun installCommand(
        packages: List<String>,
        wine: Boolean,
        payloadUrl: String? = null,
        payloadSha: String? = null,
        payloadDest: String? = null,
    ): String = buildList {
        add("$BIN/hatch-install-app")
        if (packages.isNotEmpty()) { add("--packages"); add(sq(packages.joinToString(" "))) }
        if (wine) add("--wine")
        if (!payloadUrl.isNullOrEmpty() && !payloadDest.isNullOrEmpty()) {
            add("--payload"); add(sq(payloadUrl)); add(sq(payloadSha ?: "")); add(sq(payloadDest))
        }
    }.joinToString(" ") + " 2>&1"
}
