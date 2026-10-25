package dev.hatch.rootfs

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Streaming tar → directory extractor for Hatch's prebuilt rootfs (Android-free; the arm64 CI bench runs it too).
 * ustar + pax (path/linkpath/size) + GNU long names. Symlinks keep their (guest) targets; hard links are written in
 * proot's link2symlink layout so the guest sees real links:
 *   <l2s>/.l2s.<name>NNNN.0002   the content (suffix = link count)
 *   <l2s>/.l2s.<name>NNNN        → the content file
 *   every linked path           → <l2s>/.l2s.<name>NNNN
 * Modes keep their x bits but stay owner-writable, so updates and the Storage reset can always replace/delete.
 * Entries that would land outside [dest] (.., or through an absolute symlink) are skipped.
 */
class TarExtractor(private val dest: File, private val l2sDir: File, private val fs: Fs = JvmFs) {
    interface Fs {
        fun chmod(f: File, mode: Int)
        fun symlink(target: String, link: File)
        fun readlink(f: File): String?
    }

    object JvmFs : Fs {
        private val bits = listOf(
            0x100 to PosixFilePermission.OWNER_READ, 0x80 to PosixFilePermission.OWNER_WRITE, 0x40 to PosixFilePermission.OWNER_EXECUTE,
            0x20 to PosixFilePermission.GROUP_READ, 0x10 to PosixFilePermission.GROUP_WRITE, 0x8 to PosixFilePermission.GROUP_EXECUTE,
            0x4 to PosixFilePermission.OTHERS_READ, 0x2 to PosixFilePermission.OTHERS_WRITE, 0x1 to PosixFilePermission.OTHERS_EXECUTE,
        )
        override fun chmod(f: File, mode: Int) { Files.setPosixFilePermissions(f.toPath(), bits.filter { mode and it.first != 0 }.map { it.second }.toSet()) }
        override fun symlink(target: String, link: File) { Files.createSymbolicLink(link.toPath(), Paths.get(target)) }
        override fun readlink(f: File): String? =
            if (Files.isSymbolicLink(f.toPath())) Files.readSymbolicLink(f.toPath()).toString() else null
    }

    class Stats { var files = 0; var dirs = 0; var symlinks = 0; var hardlinks = 0; var skipped = 0; var bytes = 0L }

    private val stats = Stats()
    private val destPath = dest.canonicalPath
    private val safeDirs = HashSet<String>()
    private val dirModes = ArrayList<Pair<File, Int>>()
    private val buf = ByteArray(1 shl 16)

    fun extract(input: InputStream, onEntry: ((Stats) -> Unit)? = null): Stats {
        dest.mkdirs()
        val hdr = ByteArray(512)
        var paxPath: String? = null; var paxLink: String? = null; var paxSize: Long? = null
        var gnuName: String? = null; var gnuLink: String? = null
        var zeros = 0
        while (true) {
            if (!readFully(input, hdr, 512)) break
            if (hdr.all { it.toInt() == 0 }) { if (++zeros == 2) break else continue }
            zeros = 0
            val type = hdr[156].toInt().toChar()
            var size = paxSize ?: number(hdr, 124, 12)
            when (type) {
                'x' -> { val m = pax(readData(input, size)); paxPath = m["path"]; paxLink = m["linkpath"]; paxSize = m["size"]?.toLongOrNull(); continue }
                'g' -> { skipData(input, size); continue }
                'L' -> { gnuName = cstr(readData(input, size), 0, size.toInt()); continue }
                'K' -> { gnuLink = cstr(readData(input, size), 0, size.toInt()); continue }
            }
            val ustar = String(hdr, 257, 5, Charsets.US_ASCII) == "ustar"
            val prefix = if (ustar) cstr(hdr, 345, 155) else ""
            val rawName = paxPath ?: gnuName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + cstr(hdr, 0, 100)
            val link = paxLink ?: gnuLink ?: cstr(hdr, 157, 100)
            val mode = number(hdr, 100, 8).toInt() and 0xFFF
            paxPath = null; paxLink = null; paxSize = null; gnuName = null; gnuLink = null
            val rel = clean(rawName)
            val target = rel?.let { File(dest, it) }
            if (target == null || (rel.isNotEmpty() && !insideDest(target.parentFile!!))) {
                stats.skipped++; skipData(input, if (type == '0' || type == '\u0000' || type == '7') size else 0); continue
            }
            when (type) {
                '0', '\u0000', '7' -> { writeFile(input, target, size, mode); size = 0 }
                '5' -> { target.mkdirs(); dirModes += target to mode; stats.dirs++ }
                '2' -> { replace(target); target.parentFile?.mkdirs(); fs.symlink(link, target); stats.symlinks++ }
                '1' -> { val src = clean(link)?.let { File(dest, it) }; if (src != null && insideDest(src.parentFile!!)) hardlink(src, target) else stats.skipped++ }
                else -> stats.skipped++   // devices, fifos: not needed (proot binds /dev)
            }
            skipData(input, size)
            onEntry?.invoke(stats)
        }
        // Deepest first, so a read-only parent never blocks its children; always owner rwx.
        for ((d, m) in dirModes.sortedByDescending { it.first.path.length }) runCatching { fs.chmod(d, m or 0x1C0) }
        return stats
    }

