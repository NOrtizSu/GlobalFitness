package com.enjoyyourtime.globalfitness.band.xiaomi

import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Known-answer vectors generated independently with Python `cryptography` 50.0 (HKDF-SHA256, AESCCM with
 * a 4-byte tag, AES-CTR); Gadgetbridge has no crypto vectors of its own.
 */
class XiaomiCryptoTest {
    private val secret = range(0x00, 0x10)
    private val phoneNonce = range(0x10, 0x20)
    private val watchNonce = range(0x20, 0x30)
    private val key = range(0x40, 0x50)
    private val nonce = range(0x10, 0x1c)

    @Test
    fun keyDerivationMatchesHkdfWithMiwearAuthInfo() {
        // Gadgetbridge's computeAuthStep3Hmac is HKDF(salt = phoneNonce || watchNonce, ikm = key, info = "miwear-auth").
        val expected = "d738074e6570abb50d001db70f497a37923e295e02aecb7619a8e1b9f574c988" +
            "8676d22523869a15ee4340ac1bab5e35044aed283e7cace9aad41acb59fe75eb"
        assertEquals(expected, XiaomiCrypto.deriveKeyMaterial(secret, phoneNonce, watchNonce).toHexString())
    }

    @Test
    fun hmacSha256OverNonces() {
        val material = XiaomiCrypto.deriveKeyMaterial(secret, phoneNonce, watchNonce)
        val decryptionKey = material.copyOfRange(0, 16)
        assertEquals(
            "17aa8eecac21d9d71cba6ea653d126d77abc2f4d0357f1cce01d433d40e42235",
            XiaomiCrypto.hmacSha256(decryptionKey, watchNonce + phoneNonce).toHexString(),
        )
    }

    @Test
    fun ccmMatchesReferenceVectors() {
        val vectors = listOf(
            ByteArray(0) to "d2ca8861",
            hex("0102030405") to "c291208ed4ce9c72e0",
            range(0, 16) to "c3922189d5973a5abb3ccaccedb7c72b2b7a8afd",
            range(0, 33) to "c3922189d5973a5abb3ccaccedb7c72b41568af98462aa85743bf1f436da2cc38c096dc9a9",
        )
        for ((plaintext, ciphertext) in vectors) {
            assertEquals(ciphertext, XiaomiCrypto.aesCcmEncrypt(key, nonce, plaintext).toHexString())
            assertArrayEquals(plaintext, XiaomiCrypto.aesCcmDecrypt(key, nonce, hex(ciphertext)))
        }
    }

    @Test
    fun ccmRejectsTamperedData() {
        val ciphertext = XiaomiCrypto.aesCcmEncrypt(key, nonce, range(0, 20))
        for (index in listOf(0, 19, 20, 23)) {
            val tampered = ciphertext.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            try {
                XiaomiCrypto.aesCcmDecrypt(key, nonce, tampered)
                fail("tampered byte $index accepted")
            } catch (expected: GeneralSecurityException) {
            }
        }
    }

    @Test
    fun ctrWithKeyAsIvMatchesReference() {
        assertEquals("80d3a7b38ba5e8561c324f956f8c08d03b60413c", XiaomiCrypto.aesCtr(key, key, range(0, 20)).toHexString())
        assertArrayEquals(range(0, 20), XiaomiCrypto.aesCtr(key, key, hex("80d3a7b38ba5e8561c324f956f8c08d03b60413c")))
    }
}
