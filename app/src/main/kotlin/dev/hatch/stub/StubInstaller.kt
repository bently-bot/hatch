package dev.hatch.stub

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.math.BigInteger
import java.util.Date
import javax.security.auth.x500.X500Principal
import java.security.PrivateKey

/**
 * Build a per-app stub APK from assets/stub-template.apk and install via PackageInstaller.
 */
object StubInstaller {
    private const val TAG = "HatchStub"
    private const val KS_PASS = "***REMOVED***"

    fun stubPackageFor(appId: String): String =
        "dev.hatch.app." + appId.lowercase().replace(Regex("[^a-z0-9_]"), "_")

    fun ensureKeystore(context: Context): File {
        val ks = File(context.filesDir, "stub-keystore.p12")
        if (ks.exists()) return ks
        // Generate a simple self-signed cert with Android KeyPairGenerator + Bouncy-less approach:
        // Use keytool via... we don't have keytool on device. Use a pre-baked approach:
        // Create PKCS12 with Android's built-in APIs is hard without BouncyCastle.
        // Ship a build-time generated keystore in assets and copy it.
        context.assets.open("stub-keystore.p12").use { inn ->
            ks.outputStream().use { out -> inn.copyTo(out) }
        }
        return ks
    }

    fun buildAndStage(context: Context, appId: String, label: String, iconSource: ByteArray? = null): File {
        val template = File(context.cacheDir, "stub-template.apk")
        if (!template.exists()) {
            context.assets.open("stub-template.apk").use { inn ->
                template.outputStream().use { out -> inn.copyTo(out) }
            }
        }
        val ks = ensureKeystore(context)
        val out = File(context.cacheDir, "stub-$appId.apk")
        val icon = makeIconPng(label, iconSource?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) })
        StubFactory.build(
            context = context,
            template = template,
            pkg = stubPackageFor(appId),
            label = label,
            appId = appId,
            iconPng = icon,
            keystore = ks,
            keystorePass = KS_PASS.toCharArray(),
            out = out,
        )
        return out
    }

    fun install(context: Context, apk: File): Int {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                FileInputStream(apk).use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
        Log.i(TAG, "committed session $sessionId for ${apk.name}")
        return sessionId
    }

    fun uninstall(context: Context, stubPackage: String) {
        val pi = context.packageManager.packageInstaller
        val callback = PendingIntent.getBroadcast(
            context, stubPackage.hashCode(),
            Intent(context, InstallResultReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        pi.uninstall(stubPackage, callback.intentSender)
    }

    /** 192px launcher icon: the app's real icon on a rounded light tile (or a letter tile as fallback). */
    private fun makeIconPng(label: String, src: Bitmap?): ByteArray {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (src != null) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F4F4F6") }
            c.drawRoundRect(android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), 44f, 44f, bg)
            val pad = 28
            c.drawBitmap(src, null, android.graphics.Rect(pad, pad, size - pad, size - pad), Paint(Paint.FILTER_BITMAP_FLAG))
            val os = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os); bmp.recycle()
            return os.toByteArray()
        }
        c.drawColor(Color.parseColor("#1A1A1A"))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF6B2C")
            textSize = 72f
            textAlign = Paint.Align.CENTER
        }
        val letter = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        c.drawText(letter, size / 2f, size / 2f + 24f, p)
        val os = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
        bmp.recycle()
        return os.toByteArray()
    }
}
