package com.enjoyyourtime.globalfitness.band.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiAuthTest {
    private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(out: ByteArray) {
            bytes.copyInto(out, 0, 0, out.size)
        }
    }

    private val key = (AuthKey.parse(range(0, 16).toHexString()) as AuthKeyResult.Valid).key
    private val phoneNonce = range(0x10, 0x20)
    private val watchNonce = range(0x20, 0x30)
    private val companion = CompanionInfo(apiLevel = 34, phoneModel = "Pixel 8", region = "ES")

    // Same inputs as XiaomiCryptoTest, so the session keys are the HKDF reference vector.
    private val material = hex(
        "d738074e6570abb50d001db70f497a37923e295e02aecb7619a8e1b9f574c988" +
            "8676d22523869a15ee4340ac1bab5e35044aed283e7cace9aad41acb59fe75eb",
    )
    private val decryptionKey = material.copyOfRange(0, 16)
    private val encryptionKey = material.copyOfRange(16, 32)
    private val decryptionNonce = material.copyOfRange(32, 36)
    private val encryptionNonce = material.copyOfRange(36, 40)

    private fun newAuth() = XiaomiAuth(key, FixedRandom(phoneNonce))

    private fun deviceVerify(hmac: ByteArray) = XiaomiProto.Auth.DeviceVerify.newBuilder()
        .setNonce(ByteString.copyFrom(watchNonce))
        .setHmac(ByteString.copyFrom(hmac))
        .build()

    private fun authenticated(): XiaomiAuth = newAuth().also {
        it.startHandshake()
        assertTrue(it.handleWatchNonce(deviceVerify(hex(WATCH_HMAC)), companion) is XiaomiAuth.WatchNonceResult.Confirm)
        it.onAuthenticated(encrypted = true)
    }

    @Test
    fun stepOneSendsTheNonceOnly() {
        val command = newAuth().startHandshake()
        assertEquals(XiaomiCommands.TYPE_AUTH, command.type)
        assertEquals(XiaomiCommands.AUTH_NONCE, command.subtype)
        assertArrayEquals(phoneNonce, command.auth.appVerify.nonce.toByteArray())
        assertFalse(command.auth.appVerify.hasDeviceId())
    }

    @Test
    fun stepTwoConfirmsWithHmacAndEncryptedPhoneInfo() {
        val auth = newAuth()
        auth.startHandshake()
        val result = auth.handleWatchNonce(deviceVerify(hex(WATCH_HMAC)), companion)

        val command = (result as XiaomiAuth.WatchNonceResult.Confirm).command
        assertEquals(XiaomiCommands.TYPE_AUTH, command.type)
        assertEquals(XiaomiCommands.AUTH_CONFIRM, command.subtype)
        val confirm = command.auth.appConfirm
        assertArrayEquals(XiaomiCrypto.hmacSha256(encryptionKey, phoneNonce + watchNonce), confirm.encryptedNonces.toByteArray())

        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(encryptionNonce).putInt(0).putInt(0).array()
        val info = XiaomiProto.CompanionDevice.parseFrom(
            XiaomiCrypto.aesCcmDecrypt(encryptionKey, nonce, confirm.encryptedDeviceInfo.toByteArray()),
        )
        assertTrue(info.hasUnknown1())
        assertEquals(0, info.unknown1)
        assertEquals(34f, info.phoneApiLevel)
        assertEquals("Pixel 8", info.phoneName)
        assertEquals(224, info.unknown3)
        assertEquals("ES", info.region)
        assertFalse(auth.isEncryptionInitialized) // only after the band's 1/27
    }

    @Test
    fun wrongKeyIsDetectedFromTheBandHmac() {
        val auth = newAuth()
        auth.startHandshake()
        val tampered = hex(WATCH_HMAC).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertSame(XiaomiAuth.WatchNonceResult.WrongKey, auth.handleWatchNonce(deviceVerify(tampered), companion))
    }

    @Test
    fun v1EncryptionUsesTheCounterInTheNonce() {
        // Reference: AESCCM(encryptionKey, tag 4) over nonce = encryptionNonce || 00000000 || 07000000.
        assertEquals("264f6536851ed9b3c4ad", authenticated().encryptV1(hex("080210011801"), 7).toHexString())
    }

    @Test
    fun v1DecryptsBandTrafficWithTheDecryptionKey() {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(decryptionNonce).putInt(0).putInt(0).array()
        val fromBand = XiaomiCrypto.aesCcmEncrypt(decryptionKey, nonce, hex("08021001"))
        assertArrayEquals(hex("08021001"), authenticated().decryptV1(fromBand))
    }

    @Test
    fun v2UsesCtrWithKeyAsIvInBothDirections() {
        val auth = authenticated()
        assertArrayEquals(XiaomiCrypto.aesCtr(encryptionKey, encryptionKey, hex("0802")), auth.encryptV2(hex("0802")))
        assertArrayEquals(hex("0802"), auth.decryptV2(XiaomiCrypto.aesCtr(decryptionKey, decryptionKey, hex("0802"))))
    }

    private companion object {
        const val WATCH_HMAC = "17aa8eecac21d9d71cba6ea653d126d77abc2f4d0357f1cce01d433d40e42235"
    }
}
