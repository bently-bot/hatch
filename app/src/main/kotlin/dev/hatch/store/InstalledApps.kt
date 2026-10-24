package dev.hatch.store

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class InstalledRecord(
    val id: String,
    val name: String,
    val source: String,
    val stubPackage: String,
    val command: List<String>,
    val wine: Boolean = false,
    val x11: Boolean = false,
)

object InstalledApps {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private fun file(ctx: Context) = File(ctx.filesDir, "installed.json")

    fun list(ctx: Context): List<InstalledRecord> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<InstalledRecord>>(f.readText()) }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, id: String): InstalledRecord? = list(ctx).find { it.id == id }

    fun put(ctx: Context, rec: InstalledRecord) {
        val cur = list(ctx).filterNot { it.id == rec.id } + rec
        file(ctx).writeText(json.encodeToString(cur))
    }

    fun remove(ctx: Context, id: String) {
        file(ctx).writeText(json.encodeToString(list(ctx).filterNot { it.id == id }))
    }
}
