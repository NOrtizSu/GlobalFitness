package com.enjoyyourtime.globalfitness.cloud

import kotlinx.coroutines.flow.Flow

/** Written to every cloud document so future versions can migrate old data. */
const val CLOUD_SCHEMA_VERSION = 1

/** The single user profile in portable form: no local ids and no UI-only fields (colors...). */
data class ProfileRecord(
    val name: String,
    /** "M" or "F". */
    val sex: String,
    /** ISO date, yyyy-MM-dd. */
    val birthDate: String,
    val heightCm: Double,
    val targetWeightKg: Double?,
    /** Client time of the last edit (epoch ms) for last-writer-wins; 0 when unknown. */
    val editedAt: Long,
)

/** Portable settings only (see [PortableSettings]); values are String, Number or Boolean. */
data class SettingsRecord(
    val values: Map<String, Any>,
    /** Client time of the last edit (epoch ms) for last-writer-wins; 0 when unknown. */
    val editedAt: Long,
)

data class MeasurementRecord(
    val timestamp: Long,
    val weightGrams: Long,
    val impedance: Int?,
    /** "scale" or "manual". */
    val source: String,
    /** Scale-specific duplicate key, if any. */
    val scaleKey: String?,
    /** Best known time of the last change (epoch ms): server time for cloud docs. */
    val updatedAt: Long,
) {
    val id: String get() = MeasurementIds.idOf(timestamp, weightGrams)
}

/** A configured scale: driver, user-facing name and per-driver options (never secrets or addresses). */
data class DeviceRecord(
    /** Stable id chosen by the data source, e.g. "<driverId>_<n>"; only [A-Za-z0-9_-]. */
    val id: String,
    val driverId: String,
    val name: String,
    val config: Map<String, String>,
    val updatedAt: Long,
)

/** A deleted document. Tombstones win over live versions that are older than [deletedAt]. */
data class Tombstone(val id: String, val deletedAt: Long)

/** Everything the phone wants backed up. */
data class LocalSnapshot(
    /**
     * Identifies the local dataset (e.g. the owner profile). When it changes, the previous sync base
     * is not trusted to detect local deletions, so nothing is deleted from the cloud by accident.
     */
    val datasetKey: String,
    val profile: ProfileRecord?,
    val settings: SettingsRecord?,
    val measurements: List<MeasurementRecord>,
    val devices: List<DeviceRecord>,
)

/** Cloud state read for one sync. Measurements may be incremental (only docs changed since the cursor). */
data class RemoteSnapshot(
    val profile: ProfileRecord?,
    val settings: SettingsRecord?,
    val measurements: List<MeasurementRecord>,
    val measurementTombstones: List<Tombstone>,
    val devices: List<DeviceRecord>,
    val deviceTombstones: List<Tombstone>,
    /** True when [measurements] + [measurementTombstones] is the whole collection. */
    val complete: Boolean,
    /** Highest server updatedAt (epoch ms) among the measurement docs read, for the next pull. */
    val maxUpdatedAt: Long?,
) {
    companion object {
        val EMPTY = RemoteSnapshot(null, null, emptyList(), emptyList(), emptyList(), emptyList(), complete = true, maxUpdatedAt = null)
    }
}

/** Changes the sync engine asks the phone to apply. */
data class RemoteChanges(
    val profile: ProfileRecord? = null,
    val settings: SettingsRecord? = null,
    val upsertMeasurements: List<MeasurementRecord> = emptyList(),
    val deleteMeasurementIds: Set<String> = emptySet(),
    val upsertDevices: List<DeviceRecord> = emptyList(),
    val deleteDeviceIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = profile == null && settings == null && upsertMeasurements.isEmpty() && deleteMeasurementIds.isEmpty() &&
            upsertDevices.isEmpty() && deleteDeviceIds.isEmpty()
}

/** One cloud write produced by a sync. */
sealed interface CloudWrite {
    data class PutProfile(val record: ProfileRecord) : CloudWrite
    data class PutSettings(val record: SettingsRecord) : CloudWrite
    data class PutMeasurement(val record: MeasurementRecord) : CloudWrite
    data class DeleteMeasurement(val tombstone: Tombstone) : CloudWrite
    data class PutDevice(val record: DeviceRecord) : CloudWrite
    data class DeleteDevice(val tombstone: Tombstone) : CloudWrite
}

/**
 * The only door between the cloud backup and the app's local data. The sync engine never touches
 * the database or the settings directly; one adapter implements this over the data layer.
 */
interface BackupDataSource {
    /** Current local state (suspends until the local data is loaded). */
    suspend fun snapshot(): LocalSnapshot

    /** Applies changes that came from the cloud (or from a merge). */
    suspend fun apply(remote: RemoteChanges)

    /** Emits after any local change that could need uploading. */
    val changes: Flow<Unit>
}
