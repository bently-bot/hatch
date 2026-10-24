package dev.hatch.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Pure (Android-free) pieces of the remote catalogue: Ed25519 verification, gzip, forward-compatible parsing and
 * the cache-vs-bundled choice. Unit-tested in CatalogueCodecTest.
 */
object CatalogueCodec {
    /** Highest catalogue schema this build understands. Newer *major* schemas are rejected (keep current data). */
    const val MAX_SCHEMA = 2
    /** Install types this build can handle; entries of other (future) types are skipped, not fatal. */
    val KNOWN_SOURCES = setOf("debian", "vendor", "flathub", "release", "winget", "wine")

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /** The signature covers the *uncompressed* JSON bytes; [sig] is base64 (as published) or raw 64 bytes. */
    fun verify(data: ByteArray, sig: ByteArray, publicKeyB64: String): Boolean = runCatching {
        val raw = if (sig.size == 64) sig else Base64.getDecoder().decode(String(sig, Charsets.US_ASCII).trim())
        if (raw.size != 64) return false
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(Base64.getDecoder().decode(publicKeyB64.trim()), 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(raw)
    }.getOrDefault(false)

    fun gunzipIfNeeded(b: ByteArray): ByteArray =
        if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(b)).readBytes() else b

    /**
     * Parse leniently: unknown fields are ignored, entries with an unknown `source` or that fail to decode are
     * skipped individually, and a schema newer than [MAX_SCHEMA] (field `schemaVersion`, falling back to `version`)
     * is rejected as a whole (returns null) so an old app never misreads a breaking format.
     */
    fun parse(text: String): CatalogueFile? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val schema = (root["schemaVersion"] ?: root["version"])?.jsonPrimitive?.intOrNull ?: 1
        if (schema > MAX_SCHEMA) return null
        val apps = (root["apps"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val src = o["source"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (src !in KNOWN_SOURCES) return@mapNotNull null
            runCatching { json.decodeFromJsonElement<StoreApp>(o) }.getOrNull()
        }
        val cats = runCatching { json.decodeFromJsonElement<List<CatalogueCategory>>(root["categories"] ?: JsonArray(emptyList())) }
            .getOrDefault(emptyList())
        CatalogueFile(
            version = schema,
            updated = root["updated"]?.jsonPrimitive?.content.orEmpty(),
            categories = cats,
            apps = apps,
        )
    }.getOrNull()

    /**
     * Cached remote copy vs the catalogue bundled in the APK: use the cache if it parses, has apps and isn't older
     * than the bundled one (an app update can ship a newer bundled list); otherwise the bundled asset.
     */
    fun choose(cached: String?, bundled: String): CatalogueFile {
        val b = parse(bundled) ?: CatalogueFile()
        val c = cached?.let { parse(it) }?.takeIf { it.apps.isNotEmpty() } ?: return b
        return if (c.updated >= b.updated) c else b
    }

    /** Signature URL for a catalogue URL: ".../catalogue.json(.gz)" → ".../catalogue.json.sig". */
    fun sigUrl(url: String): String = url.removeSuffix(".gz") + ".sig"
}
