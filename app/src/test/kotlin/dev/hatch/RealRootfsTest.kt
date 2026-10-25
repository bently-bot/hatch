package dev.hatch

import dev.hatch.rootfs.TarExtractor
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: HATCH_ROOTFS_ZST=<file> HATCH_ROOTFS_SHA=<sha256> HATCH_ROOTFS_DEST=<dir> extracts a real prebuilt image. */
class RealRootfsTest {
    @Test fun extractsThePublishedImage() {
        val zst = System.getenv("HATCH_ROOTFS_ZST"); assumeTrue(zst != null)
        val dest = File(System.getenv("HATCH_ROOTFS_DEST") ?: return)
        val md = MessageDigest.getInstance("SHA-256")
        val t0 = System.nanoTime()
        val st = DigestInputStream(File(zst!!).inputStream().buffered(1 shl 20), md).use { d ->
            com.github.luben.zstd.ZstdInputStream(d).use { z ->
                val s = TarExtractor(dest, File(dest, ".l2s")).extract(z.buffered(1 shl 20))
                val b = ByteArray(1 shl 16); while (z.read(b) >= 0) {}; while (d.read(b) >= 0) {}
                s
            }
        }
        println("extracted in %.1fs: files=${st.files} dirs=${st.dirs} symlinks=${st.symlinks} hardlinks=${st.hardlinks} skipped=${st.skipped} bytes=${st.bytes}".format((System.nanoTime() - t0) / 1e9))
        assertEquals(System.getenv("HATCH_ROOTFS_SHA"), md.digest().joinToString("") { "%02x".format(it) })
        listOf("usr/bin/Xwayland", "usr/bin/lsof", "etc/os-release", "home/hatch/Downloads", "usr/bin/bash").forEach { assertTrue(it, File(dest, it).exists()) }
        assertTrue(File(dest, "etc/passwd").readText().contains("hatch:x:1000:1000"))
        assertEquals(0, st.skipped)
    }
}
