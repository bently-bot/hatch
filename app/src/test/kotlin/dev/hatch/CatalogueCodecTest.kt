package dev.hatch

import dev.hatch.store.CatalogueCodec
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPOutputStream

class CatalogueCodecTest {
    private val kp = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString((kp.public as Ed25519PublicKeyParameters).encoded)
    private fun sign(b: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, kp.private as Ed25519PrivateKeyParameters); update(b, 0, b.size)
        Base64.getEncoder().encode(generateSignature())
    }

    private fun app(id: String, source: String = "debian", extra: String = "") =
        """{"id":"$id","name":"$id","summary":"s","source":"$source","command":["$id"]$extra}"""
    private fun cat(updated: String, vararg apps: String, schema: Int = 2) =
        """{"schemaVersion":$schema,"updated":"$updated","categories":[{"id":"c","name":"C"}],"apps":[${apps.joinToString(",")}]}"""

    @Test fun validSignatureAccepted() {
        val d = cat("2026-10-24", app("a")).toByteArray()
        assertTrue(CatalogueCodec.verify(d, sign(d), pub))
    }

    @Test fun tamperedDataRejected() {
        val d = cat("2026-10-24", app("a")).toByteArray(); val s = sign(d)
        val t = d.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(CatalogueCodec.verify(t, s, pub))
    }

    @Test fun wrongKeyRejected() {
        val other = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
        val otherPub = Base64.getEncoder().encodeToString((other.public as Ed25519PublicKeyParameters).encoded)
        val d = cat("x", app("a")).toByteArray()
        assertFalse(CatalogueCodec.verify(d, sign(d), otherPub))
    }

    @Test fun garbageSignatureRejected() {
        val d = cat("x", app("a")).toByteArray()
        assertFalse(CatalogueCodec.verify(d, "not-a-signature".toByteArray(), pub))
        assertFalse(CatalogueCodec.verify(d, ByteArray(0), pub))
    }

    @Test fun unknownSourcesAndFieldsSkipped() {
        val f = CatalogueCodec.parse(cat("x", app("a"), app("b", "snap"), app("c", extra = ""","futureField":{"k":1}"""), """{"broken":true}"""))!!
        assertEquals(listOf("a", "c"), f.apps.map { it.id })
    }

    @Test fun newerSchemaRejected() {
        assertNull(CatalogueCodec.parse(cat("x", app("a"), schema = CatalogueCodec.MAX_SCHEMA + 1)))
    }

    @Test fun legacyVersionFieldAccepted() {
        assertEquals(1, CatalogueCodec.parse("""{"version":2,"apps":[${app("a")}]}""")!!.apps.size)
    }

    @Test fun fallbackToBundledWhenCacheMissingCorruptOrEmpty() {
        val bundled = cat("2026-10-01", app("bundled"))
        assertEquals("bundled", CatalogueCodec.choose(null, bundled).apps.single().id)
        assertEquals("bundled", CatalogueCodec.choose("{not json", bundled).apps.single().id)
        assertEquals("bundled", CatalogueCodec.choose(cat("2026-12-01"), bundled).apps.single().id)
        assertEquals("bundled", CatalogueCodec.choose(cat("2026-12-01", app("r"), schema = 99), bundled).apps.single().id)
    }

    @Test fun newerCacheWinsOlderCacheLoses() {
        val bundled = cat("2026-10-01", app("bundled"))
        assertEquals("remote", CatalogueCodec.choose(cat("2026-10-20", app("remote")), bundled).apps.single().id)
        assertEquals("bundled", CatalogueCodec.choose(cat("2026-09-01", app("remote")), bundled).apps.single().id)
    }

    @Test fun gzipRoundTripAndSigUrl() {
        val d = cat("x", app("a")).toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(d) } }.toByteArray()
        assertTrue(CatalogueCodec.gunzipIfNeeded(gz).contentEquals(d))
        assertTrue(CatalogueCodec.gunzipIfNeeded(d).contentEquals(d))
        assertEquals("https://h/r/catalogue.json.sig", CatalogueCodec.sigUrl("https://h/r/catalogue.json.gz"))
        assertEquals("https://h/r/catalogue.json.sig", CatalogueCodec.sigUrl("https://h/r/catalogue.json"))
    }

    @Test fun bundledCatalogueParses() {
        val f = CatalogueCodec.parse(java.io.File("src/main/assets/catalogue.json").readText())!!
        assertTrue(f.apps.size > 1000)
    }
}
