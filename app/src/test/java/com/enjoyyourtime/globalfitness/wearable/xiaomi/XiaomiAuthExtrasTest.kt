package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKeyResult
import com.enjoyyourtime.globalfitness.band.xiaomi.CompanionInfo
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiAuth
import com.enjoyyourtime.globalfitness.band.xiaomi.XiaomiCrypto
import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** XiaomiAuth additions: CCM decryption without tag check (Redmi Smart Band Pro) and the default device id. */
class XiaomiAuthExtrasTest {
    private val keyBytes = ByteArray(16) { (it * 3).toByte() }
    private val key = (AuthKey.parse(keyBytes.joinToString("") { "%02x".format(it) }) as AuthKeyResult.Valid).key
    private val watchNonce = ByteArray(16) { (0x40 + it).toByte() }

    /** Runs the handshake as the band would and returns the band's (phone decryption) key and nonce. */
    private fun authenticate(auth: XiaomiAuth): Pair<ByteArray, ByteArray> {
        val phoneNonce = auth.startHandshake().auth.appVerify.nonce.toByteArray()
        val material = XiaomiCrypto.deriveKeyMaterial(keyBytes, phoneNonce, watchNonce)
        val hmac = XiaomiCrypto.hmacSha256(material.copyOfRange(0, 16), watchNonce + phoneNonce)
        val result = auth.handleWatchNonce(
            XiaomiProto.Auth.DeviceVerify.newBuilder().setNonce(ByteString.copyFrom(watchNonce)).setHmac(ByteString.copyFrom(hmac)).build(),
            CompanionInfo(34, "Pixel", "ES"),
        )
        assertTrue(result is XiaomiAuth.WatchNonceResult.Confirm)
        auth.onAuthenticated(encrypted = true)
        return material.copyOfRange(0, 16) to material.copyOfRange(32, 36)
    }

    @Test
    fun uncheckedDecryptionIgnoresOnlyTheTag() {
        val auth = XiaomiAuth(key, SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1L) })
        val (bandKey, bandNonce) = authenticate(auth)
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(bandNonce).putInt(0).putInt(0).array()
        val plain = ByteArray(45) { it.toByte() }
        val sealed = XiaomiCrypto.aesCcmEncrypt(bandKey, nonce, plain)

        assertArrayEquals(plain, auth.decryptV1(sealed))
        assertArrayEquals(plain, auth.decryptV1Unchecked(sealed))

        val badTag = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            auth.decryptV1(badTag)
            fail("tag not checked")
        } catch (e: GeneralSecurityException) {
            // expected
        }
        assertArrayEquals(plain, auth.decryptV1Unchecked(badTag))
        assertEquals(0, auth.decryptV1Unchecked(ByteArray(3)).size)
    }

    @Test
    fun defaultDeviceIdGoesIntoTheNonce() {
        val auth = XiaomiAuth(key, defaultDeviceId = "watch-device-id")
        assertEquals("watch-device-id", auth.startHandshake().auth.appVerify.deviceId)
        assertTrue(!XiaomiAuth(key).startHandshake().auth.appVerify.hasDeviceId())
        assertEquals("explicit", auth.startHandshake("explicit").auth.appVerify.deviceId)
    }
}
