package dev.hatch.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.hatch.HatchApp
import dev.hatch.R
import dev.hatch.guest.AppSession
import dev.hatch.store.InstalledApps
import dev.hatch.ui.MainActivity

/**
 * Foreground service that keeps the guest + compositor alive across fold/unfold while apps run.
 * Notification: "N apps running: …" with Stop all / Open Hatch; stops itself once nothing runs (RuntimeControl).
 */
class HatchRuntimeService : Service() {
    companion object {
        private const val ID = 42
        @Volatile var running = false

        fun build(ctx: Context): Notification {
            val names = AppSession.runningIds().map { id -> InstalledApps.get(ctx, id)?.name ?: id }.sorted()
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, HatchRuntimeService::class.java).setAction(RuntimeControl.ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = if (names.isEmpty()) ctx.getString(R.string.runtime_notification_idle) else RuntimeControl.summary(names)
            return NotificationCompat.Builder(ctx, HatchApp.CHANNEL_RUNTIME)
                .setContentTitle(ctx.getString(R.string.runtime_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.InboxStyle().also { s -> names.forEach { s.addLine(it) } }.setSummaryText(text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(open)
                .addAction(0, ctx.getString(R.string.runtime_stop_all), stop)
                .addAction(0, ctx.getString(R.string.runtime_open), open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun refresh(ctx: Context) {
            if (!running) return
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        running = true
        if (intent?.action == RuntimeControl.ACTION_STOP_ALL) {
            RuntimeControl.stopAll(this, "notification")
            return START_NOT_STICKY
        }
        HatchApp.instance.runtime.ensureStarted()
        RuntimeControl.changed(this)
        // Not sticky: after a process death there are no guests to keep alive, so don't resurrect the notification.
        return START_NOT_STICKY
    }

    /** Hatch swiped away from Recents: keep floating apps running, otherwise shut down now. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("HatchRuntime", "task removed; idle=${RuntimeControl.idle()}")
        if (RuntimeControl.idle()) RuntimeControl.stopAll(this, "task removed") else RuntimeControl.changed(this)
    }

    override fun onDestroy() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
