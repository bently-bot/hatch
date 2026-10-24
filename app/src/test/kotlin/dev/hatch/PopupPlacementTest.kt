package dev.hatch

import dev.hatch.wayland.PopupPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupPlacementTest {
    // GTK menubar item "File" at (6,30,36,20): anchor bottom_left(6), gravity bottom_right(8)
    @Test fun menuDropsBelowItem() {
        assertEquals(6 to 50, PopupPlacement.place(6, 30, 36, 20, 6, 8, 0, 0, 240, 300))
    }
    @Test fun centredDefault() {
        assertEquals(50 to 50, PopupPlacement.place(100, 100, 0, 0, 0, 0, 0, 0, 100, 100))
    }
    @Test fun flipsAboveWhenNoRoomBelow() {
        // anchor near bottom of an 800-high window, 300-high menu, flip_y allowed
        assertEquals(10 to 450, PopupPlacement.constrain(10, 770, 200, 300, 1280, 800, 10, 750, 30, 20, 8))
    }
    @Test fun slidesInsideWindow() {
        assertEquals(1080 to 0, PopupPlacement.constrain(1200, -5, 200, 100, 1280, 800, 0, 0, 0, 0, 0))
    }
}
