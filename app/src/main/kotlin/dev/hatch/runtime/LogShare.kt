package dev.hatch.runtime

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dev.hatch.gpu.Gpu
import dev.hatch.guest.AppSession
import dev.hatch.guest.LinuxRuntime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Share logs" / "Report": zips the per-launch app logs (filesDir/logs), Hatch's own logcat, device + GPU info and
 * the renderer choices, then opens Android's share sheet. Nothing is sent anywhere without the user picking a target.
 */
object LogShare {
    private const val TAG = "HatchLogs"
    private const val MAX_FILE = 2L * 1024 * 1024

    fun bundle(ctx: Context, appId: String? = null): File {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val zip = File(dir, "hatch-logs-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            fun putFile(name: String, f: File) {
                if (!f.isFile) return
                val bytes = if (f.length() <= MAX_FILE) f.readBytes()
                    else f.inputStream().use { it.skip(f.length() - MAX_FILE); it.readBytes() }   // keep the tail
                put(name, bytes)
            }
            put("device.txt", deviceInfo(ctx, appId).toByteArray())
            AppSession.logsDir(ctx).listFiles()?.sortedBy { it.name }?.forEach { putFile("logs/${it.name}", it) }
            putFile("installed.json", File(ctx.filesDir, "installed.json"))
            put("renderers.txt", ctx.getSharedPreferences("renderers", Context.MODE_PRIVATE).all.entries
                .joinToString("\n") { "${it.key}=${it.value}" }.toByteArray())
            put("logcat.txt", logcat())
        }
        Log.i(TAG, "bundle ${zip.name} (${zip.length() / 1024} KB)")
        return zip
    }

    fun share(ctx: Context, appId: String? = null, label: String? = null) {
        runCatching {
            val zip = bundle(ctx, appId)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.logs", zip)
            val subject = if (label != null) "Hatch ${version(ctx)}: $label problem" else "Hatch ${version(ctx)} logs"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, "$subject\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                clipData = ClipData.newRawUri(zip.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share Hatch logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Log.w(TAG, "share failed: ${it.message}") }
    }

    private fun version(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun deviceInfo(ctx: Context, appId: String?): String = buildString {
        appendLine("Hatch ${version(ctx)}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        val dm = ctx.resources.displayMetrics
        appendLine("Display: ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi")
        runCatching { appendLine("GPU: ${Gpu.describe(Gpu.mode(ctx), Gpu.info(ctx, LinuxRuntime.rootfsDir(ctx)))}") }
        if (appId != null) {
            appendLine("App: $appId")
            AppSession.lastExit[appId]?.let { appendLine("Last exit: status=${it.status}\n" + it.tail.joinToString("\n") { "  | $it" }) }
        }
    }

    private fun logcat(): ByteArray = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "6000", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
        p.inputStream.readBytes().also { p.waitFor() }
    }.getOrElse { "logcat unavailable: ${it.message}".toByteArray() }
}
