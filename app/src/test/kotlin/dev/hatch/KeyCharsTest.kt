package dev.hatch

import dev.hatch.wayland.KeyChars
import dev.hatch.wayland.ModState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCharsTest {
    @Test fun lettersAndShift() {
        assertEquals(35 to false, KeyChars.forChar('h'))
        assertEquals(35 to true, KeyChars.forChar('H'))
        assertEquals(2 to true, KeyChars.forChar('!'))
        assertEquals(57 to false, KeyChars.forChar(' '))
        assertEquals(40 to true, KeyChars.forChar('"'))
        assertNull(KeyChars.forChar('é'))
    }
    @Test fun modifiers() {
        val m = ModState()
        assertTrue(m.update(29, true)); assertEquals(4, m.depressed)
        assertTrue(m.update(42, true)); assertEquals(5, m.depressed)
        assertTrue(m.update(29, false)); assertEquals(1, m.depressed)
        assertTrue(m.update(58, true)); assertEquals(2, m.locked)
        assertEquals(false, m.update(30, true))
    }
}
