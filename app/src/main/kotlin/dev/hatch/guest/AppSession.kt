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

    /** How the last run of an app ended (for the in-window error card). */
    data class ExitInfo(val status: Int?, val tail: List<String>, val atMs: Long)
    val lastExit = java.util.concurrent.ConcurrentHashMap<String, ExitInfo>()

    fun logsDir(context: Context) = java.io.File(context.filesDir, "logs")
    /** filesDir/logs/<id>.log is the current/last launch; <id>.prev.log the one before. */
    fun logFile(context: Context, appId: String) = logsDir(context).resolve("${safe(appId)}.log")
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun isRunning(appId: String): Boolean = sessions.containsKey(appId)
    fun runningIds(): List<String> = sessions.keys.toList()
    private fun changed() { runCatching { dev.hatch.runtime.RuntimeControl.changed(dev.hatch.HatchApp.instance) } }

    fun start(
        context: Context,
        appId: String,
        command: List<String>,
        waylandHostDir: String,
        extraEnv: Map<String, String> = emptyMap(),
        onExit: (() -> Unit)? = null,
        header: String = "",
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
        changed()
        // Drain stdout in background so pipe doesn't block
        Thread({
            val buf = ByteArray(4096)
            val tail = OutputTail()
            val logFile = runCatching {
                logsDir(context).mkdirs()
                logFile(context, appId).also { f -> if (f.exists()) f.renameTo(logsDir(context).resolve("${safe(appId)}.prev.log")) }
            }.getOrNull()
            val log = runCatching { logFile?.outputStream() }.getOrNull()
            runCatching {
                log?.write(("# Hatch ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}  $appId\n" +
                    header.lines().filter { it.isNotBlank() }.joinToString("") { "# $it\n" } + "# argv: $command\n").toByteArray())
            }
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
            changed()
            val code = LinuxRuntime.reap(s.pid)
            Log.i(TAG, "$appId exited (status=$code)")
            lastExit[appId] = ExitInfo(code, tail.lines(60), System.currentTimeMillis())
            tail.lines().forEach { Log.i(TAG, "$appId| $it") }
            onExit?.invoke()
        }, "hatch-guest-$appId").apply { isDaemon = true; start() }
        return true
    }

    /** End a session: SIGTERM to the proot group now, SIGKILL in the background if anything is still there. */
    fun stop(appId: String) {
        sessions.remove(appId)?.let {
            runCatching { dev.hatch.wayland.Native.killGroup(it.pid, 15) }
            Thread({ Thread.sleep(1200); runCatching { dev.hatch.wayland.Native.killGroup(it.pid, 9) } }, "hatch-kill-$appId").apply { isDaemon = true; start() }
            runCatching { dev.hatch.wayland.Native.close(it.outFd) }
            changed()
        }
    }

    fun stopAll() {
        sessions.keys.toList().forEach { stop(it) }
    }
}
