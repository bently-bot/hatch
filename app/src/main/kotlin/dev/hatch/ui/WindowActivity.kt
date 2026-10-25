package dev.hatch.ui

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
class WindowActivity : ComponentActivity(), HatchRuntime.WindowSlot {
    private var appId: String = ""
    private var label: String = "App"
    private var secondary = false
    private var surfaceView: GuestSurfaceView? = null
    private var overlay: View? = null
    private var overlayText: TextView? = null
    private var started = false
    private val rt get() = HatchApp.instance.runtime

    override val boundWindow: Toplevel? get() = surfaceView?.toplevel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getStringExtra(EXTRA_APP_ID) ?: intent.data?.lastPathSegment ?: "unknown"
        secondary = intent.getBooleanExtra(EXTRA_SECONDARY, false)
        label = intent.getStringExtra(EXTRA_LABEL) ?: InstalledApps.get(this, appId)?.name ?: appId

        if (!secondary && appId != "unknown") reportRecent(appId, label)
        // "Stop all" from the runtime notification closes full-screen app windows too.
        lifecycleScope.launch { dev.hatch.runtime.RuntimeControl.stopped.collect { finishAndRemoveTask() } }

        // Floating mode (default, like a desktop): the program opens as an overlay window owned by the runtime
        // service, so this Activity is only a trampoline and leaves no Recents card behind.
        if (!secondary && dev.hatch.floating.FloatingWindows.enabled(this)) {
            rt.ensureStarted()
            dev.hatch.floating.FloatingWindows.launch(applicationContext, appId, label,
                intent.getStringArrayListExtra(EXTRA_COMMAND)?.toList())
            finishAndRemoveTask(); overridePendingTransition(0, 0)
            return
        }

        // Floating is the default, but the overlay grant is missing: ask for it rather than silently opening full screen.
        val fw = dev.hatch.floating.FloatingWindows
        if (!secondary && fw.mode(this) == fw.MODE_FLOATING && !fw.canOverlay(this) &&
            !getSharedPreferences("hatch", MODE_PRIVATE).getBoolean("overlay_asked", false)) {
            getSharedPreferences("hatch", MODE_PRIVATE).edit().putBoolean("overlay_asked", true).apply()
            android.widget.Toast.makeText(this, "Allow Hatch to display over other apps, then open $label again: it will float in its own window.",
                android.widget.Toast.LENGTH_LONG).show()
            startActivity(fw.overlayPermissionIntent(this))
            finishAndRemoveTask(); overridePendingTransition(0, 0)
            return
        }

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

        val sv = GuestSurfaceView(this)
        surfaceView = sv
        sv.onSurfaceSize = { _, _ -> maybeStartGuest() }

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
            setOnClickListener { sv.toggleKeyboard() }
        }
        root.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 24) })
        setContentView(root)
        sv.requestFocus()

        rt.ensureStarted()
        rt.secondaryLauncher = { id -> dev.hatch.floating.FloatingWindows.openSecondary(applicationContext, id) }
        rt.register(appId, this)
        maybeStartGuest()
        sv.syncClipboardToGuest()
    }

    private fun setTaskLabel(text: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(text).build())
        } else {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(text))
        }
    }

    private fun maybeStartGuest() {
        if (started || secondary) return
        if (AppSession.isRunning(appId)) { started = true; return }
        when (val r = AppLauncher.startGuest(this, appId, label, intent.getStringArrayListExtra(EXTRA_COMMAND)?.toList())) {
            is AppLauncher.Result.Error -> overlayText?.text = r.message
            else -> {
                started = true
                if (AppLauncher.isWine(this, appId))
                    overlayText?.text = "Starting $label…\n(first launch prepares Windows support — this can take a minute)"
            }
        }
    }

    // ---- WindowSlot ----
    override fun bind(w: Toplevel) = runOnUiThread {
        overlay?.visibility = View.GONE
        if (w.title.isNotEmpty() && secondary) setTaskLabel(w.title)
        surfaceView?.bind(w)
    }

    override fun windowGone(w: Toplevel) = runOnUiThread {
        if (surfaceView?.unbind(w) != true) return@runOnUiThread
        if (secondary) { finishAndRemoveTask(); return@runOnUiThread }
        // Primary: the app may open its real window next (e.g. Wine's setup dialog closes, then the app appears).
        overlay?.visibility = View.VISIBLE
        overlayText?.text = "Waiting for $label…"
        rt.claimFor(appId, this)
    }

    override fun guestExited() = runOnUiThread {
        Log.i(TAG, "guest for $appId exited")
        if (boundWindow == null) finishAndRemoveTask()
    }

    override fun textInput(enabled: Boolean) = runOnUiThread { if (enabled && surfaceView?.recentlyTouched == true) surfaceView?.showKeyboard() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            rt.focusedSlot = this
            surfaceView?.syncClipboardToGuest()
            surfaceView?.focusGuest()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "configChanged ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp — session kept")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back = ask the program to close this window (it may prompt to save), like a desktop close button.
        val w = boundWindow
        if (w != null) rt.server?.post { w.xdg?.sendClose() } else super.onBackPressed()
    }

    override fun onDestroy() {
        if (surfaceView == null) { super.onDestroy(); return } // floating-mode trampoline
        rt.unregister(appId, this)
        val w = surfaceView?.detach()
        if (isFinishing && w != null) rt.server?.post { w.xdg?.sendClose() }
        if (isFinishing && !secondary) {
            primaryTasks.remove(appId)
            // If nothing reopens a window soon, stop the guest so it doesn't linger in the background.
            val id = appId
            android.os.Handler(mainLooper).postDelayed({
                if (!rt.hasSlots(id) && rt.windowsOf(id).isEmpty()) AppSession.stop(id)
            }, 20_000)
        }
        super.onDestroy()
    }

    /** Recent apps as dynamic shortcuts on Hatch's own icon (off the main thread: icon decode + ShortcutManager IPC). */
    private fun reportRecent(id: String, name: String) {
        val ctx = applicationContext
        Thread {
            val app = runCatching { dev.hatch.store.Catalogue.load(ctx).firstOrNull { it.id == id } }.getOrNull()
            val icon = app?.let { runCatching { dev.hatch.store.AppIcons.png(ctx, it) }.getOrNull() }
            dev.hatch.launcher.Launchers.reportUsed(ctx, id, name, icon)
        }.start()
    }

    companion object {
        const val EXTRA_APP_ID = "appId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SECONDARY = "secondary"
        private const val TAG = "HatchWindow"
        private val primaryTasks = java.util.concurrent.ConcurrentHashMap<String, Int>()

        /** Task-per-window mode: a 2nd toplevel of [id] (dialog, extra document) gets its own Activity/task. */
        fun launchSecondary(ctx: android.content.Context, id: String) {
            ctx.startActivity(Intent(ctx, WindowActivity::class.java).apply {
                action = "dev.hatch.action.WINDOW"
                data = Uri.parse("hatch://window/$id/${SystemClock.uptimeMillis()}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(EXTRA_APP_ID, id)
                putExtra(EXTRA_SECONDARY, true)
                putExtra(EXTRA_LABEL, InstalledApps.get(ctx, id)?.name ?: id)
            })
        }

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
