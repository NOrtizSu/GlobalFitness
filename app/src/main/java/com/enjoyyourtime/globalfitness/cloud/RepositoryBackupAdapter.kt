package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.data.CsvImportRow
import com.enjoyyourtime.globalfitness.data.ImportPlanner
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.ProfileDraft
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.data.SettingsStore
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.domain.WeightMath
import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.ConfiguredScale
import com.enjoyyourtime.globalfitness.scale.DeviceComposition
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The ONLY class that connects the cloud backup to the data layer (single-profile [Repository] and
 * [SettingsStore]).
 *
 * - Profile: the app's single profile; its local identity ([LocalSnapshot.ownerKey]) is
 *   `profile:<id>:<createdAt>`. The data layer has no edit time, so `editedAt` is 0 and the engine
 *   stamps real edits (a new phone never overwrites the cloud profile with its fresh copy).
 * - Measurements: all of them, with the scale data (impedance, device composition, composition
 *   source, formula library, driver). The scale's Bluetooth address is not uploaded.
 * - Settings: nothing portable remains (assignment tolerance is gone), so none are synced.
 * - Devices: configured scales (id, driver, name, user slot only). Bind keys, PINs, pairing
 *   secrets and addresses stay on the phone; a scale restored on a new phone matches any device of
 *   its driver until the user re-enters its secrets.
 */
