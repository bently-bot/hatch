package dev.hatch

import dev.hatch.floating.FocusRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DEVICE-VERIFY #20: keyboard focus must follow the last touch, not the last window a guest mapped. */
class FocusRulesTest {
    @Test fun freshlyLaunchedWindowGetsTheKeyboard() =
        assertTrue(FocusRules.mayAutoFocus("mousepad", false, null, nonGuestTouchAt = 100, openedAt = 200))   // tapped Open, then window

    @Test fun windowMappedAfterTheStoreWasTouchedDoesNotStealFocus() =
        // Notepad++ typed in, then the store's search was tapped; Wine maps/rebinds a window afterwards.
        assertFalse(FocusRules.mayAutoFocus("notepadpp", false, null, nonGuestTouchAt = 500, openedAt = 200))

    @Test fun lastTouchedWindowKeepsIt() =
        assertTrue(FocusRules.mayAutoFocus("notepadpp", true, "notepadpp", nonGuestTouchAt = 500, openedAt = 200))

    @Test fun appsOwnDialogMayTakeIt() =
        assertTrue(FocusRules.mayAutoFocus("notepadpp", false, "notepadpp", nonGuestTouchAt = 100, openedAt = 900))

    @Test fun anotherAppsWindowMayNot() =
        assertFalse(FocusRules.mayAutoFocus("mousepad", false, "notepadpp", nonGuestTouchAt = 100, openedAt = 900))
}
