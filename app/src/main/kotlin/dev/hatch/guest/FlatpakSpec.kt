package dev.hatch.guest

import java.io.File

/**
 * How Hatch runs a Flathub app without bubblewrap: `hatch-flatpak install` deploys it with flatpak and writes
 * /var/lib/hatch/flatpak/<id>.spec; we turn that into a proot invocation with Flatpak's own layout
 * (runtime → /usr, app → /app, GL extension, a skeleton root). scripts/guest-run.sh does the same for CI.
 *
 * Spec lines: `root <guest dir>` | `bind <guest src>:<dst>` | `env K=V` | `cmd <argv…>` (guest paths).
 */
data class FlatpakSpec(
    val root: String,
    val binds: List<Pair<String, String>>,
    val env: List<Pair<String, String>>,
    val command: List<String>,
) {
    /** proot arguments after the binary: identity, root, binds (host paths), cwd, then the command. */
    fun prootArgs(rootfs: String, commonBinds: List<String>, uid: Int, home: String): List<String> = buildList {
        add("-i"); add("$uid:$uid")
        add("-l")
        add("-r"); add(rootfs + root)
        for (b in commonBinds) { add("-b"); add(b) }
        for ((src, dst) in binds) { add("-b"); add("$rootfs$src:$dst") }
        add("-w"); add(home)
        addAll(command)
    }

    /** Spec env on top of [base] (later entries win, like the shell script). */
    fun environment(base: Map<String, String>): Map<String, String> =
        LinkedHashMap(base).apply { for ((k, v) in env) put(k, v) }

    companion object {
        const val RUN = "hatch-flatpak-run"
        fun specPath(appId: String) = "/var/lib/hatch/flatpak/$appId.spec"

        fun parse(text: String): FlatpakSpec? {
            var root: String? = null
            val binds = ArrayList<Pair<String, String>>()
            val env = ArrayList<Pair<String, String>>()
            var cmd: List<String> = emptyList()
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd()
                when {
                    line.startsWith("root ") -> root = line.removePrefix("root ")
                    line.startsWith("bind ") -> line.removePrefix("bind ").let { b ->
                        val i = b.indexOf(':'); if (i > 0) binds += b.substring(0, i) to b.substring(i + 1)
                    }
                    line.startsWith("env ") -> line.removePrefix("env ").let { e ->
                        val i = e.indexOf('='); if (i > 0) env += e.substring(0, i) to e.substring(i + 1)
                    }
                    line.startsWith("cmd ") -> cmd = line.removePrefix("cmd ").split(' ').filter { it.isNotEmpty() }
                }
            }
            return if (root != null && cmd.isNotEmpty()) FlatpakSpec(root, binds, env, cmd) else null
        }

        fun load(rootfs: File, appId: String): FlatpakSpec? =
            File(rootfs, specPath(appId).removePrefix("/")).takeIf { it.isFile }?.readText()?.let(::parse)
    }
}
