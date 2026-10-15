package com.enjoyyourtime.globalfitness.cloud

/**
 * Converts records to and from Firestore field maps. Pure Kotlin: the Firestore layer adds the
 * server `updatedAt` timestamp on write and passes it back as epoch millis on read.
 *
 * Layout under `users/{uid}/`:
 * - `meta/profile` (live: profile fields + `datasetId`; deleted: `{deleted: true, deletedAt, datasetId}`)
 * - `meta/settings`
 * - `measurements/{m_<epochMillis>_<weightGrams>}` (tombstone: `{deleted: true, deletedAt}`)
 * - `devices/{id}` (tombstone: `{deleted: true, deletedAt}`)
 */
object FirestoreCodec {
    const val USERS = "users"
    const val META = "meta"
    const val PROFILE_DOC = "profile"
    const val SETTINGS_DOC = "settings"
    const val MEASUREMENTS = "measurements"
    const val DEVICES = "devices"

    const val F_SCHEMA = "schemaVersion"
    const val F_UPDATED_AT = "updatedAt"
    const val F_EDITED_AT = "editedAt"
    const val F_DELETED = "deleted"
    const val F_DELETED_AT = "deletedAt"
    const val F_PREMIUM = "premium"
    const val F_DATASET = "datasetId"
    const val F_COMPOSITION = "composition"

    /** Live profile fields; written with mergeFields so other fields (premium) survive. */
    val PROFILE_FIELDS = listOf(F_SCHEMA, "name", "sex", "birthDate", "heightCm", "targetWeightKg", F_EDITED_AT, F_DATASET, F_DELETED, F_DELETED_AT)

    sealed interface Decoded<out T> {
        data class Live<T>(val record: T) : Decoded<T>
        data class Dead(val tombstone: Tombstone) : Decoded<Nothing>
    }

    // ---- Profile -------------------------------------------------------------------------------

    fun profileToMap(p: ProfileRecord, datasetId: String): Map<String, Any?> = mapOf(
        F_SCHEMA to CLOUD_SCHEMA_VERSION,
        "name" to p.name,
        "sex" to p.sex,
        "birthDate" to p.birthDate,
        "heightCm" to p.heightCm,
        "targetWeightKg" to p.targetWeightKg,
        F_EDITED_AT to p.editedAt,
        F_DATASET to datasetId,
        F_DELETED to false,
    )

    fun profileTombstoneToMap(deletedAt: Long, datasetId: String): Map<String, Any?> = mapOf(
        F_SCHEMA to CLOUD_SCHEMA_VERSION,
        F_DELETED to true,
        F_DELETED_AT to deletedAt,
        F_DATASET to datasetId,
    )

    /** State of the profile document; null [data] means the document does not exist. */
    fun profileDocFromMap(data: Map<String, Any?>?, updatedAtMillis: Long?): RemoteProfileDoc {
        if (data == null) return RemoteProfileDoc.Absent
        val datasetId = data[F_DATASET] as? String
        if (data[F_DELETED] == true) {
            return RemoteProfileDoc.Deleted((data[F_DELETED_AT] as? Number)?.toLong() ?: updatedAtMillis ?: 0L, datasetId)
        }
        return RemoteProfileDoc.Live(profileFromMap(data, updatedAtMillis), datasetId)
    }

    fun profileFromMap(data: Map<String, Any?>, updatedAtMillis: Long?): ProfileRecord? {
        val name = data["name"] as? String ?: return null
        val sex = data["sex"] as? String ?: return null
        val birthDate = data["birthDate"] as? String ?: return null
        val height = (data["heightCm"] as? Number)?.toDouble() ?: return null
        return ProfileRecord(
            name = name,
            sex = sex,
            birthDate = birthDate,
            heightCm = height,
            targetWeightKg = (data["targetWeightKg"] as? Number)?.toDouble(),
            editedAt = (data[F_EDITED_AT] as? Number)?.toLong() ?: updatedAtMillis ?: 0L,
        )
    }

    // ---- Settings ------------------------------------------------------------------------------

    /** Only [PortableSettings.ALLOWED] keys are written, whatever the record contains. */
    fun settingsToMap(s: SettingsRecord): Map<String, Any?> = mapOf(
        F_SCHEMA to CLOUD_SCHEMA_VERSION,
        "values" to PortableSettings.filterSettings(s.values),
        F_EDITED_AT to s.editedAt,
    )

