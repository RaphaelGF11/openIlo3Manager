package net.raphaelgf11.ilo3manager.backup

import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongSecretException : Exception("Phrase secrète incorrecte.")

/**
 * Encrypts a backup with a secret the user chooses, so what leaves the device is unreadable.
 *
 * Drive's application data folder hides a file from the Drive interface, but it does not encrypt
 * it: the contents still sit on Google's servers in the clear. Since this backup carries iLO
 * passwords, SSH private keys and WireGuard keys, invisibility alone is not protection.
 *
 * The key is derived from the secret rather than kept only on the device: an Android Keystore key
 * cannot leave the phone it was created on, so a backup sealed with one could never be restored
 * onto a new device — which is precisely when a backup matters most.
 *
 * scrypt is used for the derivation rather than PBKDF2: it is deliberately memory-hard, which
 * matters when the secret may be a short PIN, and Bouncy Castle already ships with the app, so it
 * is available on every supported API level unlike PBKDF2WithHmacSHA256.
 */
object BackupCrypto {

    private const val VERSION = 1
    private const val SCRYPT_N = 1 shl 15
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, secret: String): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = deriveKey(secret, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        return JSONObject().apply {
            put("version", VERSION)
            put("kdf", "scrypt")
            put("n", SCRYPT_N)
            put("r", SCRYPT_R)
            put("p", SCRYPT_P)
            put("salt", encode(salt))
            put("nonce", encode(nonce))
            put("ciphertext", encode(ciphertext))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decrypt(envelope: ByteArray, secret: String): ByteArray {
        val json = JSONObject(String(envelope, Charsets.UTF_8))
        require(json.optInt("version") == VERSION) { "Format de sauvegarde non reconnu." }

        // Parameters come from the envelope, not from the constants: a backup written by an older
        // build must stay readable even if the cost factors are raised later.
        val key = deriveKey(
            secret = secret,
            salt = decode(json.getString("salt")),
            n = json.optInt("n", SCRYPT_N),
            r = json.optInt("r", SCRYPT_R),
            p = json.optInt("p", SCRYPT_P),
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, decode(json.getString("nonce"))),
        )
        return try {
            cipher.doFinal(decode(json.getString("ciphertext")))
        } catch (e: AEADBadTagException) {
            // GCM authentication failing means the key was wrong or the data was tampered with;
            // for the user the actionable reading is always the first.
            throw WrongSecretException()
        }
    }

    private fun deriveKey(
        secret: String,
        salt: ByteArray,
        n: Int = SCRYPT_N,
        r: Int = SCRYPT_R,
        p: Int = SCRYPT_P,
    ): ByteArray = SCrypt.generate(secret.toByteArray(Charsets.UTF_8), salt, n, r, p, KEY_BYTES)

    // Bouncy Castle's encoder rather than android.util.Base64: it behaves identically on every
    // API level and keeps this class free of Android types, so the crypto can be unit-tested.
    private fun encode(bytes: ByteArray): String = String(Base64.encode(bytes), Charsets.US_ASCII)

    private fun decode(value: String): ByteArray = Base64.decode(value)
}
