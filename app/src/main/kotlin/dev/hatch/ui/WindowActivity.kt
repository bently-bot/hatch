package dev.hatch.ui

import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.hatch.HatchApp
import dev.hatch.guest.AppSession
import dev.hatch.guest.GuestScripts
import dev.hatch.guest.LinuxRuntime
import dev.hatch.runtime.HatchRuntime
import dev.hatch.store.InstalledApps
import dev.hatch.wayland.Native
import dev.hatch.wayland.Toplevel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One Android window per guest toplevel. The primary instance (launched by the app's stub icon) starts the guest;
 * extra toplevels (second windows, dialogs) open secondary instances. Fold/unfold and freeform resizes are config
 * changes, so neither the Activity nor the guest restarts; we just re-target and send xdg configure.
 */
class WindowActivity : ComponentActivity(), SurfaceHolder.Callback, HatchRuntime.WindowSlot {
    private var appId: String = ""
    private var label: String = "App"
    private var secondary = false
    private var surfaceView: SurfaceView? = null
    private var overlay: View? = null
    private var overlayText: TextView? = null
    @Volatile private var toplevel: Toplevel? = null
    private var target: Long = 0
    private var surfaceW = 0
    private var surfaceH = 0
    private var started = false
    private val rt get() = HatchApp.instance.runtime

    override val boundWindow: Toplevel? get() = toplevel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getStringExtra(EXTRA_APP_ID) ?: intent.data?.lastPathSegment ?: "unknown"
        secondary = intent.getBooleanExtra(EXTRA_SECONDARY, false)
        label = intent.getStringExtra(EXTRA_LABEL) ?: InstalledApps.get(this, appId)?.name ?: appId

        // Tapping the icon of an app that's already running brings its window back instead of starting a 2nd copy.
        if (!secondary && AppSession.isRunning(appId)) {
            val existing = primaryTasks[appId]
            val am = getSystemService(ActivityManager::class.java)
            val task = am?.appTasks?.firstOrNull { it.taskInfo?.taskId == existing }
            if (task != null && existing != taskId) { task.moveToFront(); finish(); return }
        }
        if (!secondary) primaryTasks[appId] = taskId
        setTaskLabel(label); title = label
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sv = object : SurfaceView(this) {
            override fun onCheckIsTextEditor(): Boolean = true
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                // Visible-password + no-suggestions makes IMEs commit every character immediately (no composing).
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_ACTION_NONE
                return GuestInputConnection(this)
            }
        }
        surfaceView = sv
        sv.isFocusable = true
        sv.isFocusableInTouchMode = true
        sv.holder.addCallback(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(0x1b, 0x1b, 0x1f))
        root.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0x1b, 0x1b, 0x1f))
            addView(ProgressBar(context))
            overlayText = TextView(context).apply {
                text = "Starting $label…"; setTextColor(Color.LTGRAY); textSize = 16f; gravity = Gravity.CENTER
                setPadding(32, 24, 32, 0)
            }
            addView(overlayText)
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val kb = TextView(this).apply {
            text = "⌨"; textSize = 22f; setTextColor(Color.WHITE); alpha = 0.55f
            setBackgroundColor(Color.argb(140, 30, 30, 30)); setPadding(28, 12, 28, 12)
            contentDescription = "Keyboard"
            setOnClickListener { toggleKeyboard() }
        }
        root.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 24) })
        setContentView(root)
        sv.requestFocus()

        rt.ensureStarted()
        rt.secondaryLauncher = { id -> launchSecondary(id) }
        rt.register(appId, this)
        maybeStartGuest()
        syncClipboardToGuest()
    }

    private fun setTaskLabel(text: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(text).build())
        } else {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(text))
        }
    }

    private fun launchSecondary(id: String) {
        startActivity(Intent(applicationContext, WindowActivity::class.java).apply {
            action = "dev.hatch.action.WINDOW"
            data = Uri.parse("hatch://window/$id/${SystemClock.uptimeMillis()}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(EXTRA_APP_ID, id)
            putExtra(EXTRA_SECONDARY, true)
            putExtra(EXTRA_LABEL, InstalledApps.get(applicationContext, id)?.name ?: id)
        })
    }

    private fun maybeStartGuest() {
        if (started || secondary) return
        if (AppSession.isRunning(appId)) { started = true; return }
        if (!LinuxRuntime.isInstalled(this)) {
            overlayText?.text = "Hatch isn't set up yet — open Hatch first."
            return
        }
        val rec = InstalledApps.get(this, appId)
        val cmd = rec?.command ?: intent.getStringArrayListExtra(EXTRA_COMMAND)?.toList()
        if (cmd.isNullOrEmpty()) {
            overlayText?.text = "$label isn't installed."
            return
        }
        started = true
        val waylandDir = rt.ensureAppSocket(appId).absolutePath
        val argv = GuestScripts.launchArgv(cmd, wine = rec?.wine == true, x11 = rec?.x11 == true)
        if (rec?.wine == true) overlayText?.text = "Starting $label…\n(first launch prepares Windows support — this can take a minute)"
        val gpu = dev.hatch.gpu.Gpu.run { useGpu(mode(this@WindowActivity), info(this@WindowActivity, LinuxRuntime.rootfsDir(this@WindowActivity))) }
        val ok = AppSession.start(this, appId, argv, waylandDir, extraEnv = dev.hatch.gpu.Gpu.env(gpu), onExit = { rt.guestExited(appId) })
        Log.i(TAG, "rendering for $appId: ${if (gpu) "GPU (Turnip/KGSL + Zink)" else "software"}")
        Log.i(TAG, "spawn $appId ok=$ok argv=$argv")
        if (!ok) overlayText?.text = "Couldn't start $label."
    }

    // ---- WindowSlot ----
    override fun bind(w: Toplevel) = runOnUiThread {
        toplevel = w
        overlay?.visibility = View.GONE
        if (w.title.isNotEmpty() && secondary) setTaskLabel(w.title)
        rebindTarget()
        rt.server?.post { rt.server?.seat?.focusWindow(w) }
    }

    override fun windowGone(w: Toplevel) = runOnUiThread {
        if (toplevel !== w) return@runOnUiThread
        toplevel = null
        if (target != 0L) { Native.targetRelease(target); target = 0 }
        if (secondary) { finishAndRemoveTask(); return@runOnUiThread }
        // Primary: the app may open its real window next (e.g. Wine's setup dialog closes, then the app appears).
        overlay?.visibility = View.VISIBLE
        overlayText?.text = "Waiting for $label…"
        if (!rt.claimFor(appId, this)) rt.server?.post {} // stays until guestExited
    }

    override fun guestExited() = runOnUiThread {
        Log.i(TAG, "guest for $appId exited")
        if (toplevel == null) finishAndRemoveTask()
    }

    override fun textInput(enabled: Boolean) = runOnUiThread {
        val imm = getSystemService(InputMethodManager::class.java) ?: return@runOnUiThread
        if (enabled) { surfaceView?.requestFocus(); imm.showSoftInput(surfaceView, 0) }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        surfaceView?.requestFocus()
        if (!imm.showSoftInput(surfaceView, InputMethodManager.SHOW_FORCED)) imm.hideSoftInputFromWindow(surfaceView?.windowToken, 0)
    }

    private fun rebindTarget() {
        val w = toplevel ?: return
        val surf = surfaceView?.holder?.surface ?: return
        if (!surf.isValid || surfaceW <= 0 || surfaceH <= 0) return
        val t = Native.targetForSurface(surf, surfaceW, surfaceH)
        if (t == 0L) { Log.e(TAG, "targetForSurface failed"); return }
        if (target != 0L && target != t) Native.targetRelease(target)
        target = t
        w.attachTarget(t, surfaceW, surfaceH)
        Log.i(TAG, "bound target ${surfaceW}x${surfaceH} for '${w.title}'")
    }

    private fun syncClipboardToGuest() {
        val server = rt.server ?: return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        if (!text.isNullOrEmpty()) server.post { server.clipboard.setFromHost(text) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width; surfaceH = height
        rebindTarget()
        maybeStartGuest()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        toplevel?.detachTarget()
        target = 0 // released by detachTarget on the server thread
    }

    // ---- Input ----
    private fun post(block: (dev.hatch.wayland.WaylandServer, Toplevel) -> Unit) {
        val s = rt.server ?: return; val w = toplevel ?: return
        s.post { block(s, w) }
    }

    /** Touch: 1 finger = left button drag; long-press = right click; 2 fingers = scroll, 2-finger tap = right click. */
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private var leftDown = false; private var twoFinger = false; private var twoMoved = 0f
    private var lastScrollY = 0f; private var lastScrollX = 0f; private var longPressFired = false
    private val longPress = Runnable {
        if (leftDown && !twoFinger) {
            longPressFired = true; leftDown = false
            post { s, w -> s.seat.pointerButton(w, 0x110, false); s.seat.pointerButton(w, 0x111, true); s.seat.pointerButton(w, 0x111, false) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (toplevel == null) return super.onTouchEvent(event)
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(event)
        val x = event.x.toInt(); val y = event.y.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = event.eventTime
                twoFinger = false; longPressFired = false; leftDown = true
                post { s, w -> s.seat.pointerMotion(w, x, y); s.seat.pointerButton(w, 0x110, true) }
                surfaceView?.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                surfaceView?.removeCallbacks(longPress)
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
                    if (hypot(event.x - downX, event.y - downY) > 24) surfaceView?.removeCallbacks(longPress)
                    post { s, w -> s.seat.pointerMotion(w, x, y) }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (twoFinger && event.pointerCount == 2 && twoMoved < 30) {
                post { s, w -> s.seat.pointerButton(w, 0x111, true); s.seat.pointerButton(w, 0x111, false) }
                twoMoved = 1000f // one right-click per gesture
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                surfaceView?.removeCallbacks(longPress)
                if (leftDown) { leftDown = false; post { s, w -> s.seat.pointerMotion(w, x, y); s.seat.pointerButton(w, 0x110, false) } }
                twoFinger = false
            }
        }
        return true
    }

    /** Real mouse / trackpad (DeX, Bluetooth): hover, all buttons, wheel. */
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
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.source and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD)
            return super.onKeyDown(keyCode, event)
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_V) syncClipboardToGuest()
        val sc = linuxCode(event); if (sc == 0) return super.onKeyDown(keyCode, event)
        post { s, w -> s.seat.key(w, sc, true) }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.source and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD)
            return super.onKeyUp(keyCode, event)
        val sc = linuxCode(event); if (sc == 0) return super.onKeyUp(keyCode, event)
        post { s, w -> s.seat.key(w, sc, false) }
        return true
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        // IMEs / `input text` sometimes deliver whole strings as ACTION_MULTIPLE
        val chars = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        post { s, w -> s.seat.typeText(w, chars) }
        return true
    }

    private fun linuxCode(e: KeyEvent): Int {
        // Hardware keyboards report evdev scancodes; soft keyboards/injected keys don't.
        if (e.scanCode in 1..0x2ff && e.deviceId > 0 && e.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            !e.isVirtualHardKey()) return e.scanCode
        return androidScanToLinux(e.keyCode)
    }
    private fun KeyEvent.isVirtualHardKey() = flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0

    /** Soft-keyboard text → guest. */
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            rt.focusedSlot = this
            syncClipboardToGuest()
            toplevel?.let { w -> rt.server?.post { rt.server?.seat?.focusWindow(w) } }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "configChanged ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp — session kept")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back = ask the program to close this window (it may prompt to save), like a desktop close button.
        val w = toplevel
        if (w != null) rt.server?.post { w.xdg?.sendClose() } else super.onBackPressed()
    }

    override fun onDestroy() {
        rt.unregister(appId, this)
        val w = toplevel
        toplevel = null
        w?.detachTarget()
        if (isFinishing && w != null) {
            // User closed the Android window: close the program window gracefully.
            rt.server?.post { w.xdg?.sendClose() }
        }
        if (isFinishing && !secondary) {
            primaryTasks.remove(appId)
            // If nothing reopens a window soon, stop the guest so it doesn't linger in the background.
            val id = appId; val ctx = applicationContext
            android.os.Handler(mainLooper).postDelayed({
                if (!rt.hasSlots(id) && rt.windowsOf(id).isEmpty()) AppSession.stop(id)
            }, 20_000)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP_ID = "appId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SECONDARY = "secondary"
        private const val TAG = "HatchWindow"
        private val primaryTasks = java.util.concurrent.ConcurrentHashMap<String, Int>()

        /** Rough Android keyCode → Linux KEY_* for when scanCode is 0 (software keyboard). */
        fun androidScanToLinux(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_A -> 30; KeyEvent.KEYCODE_B -> 48; KeyEvent.KEYCODE_C -> 46
            KeyEvent.KEYCODE_D -> 32; KeyEvent.KEYCODE_E -> 18; KeyEvent.KEYCODE_F -> 33
            KeyEvent.KEYCODE_G -> 34; KeyEvent.KEYCODE_H -> 35; KeyEvent.KEYCODE_I -> 23
            KeyEvent.KEYCODE_J -> 36; KeyEvent.KEYCODE_K -> 37; KeyEvent.KEYCODE_L -> 38
            KeyEvent.KEYCODE_M -> 50; KeyEvent.KEYCODE_N -> 49; KeyEvent.KEYCODE_O -> 24
            KeyEvent.KEYCODE_P -> 25; KeyEvent.KEYCODE_Q -> 16; KeyEvent.KEYCODE_R -> 19
            KeyEvent.KEYCODE_S -> 31; KeyEvent.KEYCODE_T -> 20; KeyEvent.KEYCODE_U -> 22
            KeyEvent.KEYCODE_V -> 47; KeyEvent.KEYCODE_W -> 17; KeyEvent.KEYCODE_X -> 45
            KeyEvent.KEYCODE_Y -> 21; KeyEvent.KEYCODE_Z -> 44
            KeyEvent.KEYCODE_1 -> 2; KeyEvent.KEYCODE_2 -> 3; KeyEvent.KEYCODE_3 -> 4
            KeyEvent.KEYCODE_4 -> 5; KeyEvent.KEYCODE_5 -> 6; KeyEvent.KEYCODE_6 -> 7
            KeyEvent.KEYCODE_7 -> 8; KeyEvent.KEYCODE_8 -> 9; KeyEvent.KEYCODE_9 -> 10
            KeyEvent.KEYCODE_0 -> 11
            KeyEvent.KEYCODE_SPACE -> 57; KeyEvent.KEYCODE_ENTER -> 28
            KeyEvent.KEYCODE_DEL -> 14; KeyEvent.KEYCODE_FORWARD_DEL -> 111
            KeyEvent.KEYCODE_TAB -> 15; KeyEvent.KEYCODE_ESCAPE -> 1
            KeyEvent.KEYCODE_DPAD_LEFT -> 105; KeyEvent.KEYCODE_DPAD_RIGHT -> 106
            KeyEvent.KEYCODE_DPAD_UP -> 103; KeyEvent.KEYCODE_DPAD_DOWN -> 108
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 42
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 29
            KeyEvent.KEYCODE_ALT_LEFT -> 56; KeyEvent.KEYCODE_ALT_RIGHT -> 100
            KeyEvent.KEYCODE_MINUS -> 12; KeyEvent.KEYCODE_EQUALS -> 13
            KeyEvent.KEYCODE_LEFT_BRACKET -> 26; KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
            KeyEvent.KEYCODE_SEMICOLON -> 39; KeyEvent.KEYCODE_APOSTROPHE -> 40
            KeyEvent.KEYCODE_COMMA -> 51; KeyEvent.KEYCODE_PERIOD -> 52
            KeyEvent.KEYCODE_SLASH -> 53; KeyEvent.KEYCODE_BACKSLASH -> 43
            KeyEvent.KEYCODE_GRAVE -> 41
            KeyEvent.KEYCODE_PAGE_UP -> 104; KeyEvent.KEYCODE_PAGE_DOWN -> 109
            KeyEvent.KEYCODE_MOVE_HOME -> 102; KeyEvent.KEYCODE_MOVE_END -> 107; KeyEvent.KEYCODE_INSERT -> 110
            KeyEvent.KEYCODE_F1 -> 59; KeyEvent.KEYCODE_F2 -> 60; KeyEvent.KEYCODE_F3 -> 61; KeyEvent.KEYCODE_F4 -> 62
            KeyEvent.KEYCODE_F5 -> 63; KeyEvent.KEYCODE_F6 -> 64; KeyEvent.KEYCODE_F7 -> 65; KeyEvent.KEYCODE_F8 -> 66
            KeyEvent.KEYCODE_F9 -> 67; KeyEvent.KEYCODE_F10 -> 68; KeyEvent.KEYCODE_F11 -> 87; KeyEvent.KEYCODE_F12 -> 88
            KeyEvent.KEYCODE_META_LEFT -> 125; KeyEvent.KEYCODE_META_RIGHT -> 126; KeyEvent.KEYCODE_CAPS_LOCK -> 58
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 96; KeyEvent.KEYCODE_BACK -> 1
            else -> 0
        }
    }
}
