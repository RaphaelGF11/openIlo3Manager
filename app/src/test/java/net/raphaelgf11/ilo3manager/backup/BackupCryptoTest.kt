package net.raphaelgf11.ilo3manager.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {

    private val plaintext = """{"hosts":[{"password":"motdepasse-ilo"}]}""".toByteArray()

    @Test
    fun roundTripsWithTheRightSecret() {
        val sealed = BackupCrypto.encrypt(plaintext, "phrase secrète")
        assertArrayEquals(plaintext, BackupCrypto.decrypt(sealed, "phrase secrète"))
    }

    @Test
    fun rejectsTheWrongSecret() {
        val sealed = BackupCrypto.encrypt(plaintext, "phrase secrète")
        assertThrows(WrongSecretException::class.java) { BackupCrypto.decrypt(sealed, "mauvaise") }
    }

    @Test
    fun worksWithAShortPin() {
        val sealed = BackupCrypto.encrypt(plaintext, "1234")
        assertArrayEquals(plaintext, BackupCrypto.decrypt(sealed, "1234"))
    }

    @Test
    fun leaksNothingInClear() {
        val sealed = String(BackupCrypto.encrypt(plaintext, "x"))
        assertEquals(false, sealed.contains("motdepasse-ilo"))
        assertEquals(false, sealed.contains("hosts"))
    }

    @Test
    fun usesFreshSaltAndNonceEachTime() {
        // Identical input and secret must not produce identical output, otherwise equal backups
        // would be recognisable and a nonce could repeat under the same key.
        val first = String(BackupCrypto.encrypt(plaintext, "x"))
        val second = String(BackupCrypto.encrypt(plaintext, "x"))
        assertNotEquals(first, second)
    }

    @Test
    fun detectsTamperedCiphertext() {
        val sealed = String(BackupCrypto.encrypt(plaintext, "x"))
        val corrupted = sealed.replace(Regex(""""ciphertext":"."""), """"ciphertext":"Z""")
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(corrupted.toByteArray(), "x") }
    }
}
