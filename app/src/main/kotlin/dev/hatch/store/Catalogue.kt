package dev.hatch.store

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class WindowsPayload(
    val url: String,
    val sha256: String = "",
    val extractTo: String,
    val kind: String = "zip",
)

@Serializable
data class StoreApp(
    val id: String,
    val name: String,
    val summary: String,
    val source: String,
    val icon: String = "",
    val packages: List<String> = emptyList(),
    val command: List<String>,
    val wine: Boolean = false,
    val windowsPayload: WindowsPayload? = null,
)

@Serializable
data class CatalogueFile(val version: Int = 1, val updated: String = "", val apps: List<StoreApp> = emptyList())

object Catalogue {
    private val json = Json { ignoreUnknownKeys = true }

    /** Bundled seed (assets/catalogue.json); refreshed from GitHub release when online. */
    fun load(context: Context): List<StoreApp> {
        val cached = File(context.filesDir, "catalogue.json")
        val text = when {
            cached.exists() -> cached.readText()
            else -> context.assets.open("catalogue.json").bufferedReader().readText()
        }
        return runCatching { json.decodeFromString<CatalogueFile>(text).apps }.getOrDefault(seed)
    }

    fun refreshFromUrl(context: Context, url: String): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
        }
        conn.inputStream.bufferedReader().use { r ->
            val body = r.readText()
            json.decodeFromString<CatalogueFile>(body) // validate
            File(context.filesDir, "catalogue.json").writeText(body)
        }
        true
    }.getOrDefault(false)

    /** Fallback if assets missing. */
    val seed = listOf(
        StoreApp("mousepad", "Mousepad", "Simple text editor", "debian", packages = listOf("mousepad"), command = listOf("mousepad")),
        StoreApp("galculator", "Calculator", "GTK calculator", "debian", packages = listOf("galculator"), command = listOf("galculator")),
        StoreApp("gnome-mines", "Mines", "Minesweeper", "debian", packages = listOf("gnome-mines"), command = listOf("gnome-mines")),
        StoreApp("weston-simple-shm", "Simple SHM", "Compositor smoke test", "debian", packages = listOf("weston"), command = listOf("weston-simple-shm")),
        StoreApp(
            "notepadpp", "Notepad++", "Windows text editor (ARM64 Wine)", "winget",
            packages = listOf("wine", "wine64", "libwine", "unzip", "ca-certificates", "curl"),
            command = listOf("wine64", "/opt/hatch/npp/notepad++.exe"),
            wine = true,
            windowsPayload = WindowsPayload(
                url = "https://github.com/notepad-plus-plus/notepad-plus-plus/releases/download/v8.9.8.1/npp.8.9.8.1.portable.arm64.zip",
                sha256 = "43bf78c7569fd43b03d4f86a1073ac4306b31099e4487bdbf536c42d16d03f48",
                extractTo = "/opt/hatch/npp",
            ),
        ),
    )
}