    fun settingsFromMap(data: Map<String, Any?>, updatedAtMillis: Long?): SettingsRecord? {
        val values = data["values"] as? Map<*, *> ?: return null
        val typed = values.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }.toMap()
        return SettingsRecord(
            values = PortableSettings.filterSettings(typed),
            editedAt = (data[F_EDITED_AT] as? Number)?.toLong() ?: updatedAtMillis ?: 0L,
        )
    }

    // ---- Measurements --------------------------------------------------------------------------

    fun measurementToMap(m: MeasurementRecord): Map<String, Any?> = mapOf(
        F_SCHEMA to CLOUD_SCHEMA_VERSION,
        F_DELETED to false,
        "timestamp" to m.timestamp,
        "weightGrams" to m.weightGrams,
        "weightKg" to m.weightGrams / 1000.0,
        "impedance" to m.impedance,
        "impedanceLow" to m.impedanceLow,
        "source" to m.source,
        "scaleKey" to m.scaleKey,
        F_COMPOSITION to m.composition.filterKeys { it in CompositionKeys.ALL },
        "compositionSource" to m.compositionSource,
        "formulaLib" to m.formulaLib,
        "deviceDriver" to m.deviceDriver,
    )

    fun tombstoneToMap(t: Tombstone): Map<String, Any?> = mapOf(
        F_SCHEMA to CLOUD_SCHEMA_VERSION,
        F_DELETED to true,
        F_DELETED_AT to t.deletedAt,
    )

    fun measurementFromMap(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded<MeasurementRecord>? {
        tombstoneOf(id, data, updatedAtMillis)?.let { return it }
        val (idTimestamp, idGrams) = MeasurementIds.parse(id) ?: return null
        val timestamp = (data["timestamp"] as? Number)?.toLong() ?: idTimestamp
        val grams = (data["weightGrams"] as? Number)?.toLong() ?: idGrams
        // The id is the identity: a doc whose fields disagree with its id is ignored.
        if (timestamp != idTimestamp || grams != idGrams) return null
        val composition = (data[F_COMPOSITION] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) -> if (k is String && k in CompositionKeys.ALL && v is Number) k to v.toDouble() else null }
            ?.toMap()
            .orEmpty()
        return Decoded.Live(
            MeasurementRecord(
                timestamp = timestamp,
                weightGrams = grams,
                impedance = (data["impedance"] as? Number)?.toInt(),
                source = data["source"] as? String ?: "scale",
                scaleKey = data["scaleKey"] as? String,
                updatedAt = updatedAtMillis ?: timestamp,
                composition = composition,
                compositionSource = data["compositionSource"] as? String,
                formulaLib = data["formulaLib"] as? String,
                deviceDriver = data["deviceDriver"] as? String,
                impedanceLow = (data["impedanceLow"] as? Number)?.toInt(),
            ),
        )
    }

    // ---- Devices -------------------------------------------------------------------------------

    /** Per-scale options are filtered: no secrets or addresses are uploaded. */
    fun deviceToMap(d: DeviceRecord): Map<String, Any?> = mapOf(
        F_SCHEMA to CLOUD_SCHEMA_VERSION,
        F_DELETED to false,
        "driverId" to d.driverId,
        "name" to d.name,
        "config" to PortableSettings.filterDeviceConfig(d.config),
    )

    fun deviceFromMap(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded<DeviceRecord>? {
        tombstoneOf(id, data, updatedAtMillis)?.let { return it }
        val driverId = data["driverId"] as? String ?: return null
        val config = (data["config"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) -> if (k is String && v != null) k to v.toString() else null }
            ?.toMap()
            .orEmpty()
        return Decoded.Live(
            DeviceRecord(
                id = id,
                driverId = driverId,
                name = data["name"] as? String ?: driverId,
                config = PortableSettings.filterDeviceConfig(config),
                updatedAt = updatedAtMillis ?: 0L,
            ),
        )
    }

    /** Document ids for devices: letters, digits, '_' and '-' only (same id on write and read). */
    fun safeDocId(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120).ifEmpty { "_" }

    private fun tombstoneOf(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded.Dead? {
        if (data[F_DELETED] != true) return null
        val deletedAt = (data[F_DELETED_AT] as? Number)?.toLong() ?: updatedAtMillis ?: 0L
        return Decoded.Dead(Tombstone(id, deletedAt))
    }
}
