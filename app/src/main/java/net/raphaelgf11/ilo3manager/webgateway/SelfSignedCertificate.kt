package net.raphaelgf11.ilo3manager.webgateway

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date

private const val KEYSTORE_FILE = "web_gateway_selfsigned.p12"
private val KEYSTORE_PASSWORD = "ilo3manager".toCharArray()
private const val KEY_ALIAS = "web-gateway"

/**
 * Generates (once) and thereafter reuses a self-signed certificate for the local gateway's
 * optional HTTPS listener. There is no real domain to certify — this only exists so a browser
 * can use `https://` against the loopback/LAN proxy — so the certificate is deliberately generic
 * and always self-signed; the browser will show a trust warning, which is expected.
 */
object SelfSignedCertificate {

    @Synchronized
    fun loadOrCreate(context: Context): KeyStore {
        val file = File(context.filesDir, KEYSTORE_FILE)
        if (file.exists()) {
            runCatching {
                val keyStore = KeyStore.getInstance("PKCS12")
                file.inputStream().use { keyStore.load(it, KEYSTORE_PASSWORD) }
                return keyStore
            }
        }
        val keyStore = generate()
        file.outputStream().use { keyStore.store(it, KEYSTORE_PASSWORD) }
        return keyStore
    }

    private fun generate(): KeyStore {
        // Passing the provider *instance* (not the "BC" name) matters on Android: the platform
        // ships its own restricted provider already registered under the name "BC", which
        // shadows a plain Security.addProvider(BouncyCastleProvider()) and doesn't implement
        // "SHA256withRSA", causing a NoSuchAlgorithmException at signing time.
        val bouncyCastle = BouncyCastleProvider()

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=ilo3manager-web-gateway")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 20L * 365 * 24 * 60 * 60 * 1000)
        val serial = BigInteger.valueOf(now)

        val certBuilder = X509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bouncyCastle).build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val certificate = JcaX509CertificateConverter().setProvider(bouncyCastle).getCertificate(certHolder)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD, arrayOf(certificate))
        return keyStore
    }

    fun keyPassword(): CharArray = KEYSTORE_PASSWORD
}
