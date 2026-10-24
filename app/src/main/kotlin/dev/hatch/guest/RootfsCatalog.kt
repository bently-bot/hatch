package dev.hatch.guest

import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Debian 13 (trixie) rootfs from images.linuxcontainers.org.
 * linuxcontainers only keeps the last ~3 daily builds, so we resolve the newest build at install time and
 * verify it against that build's SHA256SUMS (HTTPS). If linuxcontainers is unreachable (or the pinned build has
 * expired), Debian's official image on Docker Hub (library/debian:trixie, built by debuerreotype) is the durable
 * fallback: one tar+gzip layer, verified against its content digest.
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

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Hatch")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private const val TAG = "HatchRootfs"
    private const val REGISTRY = "https://registry-1.docker.io/v2/library/debian"

    /** OCI platform for a linuxcontainers arch name. */
    fun ociPlatform(arch: String): Pair<String, String?> = if (arch == "amd64") "amd64" to null else "arm64" to "v8"

    /** Digest of the image manifest for [arch] in an OCI index / Docker manifest list. Pure, for tests. */
    fun manifestDigest(indexJson: String, arch: String): String? {
        val (a, v) = ociPlatform(arch)
        return Json.parseToJsonElement(indexJson).jsonObject["manifests"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { m ->
            val p = m["platform"] as? JsonObject ?: return@firstOrNull false
            p["os"]?.jsonPrimitive?.content == "linux" && p["architecture"]?.jsonPrimitive?.content == a &&
                (v == null || (p["variant"]?.jsonPrimitive?.content ?: "v8") == v)
        }?.get("digest")?.jsonPrimitive?.content
    }

    /** The single rootfs layer digest of an image manifest. Pure, for tests. */
    fun layerDigest(manifestJson: String): String? =
        Json.parseToJsonElement(manifestJson).jsonObject["layers"]?.jsonArray?.singleOrNull()?.jsonObject
            ?.get("digest")?.jsonPrimitive?.content

    private fun dockerHub(arch: String): Rootfs {
        val token = Json.parseToJsonElement(
            get("https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull")
        ).jsonObject["token"]!!.jsonPrimitive.content
        val auth = mapOf("Authorization" to "Bearer $token")
        val index = get("$REGISTRY/manifests/$DEBIAN_CODENAME", auth + ("Accept" to
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json"))
        val md = manifestDigest(index, arch) ?: error("no $arch image")
        val manifest = get("$REGISTRY/manifests/$md", auth + ("Accept" to
            "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"))
        val layer = layerDigest(manifest) ?: error("unexpected layer count")
        // Blobs redirect to a short-lived signed CDN URL; resolve it here so the token is never sent to the CDN.
        val c = URL("$REGISTRY/blobs/$layer").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 20_000; c.readTimeout = 30_000
        auth.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val url = try {
            when (c.responseCode) {
                in 300..399 -> c.getHeaderField("Location") ?: error("redirect without location")
                else -> error("HTTP ${c.responseCode} for blob")
            }
        } finally { c.disconnect() }
        return Rootfs(url, layer.removePrefix("sha256:"), "docker:debian:$DEBIAN_CODENAME@${layer.take(19)}")
    }

    private inline fun <T> attempts(what: String, n: Int, block: () -> T): T? {
        repeat(n) { i ->
            try { return block() } catch (e: Exception) {
                Log.w(TAG, "$what attempt ${i + 1}/$n failed: ${e.javaClass.simpleName}: ${e.message}")
                if (i < n - 1) Thread.sleep(2_000L * (i + 1))
            }
        }
        return null
    }

    fun resolve(arch: String = lxcArch()): Rootfs {
        attempts("linuxcontainers", 3) {
            val dir = "$BASE/$arch/default"
            val build = latestBuild(get("$dir/")) ?: error("no builds listed")
            val sha = shaFromSums(get("$dir/$build/SHA256SUMS")) ?: error("no rootfs sha")
            Rootfs("$dir/$build/rootfs.tar.xz", sha, build)
        }?.let { return it }
        attempts("Docker Hub debian:$DEBIAN_CODENAME", 3) { dockerHub(arch) }?.let { return it }
        if (arch == "arm64") return Rootfs(ROOTFS_URL, ROOTFS_SHA256, "pinned")
        error("Could not download Debian for $arch: check your internet connection and retry")
    }
}
