package dev.hatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.hatch.runtime.HatchRuntime

class HatchApp : Application() {
    lateinit var runtime: HatchRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNTIME, "Runtime", NotificationManager.IMPORTANCE_LOW)
            )
        }
        runtime = HatchRuntime(this)
    }

    companion object {
        const val CHANNEL_RUNTIME = "hatch.runtime"
        lateinit var instance: HatchApp
            private set
    }
}
