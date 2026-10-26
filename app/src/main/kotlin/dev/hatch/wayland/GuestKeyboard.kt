package dev.hatch.wayland

/**
 * Turns Android key events and IME edits into Linux key presses and text for one guest window (pure; unit-tested).
 *
 * Why it exists (the "first capital letter" glitch):
 *  - IMEs send some characters as synthetic KeyEvents whose Shift lives only in `metaState` (no Shift key event).
 *    Forwarding just the key code typed "h" for "H". Missing modifiers are now pressed around the key.
 *  - Samsung Keyboard (and others, for the auto-capitalised first letter) *compose* text even in a no-suggestions
 *    field. BaseInputConnection in non-editor mode turns composing text into key events itself, and the later
 *    commit typed it again ("HHello"). Composing text is now diffed: only what changed is typed, once.
 *  - Android auto-repeats held modifier keys too; Hatch's host-side repeat (release+press) toggled Shift mid-word.
 *    Modifier repeats are ignored, and keys are only released if they were pressed (no stray Shift-up).
 */
class GuestKeyboard(private val out: Sink) {
    interface Sink {
        fun key(code: Int, pressed: Boolean)
        fun text(t: String)
    }

    private val held = LinkedHashSet<Int>()
    var composing = ""; private set

    fun isHeld(code: Int) = code in held

    fun keyDown(code: Int, meta: Int, repeatCount: Int) {
        if (code <= 0) return
        if (isModifier(code)) {
            if (repeatCount > 0 || code in held) return
            held += code; out.key(code, true); return
        }
        if (repeatCount > 0 && code in held) { out.key(code, false); out.key(code, true); return }   // host-side repeat
        if (code in held) out.key(code, false)
        val extra = missingModifiers(meta)
        for (m in extra) out.key(m, true)
        held += code; out.key(code, true)
        for (m in extra.asReversed()) out.key(m, false)
    }

    fun keyUp(code: Int) {
        if (!held.remove(code)) return
        out.key(code, false)
    }

    /** Linux modifier keys the event's metaState says are down but which we haven't pressed (IME key events). */
    fun missingModifiers(meta: Int): List<Int> = buildList {
        if (meta and META_SHIFT_ON != 0 && SHIFT.none { it in held }) add(KeyChars.SHIFT)
        if (meta and META_CTRL_ON != 0 && CTRL.none { it in held }) add(29)
        if (meta and META_ALT_ON != 0 && ALT.none { it in held }) add(56)
    }

    // ---- IME ----
    fun setComposing(text: String) { replace(text); composing = text }
    fun commit(text: String) { replace(text); composing = "" }
    fun finishComposing() { composing = "" }
    fun deleteBefore(n: Int) { composing = ""; repeat(n.coerceAtMost(64)) { tap(BACKSPACE) } }

    /** Type the difference between what's composed now and [text]. */
    private fun replace(text: String) {
        val p = commonPrefix(composing, text)
        repeat((composing.length - p).coerceAtMost(256)) { tap(BACKSPACE) }
        if (p < text.length) out.text(text.substring(p))
    }

    /** Focus left the window: release everything still down, so no Shift/Ctrl stays stuck in the guest. */
    fun reset() {
        for (c in held.toList().asReversed()) out.key(c, false)
        held.clear(); composing = ""
    }

    private fun tap(code: Int) { out.key(code, true); out.key(code, false) }

    companion object {
        const val META_SHIFT_ON = 0x1      // android.view.KeyEvent constants
        const val META_ALT_ON = 0x2
        const val META_CTRL_ON = 0x1000
        const val BACKSPACE = 14
        private val SHIFT = setOf(42, 54); private val CTRL = setOf(29, 97); private val ALT = setOf(56, 100)
        fun isModifier(code: Int) = code in SHIFT || code in CTRL || code in ALT || code == 125 || code == 126 || code == 58
        fun commonPrefix(a: String, b: String): Int {
            var i = 0; val n = minOf(a.length, b.length)
            while (i < n && a[i] == b[i]) i++
            if (i > 0 && i < a.length && Character.isHighSurrogate(a[i - 1])) i--   // never split a surrogate pair
            return i
        }
    }
}
