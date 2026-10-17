package com.enjoyyourtime.globalfitness.cloud

import kotlinx.coroutines.flow.Flow

/**
 * Written to every cloud document so future versions can migrate old data.
 * v2: `meta/profile.datasetId`, profile tombstones, measurement composition and provenance fields.
 * Later v2 documents may also carry the optional `impedanceLow` field and the composition key
 * `skeletalMusclePercent` (additive: readers ignore unknown keys and treat missing ones as null).
 * v1 documents are still read (the new fields are simply missing).
 */
const val CLOUD_SCHEMA_VERSION = 2

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
    /** Composition reported by the scale, by [CompositionKeys] name; empty when none. */
    val composition: Map<String, Double> = emptyMap(),
    /** Where the composition comes from (data layer code, e.g. "DEVICE", "FORMULA_S400"). */
    val compositionSource: String? = null,
    /** Formula library id used for impedance-based composition. */
    val formulaLib: String? = null,
    /** Driver id of the scale that produced it. */
    val deviceDriver: String? = null,
    /** Low-frequency impedance of dual-frequency scales (S400, Keep S3). */
    val impedanceLow: Int? = null,
) {
    val id: String get() = MeasurementIds.idOf(timestamp, weightGrams)

    /** Impedance or any composition value: the scale data a later reading can add to a weighing. */
    val hasScaleData: Boolean get() = impedance != null || impedanceLow != null || composition.isNotEmpty()
}

/** Field names of the device-provided composition inside measurement documents. */
object CompositionKeys {
    const val FAT_PERCENT = "fatPercent"
    const val WATER_PERCENT = "waterPercent"
    const val MUSCLE_PERCENT = "musclePercent"
    const val MUSCLE_KG = "muscleKg"
    const val BONE_KG = "boneKg"
    const val VISCERAL_FAT = "visceralFat"
    const val BMR_KCAL = "bmrKcal"
    const val LBM_KG = "lbmKg"
    const val PROTEIN_PERCENT = "proteinPercent"
    const val METABOLIC_AGE = "metabolicAge"
    const val SKELETAL_MUSCLE_PERCENT = "skeletalMusclePercent"

    val ALL = setOf(
        FAT_PERCENT, WATER_PERCENT, MUSCLE_PERCENT, MUSCLE_KG, BONE_KG,
        VISCERAL_FAT, BMR_KCAL, LBM_KG, PROTEIN_PERCENT, METABOLIC_AGE, SKELETAL_MUSCLE_PERCENT,
    )
}

/** A configured scale: driver, user-facing name and per-driver options (never secrets or addresses). */
data class DeviceRecord(
    /** Stable id chosen by the data source; only [A-Za-z0-9_-] (see [FirestoreCodec.safeDocId]). */
    val id: String,
    val driverId: String,
    val name: String,
    val config: Map<String, String>,
    val updatedAt: Long,
)

/** A deleted document. Tombstones win over live versions that are older than [deletedAt]. */
data class Tombstone(val id: String, val deletedAt: Long)

/**
 * How the data layer de-duplicates imported readings: two measurements within [windowMs] whose
 * weights differ by at most [toleranceGrams] are the same weighing (e.g. captured by two phones).
 */
data class DuplicateRule(val windowMs: Long, val toleranceGrams: Long) {
    fun sameWeighing(a: MeasurementRecord, b: MeasurementRecord): Boolean =
        kotlin.math.abs(a.timestamp - b.timestamp) <= windowMs && kotlin.math.abs(a.weightGrams - b.weightGrams) <= toleranceGrams
}

/** Everything the phone wants backed up. */
data class LocalSnapshot(
    /**
     * Local identity of the owner profile from the data source (stable for the life of that
     * profile), or null when there is no profile yet. The engine maps it to [datasetId].
     */
    val ownerKey: String?,
    val profile: ProfileRecord?,
    val settings: SettingsRecord?,
    val measurements: List<MeasurementRecord>,
    val devices: List<DeviceRecord>,
    /** How the data source de-duplicates readings; null disables aliasing. */
    val duplicateRule: DuplicateRule? = null,
    /** Filled by the engine: the persistent UUID of this local dataset (see [DatasetIdentity]). */
    val datasetId: String = "",
    /** Filled by the engine: when the owner profile was deleted on this phone, if it was. */
    val profileDeletedAt: Long? = null,
)

/** State of the `meta/profile` document. */
sealed interface RemoteProfileDoc {
    data object Absent : RemoteProfileDoc
    data class Live(val record: ProfileRecord?, val datasetId: String?) : RemoteProfileDoc
    data class Deleted(val deletedAt: Long, val datasetId: String?) : RemoteProfileDoc
}

/** Cloud state read for one sync. Measurements may be incremental (only docs changed since the cursor). */
data class RemoteSnapshot(
    val profileDoc: RemoteProfileDoc,
    val settings: SettingsRecord?,
    val measurements: List<MeasurementRecord>,
    val measurementTombstones: List<Tombstone>,
    val devices: List<DeviceRecord>,
    val deviceTombstones: List<Tombstone>,
    /** True when [measurements] + [measurementTombstones] is the whole collection. */
    val complete: Boolean,
    /** Highest server updatedAt (epoch ms) among the measurement docs read, for the next pull. */
    val maxUpdatedAt: Long?,
    /** Whether the measurements collection holds any doc at all (probed only when the profile is absent). */
    val anyMeasurementDoc: Boolean? = null,
) {
    val profile: ProfileRecord? get() = (profileDoc as? RemoteProfileDoc.Live)?.record

    companion object {
        val EMPTY = RemoteSnapshot(
            RemoteProfileDoc.Absent, null, emptyList(), emptyList(), emptyList(), emptyList(),
            complete = true, maxUpdatedAt = null, anyMeasurementDoc = false,
        )
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
    data class PutProfile(val record: ProfileRecord, val datasetId: String) : CloudWrite
    data class DeleteProfile(val deletedAt: Long, val datasetId: String) : CloudWrite
    data class PutSettings(val record: SettingsRecord) : CloudWrite

    /** [force]: overwrite even a newer tombstone (only when replacing the whole cloud copy). */
    data class PutMeasurement(val record: MeasurementRecord, val force: Boolean = false) : CloudWrite
    data class DeleteMeasurement(val tombstone: Tombstone) : CloudWrite
    data class PutDevice(val record: DeviceRecord) : CloudWrite
    data class DeleteDevice(val tombstone: Tombstone) : CloudWrite
}

/**
 * The only door between the cloud backup and the app's local data. The sync engine never touches
 * the database or the settings directly; one adapter implements this over the data layer.
 */
interface BackupDataSource {
    /** Current local state. Suspends until the local data is loaded; throws if it cannot be. */
    suspend fun snapshot(): LocalSnapshot

    /** Applies changes that came from the cloud (or from a merge). Deletions are applied first. */
    suspend fun apply(remote: RemoteChanges)

    /** Emits after any local change that could need uploading. */
    val changes: Flow<Unit>
}
