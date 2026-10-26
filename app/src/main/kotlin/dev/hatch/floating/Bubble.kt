package dev.hatch.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.hatch.R
import dev.hatch.ui.MainActivity
import kotlin.math.abs

/**
 * Floating edge bubble (chat head) shown while floating windows exist: drag it anywhere, it snaps to the nearest edge.
 * Tap: opens a small menu — minimised windows to restore, "Open Hatch" (store), "Hide all windows".
 */
@SuppressLint("StaticFieldLeak", "ClickableViewAccessibility")
object Bubble {
    private var view: View? = null
    private var menu: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private const val PREF = "bubble"

    fun enabled(ctx: Context) = ctx.getSharedPreferences("hatch", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun setEnabled(ctx: Context, on: Boolean) { ctx.getSharedPreferences("hatch", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply(); sync(ctx) }

    /** Show the bubble while any floating window exists (and the user hasn't turned it off); hide it otherwise. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val want = enabled(app) && FloatingWindows.all.isNotEmpty() && FloatingWindows.canOverlay(app)
        if (want && view == null) show(app) else if (!want) hide(app)
        badge()
    }

    fun bringToFront() {
        val v = view ?: return; val p = lp ?: return
        val wm = v.context.getSystemService(WindowManager::class.java)
        runCatching { wm.removeViewImmediate(v); wm.addView(v, p) }
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private var badgeView: TextView? = null
    private fun badge() {
        val n = FloatingWindows.minimised.size
        badgeView?.apply { visibility = if (n > 0) View.VISIBLE else View.GONE; text = "$n" }
    }

    private fun show(ctx: Context) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val size = dp(ctx, 52f); val margin = dp(ctx, 6f)
        val sw = wm.currentWindowMetrics.bounds.width(); val sh = wm.currentWindowMetrics.bounds.height()
        val p = WindowManager.LayoutParams(
            size + dp(ctx, 8f), size + dp(ctx, 8f), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = sw - size - margin; y = (sh * 0.35f).toInt(); title = "Hatch bubble" }
        val root = FrameLayout(ctx).apply { contentDescription = "Hatch bubble" }
        val icon = ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher)
            background = GradientDrawable().apply { setColor(Color.rgb(0x2b, 0x2b, 0x33)); cornerRadius = dp(ctx, 14f).toFloat() }
            elevation = dp(ctx, 4f).toFloat(); setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
            clipToOutline = true
        }
        root.addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        badgeView = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER; visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.rgb(0x7c, 0x4d, 0xff)); cornerRadius = dp(ctx, 9f).toFloat() }
            elevation = dp(ctx, 5f).toFloat()
        }
        root.addView(badgeView, FrameLayout.LayoutParams(dp(ctx, 18f), dp(ctx, 18f), Gravity.TOP or Gravity.END))

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false; FloatingWindows.nonGuestTouched() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (moved || abs(dx) + abs(dy) > dp(ctx, 6f)) { moved = true; p.x = ox + dx; p.y = oy + dy; runCatching { wm.updateViewLayout(root, p) } }
                }
                MotionEvent.ACTION_UP -> if (moved) {
                    p.x = FloatGeometry.snapX(p.x, p.width, wm.currentWindowMetrics.bounds.width(), margin)
                    p.y = p.y.coerceIn(0, wm.currentWindowMetrics.bounds.height() - p.height)
                    runCatching { wm.updateViewLayout(root, p) }
                } else toggleMenu(ctx)
            }
            true
        }
        wm.addView(root, p); view = root; lp = p
    }

    private fun hide(ctx: Context) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        closeMenu(ctx)
        view?.let { runCatching { wm.removeViewImmediate(it) } }; view = null; lp = null; badgeView = null
    }

    private fun closeMenu(ctx: Context) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        menu?.let { runCatching { wm.removeViewImmediate(it) } }; menu = null
    }

    private fun toggleMenu(ctx: Context) {
        if (menu != null) { closeMenu(ctx); return }
        val wm = ctx.getSystemService(WindowManager::class.java)
        val bp = lp ?: return
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.rgb(0x2b, 0x2b, 0x33)); cornerRadius = dp(ctx, 16f).toFloat() }
            elevation = dp(ctx, 6f).toFloat(); setPadding(dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f))
        }
        fun item(text: String, action: () -> Unit) = col.addView(TextView(ctx).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f))
            setOnClickListener { closeMenu(ctx); action() }
        })
        FloatingWindows.minimised.forEach { w -> item("▢  ${w.label}") { w.restore(); FloatingWindows.raise(w) } }
        item("Open Hatch") {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        if (FloatingWindows.all.any { !it.isMinimised }) item("Hide all windows") { FloatingWindows.minimiseAll() }
        else item("Show all windows") { FloatingWindows.restoreAll() }
        val sw = wm.currentWindowMetrics.bounds.width()
        val mp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START; title = "Hatch bubble menu"
            y = bp.y; x = if (bp.x > sw / 2) (bp.x - dp(ctx, 200f)).coerceAtLeast(0) else bp.x + bp.width
        }
        col.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closeMenu(ctx); false }
        wm.addView(col, mp); menu = col
        col.post { FloatingWindow.logTargets(col, "bubble-menu", "bubble") }
    }
}
