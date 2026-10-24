package dev.hatch.wayland

/** US-layout character → (Linux KEY_* code, needs Shift). Used to type IME/`input text` commits as key presses. */
object KeyChars {
    const val SHIFT = 42
    private val plain = HashMap<Char, Int>()
    private val shifted = HashMap<Char, Int>()
    init {
        val rows = listOf(
            "1234567890-=" to intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13),
            "qwertyuiop[]" to intArrayOf(16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27),
            "asdfghjkl;'`" to intArrayOf(30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41),
            "\\zxcvbnm,./" to intArrayOf(43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53),
        )
        val shiftRows = listOf("!@#$%^&*()_+", "QWERTYUIOP{}", "ASDFGHJKL:\"~", "|ZXCVBNM<>?")
        for ((i, r) in rows.withIndex()) {
            r.first.forEachIndexed { j, c -> plain[c] = r.second[j] }
            shiftRows[i].forEachIndexed { j, c -> shifted[c] = r.second[j] }
        }
        plain[' '] = 57; plain['\n'] = 28; plain['\t'] = 15
    }

    /** null when the char has no key on a US layout (send via text-input instead). */
    fun forChar(c: Char): Pair<Int, Boolean>? = plain[c]?.let { it to false } ?: shifted[c]?.let { it to true }
}

/** Tracks xkb modifier masks (evdev "pc" keymap) from raw key presses. */
class ModState {
    var depressed = 0; private set
    var locked = 0; private set
    /** @return true when the modifier state changed. */
    fun update(code: Int, pressed: Boolean): Boolean {
        val before = depressed to locked
        val bit = when (code) {
            42, 54 -> 1          // Shift
            29, 97 -> 4          // Control
            56 -> 8              // Mod1 (Alt)
            125, 126 -> 64       // Mod4 (Super)
            100 -> 128           // Mod5 (AltGr / ISO_Level3)
            else -> 0
        }
        if (bit != 0) depressed = if (pressed) depressed or bit else depressed and bit.inv()
        if (code == 58 && pressed) locked = locked xor 2   // Caps Lock
        if (code == 69 && pressed) locked = locked xor 16  // Num Lock (Mod2)
        return before != (depressed to locked)
    }
}
