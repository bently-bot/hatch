package dev.hatch.ui

import android.app.ActivityManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import dev.hatch.HatchApp
import dev.hatch.wayland.Native
import dev.hatch.wayland.Toplevel

/**
 * One Android task per xdg_toplevel.
 * Fold/unfold and freeform resize are handled via configChanges so the guest session
 * (owned by HatchRuntimeService) is not killed — we only rebind the ANativeWindow target.
 */
class WindowActivity : ComponentActivity(), SurfaceHolder.Callback {
    private var appId: String = ""
    private var label: String = "App"
    private var surfaceView: SurfaceView? = null
    private var toplevel: Toplevel? = null
    private var target: Long = 0
    private var surfaceW = 0
    private var surfaceH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getStringExtra(EXTRA_APP_ID) ?: intent.data?.lastPathSegment ?: "unknown"
        label = intent.getStringExtra(EXTRA_LABEL) ?: appId
        if (Build.VERSION.SDK_INT >= 33) {
            setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(label).build())
        } else {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(label))
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sv = SurfaceView(this)
        surfaceView = sv
        sv.holder.addCallback(this)
        setContentView(sv)

        HatchApp.instance.runtime.ensureStarted()
        HatchApp.instance.runtime.onWindowAttached(appId) { w ->
            runOnUiThread { bindToplevel(w) }
        }
        Log.i(TAG, "open appId=$appId cmd=${intent.getStringExtra(EXTRA_COMMAND)}")
    }

    private fun bindToplevel(w: Toplevel) {
        toplevel = w
        rebindTarget()
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

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width; surfaceH = height
        rebindTarget()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        toplevel?.detachTarget()
        if (target != 0L) { Native.targetRelease(target); target = 0 }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "configChanged smallest=${newConfig.smallestScreenWidthDp}dp orient=${newConfig.orientation}")
    }

    override fun onDestroy() {
        HatchApp.instance.runtime.onWindowDetached(appId)
        toplevel?.detachTarget()
        if (target != 0L) { Native.targetRelease(target); target = 0 }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP_ID = "appId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_COMMAND = "command"
        private const val TAG = "HatchWindow"
    }
}
