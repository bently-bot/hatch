package dev.hatch.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.hatch.HatchApp
import dev.hatch.wayland.Native
import dev.hatch.wayland.Toplevel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Shows one Wayland toplevel and feeds it input: touch (tap/drag = left button, long-press / 2-finger tap = right,
 * 2-finger drag = scroll), real mouse, hardware keys and the soft keyboard. Hosted by WindowActivity (task-per-window
 * mode) and by FloatingWindow (overlay mode).
 */
@SuppressLint("ViewConstructor")
class GuestSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val rt get() = HatchApp.instance.runtime
    @Volatile var toplevel: Toplevel? = null
        private set
    /** Catalogue `controls` preset ("doom"…) for game controllers; see [GamepadKeys]. */
    var controls: String = ""
    private val stickHeld = HashSet<Int>()

    /** Press/release one Linux key in the guest window (touch pad, controllers). */
    fun sendKey(code: Int, pressed: Boolean) = post { s, w -> s.seat.key(w, code, pressed) }
    private var target: Long = 0
    var surfaceW = 0; private set
    var surfaceH = 0; private set
    /** Called whenever the surface gets a (new) size; hosts start the guest on the first one. */
    var onSurfaceSize: ((Int, Int) -> Unit)? = null
    /** Called on the first touch so overlay hosts can take key focus. */
    var onTouchDown: (() -> Unit)? = null

    init {
        isFocusable = true; isFocusableInTouchMode = true
        holder.addCallback(this)
    }

    fun bind(w: Toplevel) {
        toplevel = w
        rebindTarget()
        rt.server?.post { rt.server?.seat?.focusWindow(w) }
    }

    /** Forget the toplevel (it was destroyed); releases our render target. */
    fun unbind(w: Toplevel): Boolean {
        if (toplevel !== w) return false
        toplevel = null
        // Toplevel.destroyWindow() already released the target via detachTarget() on the compositor thread;
        // releasing it here too was a double free that aborted Hatch whenever a guest closed a window (e.g. wineboot's dialog).
        target = 0
        return true
    }

    /** Host is going away: detach without destroying the guest window. */
    fun detach(): Toplevel? {
        val w = toplevel; toplevel = null
        w?.detachTarget(); target = 0
        return w
    }

    fun focusGuest() {
        val w = toplevel ?: return
        rt.server?.post { rt.server?.seat?.focusWindow(w) }
    }

    private fun rebindTarget() {
        val w = toplevel ?: return
        val surf = holder.surface ?: return
        if (!surf.isValid || surfaceW <= 0 || surfaceH <= 0) return
        val t = Native.targetForSurface(surf, surfaceW, surfaceH)
        if (t == 0L) { Log.e(TAG, "targetForSurface failed"); return }
        // The Toplevel releases the previous target on the compositor thread once it stops drawing into it;
        // releasing it here as well was a double free that killed Hatch mid-resize.
        target = t
        w.attachTarget(t, surfaceW, surfaceH)
        Log.i(TAG, "bound target ${surfaceW}x${surfaceH} for '${w.title}'")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width; surfaceH = height
        rebindTarget()
        onSurfaceSize?.invoke(width, height)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        toplevel?.detachTarget()
        target = 0 // released by detachTarget on the server thread
    }

    fun syncClipboardToGuest() {
        val server = rt.server ?: return
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        if (!text.isNullOrEmpty()) server.post { server.clipboard.setFromHost(text) }
    }

    fun showKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        requestFocus(); imm.showSoftInput(this, 0)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        requestFocus()
        if (!imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)) imm.hideSoftInputFromWindow(windowToken, 0)
    }

    // ---- Input ----
    private fun post(block: (dev.hatch.wayland.WaylandServer, Toplevel) -> Unit) {
        val s = rt.server ?: return; val w = toplevel ?: return
        s.post { block(s, w) }
    }

    private var downX = 0f; private var downY = 0f
    private var leftDown = false; private var twoFinger = false; private var twoMoved = 0f
    private var lastScrollY = 0f; private var lastScrollX = 0f; private var longPressFired = false
    private val longPress = Runnable {
        if (leftDown && !twoFinger) {
            longPressFired = true; leftDown = false
            post { s, w -> s.seat.pointerButton(w, 0x110, false); s.seat.pointerButton(w, 0x111, true); s.seat.pointerButton(w, 0x111, false) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    /** Uptime of the last finger tap in this view: the IME auto-shows only right after the user taps a text field. */
    var lastTouchAt = 0L; private set
    val recentlyTouched get() = android.os.SystemClock.uptimeMillis() - lastTouchAt < 1500

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchDown?.invoke()
        if (event.actionMasked == MotionEvent.ACTION_UP && !event.isFromSource(InputDevice.SOURCE_MOUSE))
            lastTouchAt = android.os.SystemClock.uptimeMillis()
        if (toplevel == null) return super.onTouchEvent(event)
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(event)
        val x = event.x.toInt(); val y = event.y.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                twoFinger = false; longPressFired = false; leftDown = true
                if (!hasFocus()) requestFocus()
                post { s, w -> s.seat.pointerMotion(w, x, y); s.seat.pointerButton(w, 0x110, true) }
                postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                removeCallbacks(longPress)
                twoFinger = true; twoMoved = 0f
                lastScrollY = (event.getY(0) + event.getY(1)) / 2; lastScrollX = (event.getX(0) + event.getX(1)) / 2
                if (leftDown) { leftDown = false; post { s, w -> s.seat.pointerButton(w, 0x110, false) } }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && event.pointerCount >= 2) {
                    val my = (event.getY(0) + event.getY(1)) / 2; val mx = (event.getX(0) + event.getX(1)) / 2
                    val dy = lastScrollY - my; val dx = lastScrollX - mx
                    twoMoved += abs(dy) + abs(dx)
                    lastScrollY = my; lastScrollX = mx
                    val sc = (rt.server?.scale ?: 1).toDouble()
                    post { s, w -> s.seat.pointerScroll(w, dx / sc, dy / sc) }
                } else if (!twoFinger && !longPressFired) {
                    if (hypot(event.x - downX, event.y - downY) > 24) removeCallbacks(longPress)
                    post { s, w -> s.seat.pointerMotion(w, x, y) }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (twoFinger && event.pointerCount == 2 && twoMoved < 30) {
                post { s, w -> s.seat.pointerButton(w, 0x111, true); s.seat.pointerButton(w, 0x111, false) }
                twoMoved = 1000f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (leftDown) { leftDown = false; post { s, w -> s.seat.pointerMotion(w, x, y); s.seat.pointerButton(w, 0x110, false) } }
                twoFinger = false
            }
        }
        return true
    }

    private fun onMouse(event: MotionEvent): Boolean {
        val x = event.x.toInt(); val y = event.y.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_ENTER ->
                post { s, w -> s.seat.pointerMotion(w, x, y) }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val btn = when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> 0x111; MotionEvent.BUTTON_TERTIARY -> 0x112
                    MotionEvent.BUTTON_BACK -> 0x116; MotionEvent.BUTTON_FORWARD -> 0x115; else -> 0x110
                }
                val pressed = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                post { s, w -> s.seat.pointerMotion(w, x, y); s.seat.pointerButton(w, btn, pressed) }
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL); val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                post { s, w -> s.seat.pointerScroll(w, (h * 15).toDouble(), (-v * 15).toDouble()) }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (toplevel != null && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return onMouse(event)
        if (toplevel != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val want = GamepadKeys.axes(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
            for (k in stickHeld - want) sendKey(k, false)
            for (k in want - stickHeld) sendKey(k, true)
            stickHeld.clear(); stickHeld.addAll(want)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun isNavBack(keyCode: Int, e: KeyEvent) =
        keyCode == KeyEvent.KEYCODE_BACK && e.source and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isNavBack(keyCode, event) || toplevel == null) return super.onKeyDown(keyCode, event)
        if (GamepadKeys.isGamepad(event.source)) GamepadKeys.map(controls, keyCode).takeIf { it != 0 }?.let { c ->
            if (event.repeatCount == 0) sendKey(c, true); return true
        }
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_V) syncClipboardToGuest()
        val sc = linuxCode(event); if (sc == 0) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) post { s, w -> s.seat.key(w, sc, false); s.seat.key(w, sc, true) } // host-side repeat
        else post { s, w -> s.seat.key(w, sc, true) }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isNavBack(keyCode, event) || toplevel == null) return super.onKeyUp(keyCode, event)
        if (GamepadKeys.isGamepad(event.source)) GamepadKeys.map(controls, keyCode).takeIf { it != 0 }?.let { c -> sendKey(c, false); return true }
        val sc = linuxCode(event); if (sc == 0) return super.onKeyUp(keyCode, event)
        post { s, w -> s.seat.key(w, sc, false) }
        return true
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val chars = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        post { s, w -> s.seat.typeText(w, chars) }
        return true
    }

    private fun linuxCode(e: KeyEvent): Int {
        if (e.scanCode in 1..0x2ff && e.deviceId > 0 && e.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            e.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY == 0) return e.scanCode
        return WindowActivity.androidScanToLinux(e.keyCode)
    }

    override fun onCheckIsTextEditor(): Boolean = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password + no-suggestions makes IMEs commit every character immediately (no composing).
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return GuestInputConnection(this)
    }

    private inner class GuestInputConnection(v: View) : BaseInputConnection(v, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val t = text?.toString().orEmpty()
            if (t.isNotEmpty()) post { s, w -> s.seat.typeText(w, t) }
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            post { s, w ->
                repeat(beforeLength.coerceAtMost(64)) { s.seat.key(w, 14, true); s.seat.key(w, 14, false) }
                repeat(afterLength.coerceAtMost(64)) { s.seat.key(w, 111, true); s.seat.key(w, 111, false) }
            }
            return true
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
            else if (event.action == KeyEvent.ACTION_UP) onKeyUp(event.keyCode, event)
            return true
        }
        override fun performEditorAction(actionCode: Int): Boolean {
            post { s, w -> s.seat.key(w, 28, true); s.seat.key(w, 28, false) }
            return true
        }
    }

    companion object { private const val TAG = "HatchWindow" }
}
