package dev.hatch

import android.view.KeyEvent
import dev.hatch.ui.WindowActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMapTest {
    @Test
    fun lettersMapToLinuxScancodes() {
        assertEquals(30, WindowActivity.androidScanToLinux(KeyEvent.KEYCODE_A))
        assertEquals(28, WindowActivity.androidScanToLinux(KeyEvent.KEYCODE_ENTER))
        assertEquals(57, WindowActivity.androidScanToLinux(KeyEvent.KEYCODE_SPACE))
        assertTrue(WindowActivity.androidScanToLinux(KeyEvent.KEYCODE_UNKNOWN) == 0)
    }
}
