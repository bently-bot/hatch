package dev.hatch.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.hatch.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Over-the-air updates of Hatch itself from GitHub Releases ([BuildConfig.UPDATE_FEED], public, no token):
 * 1. `releases/latest` (drafts and prereleases are never "latest"; [SelfUpdates.pickOffer] filters them anyway).
 *    Rate-limited API → the public `github.com/<repo>/releases/latest` redirect gives the tag instead.
 * 2. Download `hatch-<v>.apk` with progress; its SHA-256 must match the release's `hatch-<v>.apk.sha256`.
 * 3. The APK must be Hatch (same package, higher versionCode) signed with the installed app's certificate.
 * 4. Running Linux/Windows apps are stopped (after asking) and the runtime shut down; then a PackageInstaller
 *    session installs it. "Install unknown apps" is asked for once, with a deep link and an automatic resume.
 */
object SelfUpdater {
    private const val TAG = "HatchUpdate"
    private const val PREFS = "hatch_update"
    const val ACTION_RESULT = "dev.hatch.action.SELF_UPDATE_RESULT"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val offer: UpdateOffer) : State
        data class Downloading(val offer: UpdateOffer, val done: Long, val total: Long) : State {
            val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        }
        data class Verifying(val offer: UpdateOffer) : State
        /** "Install unknown apps" isn't allowed for Hatch yet: one tap opens the setting, Hatch resumes after. */
        data class NeedsPermission(val offer: UpdateOffer, val apk: File) : State
        /** Linux/Windows apps are running: ask before stopping them. */
        data class ConfirmStop(val offer: UpdateOffer, val apk: File, val running: List<String>) : State
        data class Installing(val offer: UpdateOffer, val message: String) : State
        data class Failed(val message: String, val offer: UpdateOffer? = null) : State
        /** Shown once after Hatch was updated (by any route). */
        data class Updated(val version: String) : State
    }

    /** Outlives the Activity (the permission screen, rotation, fold): downloads keep going. */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun autoCheckEnabled(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoCheckEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()
    fun skippedVersion(ctx: Context): String? = prefs(ctx).getString("skipped", null)
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun updatesDir(ctx: Context) = File(ctx.cacheDir, "updates")

    /** On start: "Updated to x" once after an update; tidy the downloaded APK away. */
    fun onAppStart(ctx: Context) {
        val p = prefs(ctx); val last = p.getInt("last_code", 0)
        if (last in 1 until BuildConfig.VERSION_CODE && _state.value == State.Idle) _state.value = State.Updated(BuildConfig.VERSION_NAME)
        if (last != BuildConfig.VERSION_CODE) { p.edit().putInt("last_code", BuildConfig.VERSION_CODE).apply(); updatesDir(ctx).deleteRecursively() }
    }

    fun dismiss() { _state.value = State.Idle }
    /** Later: hide it until the next check (store start after the throttle, or Check for updates). */
    fun later(ctx: Context) { prefs(ctx).edit().putLong("last_check", System.currentTimeMillis()).apply(); dismiss() }
    fun skip(ctx: Context, version: String) { prefs(ctx).edit().putString("skipped", version).apply(); dismiss() }

    /** Store start: at most every 6 h, quiet on errors, never re-offers a skipped version. */
    suspend fun autoCheck(ctx: Context) {
        val now = System.currentTimeMillis()
        if (!SelfUpdates.dueForAutoCheck(autoCheckEnabled(ctx), now, prefs(ctx).getLong("last_check", 0))) return
        if (_state.value != State.Idle) return
        val offer = runCatching { fetchOffer() }.onFailure { Log.i(TAG, "auto check: ${it.message}") }.getOrElse { return }
        prefs(ctx).edit().putLong("last_check", now).apply()
        if (offer != null && offer.version != skippedVersion(ctx) && _state.value == State.Idle) _state.value = State.Available(offer)
    }

    /** Settings → Check for updates: always reports (and offers even a skipped version). */
    suspend fun checkNow(ctx: Context) {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = State.Checking
        _state.value = try {
            val offer = fetchOffer()
            prefs(ctx).edit().putLong("last_check", System.currentTimeMillis()).apply()
            if (offer == null) State.UpToDate(currentVersion) else State.Available(offer)
        } catch (e: UpdateException) { State.Failed(e.message ?: "Update check failed")
        } catch (e: IOException) { State.Failed("Couldn't reach GitHub. Check your connection and try again.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Log.w(TAG, "check failed", e); State.Failed("Couldn't read the update info from GitHub.") }
    }

    /** Null when this is the newest release. */
    suspend fun fetchOffer(): UpdateOffer? = withContext(Dispatchers.IO) {
        val feed = BuildConfig.UPDATE_FEED
        val (code, body) = get(feed, "application/vnd.github+json")
        val releases = when (code) {
            200 -> SelfUpdates.parseReleases(body)
            403, 429 -> listOfNotNull(viaRedirect(feed)) .ifEmpty { throw UpdateException("GitHub is limiting update checks right now. Try again later.") }
            404 -> emptyList()
            else -> throw UpdateException("Update check failed (HTTP $code).")
        }
        SelfUpdates.pickOffer(releases, currentVersion)
    }

    /** `https://github.com/<o>/<r>/releases/latest` redirects to `/tag/<tag>`; assets have fixed download URLs. */
    private fun viaRedirect(feed: String): GhRelease? {
        val repo = Regex("""api\.github\.com/repos/([^/]+/[^/]+)/releases""").find(feed)?.groupValues?.get(1) ?: return null
        val c = open("https://github.com/$repo/releases/latest").apply { instanceFollowRedirects = false; requestMethod = "HEAD" }
        val loc = try { c.responseCode; c.getHeaderField("Location") } finally { c.disconnect() } ?: return null
        val tag = loc.substringAfter("/releases/tag/", "").takeIf { it.isNotEmpty() } ?: return null
        val v = tag.removePrefix("v"); val base = "https://github.com/$repo/releases/download/$tag/"
        return GhRelease(tag, "Hatch $v", "See what's new on GitHub.", htmlUrl = "https://github.com/$repo/releases/tag/$tag",
            assets = listOf(GhAsset(SelfUpdates.apkName(v), 0, base + SelfUpdates.apkName(v)), GhAsset(SelfUpdates.apkName(v) + ".sha256", 0, base + SelfUpdates.apkName(v) + ".sha256")))
    }

    /** Update: download + verify, then the permission / stop-apps steps, then install. */
    suspend fun update(ctx: Context, offer: UpdateOffer) {
        val now = _state.value
        if (now is State.Downloading || now is State.Verifying || now is State.Installing) return   // double tap
        try {
            val apk = withContext(Dispatchers.IO) { downloadVerified(ctx, offer) }
            proceed(ctx, offer, apk)
        } catch (e: UpdateException) { _state.value = State.Failed(e.message ?: "Update failed", offer)
        } catch (e: IOException) { _state.value = State.Failed("Download failed: ${e.message ?: "network error"}. Try again.", offer)
        } catch (e: kotlinx.coroutines.CancellationException) { _state.value = State.Available(offer); throw e
        } catch (e: Exception) { Log.e(TAG, "update failed", e); _state.value = State.Failed("The update failed: ${e.message}", offer) }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()
    fun permissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Back from the "Install unknown apps" screen: carry on if it's allowed now. */
    fun onResume(ctx: Context) {
        val s = _state.value
        if (s is State.NeedsPermission && canInstall(ctx)) proceed(ctx, s.offer, s.apk)
    }

    private fun runningApps(ctx: Context): List<String> {
        val ids = dev.hatch.guest.AppSession.runningIds()
        val names = ids.map { id -> dev.hatch.store.InstalledApps.get(ctx, id)?.name ?: id }
        val windows = if (ids.isEmpty() && dev.hatch.floating.FloatingWindows.all.isNotEmpty()) listOf("open windows") else emptyList()
        return (names + windows).distinct()
    }

    private fun proceed(ctx: Context, offer: UpdateOffer, apk: File) {
        _state.value = when {
            !canInstall(ctx) -> State.NeedsPermission(offer, apk)
            dev.hatch.store.InstallManager.progress.value is dev.hatch.store.InstallManager.Progress.Working ||
                dev.hatch.guest.RootfsInstaller.isRunning ->
                State.Failed("An app is being installed or removed. Update Hatch when that's finished.", offer)
            runningApps(ctx).isNotEmpty() -> State.ConfirmStop(offer, apk, runningApps(ctx))
            else -> { install(ctx, offer, apk); return }
        }
    }

    /** "Stop apps and update" on the ConfirmStop prompt. */
    fun stopAppsAndInstall(ctx: Context) {
        val s = _state.value as? State.ConfirmStop ?: return
        install(ctx, s.offer, s.apk, "Closing apps…")
    }

    private fun install(ctx: Context, offer: UpdateOffer, apk: File, first: String = "Installing…") {
        _state.value = State.Installing(offer, first)
        Thread({
            try {
                // Nothing of Linux may be running while Android replaces Hatch: guests, overlays, the runtime service.
                dev.hatch.runtime.RuntimeControl.stopAllAndWait(ctx, 10_000, "self-update")
                val left = dev.hatch.runtime.RuntimeControl.guestPids(ctx)
                Log.i(TAG, "runtime stopped for the update (service running=${dev.hatch.runtime.HatchRuntimeService.running}, guest processes left=${left.size})")
                _state.value = State.Installing(offer, "Installing…")
                commit(ctx, apk)
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                _state.value = State.Failed("Couldn't start the update: ${e.message}", offer)
            }
        }, "hatch-self-update").start()
    }

    private fun commit(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Once Hatch is the installer of record (after its first in-app update), Android 12+ lets it update
            // itself without the "Update this app?" confirmation (UPDATE_PACKAGES_WITHOUT_USER_ACTION).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, SelfUpdateReceiver::class.java).setAction(ACTION_RESULT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(cb.intentSender)
        }
        Log.i(TAG, "committed self-update session $id (${apk.name}, ${apk.length()} bytes)")
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "self-update status=$status msg=$msg")
        val offer = (_state.value as? State.Installing)?.offer
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (offer != null) _state.value = State.Installing(offer, "Confirm the update in Android's dialog")
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "updated; Android restarts Hatch")
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = offer?.let { State.Available(it) } ?: State.Idle
            else -> _state.value = State.Failed(failureText(status, msg), offer)
        }
    }

    fun failureText(status: Int, msg: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space for the update."
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android refused the update (${msg ?: "conflict"}). Install it by hand from GitHub once."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "The update was blocked (${msg ?: "policy"})."
        else -> "The update didn't install${msg?.let { " ($it)" } ?: ""}. Try again."
    }

    private suspend fun downloadVerified(ctx: Context, offer: UpdateOffer): File {
        _state.value = State.Downloading(offer, 0, offer.apk.size)
        val (sc, sb) = get(offer.sha256Url)
        if (sc != 200) throw UpdateException("Couldn't fetch the update's checksum (HTTP $sc).")
        val want = SelfUpdates.parseSha256(sb, offer.apk.name) ?: throw UpdateException("The update's checksum file is unreadable, so it wasn't installed.")
        val dir = updatesDir(ctx).apply { deleteRecursively(); mkdirs() }
        val part = File(dir, offer.apk.name + ".part"); val target = File(dir, offer.apk.name)
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(offer.apk.url)
        try {
            if (c.responseCode != 200) throw UpdateException("Download failed (HTTP ${c.responseCode}).")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: offer.apk.size
            var done = 0L; var last = 0L
            c.inputStream.use { inp -> part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 150) { last = now; _state.value = State.Downloading(offer, done, total) }
                }
            } }
            _state.value = State.Downloading(offer, done, total)
        } finally { c.disconnect() }
        _state.value = State.Verifying(offer)
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(want, true)) { part.delete(); throw UpdateException("The download didn't match its checksum, so it wasn't installed. Try again.") }
        if (!part.renameTo(target)) throw UpdateException("Couldn't save the update.")
        verifyApk(ctx, target, offer)
        return target
    }

    /** Same package, newer versionCode, same signing certificate as the installed Hatch. */
    private fun verifyApk(ctx: Context, apk: File, offer: UpdateOffer) {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val archive: PackageInfo = (if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else pm.getPackageArchiveInfo(apk.absolutePath, flags)) ?: run { apk.delete(); throw UpdateException("The download isn't a valid APK.") }
        @Suppress("DEPRECATION")
        val mine: PackageInfo = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else pm.getPackageInfo(ctx.packageName, flags)
        if (archive.packageName != ctx.packageName) { apk.delete(); throw UpdateException("The download isn't Hatch (${archive.packageName}).") }
        val archCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (archCode <= BuildConfig.VERSION_CODE) { apk.delete(); throw UpdateException("Hatch ${offer.version} isn't newer than this build (version code $archCode).") }
        fun digests(sigs: Array<android.content.pm.Signature>?) = sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        val si = archive.signingInfo; val mi = mine.signingInfo
        val ok = si != null && mi != null && SelfUpdates.sameSigners(digests(mi.apkContentsSigners), digests(si.apkContentsSigners),
            if (si.hasPastSigningCertificates()) digests(si.signingCertificateHistory) else emptySet())
        if (!ok) { apk.delete(); throw UpdateException("Hatch ${offer.version} is signed with a different key than this Hatch, so it wasn't installed.") }
        Log.i(TAG, "verified ${apk.name}: sha256 ok, versionCode $archCode, same signer")
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Hatch/${BuildConfig.VERSION_NAME} (Android)")
    }

    private fun get(url: String, accept: String? = null): Pair<Int, String> {
        val c = open(url); accept?.let { c.setRequestProperty("Accept", it) }
        return try {
            val code = c.responseCode
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }
}

class UpdateException(message: String) : Exception(message)

class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SelfUpdater.onResult(context, intent)
}
