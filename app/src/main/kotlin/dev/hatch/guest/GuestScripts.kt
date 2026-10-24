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

    /** Apps run as this normal desktop user (proot -i), installs as fake root (proot -0). */
    const val USER = "hatch"
    const val UID = 1000
    const val HOME = "/home/hatch"

    /** Add the desktop user to passwd/group/shadow text (idempotent). Pure, for tests. */
    fun withUser(file: String, text: String): String {
        val line = when (file) {
            "passwd" -> "$USER:x:$UID:$UID:Hatch:$HOME:/bin/bash"
            "group" -> "$USER:x:$UID:"
            "shadow" -> "$USER:!:20000:0:99999:7:::"
            else -> return text
        }
        if (text.lineSequence().any { it.startsWith("$USER:") }) return text
        val base = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
        return base + line + "\n"
    }

    fun ensureUser(root: File) {
        for (f in listOf("passwd", "group", "shadow")) runCatching {
            val file = File(root, "etc/$f")
            if (!file.exists()) return@runCatching
            val old = file.readText(); val new = withUser(f, old)
            if (new != old) { file.setWritable(true, true); file.writeText(new) }
        }.onFailure { Log.w(TAG, "ensureUser $f: ${it.message}") }
        File(root, HOME.removePrefix("/")).mkdirs()
    }

    fun install(context: Context, root: File) {
        ensureUser(root)
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
        desktopId: String? = null,
        repo: dev.hatch.store.AptRepo? = null,
    ): String = buildList {
        add("$BIN/hatch-install-app")
        if (packages.isNotEmpty()) { add("--packages"); add(sq(packages.joinToString(" "))) }
        if (repo != null) {
            add("--repo"); add(sq(repo.id)); add(sq(repo.keyUrl)); add(sq(repo.uris)); add(sq(repo.suites)); add(sq(repo.components))
        }
        if (wine) add("--wine")
        if (!desktopId.isNullOrEmpty()) { add("--desktop"); add(sq(desktopId)) }
        if (!payloadUrl.isNullOrEmpty() && !payloadDest.isNullOrEmpty()) {
            add("--payload"); add(sq(payloadUrl)); add(sq(payloadSha ?: "")); add(sq(payloadDest))
        }
    }.joinToString(" ") + " 2>&1"
}
