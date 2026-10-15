package com.enjoyyourtime.globalfitness.cloud

/** Why the sync is paused waiting for the user. */
enum class ConflictKind {
    /** The phone and the cloud hold different datasets, both with measurements. */
    DIFFERENT_DATA,

    /** The phone deleted many measurements at once since the last sync. */
    MASS_DELETE,
}

/** The user's answer to a [ConflictKind]. */
enum class ConflictChoice {
    /** DIFFERENT_DATA: replace this phone's data with the cloud copy. */
    RESTORE_CLOUD,

    /** DIFFERENT_DATA: replace the cloud copy with this phone's data. */
    REPLACE_CLOUD,

    /** MASS_DELETE: delete them from the cloud too. */
    CONFIRM_DELETIONS,

    /** MASS_DELETE: bring the deleted measurements back from the cloud. */
    UNDO_DELETIONS,
}

sealed interface SyncMode {
    /** Same dataset on both sides: three-way merge. */
    data object Merge : SyncMode

    /** Replace the phone's data with the cloud copy and take over its dataset id. */
    data object AdoptCloud : SyncMode

    /** Replace the cloud copy with the phone's data. */
    data object ReplaceCloud : SyncMode

    /** Nothing to back up yet (no local profile). */
    data object Idle : SyncMode

    /** The cloud copy this phone synced was deleted (e.g. account deleted elsewhere): stop and sign out. */
    data object RemoteWiped : SyncMode

    /** Pause and ask the user. */
    data class NeedsChoice(val kind: ConflictKind) : SyncMode
}

/** Pure decision of how to sync, from the dataset ids on both sides (never merges two different people). */
object DatasetGate {
    fun decide(local: LocalSnapshot, remote: RemoteSnapshot, base: SyncBase?, choice: ConflictChoice?): SyncMode {
        val sameBase = base != null && base.datasetKey == local.datasetId
        // A doc with neither profile fields nor dataset id (e.g. only the premium flag) holds no dataset.
        val doc = remote.profileDoc.let {
            if (it is RemoteProfileDoc.Live && it.record == null && it.datasetId == null) RemoteProfileDoc.Absent else it
        }
        if (local.profile == null) {
            // Only a profile deleted on this phone has something to propagate; never resurrect it.
            val ourDatasetLive = doc is RemoteProfileDoc.Live && doc.datasetId == local.datasetId
            return if (local.profileDeletedAt != null && ourDatasetLive) SyncMode.Merge else SyncMode.Idle
        }
        return when (doc) {
            RemoteProfileDoc.Absent -> when {
                sameBase && base!!.profileSynced && remote.anyMeasurementDoc == false -> SyncMode.RemoteWiped
                sameBase -> SyncMode.Merge
                else -> SyncMode.ReplaceCloud // nothing of ours in the cloud yet
            }
            is RemoteProfileDoc.Deleted ->
                if (doc.datasetId == local.datasetId) SyncMode.Merge else SyncMode.ReplaceCloud
            is RemoteProfileDoc.Live -> when {
                doc.datasetId == local.datasetId -> SyncMode.Merge
                choice == ConflictChoice.RESTORE_CLOUD -> SyncMode.AdoptCloud
                choice == ConflictChoice.REPLACE_CLOUD -> SyncMode.ReplaceCloud
                local.measurements.isEmpty() -> SyncMode.AdoptCloud // fresh profile on a new phone: restore
                else -> SyncMode.NeedsChoice(ConflictKind.DIFFERENT_DATA)
            }
        }
    }
}
