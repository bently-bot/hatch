package dev.hatch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2

/**
 * On-screen game controls for touch-only play (`controls: "doom"`): an 8-way D-pad (arrows) bottom-left, FIRE (Ctrl) and
 * USE (Space) bottom-right, and MENU (Esc) / MAP (Tab) / ENTER on top. Multi-touch: move with one thumb, fire with the other.
 * Never takes focus, so a hardware keyboard keeps working alongside it.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TouchPad(ctx: Context, private val send: (code: Int, pressed: Boolean) -> Unit) : FrameLayout(ctx) {
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    init {
        isFocusable = false; isMotionEventSplittingEnabled = true
        addView(DPad(), LayoutParams(dp(150f), dp(150f), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16f), 0, 0, dp(16f)) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(button("USE", GamepadKeys.SPACE, 64f), LinearLayout.LayoutParams(dp(64f), dp(64f)).apply { rightMargin = dp(12f) })
            addView(button("FIRE", GamepadKeys.CTRL, 84f), LinearLayout.LayoutParams(dp(84f), dp(84f)))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16f), dp(20f)) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((t, c) in listOf("MENU" to GamepadKeys.ESC, "MAP" to GamepadKeys.TAB, "ENTER" to GamepadKeys.ENTER, "RUN" to GamepadKeys.SHIFT))
                addView(button(t, c, 0f), LinearLayout.LayoutParams(dp(60f), dp(34f)).apply { leftMargin = dp(6f) })
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8f), dp(12f), 0) })
    }

    /** On-screen centres of the pad's controls ("Touch FIRE" → x, y): overlays are invisible to uiautomator, so the
     *  emulator E2E taps these from logcat. */
    fun targets(): List<Pair<String, IntArray>> {
        val out = ArrayList<Pair<String, IntArray>>()
        fun walk(v: View) {
            val d = v.contentDescription?.toString()
            if (d != null && d.startsWith("Touch ")) { val xy = IntArray(2); v.getLocationOnScreen(xy); out += d to intArrayOf(xy[0] + v.width / 2, xy[1] + v.height / 2) }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this); return out
    }

    private fun button(label: String, code: Int, size: Float) = TextView(context).apply {
        text = label; contentDescription = "Touch $label"; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        textSize = if (size > 70f) 15f else 12f; isFocusable = false
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = if (size > 0f) android.graphics.drawable.GradientDrawable.OVAL else android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(10f).toFloat(); setColor(Color.argb(90, 255, 255, 255)); setStroke(dp(1.5f), Color.argb(150, 255, 255, 255))
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; send(code, true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; send(code, false) }
            }
            true
        }
    }

    /** 8-way pad: the angle from the centre picks one or two arrows; releasing or sliding updates only what changed. */
    private inner class DPad : View(context) {
        private val held = HashSet<Int>()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = dp(1.5f).toFloat() }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) }
        private var kx = -1f; private var ky = -1f
        init { contentDescription = "Touch D-pad"; isFocusable = false }

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            c.drawCircle(r, r, r - edge.strokeWidth, ring); c.drawCircle(r, r, r - edge.strokeWidth, edge)
            c.drawCircle(if (kx < 0) r else kx, if (ky < 0) r else ky, r / 3.2f, knob)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val want = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { kx = e.x; ky = e.y; dirs(e.x - width / 2f, e.y - height / 2f) }
                else -> { kx = -1f; ky = -1f; emptySet() }
            }
            for (k in held - want) send(k, false)
            for (k in want - held) send(k, true)
            held.clear(); held.addAll(want); invalidate()
            return true
        }

        private fun dirs(dx: Float, dy: Float): Set<Int> {
            if (abs(dx) < width * 0.12f && abs(dy) < height * 0.12f) return emptySet()
            val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))   // 0 = right, 90 = down
            return buildSet {
                if (a > -67.5 && a < 67.5) add(GamepadKeys.RIGHT)
                if (a > 112.5 || a < -112.5) add(GamepadKeys.LEFT)
                if (a > 22.5 && a < 157.5) add(GamepadKeys.DOWN)
                if (a < -22.5 && a > -157.5) add(GamepadKeys.UP)
            }
        }
    }
}

/** Per-app "show touch controls" choice; by default shown unless a keyboard or game controller is connected. */
object TouchPads {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("touchpad", Context.MODE_PRIVATE)
    fun controlsFor(ctx: Context, appId: String): String =
        runCatching { dev.hatch.store.Catalogue.load(ctx).firstOrNull { it.id == appId }?.controls }.getOrNull().orEmpty()

    private fun hardwareControls(ctx: Context): Boolean {
        if (ctx.resources.configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY) return true
        return android.view.InputDevice.getDeviceIds().any { id ->
            val d = android.view.InputDevice.getDevice(id) ?: return@any false
            !d.isVirtual && (d.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
                d.sources and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK)
        }
    }

    fun shown(ctx: Context, appId: String): Boolean = prefs(ctx).let { p ->
        if (p.contains(appId)) p.getBoolean(appId, true) else !hardwareControls(ctx)
    }
    fun setShown(ctx: Context, appId: String, on: Boolean) { prefs(ctx).edit().putBoolean(appId, on).apply() }
}
