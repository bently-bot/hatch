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

    @Test fun assetsShipScripts() {
        val dir = java.io.File("src/main/assets/guest-bin")
        for (n in listOf("hatch-launch", "hatch-install-app", "hatch-xwayland", "hatch-x11")) {
            assertTrue("missing $n", java.io.File(dir, n).exists())
        }
    }
}
