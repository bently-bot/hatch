package dev.hatch.ui

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Game-controller → keyboard mapping (Linux evdev codes) for apps whose catalogue entry sets `controls`.
 * "doom" matches crispy-doom/chocolate-doom defaults: arrows move/turn, Ctrl fires, Space opens doors, Shift runs,
 * , / . strafe, Tab = automap, Escape = menu, Enter = confirm. Any other app gets a menu-friendly default
 * (D-pad → arrows, A → Enter, B → Escape) so a controller never falls through to Android's Back.
 */
object GamepadKeys {
    const val UP = 103; const val DOWN = 108; const val LEFT = 105; const val RIGHT = 106
    const val CTRL = 29; const val SPACE = 57; const val SHIFT = 42; const val TAB = 15; const val ESC = 1; const val ENTER = 28
    const val COMMA = 51; const val DOT = 52; const val Y_KEY = 21

    fun isGamepad(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD && source and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD)

    fun map(preset: String, keyCode: Int): Int {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> return UP
            KeyEvent.KEYCODE_DPAD_DOWN -> return DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> return LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> return RIGHT
            KeyEvent.KEYCODE_BUTTON_START -> return ESC
        }
        return if (preset == "doom") when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2 -> CTRL         // fire
            KeyEvent.KEYCODE_BUTTON_B -> SPACE                                     // use / open
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_L2 -> SHIFT         // run
            KeyEvent.KEYCODE_BUTTON_Y -> TAB                                       // automap
            KeyEvent.KEYCODE_BUTTON_L1 -> COMMA                                    // strafe left
            KeyEvent.KEYCODE_BUTTON_R1 -> DOT                                      // strafe right
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DPAD_CENTER -> ENTER
            else -> 0
        } else when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> ENTER
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_SELECT -> ESC
            KeyEvent.KEYCODE_BUTTON_X -> SPACE
            KeyEvent.KEYCODE_BUTTON_Y -> TAB
            else -> 0
        }
    }

    /** Keys held by a stick/hat position (left stick or hat → arrows; 0.5 deadzone). */
    fun axes(x: Float, y: Float, hatX: Float, hatY: Float): Set<Int> = buildSet {
        val dx = if (kotlin.math.abs(hatX) > 0.5f) hatX else x
        val dy = if (kotlin.math.abs(hatY) > 0.5f) hatY else y
        if (dx < -0.5f) add(LEFT); if (dx > 0.5f) add(RIGHT)
        if (dy < -0.5f) add(UP); if (dy > 0.5f) add(DOWN)
    }
}
