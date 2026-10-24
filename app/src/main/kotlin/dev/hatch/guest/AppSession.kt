package dev.hatch.guest

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Tracks running guest processes keyed by store app id. */
object AppSession {
    private const val TAG = "HatchSession"
    private val sessions = ConcurrentHashMap<String, LinuxRuntime.Session>()

    fun isRunning(appId: String): Boolean = sessions.containsKey(appId)

    fun start(
        context: Context,
        appId: String,
        command: List<String>,
        waylandHostDir: String,
        extraEnv: Map<String, String> = emptyMap(),
        onExit: (() -> Unit)? = null,
    ): Boolean {
        stop(appId)
        Log.i(TAG, "start $appId: $command")
        val s = LinuxRuntime.spawn(
            context,
            command,
            workingDir = "/root",
            waylandHostDir = waylandHostDir,
            extraEnv = extraEnv,
            usePty = false,
        ) ?: return false
        sessions[appId] = s
        // Drain stdout in background so pipe doesn't block
        Thread({
            val buf = ByteArray(4096)
            while (true) {
                val n = try { dev.hatch.wayland.Native.readSome(s.outFd, buf) } catch (_: Throwable) { -1 }
                if (n <= 0) break
            }
            // stdout EOF ⇒ proot (and every guest process) exited
            if (sessions[appId] === s) sessions.remove(appId)
            runCatching { dev.hatch.wayland.Native.waitPid(s.pid, false) }
            Log.i(TAG, "$appId exited")
            onExit?.invoke()
        }, "hatch-guest-$appId").apply { isDaemon = true; start() }
        return true
    }

    fun stop(appId: String) {
        sessions.remove(appId)?.let {
            runCatching { it.kill() }
            runCatching { dev.hatch.wayland.Native.close(it.outFd) }
        }
    }

    fun stopAll() {
        sessions.keys.toList().forEach { stop(it) }
    }
}