    private fun writeFile(input: InputStream, f: File, size: Long, mode: Int) {
        replace(f); f.parentFile?.mkdirs()
        FileOutputStream(f).use { out ->
            var left = size
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IOException("truncated tar at ${f.path}")
                out.write(buf, 0, n); left -= n
            }
        }
        skipPad(input, size)
        fs.chmod(f, (mode and 0x1FF) or 0x180)
        stats.files++; stats.bytes += size
    }

    /** proot link2symlink: see class doc. */
    private fun hardlink(src: File, dst: File) {
        if (fs.readlink(src) == null && !src.exists()) { stats.skipped++; return }
        val existing = fs.readlink(src)
        val intermediate: File
        if (existing != null && File(existing).name.startsWith(PREFIX)) {
            intermediate = File(existing)
            val fin = File(fs.readlink(intermediate) ?: throw IOException("broken l2s link $existing"))
            val count = fin.name.takeLast(4).toIntOrNull() ?: 1
            val next = File(fin.path.dropLast(4) + "%04d".format(count + 1))
            Files.move(fin.toPath(), next.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.delete(intermediate.toPath()); fs.symlink(next.absolutePath, intermediate)
        } else {
            l2sDir.mkdirs()
            var i = 0
            var inter: File
            do { inter = File(l2sDir, PREFIX + src.name + "%04d".format(i++)) } while (exists(inter) || exists(File(inter.path + ".0002")))
            val fin = File(inter.path + ".0002")
            Files.move(src.toPath(), fin.toPath(), StandardCopyOption.ATOMIC_MOVE)
            fs.symlink(fin.absolutePath, inter)
            fs.symlink(inter.absolutePath, src)
            intermediate = inter
        }
        replace(dst); dst.parentFile?.mkdirs()
        fs.symlink(intermediate.absolutePath, dst)
        stats.hardlinks++
    }

    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun replace(f: File) { if (exists(f) && !(f.isDirectory && fs.readlink(f) == null)) Files.delete(f.toPath()) }

    /** The parent dir resolves (following host symlinks) inside dest. Cached per directory. */
    private fun insideDest(dir: File): Boolean {
        val p = dir.path
        if (p in safeDirs) return true
        // Walk up to the nearest existing ancestor; new dirs are created under it.
        var d: File? = dir
        while (d != null && !exists(d)) d = d.parentFile
        val c = (d ?: return false).canonicalPath
        val ok = c == destPath || c.startsWith("$destPath/")
        if (ok && exists(dir)) safeDirs += p
        return ok
    }

    private fun readFully(input: InputStream, b: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) { val r = input.read(b, off, n - off); if (r < 0) { if (off == 0) return false; throw IOException("truncated tar header") }; off += r }
        return true
    }
    private fun readData(input: InputStream, size: Long): ByteArray {
        val b = ByteArray(size.toInt()); if (size > 0) readFully(input, b, size.toInt()); skipPad(input, size); return b
    }
    private fun skipData(input: InputStream, size: Long) { if (size > 0) { skip(input, size); skipPad(input, size) } }
    private fun skipPad(input: InputStream, size: Long) { val pad = (512 - size % 512) % 512; if (pad > 0) skip(input, pad) }
    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) { val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw IOException("truncated tar"); left -= r }
    }

    companion object {
        const val PREFIX = ".l2s."

        /** Archive path → path under dest; null if it climbs out (".."). "" is the root itself. */
        fun clean(name: String): String? {
            val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) return null
            return parts.joinToString("/")
        }

        fun cstr(b: ByteArray, off: Int, len: Int): String {
            var end = off; while (end < off + len && end < b.size && b[end].toInt() != 0) end++
            return String(b, off, end - off, Charsets.UTF_8)
        }

        /** Octal, or GNU base-256 when the high bit is set. */
        fun number(b: ByteArray, off: Int, len: Int): Long {
            if (b[off].toInt() and 0x80 != 0) { var v = 0L; for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }
            val s = cstr(b, off, len).trim(); return if (s.isEmpty()) 0 else s.toLong(8)
        }

        /** pax records: "<len> key=value\n". */
        fun pax(data: ByteArray): Map<String, String> {
            val out = HashMap<String, String>(); var i = 0
            while (i < data.size) {
                var sp = i; while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
                val len = String(data, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
                if (len <= 0 || i + len > data.size) break
                val rec = String(data, sp + 1, i + len - sp - 2, Charsets.UTF_8)
                val eq = rec.indexOf('='); if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
                i += len
            }
            return out
        }
    }
}
