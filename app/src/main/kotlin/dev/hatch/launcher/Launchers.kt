package dev.hatch.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import dev.hatch.ui.WindowActivity

/**
 * Per-app launcher icons as home-screen shortcuts (default). Pinning a shortcut installs nothing, so Play Protect
 * doesn't scan anything and Hatch never needs "Install unknown apps". The shortcut opens WindowActivity with the
 * same intent the old stub APK sent, so the app opens the same way: a floating window by default.
 * Per-app stub APKs remain as an advanced option (Settings → Launcher icons), off by default.
 */
object Launchers {
    private const val TAG = "HatchLauncher"
    private const val PREFS = "hatch"
    private const val KEY_STUBS = "launcher_stub_apks"
    private const val KEY_PENDING = "launcher_pending_pins"
    const val EXTRA_PIN_APP = "dev.hatch.extra.PIN_APP"

    fun stubsEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_STUBS, false)
    fun setStubsEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_STUBS, on).apply()

    fun shortcutId(appId: String) = "app:$appId"

    /** Same intent as the stub APK's trampoline (stub-template Launch.java), so behaviour is identical. */
    fun launchIntent(ctx: Context, appId: String, label: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("hatch://app/$appId")).setClass(ctx, WindowActivity::class.java)
            .putExtra(WindowActivity.EXTRA_APP_ID, appId).putExtra(WindowActivity.EXTRA_LABEL, label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    /**
     * Adaptive icon: the app's real icon on a light tile, inside the 66/108 safe zone so every launcher mask
     * (circle, squircle, One UI's rounded square) keeps it whole. Letter tile when the app has no icon.
     */
    fun adaptiveBitmap(iconPng: ByteArray?, label: String, px: Int = 432): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val src = iconPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (src != null) {
            c.drawColor(Color.parseColor("#F4F4F6"))
            val inner = px * 52f / 108f; val o = (px - inner) / 2f
            c.drawBitmap(src, null, RectF(o, o, o + inner, o + inner), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            c.drawColor(Color.parseColor("#2B2930"))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D0BCFF"); textSize = px * 0.30f; textAlign = Paint.Align.CENTER }
            c.drawText((label.firstOrNull()?.uppercaseChar() ?: '?').toString(), px / 2f, px / 2f + px * 0.105f, p)
        }
        return bmp
    }

    fun shortcut(ctx: Context, appId: String, label: String, iconPng: ByteArray?): ShortcutInfo =
        ShortcutInfo.Builder(ctx, shortcutId(appId))
            .setShortLabel(label.take(24)).setLongLabel(label)
            .setIcon(Icon.createWithAdaptiveBitmap(adaptiveBitmap(iconPng, label)))
            .setIntent(launchIntent(ctx, appId, label))
            .build()

    private fun sm(ctx: Context): ShortcutManager? = ctx.getSystemService(ShortcutManager::class.java)

    fun isPinned(ctx: Context, appId: String): Boolean =
        runCatching { sm(ctx)?.pinnedShortcuts?.any { it.id == shortcutId(appId) && it.isEnabled } }.getOrNull() == true

    fun pinSupported(ctx: Context): Boolean = runCatching { sm(ctx)?.isRequestPinShortcutSupported }.getOrNull() == true

    enum class PinResult { REQUESTED, ALREADY_PINNED, UNSUPPORTED, FAILED }

    /**
     * Ask the launcher to pin the app's icon. Must run while Hatch is in the foreground (launchers ignore or
     * reject background requests). One UI / Pixel show their own "Add to Home screen" confirmation; if the user
     * dismisses it nothing breaks: the app stays in Hatch's Apps tab, as a dynamic shortcut on Hatch's icon, and
     * the detail page offers "Add to home screen" again.
     */
    fun requestPin(ctx: Context, appId: String, label: String, iconPng: ByteArray?): PinResult {
        val m = sm(ctx) ?: return PinResult.FAILED
        val info = shortcut(ctx, appId, label, iconPng)
        return try {
            runCatching { m.enableShortcuts(listOf(info.id)) }
            if (isPinned(ctx, appId)) { m.updateShortcuts(listOf(info)); return PinResult.ALREADY_PINNED }
            if (!m.isRequestPinShortcutSupported) { reportUsed(ctx, appId, label, iconPng); return PinResult.UNSUPPORTED }
            val cb = PendingIntent.getBroadcast(ctx, info.id.hashCode(),
                Intent(ctx, PinResultReceiver::class.java).putExtra(EXTRA_PIN_APP, appId).putExtra(WindowActivity.EXTRA_LABEL, label),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (m.requestPinShortcut(info, cb.intentSender)) { Log.i(TAG, "pin requested $appId"); PinResult.REQUESTED }
            else PinResult.FAILED
        } catch (e: Exception) {
            Log.w(TAG, "pin $appId failed: ${e.message}"); PinResult.FAILED
        }
    }

    /** Recent apps as dynamic shortcuts (long-press Hatch's icon), most recent first. */
    fun reportUsed(ctx: Context, appId: String, label: String, iconPng: ByteArray?) {
        val m = sm(ctx) ?: return
        runCatching {
            val info = shortcut(ctx, appId, label, iconPng)
            if (Build.VERSION.SDK_INT >= 30) m.pushDynamicShortcut(info)
            else {
                val keep = m.dynamicShortcuts.filter { it.id != info.id }.take((m.maxShortcutCountPerActivity - 1).coerceAtLeast(0))
                m.dynamicShortcuts = listOf(info) + keep
            }
            m.reportShortcutUsed(info.id)
        }.onFailure { Log.w(TAG, "dynamic shortcut $appId: ${it.message}") }
    }

    /** Uninstall: remove the recent entry and grey out a pinned icon (launchers don't let apps delete pins). */
    fun remove(ctx: Context, appId: String) {
        val m = sm(ctx) ?: return
        runCatching { m.removeDynamicShortcuts(listOf(shortcutId(appId))) }
        runCatching { m.disableShortcuts(listOf(shortcutId(appId)), "Removed from Hatch") }
    }

    // ---- pins requested while Hatch was in the background (e.g. an install finished off screen) ----
    fun queuePin(ctx: Context, appId: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet(KEY_PENDING, (p.getStringSet(KEY_PENDING, emptySet()).orEmpty() + appId)).apply()
    }

    fun takePending(ctx: Context): Set<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = p.getStringSet(KEY_PENDING, emptySet()).orEmpty().toSet()
        if (s.isNotEmpty()) p.edit().remove(KEY_PENDING).apply()
        return s
    }
}

/** The launcher confirms a pin (One UI: after "Add" in its sheet). */
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(WindowActivity.EXTRA_LABEL) ?: return
        Log.i("HatchLauncher", "pinned ${intent.getStringExtra(Launchers.EXTRA_PIN_APP)}")
        Toast.makeText(context, "$label is on your Home screen", Toast.LENGTH_SHORT).show()
    }
}
