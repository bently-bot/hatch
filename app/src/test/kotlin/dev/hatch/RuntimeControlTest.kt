package dev.hatch

import dev.hatch.runtime.RuntimeControl
import dev.hatch.runtime.RuntimeControl.Proc
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeControlTest {
    @Test fun orphansAreGuestProcessesOfOurUidOnly() {
        val procs = listOf(
            Proc(100, 10698, "dev.hatch"),            // us
            Proc(101, 10698, "dev.hatch:remote"),     // another of our Android processes
            Proc(200, 10698, "/data/app/x/lib/arm64/libproot.so"),
            Proc(201, 10698, "/usr/bin/Xwayland"),
            Proc(202, 10698, "wineserver"),
            Proc(300, 10111, "/usr/bin/something"),   // another app
            Proc(1, 0, "/init"),
        )
        assertEquals(listOf(200, 201, 202), RuntimeControl.orphans(procs, myPid = 100, myUid = 10698, packageName = "dev.hatch"))
    }

    @Test fun summaryListsRunningApps() {
        assertEquals("No apps running", RuntimeControl.summary(emptyList()))
        assertEquals("1 app running: Blender", RuntimeControl.summary(listOf("Blender")))
        assertEquals("2 apps running: Blender, VS Code", RuntimeControl.summary(listOf("Blender", "VS Code")))
    }
}
