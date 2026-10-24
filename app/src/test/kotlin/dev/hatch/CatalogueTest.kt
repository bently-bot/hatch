package dev.hatch

import dev.hatch.store.CatalogueFile
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CatalogueTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): CatalogueFile {
        val text = javaClass.classLoader!!.getResourceAsStream("catalogue.json")!!
            .bufferedReader().readText()
        return json.decodeFromString(text)
    }

    @Test
    fun catalogueParsesWithCategories() {
        val cat = load()
        assertTrue(cat.apps.size >= 8)
        assertTrue(cat.categories.isNotEmpty())
        assertTrue(cat.apps.any { it.id == "mousepad" })
        assertTrue(cat.apps.any { it.wine })
        assertTrue(cat.apps.any { it.x11 })
        cat.apps.forEach { app ->
            assertTrue(app.name.isNotBlank())
            assertTrue(app.command.isNotEmpty())
        }
    }

    @Test
    fun winePayloadHasSha() {
        val npp = load().apps.first { it.id == "notepadpp" }
        assertNotNull(npp.windowsPayload)
        assertEquals(64, npp.windowsPayload!!.sha256.length)
    }
}
