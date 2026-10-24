package dev.hatch.guest

import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debian 13 (trixie) rootfs from images.linuxcontainers.org.
 * linuxcontainers only keeps the last ~3 daily builds, so we resolve the newest build at install time and
 * verify it against that build's SHA256SUMS (HTTPS). The pinned build is a fallback if the index is unreachable.
 */
object RootfsCatalog {
    const val DEBIAN_RELEASE = "13"
    const val DEBIAN_CODENAME = "trixie"
    const val BASE = "https://images.linuxcontainers.org/images/debian/trixie"

    /** Pinned snapshot (2026-10-22), arm64. CI reads these two constants. */
    const val ROOTFS_URL =
        "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20261022_05:24/rootfs.tar.xz"
    const val ROOTFS_SHA256 =
        "cb8f1d5402236096587358a9734f5cb3c7621aa2a13a6b692c61506966ec2810"
    const val ROOTFS_NAME = "debian-trixie-rootfs.tar.xz"

    data class Rootfs(val url: String, val sha256: String, val build: String)

    /** linuxcontainers arch name for this device (arm64 phones; amd64 for x86_64 emulators/Chromebooks). */
    fun lxcArch(abis: Array<String> = Build.SUPPORTED_ABIS): String =
        if (abis.firstOrNull()?.startsWith("x86_64") == true) "amd64" else "arm64"

    private val BUILD_RE = Regex("""\d{8}_\d{2}:\d{2}""")

    fun latestBuild(indexHtml: String): String? = BUILD_RE.findAll(indexHtml).map { it.value }.distinct().maxOrNull()

    fun shaFromSums(sums: String, file: String = "rootfs.tar.xz"): String? =
        sums.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it[1].trimStart('*') == file && it[0].length == 64 }?.get(0)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Hatch")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    fun resolve(arch: String = lxcArch()): Rootfs {
        runCatching {
            val dir = "$BASE/$arch/default"
            val build = latestBuild(get("$dir/")) ?: error("no builds listed")
            val sha = shaFromSums(get("$dir/$build/SHA256SUMS")) ?: error("no rootfs sha")
            return Rootfs("$dir/$build/rootfs.tar.xz", sha, build)
        }
        if (arch == "arm64") return Rootfs(ROOTFS_URL, ROOTFS_SHA256, "pinned")
        error("Could not resolve Debian rootfs for $arch")
    }
}
