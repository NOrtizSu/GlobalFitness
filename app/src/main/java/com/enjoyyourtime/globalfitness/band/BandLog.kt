package com.enjoyyourtime.globalfitness.band

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BandLogLine(val timeMs: Long, val text: String)

/**
 * In-app diagnostics for the "Diagnóstico" panel. Nothing written here contains the auth key, and as a
 * second line of defence every line is scrubbed of the registered secrets before it is stored.
 */
class BandLog(private val capacity: Int = 300, private val clock: () -> Long = System::currentTimeMillis) {
    private val _lines = MutableStateFlow<List<BandLogLine>>(emptyList())
    val lines: StateFlow<List<BandLogLine>> = _lines.asStateFlow()

    @Volatile
    private var secrets: Set<String> = emptySet()

    fun registerSecret(secret: String) {
        if (secret.isNotEmpty()) secrets = secrets + secret
    }

    fun add(text: String) {
        val line = BandLogLine(clock(), redact(text, secrets))
        _lines.update { (it + line).takeLast(capacity) }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    companion object {
        const val REDACTED = "[clave oculta]"

        fun redact(text: String, secrets: Set<String>): String =
            secrets.fold(text) { acc, secret -> acc.replace(secret, REDACTED, ignoreCase = true) }
    }
}
