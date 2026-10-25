package dev.hatch

import com.android.apksig.ApkVerifier
import dev.hatch.stub.StubFactory
import dev.hatch.stub.StubKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StubKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun noSharedKeyShipsInTheApp() {
        assertFalse("bundled stub keystore must not exist", File("src/main/assets/stub-keystore.p12").exists())
        val src = File("src/main/kotlin/dev/hatch/stub").walk().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse("no hard-coded keystore password", Regex("""KS_PASS\s*=""").containsMatchIn(src))
    }

    @Test fun fileSignerIsCreatedOnceAndReused() {
        val dir = tmp.newFolder()
        assertNull(StubKeys.FileSigner.load(dir))
        val a = StubKeys.FileSigner.loadOrCreate(dir)
        val b = StubKeys.FileSigner.loadOrCreate(dir)
        assertArrayEquals(a.cert.encoded, b.cert.encoded)
        assertEquals(a.key.encoded.toList(), b.key.encoded.toList())
        assertEquals("file", a.kind)
        assertTrue(File(dir, StubKeys.FILE).exists())
        StubKeys.probe(a.key, a.cert)
    }

    @Test fun eachInstallGetsItsOwnKeyAndRandomPassword() {
        val d1 = tmp.newFolder(); val d2 = tmp.newFolder()
        val a = StubKeys.FileSigner.loadOrCreate(d1); val b = StubKeys.FileSigner.loadOrCreate(d2)
        assertNotEquals(a.cert.publicKey, b.cert.publicKey)
        val p1 = File(d1, StubKeys.PASS_FILE).readText(); val p2 = File(d2, StubKeys.PASS_FILE).readText()
        assertNotEquals(p1, p2)
        assertTrue("password should be long and random", p1.length >= 40)
        assertTrue(File(d1, StubKeys.PASS_FILE).canRead())
    }

    @Test fun wrongPasswordDoesNotLoad() {
        val dir = tmp.newFolder()
        StubKeys.FileSigner.loadOrCreate(dir)
        File(dir, StubKeys.PASS_FILE).writeText("not-the-password")
        assertNull(StubKeys.FileSigner.load(dir))
    }

    @Test fun stubIsSignedWithThePerInstallKey() {
        val template = File("src/main/assets/stub-template.apk")
        org.junit.Assume.assumeTrue(template.exists())
        val signer = StubKeys.FileSigner.loadOrCreate(tmp.newFolder())
        val out = File(tmp.root, "stub-test.apk")
        StubFactory.build(null, template, "dev.hatch.app.unit_test", "Unit Test", "unit-test", ByteArray(0).let { png1x1() }, signer, out)
        val r = ApkVerifier.Builder(out).setMinCheckedPlatformVersion(29).build().verify()
        assertTrue("verify errors: ${r.errors}", r.isVerified)
        assertTrue(r.isVerifiedUsingV2Scheme || r.isVerifiedUsingV3Scheme)
        assertArrayEquals(signer.cert.encoded, r.signerCertificates.first().encoded)
    }

    private fun png1x1(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
}
