package dev.hatch.guest

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val http = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

/**
 * Download [url] to [dest], verifying [sha256] (hex). Resumes via `.part` when possible.
 * [onProgress] receives (bytesGot, totalOr -1).
 */
fun download(url: String, dest: File, sha256: String, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
    dest.parentFile?.mkdirs()
    val part = File(dest.path + ".part")
    if (dest.exists() && sha256Hex(dest).equals(sha256, ignoreCase = true)) return
    if (part.exists()) {
        val got = sha256Hex(part)
        if (got.equals(sha256, ignoreCase = true)) {
            part.renameTo(dest); return
        }
    }
    val existing = if (part.exists()) part.length() else 0L
    val req = Request.Builder().url(url).apply {
        if (existing > 0) header("Range", "bytes=$existing-")
    }.build()
    http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful && resp.code != 206) error("HTTP ${resp.code} for $url")
        val body = resp.body ?: error("empty body")
        val total = when {
            resp.code == 206 -> existing + (body.contentLength().takeIf { it >= 0 } ?: -1)
            body.contentLength() >= 0 -> body.contentLength()
            else -> -1L
        }
        val append = resp.code == 206
        FileOutputStream(part, append).use { out ->
            val buf = ByteArray(64 * 1024)
            var got = existing
            body.byteStream().use { inn ->
                while (true) {
                    val n = inn.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    got += n
                    onProgress(got, total)
                }
            }
        }
    }
    val digest = sha256Hex(part)
    check(digest.equals(sha256, ignoreCase = true)) {
        "checksum mismatch: got $digest want $sha256"
    }
    dest.delete()
    check(part.renameTo(dest)) { "rename failed" }
}

fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { inn ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = inn.read(buf); if (n <= 0) break; md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Open [url] for streaming (no temp file); returns the body stream and its length (-1 if unknown). */
fun openStream(url: String): Pair<java.io.InputStream, Long> {
    val resp = http.newCall(Request.Builder().url(url).build()).execute()
    if (!resp.isSuccessful) { resp.close(); error("HTTP ${resp.code} for $url") }
    val body = resp.body ?: run { resp.close(); error("empty body") }
    return body.byteStream() to body.contentLength()
}
