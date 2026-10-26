package dev.hatch.floating

/**
 * Who may hold keyboard/IME focus (pure; unit-tested). Focus follows the user's last touch: a guest window that
 * maps or redraws (Wine makes new windows all the time) must not pull focus back from the store after the user
 * touched the store, the bubble or another app (DEVICE-VERIFY #20: "doom" typed into Notepad++).
 */
object FocusRules {
    /**
     * May a window of [appId], opened at [openedAt], take key focus by itself (on bind / when the app asks for text)?
     * Yes if the user last touched that app's window, or touched nothing non-guest since the window opened
     * (a freshly launched app gets the keyboard).
     */
    fun mayAutoFocus(appId: String, isLastTouched: Boolean, lastTouchedApp: String?, nonGuestTouchAt: Long, openedAt: Long): Boolean =
        when {
            isLastTouched -> true
            lastTouchedApp != null -> lastTouchedApp == appId   // the app's own dialog, opened from its main window
            else -> nonGuestTouchAt < openedAt
        }
}
