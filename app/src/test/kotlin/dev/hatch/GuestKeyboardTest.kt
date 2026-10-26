package dev.hatch

import android.view.KeyEvent
import dev.hatch.wayland.GuestKeyboard
import dev.hatch.wayland.KeyChars
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for the "first capital letter" glitch (lowercase / doubled / missing Shift) and key repeat. */
class GuestKeyboardTest {
    private val log = mutableListOf<String>()
    private val kb = GuestKeyboard(object : GuestKeyboard.Sink {
        override fun key(code: Int, pressed: Boolean) { log += (if (pressed) "+" else "-") + code }
        override fun text(t: String) { log += "\"$t\"" }
    })
    private val H = 35; private val SHIFT = KeyChars.SHIFT

    /** What the guest would see typed, given US layout: letters from key presses + Shift state, plus text events. */
    private fun typed(): String {
        val sb = StringBuilder(); var shift = 0
        for (e in log) when {
            e.startsWith("\"") -> sb.append(e.trim('"'))
            e == "+$SHIFT" || e == "+54" -> shift++
            e == "-$SHIFT" || e == "-54" -> shift--
            e == "+${KeyChars.forChar('h')!!.first}" -> sb.append(if (shift > 0) 'H' else 'h')
            e == "+${KeyChars.forChar('i')!!.first}" -> sb.append(if (shift > 0) 'I' else 'i')
            e == "+14" -> if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    @Test fun constantsMatchAndroid() {
        assertEquals(KeyEvent.META_SHIFT_ON, GuestKeyboard.META_SHIFT_ON)
        assertEquals(KeyEvent.META_ALT_ON, GuestKeyboard.META_ALT_ON)
        assertEquals(KeyEvent.META_CTRL_ON, GuestKeyboard.META_CTRL_ON)
    }

    @Test fun imeKeyEventWithShiftOnlyInMetaState() {
        // Gboard-style: KEYCODE_H down/up with META_SHIFT_ON and no Shift key events. Used to type "h".
        kb.keyDown(H, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON, 0); kb.keyUp(H)
        assertEquals(listOf("+42", "+35", "-42", "-35"), log)
        assertEquals("H", typed())
    }

    @Test fun hardwareShiftIsNotDoubled() {
        kb.keyDown(SHIFT, KeyEvent.META_SHIFT_ON, 0)
        kb.keyDown(H, KeyEvent.META_SHIFT_ON, 0); kb.keyUp(H)
        kb.keyUp(SHIFT)
        assertEquals(listOf("+42", "+35", "-35", "-42"), log)
        assertEquals("H", typed())
    }

    @Test fun composingFirstCapitalIsTypedOnce() {
        // Samsung Keyboard: auto-capitalised first letter arrives as composing text, then the word is committed.
        kb.setComposing("H"); kb.setComposing("Hi"); kb.commit("Hi")
        assertEquals("Hi", typed())
        assertEquals(listOf("\"H\"", "\"i\""), log)   // nothing re-typed on commit
    }

    @Test fun composingCorrectionsUseBackspace() {
        kb.setComposing("Hu"); kb.setComposing("Hi")       // IME replaced a letter
        assertEquals("Hi", typed())
        kb.commit("Hi "); assertEquals("Hi ", typed())
        kb.finishComposing(); kb.commit("x"); assertEquals("Hi x", typed())
    }

    @Test fun finishComposingKeepsWhatWasTyped() {
        kb.setComposing("Hello"); kb.finishComposing()
        assertEquals(listOf("\"Hello\""), log)
    }

    @Test fun modifierAutoRepeatIsIgnored() {
        kb.keyDown(SHIFT, KeyEvent.META_SHIFT_ON, 0)
        repeat(5) { kb.keyDown(SHIFT, KeyEvent.META_SHIFT_ON, it + 1) }   // Android repeats a held Shift
        kb.keyDown(H, KeyEvent.META_SHIFT_ON, 0); kb.keyUp(H); kb.keyUp(SHIFT)
        assertEquals(listOf("+42", "+35", "-35", "-42"), log)
    }

    @Test fun letterRepeatIsHostSide() {
        kb.keyDown(H, 0, 0); kb.keyDown(H, 0, 1); kb.keyDown(H, 0, 2); kb.keyUp(H)
        assertEquals(listOf("+35", "-35", "+35", "-35", "+35", "-35"), log)
        assertEquals("hhh", typed())
    }

    @Test fun strayKeyUpIsDropped() {
        kb.keyUp(SHIFT); kb.keyUp(H)
        assertEquals(emptyList<String>(), log)
    }

    @Test fun resetReleasesHeldKeys() {
        kb.keyDown(SHIFT, KeyEvent.META_SHIFT_ON, 0); kb.keyDown(29, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, 0)
        log.clear(); kb.reset()
        assertEquals(listOf("-29", "-42"), log)
        log.clear(); kb.keyDown(H, 0, 0)
        assertEquals(listOf("+35"), log)   // no Shift left over after focus moved away
    }

    @Test fun ctrlFromMeta() {
        kb.keyDown(46, KeyEvent.META_CTRL_ON, 0)   // IME Ctrl+C
        assertEquals(listOf("+29", "+46", "-29"), log)
    }

    @Test fun surrogatePairsAreNotSplit() {
        kb.setComposing("a😀"); kb.setComposing("a😃")
        assertEquals(listOf("\"a😀\"", "+14", "-14", "+14", "-14", "\"😃\""), log)
    }
}
