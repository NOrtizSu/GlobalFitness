package com.enjoyyourtime.globalfitness.cloud

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Persistent UUID of the local dataset (the owner profile and its data on this phone), mirrored in
 * `meta/profile.datasetId`. It lives in normal preferences, so Android Auto Backup restores it
 * together with the database; clearing the app data or a reinstall without backup creates a new one.
 * The cloud copy is only merged with a phone whose dataset id matches.
 */
data class DatasetIdentity(
    val datasetId: String,
    /** [LocalSnapshot.ownerKey] this dataset belongs to; null while there is no profile. */
    val ownerKey: String?,
    /** When the owner profile was deleted on this phone (deletion journal), if it was. */
    val profileDeletedAt: Long?,
) {
    /** Takes over the cloud's dataset after restoring the cloud copy on this phone. */
    fun adopt(cloudDatasetId: String): DatasetIdentity = copy(datasetId = cloudDatasetId, profileDeletedAt = null)

    companion object {
        /**
         * Next identity for the owner seen now:
         * - first run: a new dataset;
         * - same owner: unchanged;
         * - owner profile deleted: same dataset, deletion journaled (propagated by the next sync);
         * - a profile created after that deletion (or the first one): same dataset;
         * - a different owner replacing an existing one: a new dataset (never merged automatically).
         */
        fun observe(stored: DatasetIdentity?, ownerKey: String?, now: Long, newId: () -> String): DatasetIdentity = when {
            stored == null -> DatasetIdentity(newId(), ownerKey, null)
            ownerKey == stored.ownerKey -> stored
            ownerKey == null -> stored.copy(ownerKey = null, profileDeletedAt = stored.profileDeletedAt ?: now)
            stored.ownerKey == null -> stored.copy(ownerKey = ownerKey)
            else -> DatasetIdentity(newId(), ownerKey, null)
        }
    }
}

/** Stores the [DatasetIdentity] in the `cloud_dataset` preferences (included in Auto Backup). */
class DatasetIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_dataset", Context.MODE_PRIVATE)

    fun load(): DatasetIdentity? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        return DatasetIdentity(
            datasetId = id,
            ownerKey = prefs.getString(KEY_OWNER, null),
            profileDeletedAt = prefs.getLong(KEY_DELETED_AT, -1L).takeIf { it >= 0 },
        )
    }

    /** Observes the current owner, persisting any transition. */
    fun observe(ownerKey: String?, now: Long): DatasetIdentity {
        val stored = load()
        val next = DatasetIdentity.observe(stored, ownerKey, now) { UUID.randomUUID().toString() }
        if (next != stored) save(next)
        return next
    }

    fun save(identity: DatasetIdentity) {
        prefs.edit {
            putString(KEY_ID, identity.datasetId)
            if (identity.ownerKey == null) remove(KEY_OWNER) else putString(KEY_OWNER, identity.ownerKey)
            if (identity.profileDeletedAt == null) remove(KEY_DELETED_AT) else putLong(KEY_DELETED_AT, identity.profileDeletedAt)
        }
    }

    private companion object {
        const val KEY_ID = "dataset_id"
        const val KEY_OWNER = "owner_key"
        const val KEY_DELETED_AT = "profile_deleted_at"
    }
}
