// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

import com.enjoyyourtime.globalfitness.band.proto.XiaomiProto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Encrypted handshake and session ciphers (Gadgetbridge XiaomiAuthService):
 *
 * 1. phone → band 1/26 with a random 16-byte nonce;
 * 2. band → phone 1/26 with its nonce and HMAC(decryptionKey, watchNonce || phoneNonce). Both sides derive
 *    the session keys from the shared auth key and the nonces; a wrong auth key shows up here as an HMAC
 *    mismatch, before anything else is sent;
 * 3. phone → band 1/27 with HMAC(encryptionKey, phoneNonce || watchNonce) and the encrypted phone info;
 * 4. band → phone 1/27: authenticated, the rest of the session is encrypted.
 *
 * Holds key material only in memory; nothing here is ever logged.
 */
class XiaomiAuth(secretKey: AuthKey, private val random: SecureRandom = SecureRandom()) {
    private val secret = secretKey.bytes()
    private val phoneNonce = ByteArray(16)

    // Zero until the watch nonce arrives, as in Gadgetbridge.
    private val encryptionKey = ByteArray(16)
    private val decryptionKey = ByteArray(16)
    private val encryptionNonce = ByteArray(4)
    private val decryptionNonce = ByteArray(4)

    var isEncryptionInitialized: Boolean = false
        private set

    sealed interface WatchNonceResult {
        data class Confirm(val command: XiaomiProto.Command) : WatchNonceResult

        /** The band's HMAC does not match: the auth key is wrong. */
        data object WrongKey : WatchNonceResult
    }

    /** Step 1: a fresh nonce for this session. */
    fun startHandshake(deviceId: String? = null): XiaomiProto.Command {
        isEncryptionInitialized = false
        random.nextBytes(phoneNonce)
        return XiaomiCommands.nonce(phoneNonce, deviceId)
    }

    /** Step 2/3: derive the keys, check the band's HMAC and build the confirmation. */
    fun handleWatchNonce(verify: XiaomiProto.Auth.DeviceVerify, companion: CompanionInfo): WatchNonceResult {
        val watchNonce = verify.nonce.toByteArray()
        val material = XiaomiCrypto.deriveKeyMaterial(secret, phoneNonce, watchNonce)
        System.arraycopy(material, 0, decryptionKey, 0, 16)
        System.arraycopy(material, 16, encryptionKey, 0, 16)
        System.arraycopy(material, 32, decryptionNonce, 0, 4)
        System.arraycopy(material, 36, encryptionNonce, 0, 4)

        val expected = XiaomiCrypto.hmacSha256(decryptionKey, watchNonce + phoneNonce)
        if (!expected.contentEquals(verify.hmac.toByteArray())) return WatchNonceResult.WrongKey

        val encryptedNonces = XiaomiCrypto.hmacSha256(encryptionKey, phoneNonce + watchNonce)
        val encryptedDeviceInfo = encryptV1(XiaomiCommands.companionDevice(companion).toByteArray(), 0)
        return WatchNonceResult.Confirm(XiaomiCommands.authConfirm(encryptedNonces, encryptedDeviceInfo))
    }

    /** Step 4 (1/27 received, or 1/5 with status 1 on plaintext devices). */
    fun onAuthenticated(encrypted: Boolean) {
        isEncryptionInitialized = encrypted
    }

    /** SPP V1 / handshake: AES-CCM, nonce = encryptionNonce || 0 (int LE) || counter (int LE). */
    fun encryptV1(payload: ByteArray, counter: Int): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(encryptionNonce).putInt(0).putInt(counter).array()
        return XiaomiCrypto.aesCcmEncrypt(encryptionKey, nonce, payload)
    }

    /** SPP V1: AES-CCM, nonce = decryptionNonce || 0 || 0, tag checked. */
    fun decryptV1(payload: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(decryptionNonce).putInt(0).putInt(0).array()
        return XiaomiCrypto.aesCcmDecrypt(decryptionKey, nonce, payload)
    }

    /** SPP V2: AES-CTR with the key reused as IV. */
    fun encryptV2(payload: ByteArray): ByteArray = XiaomiCrypto.aesCtr(encryptionKey, encryptionKey, payload)

    fun decryptV2(payload: ByteArray): ByteArray = XiaomiCrypto.aesCtr(decryptionKey, decryptionKey, payload)
}
