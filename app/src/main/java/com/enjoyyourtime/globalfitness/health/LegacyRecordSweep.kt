package com.enjoyyourtime.globalfitness.health

import com.enjoyyourtime.globalfitness.data.Measurement

/** A record read back from Health Connect: ids, source package and time (epoch millis, null if unknown). */
data class StoredRecord(val id: String, val clientRecordId: String?, val origin: String, val time: Long?)

/**
 * Clean-up of this app's records in the legacy `gf-<measurementId>-<type>` scheme
 * ([ClientRecordIds.legacy]) that no ledger entry tracks. They were written by an earlier
 * installation whose data (and health.db ledger) was wiped, so nothing would ever delete them and
 * they duplicate measurements exported again under natural ids. Legacy records the ledger still
 * tracks belong to current measurements and are left to [ScaleExportPlanner]'s migration.
 */
object LegacyRecordSweep {
    /**
     * Health Connect ids of the [records] to delete: own, legacy-scheme and untracked by [ledger].
     * A record at the time of a current measurement that has not been exported under its natural
     * id yet is kept: it is that measurement's only copy in Health Connect (a history export
     * writes it again, and the sweep that follows removes the old one).
     */
    fun select(
        records: List<StoredRecord>,
        ownPackage: String,
        ledger: List<LedgerEntry>,
        measurements: List<Measurement>,
    ): List<String> {
        val tracked = ledger.filter { it.recordKey == null }.flatMapTo(HashSet()) { entry -> entry.refs().map { it.clientId } }
        val exported = ledger.filter { it.recordKey != null }.mapTo(HashSet()) { it.measurementId }
        val onlyCopy = measurements.filter { it.id !in exported }.mapTo(HashSet()) { it.timestamp }
        return records
            .filter { r ->
                r.origin == ownPackage && ClientRecordIds.isLegacy(r.clientRecordId) && r.clientRecordId !in tracked &&
                    r.time != null && r.time !in onlyCopy
            }
            .map { it.id }
    }

    /**
     * Record types to sweep now: only [writable] ones (deleting needs the write permission); every
     * one of them when [force]d by a history export, otherwise those not swept yet ([swept]).
     */
    fun pendingTypes(writable: Set<ExportRecordType>, swept: Set<ExportRecordType>, force: Boolean): List<ExportRecordType> =
        ExportRecordType.entries.filter { it in writable && (force || it !in swept) }
}
