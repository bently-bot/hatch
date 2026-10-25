package dev.hatch

import dev.hatch.guest.GuestScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestScriptsTest {
    @Test fun wineLaunchGoesThroughHatchLaunch() {
        val argv = GuestScripts.launchArgv(listOf("wine64", "/opt/hatch/npp/notepad++.exe"), wine = true, x11 = false)
        assertEquals(listOf("/usr/local/bin/hatch-launch", "--wine", "--", "wine64", "/opt/hatch/npp/notepad++.exe"), argv)
    }

    @Test fun installCommandQuotesPayload() {
        val c = GuestScripts.installCommand(listOf("a", "b"), wine = true, payloadUrl = "https://x/y z.zip", payloadSha = "ab", payloadDest = "/opt/x")
        assertTrue(c, c.startsWith("/usr/local/bin/hatch-install-app --packages 'a b' --wine --payload 'https://x/y z.zip' 'ab' '/opt/x'"))
    }

    @Test fun installCommandAddsVendorRepo() {
        val repo = dev.hatch.store.AptRepo("cursor", "https://downloads.cursor.com/keys/anysphere.asc", "https://downloads.cursor.com/aptrepo", "stable")
        val c = GuestScripts.installCommand(listOf("cursor"), wine = false, repo = repo)
        assertTrue(c, c.startsWith("/usr/local/bin/hatch-install-app --packages 'cursor' --repo 'cursor' 'https://downloads.cursor.com/keys/anysphere.asc' 'https://downloads.cursor.com/aptrepo' 'stable' 'main'"))
    }

    @Test fun installCommandRecordsWhatTheAppAdds() {
        val c = GuestScripts.installCommand(listOf("a"), wine = false, id = "steam")
        assertTrue(c, c.startsWith("/usr/local/bin/hatch-install-app --id 'steam' --packages 'a'"))
    }

    @Test fun assetsShipScripts() {
        val dir = java.io.File("src/main/assets/guest-bin")
        for (n in listOf("hatch-launch", "hatch-install-app", "hatch-uninstall-app", "hatch-base-packages", "hatch-xwayland", "hatch-x11")) {
            assertTrue("missing $n", java.io.File(dir, n).exists())
        }
    }
}

class RootfsCatalogTest {
    @Test fun picksNewestBuildAndSha() {
        val html = """<a href="20261020_05%3A24/">20261020_05:24/</a> <a>20261022_05:24/</a> <a>20261021_06:02/</a>"""
        assertEquals("20261022_05:24", dev.hatch.guest.RootfsCatalog.latestBuild(html))
        val sums = "aaaa  meta.tar.xz\n" + "b".repeat(64) + "  rootfs.tar.xz\n"
        assertEquals("b".repeat(64), dev.hatch.guest.RootfsCatalog.shaFromSums(sums))
        assertEquals("amd64", dev.hatch.guest.RootfsCatalog.lxcArch(arrayOf("x86_64", "arm64-v8a")))
        assertEquals("arm64", dev.hatch.guest.RootfsCatalog.lxcArch(arrayOf("arm64-v8a")))
    }
}

class GuestUserTest {
    @Test fun addsUserOnce() {
        val p = "root:x:0:0:root:/root:/bin/bash\n"
        val once = dev.hatch.guest.GuestScripts.withUser("passwd", p)
        org.junit.Assert.assertTrue(once.endsWith("hatch:x:1000:1000:Hatch:/home/hatch:/bin/bash\n"))
        org.junit.Assert.assertEquals(once, dev.hatch.guest.GuestScripts.withUser("passwd", once))
        org.junit.Assert.assertEquals("a\nhatch:x:1000:\n", dev.hatch.guest.GuestScripts.withUser("group", "a"))
    }
}

class OutputScaleTest {
    @Test fun scales() {
        org.junit.Assert.assertEquals(2, dev.hatch.runtime.outputScale(2.625f))
        org.junit.Assert.assertEquals(1, dev.hatch.runtime.outputScale(1.0f))
        org.junit.Assert.assertEquals(3, dev.hatch.runtime.outputScale(4.0f))
        org.junit.Assert.assertEquals(1, dev.hatch.runtime.outputScale(2.625f, 1))
    }
}
