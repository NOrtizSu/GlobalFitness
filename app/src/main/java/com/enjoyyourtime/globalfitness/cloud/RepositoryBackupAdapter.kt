package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.data.CsvImportRow
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.ProfileDraft
import com.enjoyyourtime.globalfitness.data.Repository
import com.enjoyyourtime.globalfitness.data.SettingsStore
import com.enjoyyourtime.globalfitness.data.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The ONLY class that connects the cloud backup to the data layer, written against the current
 * public API of [Repository] and [SettingsStore] (the integration pass adapts just this file).
 *
 * Mapping with today's multi-profile data:
 * - The backed-up "single profile" is the owner profile: the oldest one (created at onboarding).
 *   Its measurements are backed up; other profiles and pending measurements stay local.
 * - Settings: only the assignment tolerance is portable; the pinned scale MAC, the selected
 *   profile and permission flags are device-specific and never uploaded.
 * - Devices: the current data layer has no configured-scales list, so none are reported (and cloud
 *   devices are left untouched, never deleted).
 */
class RepositoryBackupAdapter(
    private val repository: Repository,
    private val settings: SettingsStore,
) : BackupDataSource {

    override val changes: Flow<Unit> = merge(
        repository.profiles.drop(1).map { },
        repository.measurements.drop(1).map { },
        settings.toleranceKg.drop(1).map { },
    )

    override suspend fun snapshot(): LocalSnapshot {
        repository.awaitLoaded()
        val owner = owner()
        val all = repository.measurements.value
        val tolerance = settings.toleranceKg.value
        return withContext(Dispatchers.Default) {
            LocalSnapshot(
                datasetKey = owner?.let { "profile:${it.id}:${it.createdAt}" } ?: "none",
                profile = owner?.toRecord(),
                settings = SettingsRecord(mapOf(PortableSettings.ASSIGNMENT_TOLERANCE_KG to tolerance), editedAt = 0L),
                measurements = owner?.let { o -> all.filter { it.profileId == o.id }.map { it.toRecord() } }.orEmpty(),
                devices = emptyList(),
            )
        }
    }

    override suspend fun apply(remote: RemoteChanges) {
        repository.awaitLoaded()
        remote.profile?.let { applyProfile(it) }
        (remote.settings?.values?.get(PortableSettings.ASSIGNMENT_TOLERANCE_KG) as? Number)?.let {
            settings.setToleranceKg(it.toDouble())
        }

        val owner = owner() ?: return
        val byId = HashMap<String, Measurement>()
        for (m in repository.measurements.value) {
            if (m.profileId == owner.id) byId.putIfAbsent(MeasurementIds.idOf(m.timestamp, m.weightKg), m)
        }
        for (id in remote.deleteMeasurementIds) {
            byId[id]?.let { repository.deleteMeasurement(it.id) }
        }
        val inserts = ArrayList<CsvImportRow>()
        for (record in remote.upsertMeasurements) {
            val existing = byId[record.id]
            when {
                existing == null -> inserts += record.toImportRow(owner)
                record.impedance != null && record.impedance != existing.impedance ->
                    repository.setImpedance(existing.id, record.impedance)
            }
        }
        // One transaction for a whole restore. Rows the planner sees as duplicates are skipped.
        if (inserts.isNotEmpty()) repository.importRows(inserts, invalid = 0)
    }

    /** The profile created at onboarding (oldest), standing in for the single user profile. */
    private fun owner(): Profile? = repository.profiles.value.minWithOrNull(compareBy({ it.createdAt }, { it.id }))

    private suspend fun applyProfile(record: ProfileRecord) {
        val birthDate = try {
            LocalDate.parse(record.birthDate)
        } catch (e: DateTimeParseException) {
            return
        }
        if (record.name.isBlank() || record.heightCm <= 0) return
        val draft = ProfileDraft(
            name = record.name,
            sex = Sex.fromCode(record.sex),
            birthDate = birthDate,
            heightCm = record.heightCm,
            targetWeightKg = record.targetWeightKg,
        )
        val owner = owner()
        if (owner != null) {
            repository.updateProfile(owner.id, draft)
        } else {
            val created = repository.createProfile(draft)
            settings.setSelectedProfileId(created.id)
        }
    }

    private fun Profile.toRecord() = ProfileRecord(
        name = name,
        sex = sex.code,
        birthDate = birthDate.toString(),
        heightCm = heightCm,
        targetWeightKg = targetWeightKg,
        // The data layer has no edit time; creation time is the best lower bound.
        editedAt = createdAt,
    )

    private fun Measurement.toRecord() = MeasurementRecord(
        timestamp = timestamp,
        weightGrams = MeasurementIds.grams(weightKg),
        impedance = impedance,
        source = source.code,
        scaleKey = scaleKey,
        updatedAt = timestamp,
    )

    private fun MeasurementRecord.toImportRow(owner: Profile) = CsvImportRow(
        profileName = owner.name,
        sex = owner.sex,
        birthDate = owner.birthDate,
        heightCm = owner.heightCm,
        targetWeightKg = owner.targetWeightKg,
        timestamp = timestamp,
        weightKg = weightGrams / 1000.0,
        impedance = impedance,
        source = MeasurementSource.fromCode(source),
        scaleKey = scaleKey,
    )
}
