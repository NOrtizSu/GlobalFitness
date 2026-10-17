// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

/**
 * The 16-byte secret ("auth key") the band shares with the user's Xiaomi account.
 *
 * Accepted input follows Gadgetbridge's XiaomiCoordinator.validateAuthKey / XiaomiAuthService.getSecretKey:
 * 32 hex characters, optionally prefixed with `0x`. Whitespace (e.g. a trailing newline from copy & paste)
 * is ignored. [toString] never reveals the key, so it cannot leak into logs by accident.
 */
class AuthKey private constructor(private val bytes: ByteArray) {

    fun bytes(): ByteArray = bytes.copyOf()

    /** Lower-case hex, only for persisting in app-private storage. */
    fun toHex(): String = XiaomiCrypto.toHex(bytes)

    override fun equals(other: Any?): Boolean = other is AuthKey && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "AuthKey(hidden)"

    companion object {
        const val HEX_LENGTH = 32

        fun parse(input: String): AuthKeyResult {
            var text = input.filterNot { it.isWhitespace() }
            if (text.startsWith("0x") || text.startsWith("0X")) text = text.substring(2)
            if (text.isEmpty()) return AuthKeyResult.Invalid(AuthKeyError.EMPTY)
            // ASCII only: Character.digit also accepts Arabic-Indic, fullwidth and other digits.
            if (text.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return AuthKeyResult.Invalid(AuthKeyError.NOT_HEX)
            if (text.length != HEX_LENGTH) return AuthKeyResult.Invalid(AuthKeyError.WRONG_LENGTH, text.length)
            val bytes = ByteArray(HEX_LENGTH / 2) { i ->
                ((Character.digit(text[2 * i], 16) shl 4) or Character.digit(text[2 * i + 1], 16)).toByte()
            }
            return AuthKeyResult.Valid(AuthKey(bytes))
        }
    }
}

/** Why an auth key was rejected (the UI words it). */
enum class AuthKeyError { EMPTY, NOT_HEX, WRONG_LENGTH }

sealed interface AuthKeyResult {
    data class Valid(val key: AuthKey) : AuthKeyResult

    /** [length]: the number of hex characters entered, for [AuthKeyError.WRONG_LENGTH]. */
    data class Invalid(val error: AuthKeyError, val length: Int = 0) : AuthKeyResult
}
