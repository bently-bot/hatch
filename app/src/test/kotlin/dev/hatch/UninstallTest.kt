package dev.hatch

import dev.hatch.store.BasePackages
import dev.hatch.store.StoreApp
import dev.hatch.store.Uninstall
import dev.hatch.store.WindowsPayload
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class UninstallTest {
    private fun app(id: String, pk: List<String>, wine: Boolean = false) = StoreApp(id = id, name = id, summary = "", source = "debian", packages = pk, command = listOf(id), wine = wine)

    private fun steam() = StoreApp(id = "steam", name = "Steam", summary = "", source = "vendor", command = listOf("hatch-steam"),
        packages = listOf("xwayland", "curl", "libnss3", "libudev1", "libdbus-1-3", "pciutils"))

    @Test fun keepsWhatOtherInstalledAppsList() {
        val blender = app("blender", listOf("blender", "xwayland", "libgl1"))
        val xterm = app("xterm-ish", listOf("foo", "xwayland"))
        assertEquals(listOf("foo", "xwayland"), Uninstall.keepPackages(blender, listOf(blender, xterm)))
        assertEquals(emptyList<String>(), Uninstall.keepPackages(blender, listOf(blender)))
    }

    @Test fun keepsWineWhileAnotherWindowsAppRemains() {
        val npp = app("notepadpp", listOf("nppextra"), wine = true)
        val other = app("other", emptyList(), wine = true)
        assertEquals(listOf("fonts-wine", "libwine", "wine", "wine64"), Uninstall.keepPackages(npp, listOf(npp, other)))
        assertEquals(emptyList<String>(), Uninstall.keepPackages(npp, listOf(npp)))
        assertEquals(listOf("nppextra", "wine", "wine64", "libwine", "fonts-wine"), Uninstall.fallbackPackages(npp))
    }

    @Test fun flatpaksArePurgedByFlatpakNotApt() {
        val d = StoreApp(id = "x", name = "x", summary = "", source = "flathub", flatpak = "org.x.X", command = listOf("x"))
        assertEquals(emptyList<String>(), Uninstall.fallbackPackages(d))
    }

    /** The "Couldn't uninstall Steam" bug: the command must not tell apt to remove libudev1 & co. itself. */
    @Test fun steamCommandLetsTheGuestDecide() {
        val blender = app("blender", listOf("blender", "xwayland"))
        val c = Uninstall.command(steam(), listOf(steam(), blender))
        assertTrue(c, c.startsWith("/usr/local/bin/hatch-uninstall-app --id 'steam' "))
        assertFalse("no apt purge from the app side", "apt-get" in c)
        assertTrue(c, "--packages 'xwayland curl libnss3 libudev1 libdbus-1-3 pciutils'" in c)   // 0.2.0 fallback only
        assertTrue(c, "--keep 'blender xwayland'" in c)
        assertTrue(c, "--dirs '~/.local/share/Steam ~/.steam'" in c)
        assertTrue(c, c.endsWith(" 2>&1"))
        assertTrue(Uninstall.command(steam(), emptyList(), dryRun = true).endsWith("--dry-run 2>&1"))
    }

    @Test fun steamDataGoesWithIt() {
        assertEquals(listOf("~/.local/share/Steam", "~/.steam"), Uninstall.dataDirs(steam()))
        assertEquals(emptyList<String>(), Uninstall.dataDirs(app("blender", listOf("blender"))))
    }

    @Test fun exitCodes() {
        assertEquals(Uninstall.Outcome.OK, Uninstall.outcome(0))
        assertEquals(Uninstall.Outcome.WARNING, Uninstall.outcome(10))
        assertEquals(Uninstall.Outcome.FAILED, Uninstall.outcome(2))
        assertEquals(Uninstall.Outcome.FAILED, Uninstall.outcome(null))
        assertFalse(Uninstall.warningText("Steam").contains("Couldn't uninstall"))
    }

    @Test fun payloadDirsOnlyUnderOptHatch() {
        val a = StoreApp(id = "h", name = "h", summary = "", source = "release", command = listOf("h"),
            windowsPayload = WindowsPayload("u", "s", "/opt/hatch/h"), payloads = mapOf("amd64" to WindowsPayload("u", "s", "/etc")))
        assertEquals(listOf("/opt/hatch/h"), Uninstall.payloadDirs(a))
    }

    @Test fun parsesFreedSpace() {
        assertEquals(29_646L * 1024, Uninstall.parseFreedKb("hatch: steam: 17 package(s) it added\nhatch: frees-kb 29646\n"))
        assertNull(Uninstall.parseFreedKb("nothing"))
    }

    /** The guest's base list covers the rootfs build list (uninstall never removes any of those). */
    @Test fun guestBaseListCoversTheRootfsBuildList() {
        val f = listOf(File("src/main/assets/guest-bin/hatch-base-packages"), File("app/src/main/assets/guest-bin/hatch-base-packages")).first { it.exists() }
        val guest = f.readText().substringAfter("<<'EOF'").substringAfter("\n").substringBefore("\nEOF").split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        val missing = BasePackages.LIST - guest
        assertTrue("missing from hatch-base-packages: $missing", missing.isEmpty())
        assertTrue(guest.containsAll(listOf("apt", "util-linux", "libudev1", "libapt-pkg7.0", "dpkg", "libdbus-1-3")))
    }

    @Test fun basePackagesMatchTheRootfsBuildList() {
        val f = listOf(File("rootfs/base-packages.txt"), File("../rootfs/base-packages.txt")).first { it.exists() }
        val fromFile = f.readLines().filter { !it.trimStart().startsWith("#") }.flatMap { it.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() }.toSet()
        assertEquals(fromFile, BasePackages.LIST)
    }
}
