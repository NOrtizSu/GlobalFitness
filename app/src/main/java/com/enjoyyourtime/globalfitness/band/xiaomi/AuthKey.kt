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

    override fun toString(): String = "AuthKey(oculta)"

    companion object {
        const val HEX_LENGTH = 32

        fun parse(input: String): AuthKeyResult {
            var text = input.filterNot { it.isWhitespace() }
            if (text.startsWith("0x") || text.startsWith("0X")) text = text.substring(2)
            if (text.isEmpty()) return AuthKeyResult.Invalid("Introduce la clave de autenticación.")
            if (text.any { Character.digit(it, 16) < 0 }) {
                return AuthKeyResult.Invalid(
                    "La clave solo puede contener dígitos hexadecimales (0-9 y a-f), con el prefijo 0x opcional.",
                )
            }
            if (text.length != HEX_LENGTH) {
                return AuthKeyResult.Invalid(
                    "La clave debe tener $HEX_LENGTH caracteres hexadecimales (tiene ${text.length}).",
                )
            }
            val bytes = ByteArray(HEX_LENGTH / 2) { i ->
                ((Character.digit(text[2 * i], 16) shl 4) or Character.digit(text[2 * i + 1], 16)).toByte()
            }
            return AuthKeyResult.Valid(AuthKey(bytes))
        }
    }
}

sealed interface AuthKeyResult {
    data class Valid(val key: AuthKey) : AuthKeyResult
    data class Invalid(val message: String) : AuthKeyResult
}
