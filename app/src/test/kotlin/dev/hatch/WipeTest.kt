package dev.hatch

import dev.hatch.rootfs.TarExtractor
import dev.hatch.rootfs.Wipe
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WipeTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A fake Android data dir: filesDir with a rootfs (l2s links, read-only dirs, links out), cache, no_backup, prefs. */
    private fun appData(): Triple<File, File, File> {
        val data = tmp.newFolder("data")
        val files = File(data, "files"); val cache = File(data, "cache"); val nb = File(data, "no_backup")
        val outside = tmp.newFolder("outside"); File(outside, "keep.txt").writeText("not ours")
        val root = File(files, "linux")
        File(root, "usr/bin").mkdirs(); File(root, "usr/bin/perl").writeText("PERL")
        // l2s layout as TarExtractor/proot writes it
        val l2s = File(root, ".l2s").apply { mkdirs() }
        val fin = File(l2s, ".l2s.perl0000.0002").apply { writeText("PERL") }
        val inter = File(l2s, ".l2s.perl0000"); Files.createSymbolicLink(inter.toPath(), fin.toPath())
        Files.createSymbolicLink(File(root, "usr/bin/perl5").toPath(), inter.toPath())
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())      // absolute link to a host dir
        File(root, "ro/sub").mkdirs(); File(root, "ro/sub/f").writeText("x")
        Files.setPosixFilePermissions(File(root, "ro/sub").toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        Files.setPosixFilePermissions(File(root, "ro").toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        File(files, "installed.json").writeText("[]"); File(files, "logs").mkdirs(); File(files, "logs/a.log").writeText("l")
        File(files, "wayland/app-x").mkdirs()
        File(cache, "dl").mkdirs(); File(cache, "dl/x.deb").writeText("deb")
        nb.mkdirs(); File(nb, "debian-trixie-rootfs.tar.xz").writeText("xz")
        File(data, "shared_prefs").mkdirs(); File(data, "shared_prefs/hatch.xml").writeText("<map/>")
        return Triple(data, outside, root)
    }

    @Test fun fullWipeLeavesOnlyPrefs() {
        val (data, outside, _) = appData()
        Wipe.wipeAppData(File(data, "files"), listOf(File(data, "cache")), File(data, "no_backup"))
        val left = data.walkTopDown().filter { it.isFile || Files.isSymbolicLink(it.toPath()) }.map { it.relativeTo(data).path }.toList()
        assertEquals(listOf("shared_prefs/hatch.xml"), left)
        assertTrue("links out of the rootfs are not followed", File(outside, "keep.txt").exists())
    }

    @Test fun deletesAnExtractedRootfs() {
        val src = tmp.newFolder("src"); File(src, "a").writeText("A"); Files.createLink(File(src, "b").toPath(), File(src, "a").toPath())
        File(src, "ro").mkdirs(); File(src, "ro/x").writeText("x")
        Files.setPosixFilePermissions(File(src, "ro").toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        val tar = File(tmp.root, "r.tar")
        ProcessBuilder("tar", "-cf", tar.path, ".").directory(src).start().waitFor()
        val root = File(tmp.root, "root")
        TarExtractor(root, File(root, ".l2s")).extract(tar.inputStream().buffered())
        assertTrue(Wipe.size(root) > 0)
        Wipe.deleteTree(root)
        assertTrue(!root.exists())
    }
}
