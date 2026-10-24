package dev.hatch.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** App icons: bundled (assets/icons/<id>.png) → disk cache → AppStream remote URL. */
object AppIcons {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun cacheFile(context: Context, id: String) = File(context.cacheDir, "icons/$id.png")

    /** PNG bytes for [app], or null. Blocking (call off the main thread). */
    fun png(context: Context, app: StoreApp): ByteArray? {
        runCatching { return context.assets.open("icons/${app.id}.png").use { it.readBytes() } }
        val f = cacheFile(context, app.id)
        if (f.exists() && f.length() > 0) return f.readBytes()
        if (app.iconUrl.isBlank()) return null
        return runCatching {
            val c = URL(CatalogueCodec.resolveIcon(RemoteCatalogue.url(context), app.iconUrl)).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Hatch")
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > 8 && bytes[1] == 'P'.code.toByte()) { f.parentFile?.mkdirs(); f.writeBytes(bytes); bytes } else null
        }.getOrNull()
    }

    fun cached(id: String): Bitmap? = mem.get(id)

    fun bitmap(context: Context, app: StoreApp): Bitmap? {
        mem.get(app.id)?.let { return it }
        val bytes = png(context, app) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        mem.put(app.id, bmp)
        return bmp
    }
}
