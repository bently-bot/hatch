package dev.hatch.store

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.hatch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Remotely maintained store catalogue: fetched on launch and every 12 h (WorkManager), conditional on ETag,
 * Ed25519-verified against the key baked into the app, then cached in filesDir. Any failure keeps the
 * current cache, and with no valid cache the bundled assets/catalogue.json is used (see [Catalogue]).
 */
object RemoteCatalogue {
    private const val TAG = "HatchCatalogue"
    private const val PREFS = "hatch"
    const val PREF_URL = "catalogue_url"
    private const val PREF_ETAG = "catalogue_etag"
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    fun url(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.CATALOGUE_URL

    enum class Result { UPDATED, NOT_MODIFIED, FAILED }

    suspend fun refresh(ctx: Context): Result = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = url(ctx)
        val cache = Catalogue.cacheFile(ctx)
        runCatching {
            val req = Request.Builder().url(url).apply {
                val etag = prefs.getString(PREF_ETAG, null)
                if (etag != null && cache.exists()) header("If-None-Match", etag)
            }.build()
            http.newCall(req).execute().use { r ->
                if (r.code == 304) { Log.i(TAG, "not modified"); return@runCatching Result.NOT_MODIFIED }
                if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code} for $url"); return@runCatching Result.FAILED }
                val data = CatalogueCodec.gunzipIfNeeded(r.body!!.bytes())
                val sig = http.newCall(Request.Builder().url(CatalogueCodec.sigUrl(url)).build()).execute().use { s ->
                    if (!s.isSuccessful) { Log.w(TAG, "no signature (HTTP ${s.code})"); return@runCatching Result.FAILED }
                    s.body!!.bytes()
                }
                if (!CatalogueCodec.verify(data, sig, BuildConfig.CATALOGUE_PUBKEY)) {
                    Log.e(TAG, "signature check FAILED; keeping the current catalogue"); return@runCatching Result.FAILED
                }
                val text = String(data, Charsets.UTF_8)
                val parsed = CatalogueCodec.parse(text)
                if (parsed == null || parsed.apps.isEmpty()) { Log.w(TAG, "unusable catalogue (schema/empty)"); return@runCatching Result.FAILED }
                val tmp = File(cache.parentFile, cache.name + ".tmp")
                tmp.writeText(text); if (!tmp.renameTo(cache)) { cache.delete(); tmp.renameTo(cache) }
                prefs.edit().putString(PREF_ETAG, r.header("ETag")).apply()
                Catalogue.invalidate()
                Log.i(TAG, "updated: ${parsed.apps.size} apps (${parsed.updated})")
                Result.UPDATED
            }
        }.getOrElse { Log.w(TAG, "refresh failed: $it"); Result.FAILED }
    }

    /** Launch refresh + periodic refresh (network required). */
    fun schedule(ctx: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniqueWork("catalogue-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<Worker>().setConstraints(net).build())
        wm.enqueueUniquePeriodicWork("catalogue-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Worker>(12, TimeUnit.HOURS).setConstraints(net).build())
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result =
            when (refresh(applicationContext)) {
                RemoteCatalogue.Result.FAILED -> if (runAttemptCount < 3) androidx.work.ListenableWorker.Result.retry() else androidx.work.ListenableWorker.Result.success()
                else -> androidx.work.ListenableWorker.Result.success()
            }
    }
}
