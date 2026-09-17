package net.raphaelgf11.ilo3manager.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

data class GeneratedKeyPair(val privateKeyPem: String, val publicKeyOpenSsh: String)

object SshKeyGenerator {

    fun generateRsaKeyPair(comment: String, keySize: Int = 3072): GeneratedKeyPair {
        val jsch = JSch()
        val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, keySize)

        val privateOut = ByteArrayOutputStream()
        keyPair.writePrivateKey(privateOut)

        val publicOut = ByteArrayOutputStream()
        keyPair.writePublicKey(publicOut, comment)

        keyPair.dispose()

        return GeneratedKeyPair(
            privateKeyPem = privateOut.toString(Charsets.UTF_8.name()),
            publicKeyOpenSsh = publicOut.toString(Charsets.UTF_8.name()),
        )
    }
}
