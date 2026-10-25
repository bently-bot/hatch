package dev.hatch.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import dev.hatch.gpu.Renderers
import dev.hatch.guest.AppSession
import dev.hatch.store.InstalledApps
import dev.hatch.ui.AppLauncher
import dev.hatch.runtime.HatchRuntime
import dev.hatch.ui.GuestSurfaceView
import dev.hatch.wayland.Toplevel
import kotlin.math.abs

/**
 * One program window floating above everything (TYPE_APPLICATION_OVERLAY), styled like Android freeform windows:
 * borderless card with a shadow and a caption bar: ⌨ on the left, a grey pill to drag (pinch to resize), and
 * ⋮ (window menu: renderer, software rendering, log), — minimise to bubble, ⤢ maximise/restore, ✕ close on the right.
 * Drag the bottom corners to resize. Key focus is taken when a touch ends and handed back on an outside touch or Back.
 *
 * ✕ is one tap: the window hides at once, the app gets a polite close, and its session is force-ended if it hasn't
 * exited ~2 s later (FloatingWindows.closeApp). A window that stays black, or an app that dies before drawing, moves
 * to the next renderer rung automatically (gpu.Renderers) and ends in an error card, never a silent black window.
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
    private val handleH = dp(34f)
    // Dialogs (secondary windows) may be small; main windows keep a usable minimum.
    private val minW = if (primary) dp(240f) else dp(120f); private val minH = if (primary) dp(160f) else dp(64f)
    private val sv = GuestSurfaceView(ctx)
    private val controls = dev.hatch.ui.TouchPads.controlsFor(ctx, appId)
    private val touchPad: dev.hatch.ui.TouchPad? =
        if (controls.isEmpty()) null else dev.hatch.ui.TouchPad(ctx) { code, down -> sv.sendKey(code, down) }
    private val statusBox: LinearLayout
    private val statusText: TextView
    private val problemBox: LinearLayout
    private val problemTitle: TextView
    private val problemBody: TextView
    private val problemLog: TextView
    private val problemActions: LinearLayout
    private val caption: LinearLayout
    private val menu: LinearLayout
    private val root: FrameLayout
    private val lp: WindowManager.LayoutParams
    private var attached = false
    var isMinimised = false; private set
    private var maximised = false
    private var restoreBounds: Bounds? = null
    private var focusable = false

    /** Hidden by ✕ while the app is asked to close (shown again only if it answers with a dialog). */
    var hiddenForClose = false; private set
    /** Session is being restarted on another renderer rung: window/exit events from the old one are expected. */
    private var relaunching = false
    private var boundAt = 0L
    private var drewContent = false
    private var autoRetries = 0
    private val blackCheck = Runnable { onBlackTimeout() }
    private val orphanCheck = Runnable { onNoWindow() }

    override val boundWindow: Toplevel? get() = sv.toplevel

    init {
        val (sw, sh) = screen()
        val b = FloatGeometry.initial(sw, sh, cascade)
        lp = WindowManager.LayoutParams(
            b.w + 2 * shadow, b.h + handleH + 2 * shadow,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                // Never touch-modal: a focused (typing) window must not swallow taps meant for other windows or apps.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
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
        caption = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(0x26, 0x26, 0x2b))
            contentDescription = "Window handle: $label"
            addView(captionButton("⌨", "Keyboard") { takeFocus(); sv.toggleKeyboard() })
            addView(FrameLayout(ctx).apply {
                addView(View(ctx).apply {
                    background = GradientDrawable().apply { setColor(Color.rgb(0x9a, 0x9a, 0xa2)); cornerRadius = dp(3f).toFloat() }
                }, FrameLayout.LayoutParams(dp(44f), dp(5f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(captionButton("⋮", "Window menu") { toggleMenu() })
            addView(captionButton("—", "Minimise") { minimise() })
            addView(captionButton("⤢", "Maximise") { toggleMaximise() })
            addView(captionButton("✕", "Close") { close() })
        }
        column.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, handleH))
        val content = FrameLayout(ctx)
        content.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sv.controls = controls
        touchPad?.let {
            it.visibility = if (dev.hatch.ui.TouchPads.shown(ctx, appId)) View.VISIBLE else View.GONE
            content.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            it.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.visibility == View.VISIBLE) v.post { (v as dev.hatch.ui.TouchPad).targets().forEach { (d, xy) -> Log.i(TAG, "touch $d at ${xy[0]} ${xy[1]} for '$label'") } }
            }
        }
        statusText = TextView(ctx).apply {
            text = "Starting $label…"; setTextColor(Color.LTGRAY); textSize = 15f; gravity = Gravity.CENTER; setPadding(24, 16, 24, 0)
        }
        statusBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0x1b, 0x1b, 0x1f))
            addView(ProgressBar(ctx)); addView(statusText)
        }
        content.addView(statusBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        problemTitle = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 17f; setPadding(0, 0, 0, dp(6f)) }
        problemBody = TextView(ctx).apply { setTextColor(Color.rgb(0xc8, 0xc8, 0xd0)); textSize = 14f }
        problemLog = TextView(ctx).apply {
            setTextColor(Color.rgb(0xa0, 0xa0, 0xa8)); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE; setPadding(0, dp(8f), 0, 0); setTextIsSelectable(true)
        }
        problemActions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, 0) }
        problemBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(18f), dp(20f), dp(14f)); visibility = View.GONE
            setBackgroundColor(Color.rgb(0x1b, 0x1b, 0x1f))
            addView(TextView(ctx).apply { text = "⚠"; setTextColor(Color.rgb(0xff, 0xb4, 0xab)); textSize = 22f })
            addView(problemTitle); addView(problemBody)
            addView(android.widget.HorizontalScrollView(ctx).apply { addView(problemActions) })
            addView(android.widget.ScrollView(ctx).apply { addView(problemLog) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        content.addView(problemBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.argb(245, 0x33, 0x33, 0x3a)); cornerRadius = dp(12f).toFloat() }
            elevation = dp(12f).toFloat(); setPadding(0, dp(4f), 0, dp(4f)); visibility = View.GONE   // above the card (8dp): touch dispatch follows Z
        }

        root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.source and android.view.InputDevice.SOURCE_KEYBOARD != android.view.InputDevice.SOURCE_KEYBOARD) {
                    if (event.action == KeyEvent.ACTION_UP) { if (menu.visibility == View.VISIBLE) menu.visibility = View.GONE else releaseFocus() }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { releaseFocus(); menu.visibility = View.GONE; return true }
                // Guest (Wayland) focus right away; the WindowManager focus flag only changes when the finger lifts,
                // because relayouting a window mid-gesture can cancel the tap on some builds (One UI).
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { rt.focusedSlot = this@FloatingWindow; sv.focusGuest() }
                val handled = super.dispatchTouchEvent(ev)
                // Raising re-adds the view (the only z-order control for overlays), which would cancel a gesture in
                // flight, so the window comes to the front when the finger lifts.
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)
                    post { if (attached) { takeFocus(); FloatingWindows.raise(this@FloatingWindow); logCaption() } }
                return handled
            }
        }.apply { setPadding(shadow, shadow, shadow, shadow); clipToPadding = false; clipChildren = false }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(menu, FrameLayout.LayoutParams(dp(260f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { topMargin = shadow + handleH; rightMargin = shadow + dp(8f) })
        root.addView(corner(left = true), FrameLayout.LayoutParams(dp(30f), dp(30f), Gravity.BOTTOM or Gravity.START))
        root.addView(corner(left = false), FrameLayout.LayoutParams(dp(30f), dp(30f), Gravity.BOTTOM or Gravity.END))

        wireHandle(caption)
        sv.onTouchDown = { rt.focusedSlot = this }
    }

    private fun captionButton(text: String, desc: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; contentDescription = desc; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(42f), ViewGroup.LayoutParams.MATCH_PARENT)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff), null, null)
        setOnClickListener { menu.visibility = View.GONE; onClick() }
    }

    private fun actionButton(text: String, primaryStyle: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; contentDescription = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (primaryStyle) Color.rgb(0x0b, 0x1d, 0x3a) else Color.rgb(0xaa, 0xc7, 0xff))
        background = GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            if (primaryStyle) setColor(Color.rgb(0xaa, 0xc7, 0xff)) else setStroke(dp(1f), Color.rgb(0x8e, 0x90, 0x99))
        }
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) }
        setOnClickListener { onClick() }
    }

    /** Logs the caption buttons' screen centres for the emulator E2E (overlays are invisible to uiautomator). */
    private fun logCaption(delay: Long = 250) = root.postDelayed({ if (attached) logTargets(caption, "control", label) }, delay)

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
                MotionEvent.ACTION_UP -> {
                    if (moved && !pinching) apply(Bounds(start.x + (e.rawX - sx).toInt(), start.y + (e.rawY - sy).toInt(), start.w, start.h))
                    else if (!moved && !pinching) menu.visibility = View.GONE
                    if (moved || pinching) logCaption()
                }
            }
            true
        }
    }

    private fun corner(left: Boolean) = View(ctx).apply {
        contentDescription = if (left) "Resize (bottom left)" else "Resize (bottom right)"
        // Above the card's 8dp elevation: ViewGroups dispatch touches by Z, so a lower corner never got them.
        // No background → no outline → no shadow; just a subtle diagonal grip like a desktop window's.
        elevation = dp(10f).toFloat()
        background = object : android.graphics.drawable.Drawable() {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66808080; strokeWidth = dp(1.5f).toFloat(); strokeCap = android.graphics.Paint.Cap.ROUND
            }
            override fun draw(c: android.graphics.Canvas) {
                val b = bounds; val m = dp(6f).toFloat()
                for (k in 1..3) {
                    val o = k * dp(5f).toFloat()
                    if (left) c.drawLine(b.left + m, b.bottom - m - o, b.left + m + o, b.bottom - m, p)
                    else c.drawLine(b.right - m - o, b.bottom - m, b.right - m, b.bottom - m - o, p)
                }
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        outlineProvider = null
        var sx = 0f; var sy = 0f; var start = bounds(); var last = 0L
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; start = bounds(); maximised = false; last = 0L; Log.i(TAG, "resize start '$label' $start") }
                // Each relayout resizes the guest surface (the client re-renders), so throttle live updates
                // and always land exactly where the finger lifted.
                MotionEvent.ACTION_MOVE -> if (e.eventTime - last >= 60) {
                    last = e.eventTime
                    apply(FloatGeometry.resizeCorner(start, (e.rawX - sx).toInt(), (e.rawY - sy).toInt(), left, minW, minH))
                }
                MotionEvent.ACTION_UP -> {
                    apply(FloatGeometry.resizeCorner(start, (e.rawX - sx).toInt(), (e.rawY - sy).toInt(), left, minW, minH))
                    Log.i(TAG, "resize end '$label' ${bounds()}"); logCaption()
                }
            }
            true
        }
    }

    // ---- lifecycle ----
    fun show() {
        if (attached) return
        wm.addView(root, lp); attached = true
        Log.i(TAG, "show '$label' at ${lp.x},${lp.y} ${lp.width}x${lp.height}")
        logCaption(400)
    }

    fun bringToFront() {
        if (!attached || isMinimised) return
        runCatching { wm.removeViewImmediate(root); wm.addView(root, lp) }
        logCaption()
    }

    fun minimise() {
        if (isMinimised) return
        isMinimised = true; releaseFocus(); menu.visibility = View.GONE
        if (attached) { runCatching { wm.removeViewImmediate(root) }; attached = false }
        Bubble.sync(ctx)
        Log.i(TAG, "minimised '$label'")
    }

    fun restore() {
        if (!isMinimised) return
        isMinimised = false; if (!hiddenForClose) show(); Bubble.sync(ctx)
    }

    fun toggleMaximise() {
        if (maximised) { restoreBounds?.let { apply(it) }; maximised = false; logCaption(); return }
        restoreBounds = bounds()
        // Fill the usable area exactly: metrics bounds minus system bars/cutout, clamped to the real display size
        // (raw bounds overshot the bottom edge by ~100px on the Fold profile, hiding the resize grips).
        val m = wm.currentWindowMetrics
        val ins = m.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val dm = ctx.resources.displayMetrics
        val right = minOf(m.bounds.width(), dm.widthPixels) - ins.right
        val bottom = minOf(m.bounds.height(), dm.heightPixels) - ins.bottom
        val top = maxOf(ins.top, statusBarHeight())
        apply(Bounds(ins.left, top, right - ins.left, bottom - top - handleH))
        maximised = true
        logCaption()
    }

    private fun statusBarHeight(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(24f)
    }

    // ---- close ----
    /** ✕: one tap. The primary window closes the app (FloatingWindows.closeApp); a secondary one just that window. */
    fun close() {
        Log.i(TAG, "close tapped '$label' (primary=$primary, bound=${boundWindow != null})")
        if (primary) { FloatingWindows.closeApp(ctx, appId); return }
        val w = boundWindow ?: run { dismiss(); return }
        hideForClose()
        rt.server?.post { w.xdg?.sendClose() }
        // A dialog that refuses to close (e.g. it has its own buttons) comes back rather than vanishing for good.
        main.postDelayed({ if (hiddenForClose && boundWindow === w) unhideAfterClose() }, CLOSE_GRACE_MS)
    }

    fun hideForClose() {
        if (hiddenForClose) return
        hiddenForClose = true; releaseFocus(); menu.visibility = View.GONE
        main.removeCallbacks(blackCheck)
        if (attached) { runCatching { wm.removeViewImmediate(root) }; attached = false }
        Log.i(TAG, "hidden for close '$label'")
    }

    fun unhideAfterClose() {
        if (!hiddenForClose) return
        hiddenForClose = false
        if (!isMinimised) show()
        Log.i(TAG, "close answered with a dialog: showing '$label' again")
    }

    fun forceDismiss() { dismiss() }

    private fun dismiss() {
        main.removeCallbacks(blackCheck); main.removeCallbacks(orphanCheck)
        rt.unregister(appId, this)
        sv.detach()
        if (attached) runCatching { wm.removeViewImmediate(root) }
        attached = false
        FloatingWindows.removed(this, ctx)
        dev.hatch.runtime.RuntimeControl.changed(ctx)
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

    fun status(msg: String) = main.post { problemBox.visibility = View.GONE; statusBox.visibility = View.VISIBLE; statusText.text = msg }

    // ---- WindowSlot ----
    override fun bind(w: Toplevel) {
        main.post {
            main.removeCallbacks(orphanCheck)
            statusBox.visibility = View.GONE; problemBox.visibility = View.GONE
            sv.bind(w); boundAt = SystemClock.uptimeMillis(); relaunching = false
            if (hiddenForClose && !FloatingWindows.isClosing(appId)) unhideAfterClose()
            takeFocus()
            if (primary) armBlackCheck(w)
            // A dialog usually draws before its window is bound (windowContent then reaches no slot): fit it now.
            else if (w.hasContent) fitToClient(w)
        }
    }

    override fun windowGone(w: Toplevel) {
        main.post {
            if (!sv.unbind(w)) return@post
            main.removeCallbacks(blackCheck)
            if (!primary) { dismiss(); return@post }
            if (rt.claimFor(appId, this)) return@post           // the app swapped windows (splash → main)
            if (relaunching) return@post
            if (hiddenForClose || FloatingWindows.isClosing(appId)) { dismiss(); return@post }
            if (SystemClock.uptimeMillis() - boundAt > 5_000) {
                // The app closed its own last window (its own close button, File ▸ Quit): it looks closed at once,
                // never a "Waiting…" window. If a new window comes within the grace it is shown again (rebind).
                hideForClose()
            } else status("Starting $label…")
            main.removeCallbacks(orphanCheck); main.postDelayed(orphanCheck, NO_WINDOW_GRACE_MS)
        }
    }

    /** No window came back after the app's last one closed: end the session (helpers keep proot alive) and go. */
    private fun onNoWindow() {
        if (boundWindow != null || relaunching) return
        if (FloatingWindows.all.none { it !== this && it.appId == appId } && AppSession.isRunning(appId)) {
            Log.i(TAG, "'$label' has no windows left: ending its session")
            AppSession.stop(appId)
        }
        dismiss()
    }

    override fun guestExited() {
        main.post {
            if (AppSession.isRunning(appId)) return@post          // an old session ended after a relaunch
            if (relaunching) return@post
            if (hiddenForClose || FloatingWindows.isClosing(appId)) { dismiss(); return@post }
            if (boundWindow != null) return@post
            if (!primary || drewContent) { dismiss(); return@post }
            failed("exited")
        }
    }

    override fun contentShown(w: Toplevel) {
        main.post {
            if (boundWindow !== w || drewContent) return@post
            if (!primary) fitToClient(w)
            if (strictContent() && !w.varied) { Log.i(TAG, "'$label' flat frame (stats ${w.lastStats}); waiting for real content"); return@post }
            drewContent = true; autoRetries = 0
            main.removeCallbacks(blackCheck)
            Renderers.active[appId]?.let { Renderers.markWorked(ctx, appId, it); Log.i(TAG, "'$label' drew on ${it.id}") }
            problemBox.visibility = View.GONE
        }
    }

    /** Dialogs keep their own size (GTK/Qt ignore the configured one): fit the window to what the app drew. */
    private fun fitToClient(w: Toplevel) {
        val g = w.xdgSurface.windowGeometry
        val (lw, lh) = when {
            g != null && g[2] > 0 && g[3] > 0 -> g[2] to g[3]
            w.minW > 0 && w.minW == w.maxW && w.minH == w.maxH -> w.minW to w.minH
            else -> return
        }
        val sc = w.server.scale
        val (sw, sh) = screen()
        val fit = FloatGeometry.fitContent(bounds(), lw * sc, lh * sc, sw, sh - handleH) ?: return
        Log.i(TAG, "fit '$label' to its dialog: ${lw}x$lh (logical) → ${fit.w}x${fit.h}")
        apply(fit)
    }

    // ---- black window / crash → next renderer rung, then an error card ----
    /** Chromium/Electron/CEF: a uniform window (any brightness) is the failed-GPU-compositing look, not content. */
    private fun strictContent(): Boolean =
        Renderers.kind(InstalledApps.get(ctx, appId)?.command.orEmpty()) != Renderers.Kind.GENERIC
    private fun shows(w: Toplevel) = if (strictContent()) w.varied else w.hasContent

    private fun armBlackCheck(w: Toplevel) {
        if (shows(w)) { contentShown(w); return }
        main.removeCallbacks(blackCheck)
        val cmd = InstalledApps.get(ctx, appId)?.command.orEmpty()
        val sec = maxOf(Renderers.blackTimeoutSec(Renderers.kind(cmd)), if (AppLauncher.isX11(ctx, appId)) 40 else 0)
        main.postDelayed(blackCheck, sec * 1000L)
    }

    private fun onBlackTimeout() {
        val w = boundWindow ?: return
        if (shows(w) || drewContent || hiddenForClose) return
        Log.w(TAG, "'$label' window stayed black (${w.framesCommitted} frames, stats ${w.lastStats})")
        failed(if (w.framesCommitted == 0) "nodraw" else "black")
    }

    private fun failed(reason: String) {
        val cfg = Renderers.active[appId]
        val ladder = AppLauncher.ladderFor(ctx, appId)
        val next = if (cfg != null && !Renderers.softwareForced(ctx, appId) && autoRetries < ladder.size)
            Renderers.advance(ctx, appId, ladder, cfg) else null
        Log.w(TAG, "renderer ${cfg?.id} failed for $appId ($reason) → ${next?.id ?: "no more rungs"}")
        if (next != null) {
            autoRetries++
            relaunch("${cfg?.label ?: "This renderer"} didn't draw $label.\nTrying ${next.label}…")
            return
        }
        val exit = AppSession.lastExit[appId]
        val (title, body) = when (reason) {
            "exited" -> "$label closed before showing a window" to
                ("It exited" + (exit?.status?.let { " (status $it)" } ?: "") + ". The log usually says why.")
            "nodraw" -> "$label isn't showing anything" to "Its window opened but nothing was drawn."
            else -> "$label is showing a black window" to "Its window opened but stayed black."
        }
        showProblem(title, body + if (cfg != null) "\nRenderer: ${cfg.label}" else "")
    }

    /** Restart the session (new renderer rung / software toggle) in this same window. */
    fun relaunch(msg: String) {
        relaunching = true; drewContent = false
        main.removeCallbacks(blackCheck); main.removeCallbacks(orphanCheck)
        status(msg)
        sv.detach()
        AppSession.stop(appId)
        main.postDelayed({
            when (val r = AppLauncher.startGuest(ctx, appId, label)) {
                is AppLauncher.Result.Error -> { relaunching = false; showProblem("Couldn't start $label", r.message) }
                else -> {}
            }
        }, 1500)
    }

    private fun showProblem(title: String, body: String) = main.post {
        statusBox.visibility = View.GONE
        problemTitle.text = title; problemBody.text = body
        problemLog.visibility = View.GONE
        problemActions.removeAllViews()
        problemActions.addView(actionButton("Show log") {
            problemLog.text = logTail(); problemLog.visibility = if (problemLog.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        if (Renderers.active[appId]?.gpu != false)
            problemActions.addView(actionButton("Retry with software rendering", primaryStyle = true) {
                Renderers.setSoftwareForced(ctx, appId, true); autoRetries = 0; relaunch("Starting $label with software rendering…")
            })
        else problemActions.addView(actionButton("Retry", primaryStyle = true) { autoRetries = 0; relaunch("Starting $label…") })
        problemActions.addView(actionButton("Report") { dev.hatch.runtime.LogShare.share(ctx, appId, label) })
        if (drewContent && boundWindow != null) problemActions.addView(actionButton("Hide") { problemBox.visibility = View.GONE })
        problemActions.addView(actionButton("Close") { FloatingWindows.closeApp(ctx, appId) })
        problemBox.visibility = View.VISIBLE
        Log.w(TAG, "problem card '$label': $title")
        problemActions.post { if (attached) logTargets(problemActions, "problem", label) }
    }

    private fun logTail(): String = runCatching {
        AppSession.logFile(ctx, appId).readLines().takeLast(80).joinToString("\n")
    }.getOrNull()?.ifBlank { null } ?: AppSession.lastExit[appId]?.tail?.joinToString("\n") ?: "No log yet."

    // ---- ⋮ window menu ----
    private fun toggleMenu() {
        if (menu.visibility == View.VISIBLE) { menu.visibility = View.GONE; return }
        menu.removeAllViews()
        fun item(text: String, enabled: Boolean = true, onClick: (() -> Unit)? = null) = menu.addView(TextView(ctx).apply {
            this.text = text; contentDescription = text; textSize = 14f
            setTextColor(if (enabled) Color.WHITE else Color.rgb(0xa0, 0xa0, 0xa8))
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            if (onClick != null) setOnClickListener { menu.visibility = View.GONE; onClick() }
        })
        val cfg = Renderers.active[appId]
        item("Renderer: ${cfg?.label ?: "—"}", enabled = false)
        if (primary) {
            val sw = Renderers.softwareForced(ctx, appId)
            item(if (sw) "✓ Use software rendering" else "Use software rendering") {
                Renderers.setSoftwareForced(ctx, appId, !sw); autoRetries = 0
                relaunch(if (!sw) "Restarting $label with software rendering…" else "Restarting $label with GPU rendering…")
            }
        }
        touchPad?.let { pad ->
            val on = pad.visibility == View.VISIBLE
            item(if (on) "✓ Touch controls" else "Touch controls") {
                pad.visibility = if (on) View.GONE else View.VISIBLE
                dev.hatch.ui.TouchPads.setShown(ctx, appId, !on)
            }
        }
        item("Show log") { showProblem("$label log", "Latest launch (${cfg?.label ?: "unknown renderer"}).").also { main.post { problemLog.text = logTail(); problemLog.visibility = View.VISIBLE } } }
        item("Share logs") { dev.hatch.runtime.LogShare.share(ctx, appId, label) }
        menu.visibility = View.VISIBLE
        menu.post { logTargets(menu, "menu", label) }
    }

    // Text fields focused at window open don't pop the keyboard over the desktop; a tap into one does (⌨ always works).
    override fun textInput(enabled: Boolean) { if (enabled) main.post { if (sv.recentlyTouched) { takeFocus(); sv.showKeyboard() } } }

    companion object {
        private const val TAG = "HatchFloat"
        const val CLOSE_GRACE_MS = 2_000L
        private const val NO_WINDOW_GRACE_MS = 15_000L
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
