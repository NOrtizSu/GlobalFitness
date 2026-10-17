package com.enjoyyourtime.globalfitness.wearable

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-device auth keys, one small file per device in [Context.getNoBackupFilesDir] (app-private and
 * excluded from cloud and device-to-device backups). Keys are never logged, uploaded or put in
 * shared preferences; [keyedAddresses] only tells which devices have one.
 */
class WearableKeyStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, DIR_NAME)

    private val _keyedAddresses = MutableStateFlow(scan())
    val keyedAddresses: StateFlow<Set<String>> = _keyedAddresses.asStateFlow()

    @Synchronized
    fun load(address: String): String? {
        val file = fileFor(address) ?: return null
        return try {
            file.readFully().toString(Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        }
    }

    /** Stores [normalizedKey] (from [KeyCheck.Valid]: ASCII hex or digits) for [address]. */
    @Synchronized
    fun save(address: String, normalizedKey: String) {
        val file = fileFor(address) ?: throw IOException("Invalid address")
        // The file is ASCII: anything else would be written as "?" and silently corrupt the key.
        if (normalizedKey.isEmpty() || !normalizedKey.all(WearableKeys::isAsciiHex)) throw IOException("Invalid key format")
        dir.mkdirs()
        val out = file.startWrite()
        try {
            out.write(normalizedKey.toByteArray(Charsets.US_ASCII))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
        _keyedAddresses.value = scan()
    }

    @Synchronized
    fun clear(address: String) {
        fileFor(address)?.delete()
        _keyedAddresses.value = scan()
    }

    fun has(address: String): Boolean = normalize(address) in _keyedAddresses.value

    private fun fileFor(address: String): AtomicFile? {
        val name = fileName(address) ?: return null
        return AtomicFile(File(dir, name))
    }

    private fun scan(): Set<String> =
        dir.listFiles().orEmpty()
            .mapNotNull { f -> f.name.removePrefix(PREFIX).takeIf { f.name.startsWith(PREFIX) && it.length == 12 && !f.name.endsWith(".bak") } }
            .map { hex -> hex.chunked(2).joinToString(":") }
            .toSet()

    companion object {
        private const val DIR_NAME = "wearable_keys"
        private const val PREFIX = "key_"

        /** "AA:BB:CC:DD:EE:FF" form; the store's identity of a device. */
        fun normalize(address: String): String = address.trim().uppercase(Locale.ROOT)

        /** File name for a Bluetooth address; null when it is not one (no path tricks possible). */
        fun fileName(address: String): String? {
            val hex = normalize(address).replace(":", "")
            if (hex.length != 12 || hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
            return PREFIX + hex
        }
    }
}
