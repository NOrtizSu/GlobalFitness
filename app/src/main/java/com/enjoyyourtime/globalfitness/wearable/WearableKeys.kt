package com.enjoyyourtime.globalfitness.wearable

import java.util.Locale

/** Why a key does not have the expected format (the wizard maps it to a string resource). */
enum class KeyProblem { EMPTY, NOT_HEX, WRONG_LENGTH, NOT_NUMERIC, TOO_LONG }

sealed interface KeyCheck {
    /** [normalized]: lower-case hex without prefix, or the digits of a user id. */
    data class Valid(val normalized: String) : KeyCheck {
        override fun toString(): String = "Valid(hidden)"
    }

    /** [length] is the number of significant characters typed (for "has N of 32"). */
    data class Invalid(val problem: KeyProblem, val length: Int = 0) : KeyCheck
}

/**
 * Format checks of user-provided auth keys, as Gadgetbridge accepts them
 * (XiaomiCoordinator.validateAuthKey, HuamiCoordinator / XiaomiAuthService.getSecretKey): 32 hex
 * characters with an optional `0x`, or a numeric user id for plaintext Xiaomi devices. Whitespace
 * (a trailing newline from copy & paste) is ignored. Never logs or echoes the key.
 */
object WearableKeys {
    const val HEX_LENGTH = 32
    private const val MAX_USER_ID_LENGTH = 20
    private val HEX_TOKEN = Regex("(?<![0-9A-Za-z])(?:0[xX])?([0-9A-Fa-f]{32})(?![0-9A-Za-z])")
    private val DIGITS_TOKEN = Regex("(?<![0-9A-Za-z])([0-9]{1,20})(?![0-9A-Za-z])")

    fun validate(kind: AuthKeyKind, input: String): KeyCheck = when (kind) {
        AuthKeyKind.NONE -> KeyCheck.Valid("")
        AuthKeyKind.HEX_32 -> validateHex(input)
        AuthKeyKind.NUMERIC_USER_ID -> validateUserId(input)
    }

    private fun validateHex(input: String): KeyCheck {
        var text = input.filterNot { it.isWhitespace() }
        if (text.startsWith("0x") || text.startsWith("0X")) text = text.substring(2)
        if (text.isEmpty()) return KeyCheck.Invalid(KeyProblem.EMPTY)
        if (!text.all(::isAsciiHex)) return KeyCheck.Invalid(KeyProblem.NOT_HEX, text.length)
        if (text.length != HEX_LENGTH) return KeyCheck.Invalid(KeyProblem.WRONG_LENGTH, text.length)
        return KeyCheck.Valid(text.lowercase(Locale.ROOT))
    }

    private fun validateUserId(input: String): KeyCheck {
        val text = input.filterNot { it.isWhitespace() }
        if (text.isEmpty()) return KeyCheck.Invalid(KeyProblem.EMPTY)
        if (text.any { it !in '0'..'9' }) return KeyCheck.Invalid(KeyProblem.NOT_NUMERIC, text.length)
        if (text.length > MAX_USER_ID_LENGTH) return KeyCheck.Invalid(KeyProblem.TOO_LONG, text.length)
        return KeyCheck.Valid(text)
    }

    /**
     * The key inside pasted text ("authKey: 0x0123...", a line of a log export...): the first
     * stand-alone token of the right shape, or the whole text when it already validates. Null when
     * nothing key-like is found.
     */
    fun extractFromClipboard(kind: AuthKeyKind, text: String?): String? {
        if (text.isNullOrBlank()) return null
        val whole = validate(kind, text)
        if (whole is KeyCheck.Valid && kind != AuthKeyKind.NONE) return whole.normalized
        return when (kind) {
            AuthKeyKind.NONE -> null
            AuthKeyKind.HEX_32 -> HEX_TOKEN.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            AuthKeyKind.NUMERIC_USER_ID -> DIGITS_TOKEN.find(text)?.groupValues?.get(1)
        }
    }

    /**
     * Only ASCII 0-9, a-f, A-F. [Character.digit] also accepts other scripts' digits (Arabic-Indic,
     * fullwidth...), which would pass the check and then be stored as "?" in the ASCII key file.
     */
    fun isAsciiHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Hex key to 16 bytes; [normalized] must come from [KeyCheck.Valid] of [AuthKeyKind.HEX_32]. */
    fun hexToBytes(normalized: String): ByteArray {
        require(normalized.length == HEX_LENGTH && normalized.all(::isAsciiHex)) { "bad key" }
        return ByteArray(HEX_LENGTH / 2) { i ->
            ((Character.digit(normalized[2 * i], 16) shl 4) or Character.digit(normalized[2 * i + 1], 16)).toByte()
        }
    }
}
