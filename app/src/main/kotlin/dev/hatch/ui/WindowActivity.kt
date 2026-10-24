package dev.hatch.ui

import android.app.ActivityManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.KeyEvent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getStringExtra(EXTRA_APP_ID)
            ?: intent.data?.lastPathSegment
            ?: "unknown"
        // Stub meta-data path: hatch://app/<id>
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

        val sv = SurfaceView(this)
        surfaceView = sv
        sv.holder.addCallback(this)
        setContentView(sv)

        val rt = HatchApp.instance.runtime
        rt.ensureStarted()
        rt.onWindowAttached(appId) { w -> runOnUiThread { bindToplevel(w) } }
        // Also accept any toplevel with empty/unknown appId matching this session
        rt.onWindowAttached("") { w ->
            if (toplevel == null) runOnUiThread { bindToplevel(w) }
        }

        maybeStartGuest()
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
        val ok = AppSession.start(
            this, appId, cmd, waylandDir,
            extraEnv = if (rec?.wine == true) mapOf("WINEDEBUG" to "-all") else emptyMap(),
        )
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
        server.post {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    server.seat.focusWindow(w)
                    server.seat.pointerMotion(w, event.x.toInt(), event.y.toInt())
                    server.seat.pointerButton(w, 0x110 /* BTN_LEFT */, true)
                }
                MotionEvent.ACTION_MOVE -> server.seat.pointerMotion(w, event.x.toInt(), event.y.toInt())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    server.seat.pointerButton(w, 0x110, false)
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val server = HatchApp.instance.runtime.server ?: return super.onKeyDown(keyCode, event)
        val w = toplevel; if (w != null) server.post { server.seat.key(w, event.scanCode, true) }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val server = HatchApp.instance.runtime.server ?: return super.onKeyUp(keyCode, event)
        val w = toplevel; if (w != null) server.post { server.seat.key(w, event.scanCode, false) }
        return true
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
        // Keep guest running if user is just rotating; kill when finishing
        if (isFinishing) AppSession.stop(appId)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP_ID = "appId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_COMMAND = "command"
        private const val TAG = "HatchWindow"
    }
}
