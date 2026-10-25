package dev.hatch.stub

import android.content.Context
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/**
 * The key that signs per-app launcher stubs. It is generated on this phone at first use and never leaves it:
 *  1. preferred: an EC P-256 key in AndroidKeyStore (non-exportable; apksig signs through java.security.Signature);
 *  2. fallback (if the keystore can't generate/sign): a PKCS12 in the app's private files with a random per-install password.
 * Once one exists it is reused forever, so updated stubs keep the same signer.
 */
object StubKeys {
    private const val TAG = "HatchStubKeys"
    const val ALIAS = "hatch-stub-signer"
    const val FILE = "stub-signer.p12"
    const val PASS_FILE = "stub-signer.pass"
    /** Pre-2026-10-25 builds copied a shared, bundled key here; it must never be used again. */
    const val LEGACY_FILE = "stub-keystore.p12"

    data class Signer(val key: PrivateKey, val cert: X509Certificate, val kind: String)

    @Synchronized
    fun get(context: Context): Signer {
        val dir = context.filesDir
        File(dir, LEGACY_FILE).takeIf { it.exists() }?.let { it.delete(); Log.i(TAG, "deleted legacy shared stub key") }
        FileSigner.load(dir)?.let { return it }
        runCatching { androidKeyStore(create = true) }
            .onFailure { Log.w(TAG, "AndroidKeyStore unavailable (${it.message}); using a private-file key") }
            .getOrNull()?.let { return it }
        return FileSigner.loadOrCreate(dir)
    }

    private fun androidKeyStore(create: Boolean): Signer {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            check(create) { "no key" }
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS, android.security.keystore.KeyProperties.PURPOSE_SIGN,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=Hatch app launcher, O=Hatch"))
                .setCertificateSerialNumber(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 100L * 365 * 86_400_000L))
                .build()
            KeyPairGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(spec) }.generateKeyPair()
            Log.i(TAG, "generated stub signer in AndroidKeyStore")
        }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        runCatching { probe(key, cert) }.onFailure {
            runCatching { ks.deleteEntry(ALIAS) }
            throw it
        }
        return Signer(key, cert, "androidkeystore")
    }

    /** Sign and verify a probe the same way apksig will (SHA256withECDSA). */
    fun probe(key: PrivateKey, cert: X509Certificate) {
        val data = "hatch-stub-probe".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(cert.publicKey); update(data); verify(sig) }
        check(ok) { "probe signature did not verify" }
    }

    /** Fallback signer: PKCS12 in private storage, random per-install password. Pure JVM, unit-tested. */
    object FileSigner {
        fun load(dir: File): Signer? {
            val f = File(dir, FILE); val p = File(dir, PASS_FILE)
            if (!f.exists() || !p.exists()) return null
            return runCatching {
                val pass = p.readText().trim().toCharArray()
                val ks = KeyStore.getInstance("PKCS12").apply { f.inputStream().use { load(it, pass) } }
                Signer(ks.getKey("stub", pass) as PrivateKey, ks.getCertificate("stub") as X509Certificate, "file")
            }.getOrNull()
        }

        fun loadOrCreate(dir: File, now: Long = System.currentTimeMillis()): Signer {
            load(dir)?.let { return it }
            dir.mkdirs()
            val pwBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val pass = Base64.getUrlEncoder().withoutPadding().encodeToString(pwBytes)
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val bc = org.bouncycastle.jce.provider.BouncyCastleProvider()
            val name = org.bouncycastle.asn1.x500.X500Name("CN=Hatch app launcher, O=Hatch")
            val holder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
                Date(now - 86_400_000L), Date(now + 100L * 365 * 86_400_000L), name, kp.public,
            ).build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").setProvider(bc).build(kp.private))
            val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().setProvider(bc).getCertificate(holder)
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            ks.setKeyEntry("stub", kp.private, pass.toCharArray(), arrayOf(cert))
            val pf = File(dir, PASS_FILE); val f = File(dir, FILE)
            writePrivate(pf) { it.write(pass.toByteArray()) }
            writePrivate(f) { ks.store(it, pass.toCharArray()) }
            return load(dir) ?: error("stub signer was not readable after creation")
        }

        private fun writePrivate(f: File, body: (java.io.OutputStream) -> Unit) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.delete(); tmp.createNewFile()
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
            tmp.outputStream().use(body)
            check(tmp.renameTo(f)) { "rename ${tmp.name}" }
        }
    }
}
