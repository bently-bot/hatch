package dev.hatch

import dev.hatch.store.CatalogueFile
import dev.hatch.store.payloadFor
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

    @Test
    fun windowsPayloadPerArch() {
        val npp = load().apps.first { it.id == "notepadpp" }
        assertTrue(npp.payloadFor("arm64")!!.url.contains("arm64"))
        assertTrue(npp.payloadFor("amd64")!!.url.contains("x64"))
        assertEquals(64, npp.payloadFor("amd64")!!.sha256.length)
    }

    @Test
    fun vendorAppsCarryTheirRepo() {
        val apps = load().apps.associateBy { it.id }
        for (id in listOf("cursor", "code", "brave-browser")) {
            val a = apps.getValue(id)
            assertEquals("vendor", a.source)
            assertTrue(a.vendorName.isNotBlank())
            val r = a.repo!!
            assertTrue(r.keyUrl.startsWith("https://") && r.uris.startsWith("https://"))
            assertTrue("$id needs --no-sandbox under proot", "--no-sandbox" in a.command)
        }
        assertTrue(apps.getValue("blender").x11)
    }

    @Test
    fun buildPickerGroupsSourcesRecommendedFirst() {
        fun app(id: String, name: String, source: String) = dev.hatch.store.StoreApp(id = id, name = name, summary = "", source = source, command = listOf(id))
        val all = listOf(app("firefox-esr", "Firefox ESR", "debian"), app("org.mozilla.firefox", "Firefox", "flathub"),
            app("gimp", "GIMP", "debian"), app("org.gimp.GIMP", "GIMP", "flathub"), app("code", "Visual Studio Code", "vendor"))
        assertEquals(listOf("org.gimp.GIMP", "gimp"), dev.hatch.ui.buildsOf(all[2], all).map { it.id })
        assertEquals(listOf("firefox-esr"), dev.hatch.ui.buildsOf(all[0], all).map { it.id })
        val real = load().apps
        assertEquals(listOf("org.darktable.Darktable"), dev.hatch.ui.buildsOf(real.first { it.id == "org.darktable.Darktable" }, real).map { it.id })
    }
}
