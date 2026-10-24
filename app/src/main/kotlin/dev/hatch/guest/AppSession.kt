package dev.hatch.guest

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Tracks running guest processes keyed by store app id. */
object AppSession {
    private const val TAG = "HatchSession"
    private val sessions = ConcurrentHashMap<String, LinuxRuntime.Session>()
    private const val MAX_LOG = 2L * 1024 * 1024

    /** Last few KB of a guest's output, for logcat on exit. Pure, for tests. */
    class OutputTail(private val max: Int = 6000) {
        private val sb = StringBuilder()
        fun add(b: ByteArray, n: Int) {
            sb.append(String(b, 0, n, Charsets.UTF_8))
            if (sb.length > max) sb.delete(0, sb.length - max)
        }
        fun lines(limit: Int = 40): List<String> = sb.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.takeLast(limit)
    }

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
        runCatching { dev.hatch.audio.AudioBridge.ensure(context, waylandHostDir) }.onFailure { Log.w(TAG, "audio: ${it.message}") }
        val s = LinuxRuntime.spawn(
            context,
            command,
            workingDir = GuestScripts.HOME,
            waylandHostDir = waylandHostDir,
            extraEnv = extraEnv,
            usePty = false,
            asUser = true,
        ) ?: return false
        sessions[appId] = s
        // Drain stdout in background so pipe doesn't block
        Thread({
            val buf = ByteArray(4096)
            val tail = OutputTail()
            val logFile = runCatching { java.io.File(context.filesDir, "logs").apply { mkdirs() }.resolve("$appId.log") }.getOrNull()
            val log = runCatching { logFile?.outputStream() }.getOrNull()
            var written = 0L
            while (true) {
                val n = try { dev.hatch.wayland.Native.readSome(s.outFd, buf) } catch (_: Throwable) { -1 }
                if (n <= 0) break
                tail.add(buf, n)
                if (log != null && written < MAX_LOG) { runCatching { log.write(buf, 0, n) }; written += n }
            }
            runCatching { log?.close() }
            // stdout EOF ⇒ proot (and every guest process) exited
            if (sessions[appId] === s) sessions.remove(appId)
            val code = runCatching { dev.hatch.wayland.Native.waitPid(s.pid, false) }.getOrNull()
            Log.i(TAG, "$appId exited (status=$code)")
            tail.lines().forEach { Log.i(TAG, "$appId| $it") }
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
