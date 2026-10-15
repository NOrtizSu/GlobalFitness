package com.enjoyyourtime.globalfitness.cloud

/**
 * Converts records to and from Firestore field maps. Pure Kotlin: the Firestore layer adds the
 * server `updatedAt` timestamp on write and passes it back as epoch millis on read.
 *
 * Layout under `users/{uid}/`:
 * - `meta/profile`, `meta/settings`
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

    sealed interface Decoded<out T> {
        data class Live<T>(val record: T) : Decoded<T>
        data class Dead(val tombstone: Tombstone) : Decoded<Nothing>
    }

    // ---- Profile -------------------------------------------------------------------------------

    fun profileToMap(p: ProfileRecord): Map<String, Any?> = mapOf(
        F_SCHEMA to CLOUD_SCHEMA_VERSION,
        "name" to p.name,
        "sex" to p.sex,
        "birthDate" to p.birthDate,
        "heightCm" to p.heightCm,
        "targetWeightKg" to p.targetWeightKg,
        F_EDITED_AT to p.editedAt,
    )

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
        "source" to m.source,
        "scaleKey" to m.scaleKey,
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
        return Decoded.Live(
            MeasurementRecord(
                timestamp = timestamp,
                weightGrams = grams,
                impedance = (data["impedance"] as? Number)?.toInt(),
                source = data["source"] as? String ?: "scale",
                scaleKey = data["scaleKey"] as? String,
                updatedAt = updatedAtMillis ?: timestamp,
            ),
        )
    }

    // ---- Devices -------------------------------------------------------------------------------

    /** Per-driver options are filtered: no secrets or addresses are uploaded. */
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

    /** Device document ids: letters, digits, '_' and '-' only. */
    fun safeDocId(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120).ifEmpty { "_" }

    private fun tombstoneOf(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded.Dead? {
        if (data[F_DELETED] != true) return null
        val deletedAt = (data[F_DELETED_AT] as? Number)?.toLong() ?: updatedAtMillis ?: 0L
        return Decoded.Dead(Tombstone(id, deletedAt))
    }
}
