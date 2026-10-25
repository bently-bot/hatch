package dev.hatch.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** The bits of a GitHub release (`GET /repos/{owner}/{repo}/releases/latest`, or one entry of `/releases`). */
@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
)

/** A newer Hatch, ready to download: `hatch-<version>.apk` plus its `.sha256` asset. */
data class UpdateOffer(
    val version: String,
    val title: String,
    val notes: String,
    val apk: GhAsset,
    val sha256Url: String,
    val pageUrl: String?,
)

/** Semver-ish version: 0.3.0, v0.3.0, 0.3.0-rc.1 (prerelease sorts below the release), 4 numeric parts at most. */
data class Version(val parts: List<Int>, val pre: String?) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            pre == other.pre -> 0
            pre == null -> 1          // 0.3.0 > 0.3.0-rc.1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    private fun comparePre(a: String, b: String): Int {
        val x = a.split('.'); val y = b.split('.')
        for (i in 0 until minOf(x.size, y.size)) {
            val xi = x[i].toIntOrNull(); val yi = y[i].toIntOrNull()
            val c = when {
                xi != null && yi != null -> xi.compareTo(yi)
                xi != null -> -1          // numeric identifiers sort below alphanumeric ones (semver 11.4.3)
                yi != null -> 1
                else -> x[i].compareTo(y[i])
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    override fun toString() = parts.joinToString(".") + (pre?.let { "-$it" } ?: "")
}

/** Pure parts of Hatch's self-update (unit-tested); [SelfUpdater] does the network and installing. */
object SelfUpdates {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    fun parseVersion(raw: String): Version? {
        val s = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = s.substringBefore('-')
        val pre = s.substringAfter('-', "").ifEmpty { null }
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 4 || parts.any { it.isEmpty() }) return null
        return Version(parts.map { it.toIntOrNull() ?: return null }, pre)
    }

    /** > 0 when [a] is newer than [b]; unparseable versions sort lowest. */
    fun compare(a: String, b: String): Int {
        val va = parseVersion(a); val vb = parseVersion(b)
        return when {
            va == null && vb == null -> 0
            va == null -> -1
            vb == null -> 1
            else -> va.compareTo(vb)
        }
    }

    /** A single release (`/releases/latest`) or a list (`/releases`). */
    fun parseReleases(text: String): List<GhRelease> {
        val el: JsonElement = json.parseToJsonElement(text)
        return if (el is JsonArray) el.map { json.decodeFromJsonElement(GhRelease.serializer(), it) }
        else listOf(json.decodeFromJsonElement(GhRelease.serializer(), el))
    }

    fun apkName(version: String) = "hatch-$version.apk"

    /**
     * The update to offer, or null when up to date: the newest release that is published (not a draft), not a
     * prerelease (flag, or a "-dev.x" / "-rc" style tag), newer than [installedVersion], and ships both
     * `hatch-<version>.apk` and `hatch-<version>.apk.sha256` (no checksum, no update).
     */
    fun pickOffer(releases: List<GhRelease>, installedVersion: String): UpdateOffer? {
        val installedIsPre = parseVersion(installedVersion)?.pre != null
        return releases.asSequence()
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { r -> parseVersion(r.tagName)?.let { v -> r to v } }
            .filter { (_, v) -> v.pre == null || installedIsPre }
            .filter { (r, _) -> compare(r.tagName, installedVersion) > 0 }
            .sortedByDescending { it.second }
            .mapNotNull { (r, v) ->
                val ver = r.tagName.trim().removePrefix("v").removePrefix("V")
                val apk = r.assets.firstOrNull { it.name == apkName(ver) } ?: return@mapNotNull null
                val sha = r.assets.firstOrNull { it.name == apkName(ver) + ".sha256" } ?: return@mapNotNull null
                UpdateOffer(ver, r.name?.takeIf { it.isNotBlank() } ?: "Hatch $ver", shortenNotes(r.body), apk, sha.url, r.htmlUrl)
            }
            .firstOrNull()
    }

    /** `sha256sum` output ("<hex>  <name>", "<hex> *<name>" or a bare hex) → the hex for [fileName]. */
    fun parseSha256(text: String, fileName: String): String? {
        val rx = Regex("""^([0-9a-fA-F]{64})(?:\s+\*?(.+?))?\s*$""")
        val hits = text.lines().mapNotNull { rx.find(it.trim()) }
        val named = hits.firstOrNull { it.groupValues[2].substringAfterLast('/') == fileName }
        val any = hits.singleOrNull()?.takeIf { it.groupValues[2].isEmpty() || it.groupValues[2].substringAfterLast('/') == fileName }
        return (named ?: any)?.groupValues?.get(1)?.lowercase()
    }

    /** Release notes for the card: drop GitHub's "Full Changelog" footer and HTML comments, cap length on a line. */
    fun shortenNotes(body: String?, maxChars: Int = 900, maxLines: Int = 14): String {
        val lines = body.orEmpty().replace("\r\n", "\n").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .lines().filterNot { it.trim().startsWith("**Full Changelog**") }
            .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return ""
        val out = StringBuilder(); var n = 0; var cut = false
        for (l in lines) {
            if (n >= maxLines || out.length + l.length > maxChars) { cut = true; break }
            out.append(l).append('\n'); n++
        }
        return out.toString().trimEnd() + if (cut) "\n…" else ""
    }

    /** Quiet launch-time check: on, and the last one was [AUTO_CHECK_INTERVAL_MS] ago (or the clock went back). */
    fun dueForAutoCheck(enabled: Boolean, now: Long, lastCheck: Long): Boolean =
        enabled && (lastCheck <= 0 || now - lastCheck >= AUTO_CHECK_INTERVAL_MS || now < lastCheck)

    /** Signing certificates (SHA-256 digests, hex) of the downloaded APK must equal the installed app's. */
    fun sameSigners(installed: Set<String>, apk: Set<String>, apkHistory: Set<String> = emptySet()): Boolean =
        installed.isNotEmpty() && apk.isNotEmpty() && (installed == apk || (apkHistory.isNotEmpty() && installed.all { it in apkHistory }))

    fun human(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    // ---- tiny Markdown subset for release notes: headings, bullets, paragraphs; **bold**, `code`, [text](url) ----
    sealed interface Block {
        data class Heading(val text: String) : Block
        data class Bullet(val text: String, val level: Int) : Block
        data class Para(val text: String) : Block
    }

    fun markdownBlocks(md: String): List<Block> {
        val out = mutableListOf<Block>(); val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
        for (raw in md.lines()) {
            val l = raw.trimEnd()
            val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""").find(l)
            when {
                l.isBlank() -> flush()
                l.trimStart().startsWith("#") -> { flush(); out += Block.Heading(l.trimStart().trimStart('#').trim()) }
                bullet != null -> { flush(); out += Block.Bullet(bullet.groupValues[2].trim(), bullet.groupValues[1].length / 2) }
                out.lastOrNull() is Block.Bullet && para.isEmpty() && raw.startsWith("  ") -> {
                    val b = out.removeAt(out.size - 1) as Block.Bullet; out += b.copy(text = b.text + " " + l.trim())
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(l.trim()) }
            }
        }
        flush(); return out
    }

    /** Inline spans of one Markdown line: plain, bold, code, link text (links render as text). */
    enum class Style { PLAIN, BOLD, CODE }
    fun inlineSpans(text: String): List<Pair<String, Style>> {
        val out = mutableListOf<Pair<String, Style>>()
        val rx = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\([^)]+\)""")
        var i = 0
        for (m in rx.findAll(text)) {
            if (m.range.first > i) out += text.substring(i, m.range.first) to Style.PLAIN
            when {
                m.groupValues[1].isNotEmpty() -> out += m.groupValues[1] to Style.BOLD
                m.groupValues[2].isNotEmpty() -> out += m.groupValues[2] to Style.CODE
                else -> out += m.groupValues[3] to Style.PLAIN
            }
            i = m.range.last + 1
        }
        if (i < text.length) out += text.substring(i) to Style.PLAIN
        return out
    }
}
