package com.enjoyyourtime.globalfitness.cloud

/** What the phone and the cloud agreed on for one document at the end of the last sync. */
sealed interface BaseEntry {
    /** Both sides had this content (see [SyncMerge.hashOf]). */
    data class Live(val hash: String) : BaseEntry

    /** The cloud holds a tombstone. */
    data class Deleted(val deletedAt: Long) : BaseEntry
}

/**
 * Sync bookkeeping for one account on this phone. It is stored in `noBackupFilesDir`, so it never
 * travels with Auto Backup: a restored phone starts without a base and merges instead of deleting.
 */
data class SyncBase(
    val datasetKey: String,
    /** Cursor (server epoch ms) for incremental measurement pulls; null forces a full pull. */
    val lastPullAt: Long?,
    val lastSyncAt: Long?,
    val measurements: Map<String, BaseEntry>,
    val devices: Map<String, BaseEntry>,
)

/** Client edit time of the profile / settings content, so last-writer-wins has a local timestamp. */
data class EditStamp(val hash: String, val at: Long) {
    companion object {
        /**
         * Same content keeps its time; new content gets [now]; content seen for the first time gets
         * [fallback] (the best time the data layer knows, 0 if none) so old data does not look new.
         */
        fun observe(previous: EditStamp?, hash: String, now: Long, fallback: Long): EditStamp = when {
            previous == null -> EditStamp(hash, fallback)
            previous.hash == hash -> previous
            else -> EditStamp(hash, now)
        }
    }
}

data class EditStamps(val profile: EditStamp?, val settings: EditStamp?)

/** Plain-text, line-based encoding of [SyncBase] and [EditStamps] (pure Kotlin, no Android JSON). */
object SyncStateCodec {
    private const val VERSION = "1"
    private const val SEP = '\t'

    fun encodeBase(base: SyncBase): String = buildString {
        line("v", VERSION)
        line("dataset", base.datasetKey)
        base.lastPullAt?.let { line("pull", it.toString()) }
        base.lastSyncAt?.let { line("sync", it.toString()) }
        for ((id, entry) in base.measurements) entry(this, "m", id, entry)
        for ((id, entry) in base.devices) entry(this, "d", id, entry)
    }

    fun decodeBase(text: String): SyncBase? {
        var version: String? = null
        var dataset: String? = null
        var pull: Long? = null
        var sync: Long? = null
        val measurements = HashMap<String, BaseEntry>()
        val devices = HashMap<String, BaseEntry>()
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            val parts = raw.split(SEP)
            when (parts[0]) {
                "v" -> version = parts.getOrNull(1)
                "dataset" -> dataset = parts.getOrNull(1)
                "pull" -> pull = parts.getOrNull(1)?.toLongOrNull()
                "sync" -> sync = parts.getOrNull(1)?.toLongOrNull()
                "m" -> parseEntry(parts)?.let { (id, e) -> measurements[id] = e }
                "d" -> parseEntry(parts)?.let { (id, e) -> devices[id] = e }
            }
        }
        if (version != VERSION || dataset == null) return null
        return SyncBase(dataset, pull, sync, measurements, devices)
    }

    fun encodeStamps(stamps: EditStamps): String = buildString {
        line("v", VERSION)
        stamps.profile?.let { line("profile", it.hash, it.at.toString()) }
        stamps.settings?.let { line("settings", it.hash, it.at.toString()) }
    }

    fun decodeStamps(text: String): EditStamps {
        var profile: EditStamp? = null
        var settings: EditStamp? = null
        for (raw in text.lineSequence()) {
            val parts = raw.split(SEP)
            val stamp = if (parts.size >= 3) parts[2].toLongOrNull()?.let { EditStamp(parts[1], it) } else null
            when (parts[0]) {
                "profile" -> profile = stamp
                "settings" -> settings = stamp
            }
        }
        return EditStamps(profile, settings)
    }

    private fun StringBuilder.line(vararg fields: String) {
        fields.joinTo(this, SEP.toString()) { clean(it) }
        append('\n')
    }

    private fun entry(sb: StringBuilder, kind: String, id: String, entry: BaseEntry) = when (entry) {
        is BaseEntry.Live -> sb.line(kind, id, "L", entry.hash)
        is BaseEntry.Deleted -> sb.line(kind, id, "D", entry.deletedAt.toString())
    }

    private fun parseEntry(parts: List<String>): Pair<String, BaseEntry>? {
        if (parts.size < 4) return null
        val id = parts[1]
        val entry = when (parts[2]) {
            "L" -> BaseEntry.Live(parts[3])
            "D" -> parts[3].toLongOrNull()?.let { BaseEntry.Deleted(it) } ?: return null
            else -> return null
        }
        return id to entry
    }

    /** Fields never contain the separators (ids and hashes are plain), but stay safe if they ever do. */
    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ')
}
