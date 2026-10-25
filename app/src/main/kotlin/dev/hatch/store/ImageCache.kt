package dev.hatch.store

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk cache + fetcher for store screenshots/icons. Bounded in every way, so the store can never show an endless spinner:
 * per-attempt connect/read timeouts, a couple of retries for transient errors (none for 404/410), a content-type
 * check (image types only), atomic temp-file writes, a size cap (oldest evicted), and a short in-memory "known bad" list per session.
 */
object ImageCache {
    var userAgent = "Hatch (+https://github.com/bently-bot/hatch)"
    const val MAX_BYTES = 48L shl 20
    private const val MAX_IMAGE = 12L shl 20
    private val bad = ConcurrentHashMap<String, Long>()
    private const val BAD_TTL_MS = 10 * 60_000L

    fun key(url: String): String = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun file(cacheDir: File, url: String) = File(cacheDir, "shots/" + key(url))

    fun knownBad(url: String): Boolean = bad[url]?.let { System.currentTimeMillis() - it < BAD_TTL_MS } ?: false
    fun markBad(url: String) { bad[url] = System.currentTimeMillis() }
    fun forgetBad() = bad.clear()

    /** Cached file for [url], downloading it if needed; null when it can't be had (dead link, not an image, offline…). */
    fun fetch(cacheDir: File, url: String, connectMs: Int = 6_000, readMs: Int = 10_000, retries: Int = 1, backoffMs: Long = 700): File? {
        val f = file(cacheDir, url)
        if (f.isFile && f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return f }
        if (knownBad(url) || !(url.startsWith("https://") || url.startsWith("http://"))) return null
        for (attempt in 0..retries) {
            if (attempt > 0) Thread.sleep(backoffMs * attempt)
            val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: break
            try {
                c.connectTimeout = connectMs; c.readTimeout = readMs; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/*;q=0.8")
                val code = c.responseCode
                if (code == 404 || code == 410 || code == 403) break        // permanent: don't retry
                if (code != 200) continue
                val type = c.contentType.orEmpty().lowercase()
                if (type.isNotEmpty() && !type.startsWith("image/") && type != "application/octet-stream") break
                if (c.contentLengthLong > MAX_IMAGE) break
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".part")
                var n = 0L
                c.inputStream.use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) { val r = inp.read(buf); if (r < 0) break; n += r; if (n > MAX_IMAGE) error("too big"); out.write(buf, 0, r) }
                } }
                if (n == 0L || !tmp.renameTo(f)) { tmp.delete(); continue }
                trim(File(cacheDir, "shots"))
                return f
            } catch (_: Exception) {
                File(f.parentFile, f.name + ".part").delete()
            } finally { c.disconnect() }
        }
        markBad(url)
        return null
    }

    /** A cached file that doesn't decode is corrupt: drop it so the next visit re-downloads. */
    fun dropCorrupt(cacheDir: File, url: String) { file(cacheDir, url).delete(); markBad(url) }

    fun trim(dir: File, max: Long = MAX_BYTES) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= max) return
        for (f in files.sortedBy { it.lastModified() }) { if (total <= max) break; total -= f.length(); f.delete() }
    }
}
