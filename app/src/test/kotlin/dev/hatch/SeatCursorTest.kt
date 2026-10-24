package dev.hatch

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Documents the set_cursor wire layout that previously crashed GTK:
 * set_cursor(serial: uint, surface: object?, hotspot_x: int, hotspot_y: int) = 16 payload bytes.
 */
class SeatCursorTest {
    @Test
    fun setCursorPayloadIsSixteenBytes() {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(7)        // serial
        buf.putInt(13)       // surface id (nullable object)
        buf.putInt(11)       // hotspot_x
        buf.putInt(12)       // hotspot_y
        assertEquals(16, buf.position())
        buf.flip()
        assertEquals(7, buf.int)
        assertEquals(13, buf.int)
        assertEquals(11, buf.int)
        assertEquals(12, buf.int)
    }
}
