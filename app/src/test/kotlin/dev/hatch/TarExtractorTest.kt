package dev.hatch

import dev.hatch.rootfs.TarExtractor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TarExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sh(dir: File, vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")}: $out" }
    }

    private fun sampleTree(tag: String): File {
        val src = tmp.newFolder("src-$tag")
        File(src, "usr/bin").mkdirs()
        File(src, "usr/bin/tool").writeText("#!/bin/sh\necho hi\n"); File(src, "usr/bin/tool").setExecutable(true)
        File(src, "usr/bin/perl").writeText("PERL")
        Files.createLink(File(src, "usr/bin/perl5.40").toPath(), File(src, "usr/bin/perl").toPath())
        Files.createLink(File(src, "usr/bin/perl-again").toPath(), File(src, "usr/bin/perl").toPath())
        Files.createSymbolicLink(File(src, "usr/bin/abs").toPath(), File("/usr/bin/tool").toPath())
        Files.createSymbolicLink(File(src, "lib").toPath(), File("usr/lib").toPath())
        val deep = File(src, "usr/share/" + "d".repeat(80) + "/" + "f".repeat(90)).apply { parentFile.mkdirs() }
        deep.writeText("deep")
        File(src, "ro").mkdirs(); File(src, "ro/file").writeText("x")
        Files.setPosixFilePermissions(File(src, "ro").toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        return src
    }

    private fun extract(format: String): Pair<File, TarExtractor.Stats> {
        val src = sampleTree(format)
        val tar = File(tmp.root, "r-$format.tar")
        sh(src, "tar", "--format=$format", "-cf", tar.path, ".")
        Files.setPosixFilePermissions(File(src, "ro").toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val dest = File(tmp.root, "dest-$format")
        val stats = tar.inputStream().buffered().use { TarExtractor(dest, File(dest, ".l2s")).extract(it) }
        return dest to stats
    }

    @Test fun paxAndGnuArchivesExtract() { for (f in listOf("pax", "gnu")) check(f) }

    private fun check(format: String) {
        val (d, st) = extract(format)
        assertEquals("#!/bin/sh\necho hi\n", File(d, "usr/bin/tool").readText())
        assertTrue(File(d, "usr/bin/tool").canExecute())
        assertEquals("/usr/bin/tool", Files.readSymbolicLink(File(d, "usr/bin/abs").toPath()).toString())
        assertEquals("usr/lib", Files.readSymbolicLink(File(d, "lib").toPath()).toString())
        assertEquals("deep", File(d, "usr/share/" + "d".repeat(80) + "/" + "f".repeat(90)).readText())
        // Hard links → proot link2symlink: 3 names → one intermediate → content with link count 3.
        val names = listOf("perl", "perl5.40", "perl-again").map { File(d, "usr/bin/$it") }
        val inters = names.map { Files.readSymbolicLink(it.toPath()).toString() }.toSet()
        assertEquals("all names share one intermediate ($format)", 1, inters.size)
        val inter = File(inters.single())
        assertTrue(inter.name.startsWith(".l2s.perl") && inter.parentFile == File(d, ".l2s"))
        val fin = File(Files.readSymbolicLink(inter.toPath()).toString())
        assertTrue("link count suffix: ${fin.name}", fin.name.endsWith(".0003"))
        names.forEach { assertEquals("PERL", it.readText()) }
        assertEquals(2, st.hardlinks)
        // Read-only dir stays owner-writable (so updates/reset can delete inside it).
        assertTrue(File(d, "ro").canWrite()); assertTrue(File(d, "ro/file").delete())
    }

    // Minimal ustar writer for hostile entries GNU tar won't produce.
    private fun entry(out: ByteArrayOutputStream, name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "") {
        val h = ByteArray(512)
        fun put(s: String, off: Int) = s.toByteArray().copyInto(h, off)
        put(name, 0); put("0000644\u0000", 100); put("%011o\u0000".format(data.size), 124); put("00000000000\u0000", 136)
        h[156] = type.code.toByte(); put(link, 157); put("ustar\u000000", 257)
        "        ".toByteArray().copyInto(h, 148)
        put("%06o\u0000 ".format(h.sumOf { it.toInt() and 0xFF }), 148)
        out.write(h); out.write(data); out.write(ByteArray(((512 - data.size % 512) % 512)))
    }

    @Test fun refusesToWriteOutsideTheRootfs() {
        val outside = tmp.newFolder("outside")
        val b = ByteArrayOutputStream()
        entry(b, "../escape", '0', "x".toByteArray())
        entry(b, "etc/", '5')
        entry(b, "etc/evil", '2', link = outside.path)            // absolute symlink to a host dir
        entry(b, "etc/evil/pwn", '0', "pwned".toByteArray())      // ...must not be followed
        entry(b, "etc/../../escape2", '0', "x".toByteArray())
        entry(b, "etc/ok", '0', "fine".toByteArray())
        b.write(ByteArray(1024))
        val dest = File(tmp.root, "d")
        val st = TarExtractor(dest, File(dest, ".l2s")).extract(b.toByteArray().inputStream())
        assertFalse(File(outside, "pwn").exists()); assertFalse(File(tmp.root, "escape").exists()); assertFalse(File(tmp.root, "escape2").exists())
        assertEquals("fine", File(dest, "etc/ok").readText())
        assertEquals(3, st.skipped)
    }

    @Test fun pathCleaning() {
        assertEquals("usr/bin/x", TarExtractor.clean("./usr/bin/x"))
        assertEquals("etc", TarExtractor.clean("/etc/"))
        assertEquals("", TarExtractor.clean("./"))
        assertEquals(null, TarExtractor.clean("a/../../b"))
    }
}
