package dev.hatch.ui

import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import dev.hatch.HatchApp
import dev.hatch.guest.AppSession
import dev.hatch.guest.LinuxRuntime
import dev.hatch.store.InstalledApps
import dev.hatch.wayland.Native
import dev.hatch.wayland.Toplevel

class WindowActivity : ComponentActivity(), SurfaceHolder.Callback {
    private var appId: String = ""
    private var label: String = "App"
    private var surfaceView: SurfaceView? = null
    private var toplevel: Toplevel? = null
    private var target: Long = 0
    private var surfaceW = 0
    private var surfaceH = 0
    private var started = false
    private var lastX = 0
    private var lastY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getStringExtra(EXTRA_APP_ID)
            ?: intent.data?.lastPathSegment
            ?: "unknown"
        label = intent.getStringExtra(EXTRA_LABEL)
            ?: InstalledApps.get(this, appId)?.name
            ?: appId
        if (Build.VERSION.SDK_INT >= 33) {
            setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(label).build())
        } else {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(label))
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sv = object : SurfaceView(this) {
            override fun onCheckIsTextEditor(): Boolean = true
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
                outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                return null // keys still arrive via onKey*; soft keyboard visibility handled below
            }
        }
        surfaceView = sv
        sv.isFocusable = true
        sv.isFocusableInTouchMode = true
        sv.holder.addCallback(this)
        setContentView(sv)
        sv.requestFocus()

        val rt = HatchApp.instance.runtime
        rt.ensureStarted()
        rt.onWindowAttached(appId) { w -> runOnUiThread { bindToplevel(w) } }
        rt.onWindowAttached("") { w ->
            if (toplevel == null) runOnUiThread { bindToplevel(w) }
        }

        maybeStartGuest()
        syncClipboardToGuest()
    }

    private fun maybeStartGuest() {
        if (started) return
        if (!LinuxRuntime.isInstalled(this)) {
            Log.w(TAG, "runtime not installed")
            return
        }
        val rec = InstalledApps.get(this, appId)
        val cmd = rec?.command ?: intent.getStringArrayListExtra(EXTRA_COMMAND)?.toList()
        if (cmd.isNullOrEmpty()) {
            Log.w(TAG, "no command for $appId")
            return
        }
        started = true
        val waylandDir = HatchApp.instance.runtime.socketDir().absolutePath
        val env = buildMap {
            if (rec?.wine == true) put("WINEDEBUG", "-all")
        }
        val ok = AppSession.start(this, appId, cmd, waylandDir, extraEnv = env)
        Log.i(TAG, "spawn $appId ok=$ok cmd=$cmd")
    }

    private fun bindToplevel(w: Toplevel) {
        toplevel = w
        rebindTarget()
        HatchApp.instance.runtime.server?.seat?.focusWindow(w)
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
        Log.i(TAG, "bound target ${surfaceW}x${surfaceH}")
    }

    private fun syncClipboardToGuest() {
        val server = HatchApp.instance.runtime.server ?: return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        if (!text.isNullOrEmpty()) {
            server.post { server.clipboard.setFromHost(text) }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width; surfaceH = height
        rebindTarget()
        maybeStartGuest()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        toplevel?.detachTarget()
        if (target != 0L) { Native.targetRelease(target); target = 0 }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = toplevel ?: return super.onTouchEvent(event)
        val server = HatchApp.instance.runtime.server ?: return super.onTouchEvent(event)
        val x = event.x.toInt(); val y = event.y.toInt()
        lastX = x; lastY = y
        server.post {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    server.seat.focusWindow(w)
                    server.seat.pointerMotion(w, x, y)
                    server.seat.pointerButton(w, 0x110 /* BTN_LEFT */, true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // two-finger → right click
                    if (event.pointerCount >= 2) {
                        server.seat.pointerButton(w, 0x111 /* BTN_RIGHT */, true)
                        server.seat.pointerButton(w, 0x111, false)
                    }
                }
                MotionEvent.ACTION_MOVE -> server.seat.pointerMotion(w, x, y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    server.seat.pointerButton(w, 0x110, false)
            }
        }
        // scroll
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            server.post {
                if (v != 0f) server.seat.pointerScroll(w, 0.0, (-v * 10).toDouble())
                if (h != 0f) server.seat.pointerScroll(w, (h * 10).toDouble(), 0.0)
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            return onTouchEvent(event)
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val server = HatchApp.instance.runtime.server ?: return super.onKeyDown(keyCode, event)
        val w = toplevel
        // Ctrl+V / Ctrl+C bridge
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_V) {
            syncClipboardToGuest()
        }
        if (w != null) {
            val sc = if (event.scanCode != 0) event.scanCode else androidScanToLinux(keyCode)
            server.post { server.seat.key(w, sc, true) }
        }
        // Offer soft keyboard on KEYCODE_UNKNOWN from IME
        if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(surfaceView, InputMethodManager.SHOW_IMPLICIT)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        val server = HatchApp.instance.runtime.server ?: return super.onKeyUp(keyCode, event)
        val w = toplevel
        if (w != null) {
            val sc = if (event.scanCode != 0) event.scanCode else androidScanToLinux(keyCode)
            server.post { server.seat.key(w, sc, false) }
        }
        // Copy-out: when guest offers selection, Android clipboard is updated via Clipboard listener
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) syncClipboardToGuest()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "configChanged smallest=${newConfig.smallestScreenWidthDp}dp")
    }

    override fun onDestroy() {
        HatchApp.instance.runtime.onWindowDetached(appId)
        HatchApp.instance.runtime.onWindowDetached("")
        toplevel?.detachTarget()
        if (target != 0L) { Native.targetRelease(target); target = 0 }
        if (isFinishing) AppSession.stop(appId)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP_ID = "appId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_COMMAND = "command"
        private const val TAG = "HatchWindow"

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
            else -> 0
        }
    }
}
