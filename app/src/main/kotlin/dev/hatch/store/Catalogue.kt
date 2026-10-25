package dev.hatch.store

import dev.hatch.guest.RootfsCatalog
import android.content.Context
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AptRepo(
    /** File stem for /etc/apt/sources.list.d/<id>.sources and /etc/apt/keyrings/<id>.asc */
    val id: String,
    val keyUrl: String,
    /** deb822 "URIs:", "Suites:", "Components:" values. */
    val uris: String,
    val suites: String,
    val components: String = "main",
)

@Serializable
data class WindowsPayload(
    val url: String,
    val sha256: String = "",
    val extractTo: String,
    val kind: String = "zip",
)

@Serializable
data class CatalogueCategory(val id: String, val name: String)

@Serializable
data class StoreApp(
    val id: String,
    val name: String,
    val summary: String,
    val source: String,
    val category: String = "system",
    val icon: String = "",
    val packages: List<String> = emptyList(),
    val command: List<String>,
    val wine: Boolean = false,
    val x11: Boolean = false,
    val windowsPayload: WindowsPayload? = null,
    /** Per guest-arch overrides ("amd64" for x86_64 devices/emulators); [windowsPayload] is the arm64 default. */
    val payloads: Map<String, WindowsPayload> = emptyMap(),
    /** source == "flathub": the Flathub app id (installed with flatpak, run by Hatch without bubblewrap). */
    val flatpak: String = "",
    /** source == "vendor": the vendor's own signed apt repository (added on install), e.g. Anysphere for Cursor. */
    val vendorName: String = "",
    val repo: AptRepo? = null,
    val description: String = "",
    val desktopId: String = "",
    val iconUrl: String = "",
    val screenshots: List<String> = emptyList(),
    val license: String = "",
    val featured: Boolean = false,
    /** Exercised end-to-end in CI (arm64 runner screenshots). */
    val verified: Boolean = false,
    /** Works only partly or on some devices (e.g. Steam); badged "Experimental" in the store. */
    val experimental: Boolean = false,
    /** Guest arches this entry works on ("arm64", "amd64"); empty = all. */
    val arches: List<String> = emptyList(),
)

@Serializable
data class CatalogueFile(
    val version: Int = 1,
    val updated: String = "",
    val categories: List<CatalogueCategory> = emptyList(),
    val apps: List<StoreApp> = emptyList(),
)

object Catalogue {
    @Volatile private var memo: CatalogueFile? = null

    fun cacheFile(context: Context) = File(context.filesDir, "catalogue.json")
    fun invalidate() { memo = null }

    fun loadFile(context: Context): CatalogueFile = memo ?: loadUncached(context).also { memo = it }

    /** Verified remote copy (written only by [RemoteCatalogue]) unless the bundled one is newer or the cache is bad. */
    private fun loadUncached(context: Context): CatalogueFile {
        val cached = runCatching { cacheFile(context).takeIf { it.exists() }?.readText() }.getOrNull()
        val bundled = runCatching { context.assets.open("catalogue.json").bufferedReader().readText() }.getOrElse { "{\"apps\":[]}" }
        return CatalogueCodec.choose(cached, bundled).forArch(RootfsCatalog.lxcArch())
    }

    fun load(context: Context): List<StoreApp> = loadFile(context).apps
}

/** Only the entries that can run on a guest of [arch] (e.g. the native ARM64 Steam client is hidden on x86-64). */
fun CatalogueFile.forArch(arch: String): CatalogueFile = copy(apps = apps.filter { it.arches.isEmpty() || arch in it.arches })

/** The Windows payload to install on a guest of [arch] ("arm64" | "amd64"). */
fun StoreApp.payloadFor(arch: String): WindowsPayload? = payloads[arch] ?: windowsPayload
