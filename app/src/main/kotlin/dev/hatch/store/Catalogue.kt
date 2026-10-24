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
)

@Serializable
data class CatalogueFile(
    val version: Int = 1,
    val updated: String = "",
    val categories: List<CatalogueCategory> = emptyList(),
    val apps: List<StoreApp> = emptyList(),
)

object Catalogue {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadFile(context: Context): CatalogueFile {
        val cached = File(context.filesDir, "catalogue.json")
        val text = when {
            cached.exists() -> cached.readText()
            else -> runCatching {
                context.assets.open("catalogue.json").bufferedReader().readText()
            }.getOrElse { "{\"apps\":[]}" }
        }
        return runCatching { json.decodeFromString<CatalogueFile>(text) }
            .getOrElse { CatalogueFile(apps = emptyList()) }
    }

    fun load(context: Context): List<StoreApp> = loadFile(context).apps

    fun refreshFromUrl(context: Context, url: String): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
        }
        conn.inputStream.bufferedReader().use { r ->
            val body = r.readText()
            json.decodeFromString<CatalogueFile>(body)
            File(context.filesDir, "catalogue.json").writeText(body)
        }
        true
    }.getOrDefault(false)
}
