package dev.hatch

import android.view.KeyEvent
import dev.hatch.ui.GamepadKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadKeysTest {
    @Test fun doomPreset() {
        assertEquals(GamepadKeys.CTRL, GamepadKeys.map("doom", KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GamepadKeys.SPACE, GamepadKeys.map("doom", KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(GamepadKeys.SHIFT, GamepadKeys.map("doom", KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(GamepadKeys.TAB, GamepadKeys.map("doom", KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(GamepadKeys.ESC, GamepadKeys.map("doom", KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(GamepadKeys.ENTER, GamepadKeys.map("doom", KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(GamepadKeys.UP, GamepadKeys.map("doom", KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun defaultPresetNeverFallsToBack() {
        assertEquals(GamepadKeys.ENTER, GamepadKeys.map("", KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GamepadKeys.ESC, GamepadKeys.map("", KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test fun sticks() {
        assertEquals(setOf(GamepadKeys.UP, GamepadKeys.LEFT), GamepadKeys.axes(-0.9f, -0.8f, 0f, 0f))
        assertEquals(emptySet<Int>(), GamepadKeys.axes(0.2f, -0.3f, 0f, 0f))
        assertEquals(setOf(GamepadKeys.RIGHT), GamepadKeys.axes(0f, 0f, 1f, 0f))
    }
}
