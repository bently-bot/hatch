package dev.hatch

import dev.hatch.guest.FlatpakSpec
import dev.hatch.guest.GuestScripts
import org.junit.Assert.*
import org.junit.Test

class FlatpakSpecTest {
    private val text = """
        root /var/lib/hatch/flatpak/org.x.App.root
        bind /var/lib/flatpak/runtime/org.gnome.Platform/aarch64/51/active/files:/usr
        bind /var/lib/flatpak/app/org.x.App/current/active/files:/app
        env PATH=/.hatch/bin:/app/bin:/usr/bin
        env XDG_DATA_DIRS=/a
        env XDG_DATA_DIRS=/app/share:/usr/share
        env WEIRD=a=b
        cmd app --flag
    """.trimIndent()

    @Test fun parsesAndBuildsArgv() {
        val s = FlatpakSpec.parse(text)!!
        assertEquals(listOf("app", "--flag"), s.command)
        val args = s.prootArgs("/data/linux", listOf("/proc", "/dev"), 1000, "/home/hatch")
        assertEquals(listOf("-i", "1000:1000", "-l", "-r", "/data/linux/var/lib/hatch/flatpak/org.x.App.root"), args.take(5))
        assertTrue(args.containsAll(listOf("-b", "/proc", "/data/linux/var/lib/flatpak/app/org.x.App/current/active/files:/app")))
        assertEquals(listOf("-w", "/home/hatch", "app", "--flag"), args.takeLast(4))
    }

    @Test fun laterEnvWinsAndKeepsBase() {
        val env = FlatpakSpec.parse(text)!!.environment(mapOf("HOME" to "/home/hatch", "PATH" to "/usr/bin"))
        assertEquals("/home/hatch", env["HOME"])
        assertEquals("/.hatch/bin:/app/bin:/usr/bin", env["PATH"])
        assertEquals("/app/share:/usr/share", env["XDG_DATA_DIRS"])
        assertEquals("a=b", env["WEIRD"])
    }

    @Test fun rejectsIncomplete() { assertNull(FlatpakSpec.parse("bind /a:/b\n")) }

    @Test fun installCommandUsesFlatpak() {
        assertEquals("/usr/local/bin/hatch-flatpak install 'org.x.App' 2>&1",
            GuestScripts.installCommand(emptyList(), wine = false, flatpak = "org.x.App"))
    }
}
