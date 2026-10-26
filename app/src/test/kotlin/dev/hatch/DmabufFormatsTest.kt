package dev.hatch

import dev.hatch.wayland.DmabufFormats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DmabufFormatsTest {
    @Test fun fourccs() {
        assertEquals(0x34325241, DmabufFormats.ARGB8888)
        assertEquals(0x34325258, DmabufFormats.XRGB8888)
        assertEquals(0x34324241, DmabufFormats.ABGR8888)
        assertEquals(0x34324258, DmabufFormats.XBGR8888)
    }

    @Test fun copyFormatMapsToShmCodes() {
        assertEquals(0, DmabufFormats.copyFormat(DmabufFormats.ARGB8888))
        assertEquals(1, DmabufFormats.copyFormat(DmabufFormats.XRGB8888))
        assertEquals(DmabufFormats.ABGR8888, DmabufFormats.copyFormat(DmabufFormats.ABGR8888))
        assertEquals(-1, DmabufFormats.copyFormat(0x3231564e)) // NV12
    }

    @Test fun tableIsSixteenByteLinearEntries() {
        val t = ByteBuffer.wrap(DmabufFormats.table()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(DmabufFormats.formats.size * 16, t.capacity())
        DmabufFormats.formats.forEachIndexed { i, f ->
            assertEquals(f, t.getInt(i * 16)); assertEquals(0, t.getInt(i * 16 + 4)); assertEquals(0L, t.getLong(i * 16 + 8))
        }
        val idx = ByteBuffer.wrap(DmabufFormats.indices()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(DmabufFormats.formats.size * 2, idx.capacity())
        DmabufFormats.formats.indices.forEach { assertEquals(it.toShort(), idx.getShort(it * 2)) }
    }

    @Test fun devTIsLittleEndianU64() {
        val b = DmabufFormats.devT(0xe200L)
        assertEquals(8, b.size); assertEquals(0x00.toByte(), b[0]); assertEquals(0xe2.toByte(), b[1])
    }

    @Test fun createValidation() {
        val x = DmabufFormats.XRGB8888
        assertNull(DmabufFormats.check(256, 192, x, 1, 1024, 0, 0))
        assertNotNull(DmabufFormats.check(256, 192, x, 1, 1024, 0, 0x0100000000000001L)) // Qualcomm tiled
        assertNotNull(DmabufFormats.check(256, 192, x, 1, 1024, 0, DmabufFormats.MOD_INVALID))
        assertNotNull(DmabufFormats.check(256, 192, x, 2, 1024, 0, 0))
        assertNotNull(DmabufFormats.check(256, 192, x, 1, 1000, 0, 0))
        assertNotNull(DmabufFormats.check(0, 192, x, 1, 1024, 0, 0))
        assertNotNull(DmabufFormats.check(256, 192, 0x3231564e, 1, 1024, 0, 0))
    }
}
