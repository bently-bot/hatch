package dev.hatch.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.hatch.HatchApp
import dev.hatch.guest.AppSession
import dev.hatch.runtime.HatchRuntime
import dev.hatch.ui.GuestSurfaceView
import dev.hatch.wayland.Toplevel
import kotlin.math.abs

/**
 * One program window floating above everything (TYPE_APPLICATION_OVERLAY), styled like Android freeform windows:
 * borderless card with a shadow; a grey pill handle on top — drag it to move, pinch it to resize, tap it for the
 * control bar (minimise to bubble, maximise/restore, close); drag the bottom corners to resize.
 * Key focus is taken on touch and handed back to the app underneath on an outside touch or Back.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class FloatingWindow(
    private val ctx: Context,
    val appId: String,
    val label: String,
    private val primary: Boolean,
    cascade: Int,
) : HatchRuntime.WindowSlot {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val rt get() = HatchApp.instance.runtime
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private val shadow = dp(10f)
    private val handleH = dp(26f)
    private val minW = dp(220f); private val minH = dp(160f)
    private val sv = GuestSurfaceView(ctx)
    private val statusBox: LinearLayout
    private val statusText: TextView
    private val controls: LinearLayout
    private val root: FrameLayout
    private val lp: WindowManager.LayoutParams
    private var attached = false
    var isMinimised = false; private set
    private var maximised = false
    private var restoreBounds: Bounds? = null
    private var focusable = false

    override val boundWindow: Toplevel? get() = sv.toplevel

    init {
        val (sw, sh) = screen()
        val b = FloatGeometry.initial(sw, sh, cascade)
        lp = WindowManager.LayoutParams(
            b.w + 2 * shadow, b.h + handleH + 2 * shadow,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.x - shadow; y = b.y - shadow
            title = "Hatch: $label"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(Color.rgb(0x1b, 0x1b, 0x1f)); cornerRadius = dp(14f).toFloat() }
            elevation = dp(8f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(14f).toFloat())
            }
            clipToOutline = true
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val handle = FrameLayout(ctx).apply {
            setBackgroundColor(Color.rgb(0x26, 0x26, 0x2b))
            contentDescription = "Window handle: $label"
            addView(View(ctx).apply {
                background = GradientDrawable().apply { setColor(Color.rgb(0x9a, 0x9a, 0xa2)); cornerRadius = dp(3f).toFloat() }
            }, FrameLayout.LayoutParams(dp(44f), dp(5f), Gravity.CENTER))
        }
        column.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, handleH))
        val content = FrameLayout(ctx)
        content.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        statusText = TextView(ctx).apply {
            text = "Starting $label…"; setTextColor(Color.LTGRAY); textSize = 15f; gravity = Gravity.CENTER; setPadding(24, 16, 24, 0)
        }
        statusBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0x1b, 0x1b, 0x1f))
            addView(ProgressBar(ctx)); addView(statusText)
        }
        content.addView(statusBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.argb(235, 0x33, 0x33, 0x3a)); cornerRadius = dp(18f).toFloat() }
            elevation = dp(6f).toFloat(); setPadding(dp(6f), 0, dp(6f), 0); visibility = View.GONE
            addView(button("—", "Minimise") { minimise() })
            addView(button("⤢", "Maximise") { toggleMaximise() })
            addView(button("⌨", "Keyboard") { takeFocus(); sv.toggleKeyboard() })
            addView(button("✕  Close", "Close") { close() })
        }

        root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.source and android.view.InputDevice.SOURCE_KEYBOARD != android.view.InputDevice.SOURCE_KEYBOARD) {
                    if (event.action == KeyEvent.ACTION_UP) { if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else releaseFocus() }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { releaseFocus(); controls.visibility = View.GONE; return true }
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { FloatingWindows.raise(this@FloatingWindow); takeFocus() }
                return super.dispatchTouchEvent(ev)
            }
        }.apply { setPadding(shadow, shadow, shadow, shadow); clipToPadding = false; clipChildren = false }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = shadow + handleH + dp(4f) })
        root.addView(corner(left = true), FrameLayout.LayoutParams(dp(30f), dp(30f), Gravity.BOTTOM or Gravity.START))
        root.addView(corner(left = false), FrameLayout.LayoutParams(dp(30f), dp(30f), Gravity.BOTTOM or Gravity.END))

        wireHandle(handle)
        sv.onTouchDown = { takeFocus() }
    }

    private fun button(text: String, desc: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; contentDescription = desc; setTextColor(Color.WHITE); textSize = 15f
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f)); setOnClickListener { onClick(); controls.visibility = View.GONE }
    }

    private fun screen(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    private fun bounds() = Bounds(lp.x + shadow, lp.y + shadow, lp.width - 2 * shadow, lp.height - 2 * shadow - handleH)

    private fun apply(b: Bounds) {
        val (sw, sh) = screen()
        val c = FloatGeometry.clamp(Bounds(b.x, b.y, b.w, b.h + handleH), sw, sh, minW, minH + handleH, dp(72f))
        lp.x = c.x - shadow; lp.y = c.y - shadow; lp.width = c.w + 2 * shadow; lp.height = c.h + 2 * shadow
        if (attached) runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun wireHandle(handle: View) {
        var sx = 0f; var sy = 0f; var start = bounds(); var moved = false; var pinching = false
        val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            var base = start; var acc = 1f
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinching = true; base = bounds(); acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor; apply(FloatGeometry.pinch(base, acc, minW, minH)); return true
            }
        })
        handle.setOnTouchListener { _, e ->
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; start = bounds(); moved = false; pinching = false }
                MotionEvent.ACTION_MOVE -> if (!pinching && e.pointerCount == 1) {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (moved || abs(dx) + abs(dy) > dp(6f)) {
                        moved = true; maximised = false
                        apply(Bounds(start.x + dx, start.y + dy, start.w, start.h))
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved && !pinching) {
                    controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    if (controls.visibility == View.VISIBLE) controls.post { logTargets(controls, "control", label) }
                }
            }
            true
        }
    }

    private fun corner(left: Boolean) = View(ctx).apply {
        contentDescription = if (left) "Resize (bottom left)" else "Resize (bottom right)"
        var sx = 0f; var sy = 0f; var start = bounds()
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; start = bounds(); maximised = false }
                MotionEvent.ACTION_MOVE -> apply(FloatGeometry.resizeCorner(start, (e.rawX - sx).toInt(), (e.rawY - sy).toInt(), left, minW, minH))
            }
            true
        }
    }

    // ---- lifecycle ----
    fun show() {
        if (attached) return
        wm.addView(root, lp); attached = true
        Log.i(TAG, "show '$label' at ${lp.x},${lp.y} ${lp.width}x${lp.height}")
    }

    fun bringToFront() {
        if (!attached || isMinimised) return
        runCatching { wm.removeViewImmediate(root); wm.addView(root, lp) }
    }

    fun minimise() {
        if (isMinimised) return
        isMinimised = true; releaseFocus()
        if (attached) { runCatching { wm.removeViewImmediate(root) }; attached = false }
        Bubble.sync(ctx)
        Log.i(TAG, "minimised '$label'")
    }

    fun restore() {
        if (!isMinimised) return
        isMinimised = false; show(); Bubble.sync(ctx)
    }

    fun toggleMaximise() {
        if (maximised) { restoreBounds?.let { apply(it) }; maximised = false; return }
        restoreBounds = bounds()
        val (sw, sh) = screen()
        val top = statusBarHeight()
        apply(Bounds(0, top, sw, sh - top - handleH))
        maximised = true
    }

    private fun statusBarHeight(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(24f)
    }

    fun close() {
        val w = boundWindow
        if (w != null) rt.server?.post { w.xdg?.sendClose() }
        else { if (primary) AppSession.stop(appId); dismiss() }
    }

    private fun dismiss() {
        rt.unregister(appId, this)
        sv.detach()
        if (attached) runCatching { wm.removeViewImmediate(root) }
        attached = false
        FloatingWindows.removed(this, ctx)
        if (primary) {
            val id = appId
            main.postDelayed({ if (!rt.hasSlots(id) && rt.windowsOf(id).isEmpty()) AppSession.stop(id) }, 20_000)
        }
    }

    private fun takeFocus() {
        rt.focusedSlot = this
        if (focusable || !attached) { sv.focusGuest(); return }
        focusable = true
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { wm.updateViewLayout(root, lp) }
        sv.requestFocus(); sv.syncClipboardToGuest(); sv.focusGuest()
    }

    private fun releaseFocus() {
        if (!focusable) return
        focusable = false
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (attached) runCatching { wm.updateViewLayout(root, lp) }
    }

    fun status(msg: String) = main.post { statusBox.visibility = View.VISIBLE; statusText.text = msg }

    // ---- WindowSlot ----
    override fun bind(w: Toplevel) { main.post { statusBox.visibility = View.GONE; sv.bind(w); takeFocus() } }

    override fun windowGone(w: Toplevel) {
        main.post {
            if (!sv.unbind(w)) return@post
            if (!primary) { dismiss(); return@post }
            status("Waiting for $label…")
            if (!rt.claimFor(appId, this)) main.postDelayed({ if (boundWindow == null && !AppSession.isRunning(appId)) dismiss() }, 1500)
        }
    }

    override fun guestExited() { main.post { if (boundWindow == null) dismiss() } }

    override fun textInput(enabled: Boolean) { if (enabled) main.post { takeFocus(); sv.showKeyboard() } }

    companion object {
        private const val TAG = "HatchFloat"
        /** Logs the on-screen centre of each child ("control Minimise at 540 812 for 'Mousepad'"): overlay windows are
         *  invisible to uiautomator, so the emulator E2E taps these like a user would. */
        fun logTargets(group: ViewGroup, kind: String, owner: String) {
            val xy = IntArray(2)
            for (i in 0 until group.childCount) {
                val c = group.getChildAt(i); c.getLocationOnScreen(xy)
                val name = c.contentDescription ?: (c as? android.widget.TextView)?.text ?: continue
                Log.i(TAG, "$kind $name at ${xy[0] + c.width / 2} ${xy[1] + c.height / 2} for '$owner'")
            }
        }
    }
}
