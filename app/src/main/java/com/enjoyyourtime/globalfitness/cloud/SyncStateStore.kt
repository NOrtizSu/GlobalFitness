package com.enjoyyourtime.globalfitness.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists the sync bookkeeping in `noBackupFilesDir`, which Android never backs up or transfers:
 * after a restore or on a new phone the engine starts without a base, so it merges instead of
 * mistaking the missing history for deletions.
 */
class SyncStateStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "cloud_sync")

    suspend fun loadBase(uid: String): SyncBase? = read(baseFile(uid))?.let(SyncStateCodec::decodeBase)

    suspend fun saveBase(uid: String, base: SyncBase) = write(baseFile(uid), SyncStateCodec.encodeBase(base))

    suspend fun clearBase(uid: String) = withContext(Dispatchers.IO) {
        baseFile(uid).delete()
        Unit
    }

    suspend fun loadStamps(): EditStamps =
        read(File(dir, STAMPS_FILE))?.let(SyncStateCodec::decodeStamps) ?: EditStamps(null, null)

    suspend fun saveStamps(stamps: EditStamps) = write(File(dir, STAMPS_FILE), SyncStateCodec.encodeStamps(stamps))

    private fun baseFile(uid: String) = File(dir, "base_${FirestoreCodec.safeDocId(uid)}.txt")

    private suspend fun read(file: File): String? = withContext(Dispatchers.IO) {
        if (file.isFile) file.readText() else null
    }

    /** Write-then-rename, so a crash never leaves a half-written file. */
    private suspend fun write(file: File, text: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        Unit
    }

    private companion object {
        const val STAMPS_FILE = "edit_stamps.txt"
    }
}
