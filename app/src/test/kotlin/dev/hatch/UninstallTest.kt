package dev.hatch

import dev.hatch.store.BasePackages
import dev.hatch.store.StoreApp
import dev.hatch.store.Uninstall
import dev.hatch.store.WindowsPayload
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UninstallTest {
    private fun app(id: String, pk: List<String>, wine: Boolean = false) = StoreApp(id = id, name = id, summary = "", source = "debian", packages = pk, command = listOf(id), wine = wine)

    @Test fun keepsPackagesOtherAppsAndTheBaseNeed() {
        val blender = app("blender", listOf("blender", "xwayland", "libgl1"))
        val xterm = app("xterm-ish", listOf("foo", "xwayland"))
        assertEquals(listOf("blender"), Uninstall.aptTargets(blender, listOf(blender, xterm)))
    }

    @Test fun keepsWineWhileAnotherWindowsAppRemains() {
        val npp = app("notepadpp", listOf("wine", "wine64", "nppextra"), wine = true)
        val other = app("other", emptyList(), wine = true)
        assertEquals(listOf("nppextra"), Uninstall.aptTargets(npp, listOf(npp, other)))
        assertEquals(listOf("wine", "wine64", "nppextra"), Uninstall.aptTargets(npp, listOf(npp)))
    }

    @Test fun flatpaksArePurgedByFlatpakNotApt() {
        val d = StoreApp(id = "x", name = "x", summary = "", source = "flathub", flatpak = "org.x.X", command = listOf("x"))
        assertEquals(emptyList<String>(), Uninstall.aptTargets(d, listOf(d)))
    }

    @Test fun payloadDirsOnlyUnderOptHatch() {
        val a = StoreApp(id = "h", name = "h", summary = "", source = "release", command = listOf("h"),
            windowsPayload = WindowsPayload("u", "s", "/opt/hatch/h"), payloads = mapOf("amd64" to WindowsPayload("u", "s", "/etc")))
        assertEquals(listOf("/opt/hatch/h"), Uninstall.payloadDirs(a))
    }

    @Test fun parsesAptFreedSpace() {
        assertEquals(512_000_000L, Uninstall.parseAptFreed("0 upgraded, 0 newly installed, 3 to remove\nAfter this operation, 512 MB disk space will be freed.\n"))
        assertEquals(1_200L, Uninstall.parseAptFreed("After this operation, 1,200 B disk space will be freed."))
        assertNull(Uninstall.parseAptFreed("nothing"))
    }

    @Test fun basePackagesMatchTheRootfsBuildList() {
        val f = listOf(File("rootfs/base-packages.txt"), File("../rootfs/base-packages.txt")).first { it.exists() }
        val fromFile = f.readLines().filter { !it.trimStart().startsWith("#") }.flatMap { it.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() }.toSet()
        assertEquals(fromFile, BasePackages.LIST)
    }
}
