package dev.hatch.harness

import com.github.luben.zstd.ZstdInputStream
import dev.hatch.rootfs.TarExtractor
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Times Hatch's setup pipelines on a host (CI arm64 runner), with the app's own code:
 *   new <url|file.tar.zst> <dest> <sha256>   stream: download → sha256 → zstd → TarExtractor (one pass)
 *   xz  <file.tar.xz> <out.tar>              old path step: XZ → tar with tukaani-xz (as RootfsInstaller does)
 */
fun main(args: Array<String>) {
    val t0 = System.nanoTime()
    fun secs() = "%.1f".format((System.nanoTime() - t0) / 1e9)
    when (args[0]) {
        "new" -> {
            val src = args[1]; val dest = File(args[2]); val want = args.getOrNull(3).orEmpty()
            val raw: InputStream = if (src.startsWith("http")) URI(src).toURL().openStream() else File(src).inputStream()
            val md = MessageDigest.getInstance("SHA-256")
            var compressed = 0L
            val counting = object : java.io.FilterInputStream(DigestInputStream(raw.buffered(1 shl 20), md)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) compressed += it }
                override fun read(): Int = super.read().also { if (it >= 0) compressed++ }
            }
            val st = ZstdInputStream(counting).use { z ->
                val s = TarExtractor(dest, File(dest, ".l2s")).extract(z.buffered(1 shl 20))
                val sink = ByteArray(1 shl 16); while (z.read(sink) >= 0) {}
                s
            }
            while (counting.read(ByteArray(1 shl 16)) >= 0) {}
            val got = md.digest().joinToString("") { "%02x".format(it) }
            println("NEW extract ${secs()}s compressed=${compressed / 1_000_000}MB files=${st.files} dirs=${st.dirs} symlinks=${st.symlinks} hardlinks=${st.hardlinks} skipped=${st.skipped} bytes=${st.bytes / 1_000_000}MB sha256=${if (want.isEmpty()) got else if (got == want) "OK" else "MISMATCH $got"}")
            if (want.isNotEmpty() && got != want) System.exit(2)
        }
        "xz" -> {
            org.tukaani.xz.XZInputStream(File(args[1]).inputStream().buffered(1 shl 20)).use { i -> File(args[2]).outputStream().use { i.copyTo(it, 1 shl 20) } }
            println("OLD xz→tar ${secs()}s")
        }
    }
}
