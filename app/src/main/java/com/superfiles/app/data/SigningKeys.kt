package com.superfiles.app.data

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

class SigningKey(val key: PrivateKey, val cert: X509Certificate)

/**
 * The key APKs are signed with. A self-signed RSA-2048 key is generated on first use, or you can
 * import your own PKCS12 / BKS keystore. Either way it lives in the app's private storage.
 */
object SigningKeys {
    private const val ALIAS = "superfiles"

    /** Old public password, kept only to migrate keystores created by earlier builds. New
     *  keystores are protected by the per-device password from [KeyPassword]. */
    private val LEGACY_PASS = "superfiles".toCharArray()

    private fun file(ctx: Context) = File(ctx.filesDir, "signing.p12")

    fun get(ctx: Context): SigningKey {
        val f = file(ctx)
        val pass = KeyPassword.get(ctx)
        val ks = KeyStore.getInstance("PKCS12")
        if (f.exists()) {
            try {
                f.inputStream().use { ks.load(it, pass) }
            } catch (_: Exception) {
                // Keystore from an older build: open it with the legacy password, then
                // re-wrap it under the per-device password.
                val legacy = KeyStore.getInstance("PKCS12")
                f.inputStream().use { legacy.load(it, LEGACY_PASS) }
                f.outputStream().use { legacy.store(it, pass) }
                f.inputStream().use { ks.load(it, pass) }
            }
        } else {
            ks.load(null, null)
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=SuperFiles Signing Key")
            val now = System.currentTimeMillis()
            val cert = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                    Date(now + 30L * 365 * 86_400_000L), name, kp.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            )
            ks.setKeyEntry(ALIAS, kp.private, pass, arrayOf(cert))
            f.outputStream().use { ks.store(it, pass) }
        }
        return SigningKey(
            ks.getKey(ALIAS, pass) as PrivateKey,
            ks.getCertificate(ALIAS) as X509Certificate,
        )
    }

    /** Replaces the current key with the first private key found in [data] (PKCS12 or BKS). */
    fun import(ctx: Context, data: ByteArray, password: CharArray): SigningKey {
        val src = open(data, password)
        val alias = src.aliases().toList().firstOrNull { src.isKeyEntry(it) }
            ?: error("No private key found in this keystore")
        val key = src.getKey(alias, password) as? PrivateKey
            ?: error("Couldn't read the key (it may have a different password than the keystore)")
        val chain = src.getCertificateChain(alias) ?: error("Keystore entry has no certificate")

        val pass = KeyPassword.get(ctx)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, key, pass, chain)
        file(ctx).outputStream().use { ks.store(it, pass) }
        return SigningKey(key, chain[0] as X509Certificate)
    }

    /** Deletes the stored key; a fresh one is generated the next time it's needed. */
    fun reset(ctx: Context) {
        file(ctx).delete()
    }

    fun describe(ctx: Context): String {
        val k = get(ctx)
        val sha = MessageDigest.getInstance("SHA-256").digest(k.cert.encoded)
            .joinToString(":") { "%02X".format(it) }
        return "${k.cert.subjectX500Principal.name}\nSHA-256 $sha"
    }

    private fun open(data: ByteArray, password: CharArray): KeyStore {
        val makers = listOf<() -> KeyStore>(
            { KeyStore.getInstance("PKCS12", BouncyCastleProvider()) },
            { KeyStore.getInstance("BKS", BouncyCastleProvider()) },
            { KeyStore.getInstance("PKCS12") },
        )
        var last: Exception? = null
        for (make in makers) {
            try {
                return make().also { it.load(ByteArrayInputStream(data), password) }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalArgumentException(
            "Couldn't open the keystore. Check the password; JKS files need converting to PKCS12 first " +
                "(${last?.message})"
        )
    }
}