class RepositoryBackupAdapter(
    private val repository: Repository,
    private val settings: SettingsStore,
) : BackupDataSource {

    override val changes: Flow<Unit> = merge(
        repository.profile.drop(1).map { },
        repository.measurements.drop(1).map { },
        settings.configuredScales.drop(1).map { },
    )

    override suspend fun snapshot(): LocalSnapshot {
        awaitLoaded()
        val profile = repository.profile.value
        val all = repository.measurements.value
        val scales = settings.configuredScales.value
        return withContext(Dispatchers.Default) {
            LocalSnapshot(
                ownerKey = profile?.let { "profile:${it.id}:${it.createdAt}" },
                profile = profile?.toRecord(),
                settings = null,
                measurements = if (profile == null) emptyList() else all.map { it.toRecord() },
                devices = scales.map { it.toRecord() },
                duplicateRule = DuplicateRule(ImportPlanner.DUPLICATE_WINDOW_MS, WeightMath.SAME_READING_TOLERANCE_G),
            )
        }
    }

    override suspend fun apply(remote: RemoteChanges) {
        awaitLoaded()
        remote.profile?.let { applyProfile(it) }
        applyDevices(remote.upsertDevices, remote.deleteDeviceIds)

        val byId = HashMap<String, Measurement>()
        for (m in repository.measurements.value) byId.putIfAbsent(MeasurementIds.idOf(m.timestamp, m.weightKg), m)
        for (id in remote.deleteMeasurementIds) {
            byId.remove(id)?.let { repository.deleteMeasurement(it.id) }
        }
        if (repository.profile.value == null) return
        val inserts = ArrayList<CsvImportRow>()
        for (record in remote.upsertMeasurements) {
            val existing = byId[record.id]
            when {
                existing == null -> inserts += record.toImportRow()
                record.hasScaleData && SyncMerge.hashOf(existing.toRecord()) != SyncMerge.hashOf(record) ->
                    repository.updateScaleData(existing.withScaleDataOf(record))
            }
        }
        // One transaction for a whole restore. Rows the importer sees as duplicates are skipped.
        if (inserts.isNotEmpty()) repository.importRows(inserts, invalid = 0)
    }

    /** Fails fast instead of waiting forever when the local database cannot be opened. */
    private suspend fun awaitLoaded() {
        val ready = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            combine(repository.loaded, repository.loadError) { loaded, error -> loaded || error != null }.first { it }
        }
        repository.loadError.value?.let { throw IllegalStateException("No se pudo abrir la base de datos del teléfono: $it") }
        if (ready == null) throw IllegalStateException("La base de datos del teléfono no termina de cargar.")
    }

    private suspend fun applyProfile(record: ProfileRecord) {
        val birthDate = try {
            LocalDate.parse(record.birthDate)
        } catch (e: DateTimeParseException) {
            return
        }
        if (record.name.isBlank() || record.heightCm <= 0) return
        repository.saveProfile(
            ProfileDraft(
                name = record.name,
                sex = Sex.fromCode(record.sex),
                birthDate = birthDate,
                heightCm = record.heightCm,
                targetWeightKg = record.targetWeightKg,
            ),
        )
    }

    /**
     * Restored scales keep any local secrets/address of the same entry; a cloud scale whose driver is
     * already configured here (e.g. auto-detected on both phones) is not added twice.
     */
    private fun applyDevices(upserts: List<DeviceRecord>, deletes: Set<String>) {
        if (upserts.isEmpty() && deletes.isEmpty()) return
        settings.updateConfiguredScales { current ->
            val kept = current.filterNot { FirestoreCodec.safeDocId(it.id) in deletes }.toMutableList()
            for (record in upserts) {
                val index = kept.indexOfFirst { FirestoreCodec.safeDocId(it.id) == record.id }
                if (index >= 0) {
                    val local = kept[index]
                    kept[index] = local.copy(name = record.name, config = local.config + record.config)
                } else if (kept.none { it.driverId == record.driverId }) {
                    kept += ConfiguredScale(
                        id = record.id,
                        driverId = record.driverId,
                        address = null,
                        name = record.name,
                        config = record.config,
                        addedAt = record.updatedAt,
                    )
                }
            }
            kept
        }
    }

    private fun Profile.toRecord() = ProfileRecord(
        name = name,
        sex = sex.code,
        birthDate = birthDate.toString(),
        heightCm = heightCm,
        targetWeightKg = targetWeightKg,
        editedAt = 0L,
    )

    private fun Measurement.toRecord() = MeasurementRecord(
        timestamp = timestamp,
        weightGrams = MeasurementIds.grams(weightKg),
        impedance = impedance,
        source = source.code,
        scaleKey = scaleKey,
        updatedAt = timestamp,
        composition = composition?.toMap().orEmpty(),
        compositionSource = compositionSource?.code,
        formulaLib = formulaLib?.id,
        deviceDriver = deviceDriver,
    )

    private fun ConfiguredScale.toRecord() = DeviceRecord(
        id = FirestoreCodec.safeDocId(id),
        driverId = driverId,
        name = name,
        config = PortableSettings.filterDeviceConfig(config),
        updatedAt = addedAt,
    )

    private fun Measurement.withScaleDataOf(record: MeasurementRecord) = copy(
        impedance = record.impedance,
        composition = record.composition.toDeviceComposition(),
        compositionSource = CompositionSource.fromCode(record.compositionSource),
        formulaLib = FormulaLib.fromId(record.formulaLib),
        deviceDriver = record.deviceDriver,
    )

    private fun MeasurementRecord.toImportRow() = CsvImportRow(
        profileName = null,
        sex = null,
        birthDate = null,
        heightCm = null,
        targetWeightKg = null,
        timestamp = timestamp,
        weightKg = weightGrams / 1000.0,
        impedance = impedance,
        source = MeasurementSource.fromCode(source),
        scaleKey = scaleKey,
        composition = composition.toDeviceComposition(),
        compositionSource = CompositionSource.fromCode(compositionSource),
        formulaLib = FormulaLib.fromId(formulaLib),
        deviceDriver = deviceDriver,
    )

    private fun DeviceComposition.toMap(): Map<String, Double> = buildMap {
        fatPercent?.let { put(CompositionKeys.FAT_PERCENT, it) }
        waterPercent?.let { put(CompositionKeys.WATER_PERCENT, it) }
        musclePercent?.let { put(CompositionKeys.MUSCLE_PERCENT, it) }
        muscleKg?.let { put(CompositionKeys.MUSCLE_KG, it) }
        boneKg?.let { put(CompositionKeys.BONE_KG, it) }
        visceralFat?.let { put(CompositionKeys.VISCERAL_FAT, it) }
        bmrKcal?.let { put(CompositionKeys.BMR_KCAL, it) }
        lbmKg?.let { put(CompositionKeys.LBM_KG, it) }
        proteinPercent?.let { put(CompositionKeys.PROTEIN_PERCENT, it) }
        metabolicAge?.let { put(CompositionKeys.METABOLIC_AGE, it) }
    }

    private fun Map<String, Double>.toDeviceComposition(): DeviceComposition? =
        if (isEmpty()) {
            null
        } else {
            DeviceComposition(
                fatPercent = this[CompositionKeys.FAT_PERCENT],
                waterPercent = this[CompositionKeys.WATER_PERCENT],
                musclePercent = this[CompositionKeys.MUSCLE_PERCENT],
                muscleKg = this[CompositionKeys.MUSCLE_KG],
                boneKg = this[CompositionKeys.BONE_KG],
                visceralFat = this[CompositionKeys.VISCERAL_FAT],
                bmrKcal = this[CompositionKeys.BMR_KCAL],
                lbmKg = this[CompositionKeys.LBM_KG],
                proteinPercent = this[CompositionKeys.PROTEIN_PERCENT],
                metabolicAge = this[CompositionKeys.METABOLIC_AGE],
            )
        }

    private companion object {
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}
