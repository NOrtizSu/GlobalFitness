package com.enjoyyourtime.globalfitness.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ImportResult(val imported: Int, val duplicates: Int, val invalid: Int, val createdProfiles: Int)

/**
 * Single source of truth. Everything is loaded into memory at start; writes go to SQLite on
 * the IO dispatcher and then update the in-memory StateFlows.
 */
class Repository(
    private val db: AppDatabase,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _measurements = MutableStateFlow<List<Measurement>>(emptyList())

    /** All measurements sorted by timestamp ascending. */
    val measurements: StateFlow<List<Measurement>> = _measurements.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)

    /** Set when the database could not be opened/read; the UI shows it instead of loading forever. */
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                try {
                    val (p, m) = withContext(io) {
                        db.fixOrphans()
                        db.loadProfiles() to db.loadMeasurements()
                    }
                    _profiles.value = p
                    _measurements.value = m
                    _loaded.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _loadError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    private suspend fun <T> write(block: suspend () -> T): T {
        awaitLoaded()
        return mutex.withLock { block() }
    }

    private fun profileExists(id: Long?): Boolean = id != null && _profiles.value.any { it.id == id }

    // ---- Profiles -------------------------------------------------------------------------

    suspend fun createProfile(draft: ProfileDraft): Profile = write {
        val profile = Profile(
            id = 0,
            name = draft.name.trim(),
            sex = draft.sex,
            birthDate = draft.birthDate,
            heightCm = draft.heightCm,
            targetWeightKg = draft.targetWeightKg,
            colorIndex = ProfileColors.nextColorIndex(_profiles.value),
            createdAt = clock(),
        )
        val id = withContext(io) { db.insertProfile(profile) }
        val created = profile.copy(id = id)
        _profiles.value = _profiles.value + created
        created
    }

    suspend fun updateProfile(id: Long, draft: ProfileDraft) = write {
        val current = _profiles.value.firstOrNull { it.id == id } ?: return@write
        val updated = current.copy(
            name = draft.name.trim(),
            sex = draft.sex,
            birthDate = draft.birthDate,
            heightCm = draft.heightCm,
            targetWeightKg = draft.targetWeightKg,
        )
        withContext(io) { db.updateProfile(updated) }
        _profiles.value = _profiles.value.map { if (it.id == id) updated else it }
    }

    suspend fun deleteProfile(id: Long, deleteMeasurements: Boolean) = write {
        withContext(io) { db.deleteProfile(id, deleteMeasurements) }
        _profiles.value = _profiles.value.filterNot { it.id == id }
        _measurements.value = if (deleteMeasurements) {
            _measurements.value.filterNot { it.profileId == id }
        } else {
            _measurements.value.map { if (it.profileId == id) it.copy(profileId = null) else it }
        }
    }

    // ---- Measurements ---------------------------------------------------------------------

    /**
     * Inserts and returns the stored measurement, or null if its scale_key already exists.
     * A profile that no longer exists (deleted meanwhile) turns it into a pending measurement.
     */
    suspend fun insertMeasurement(m: Measurement): Measurement? = write {
        val safe = if (m.profileId != null && !profileExists(m.profileId)) m.copy(profileId = null) else m
        val id = withContext(io) { db.insertMeasurement(safe) }
        if (id < 0) return@write null
        val stored = safe.copy(id = id)
        _measurements.value = sorted(_measurements.value + stored)
        stored
    }

    suspend fun setImpedance(id: Long, impedance: Int): Measurement? = write {
        if (_measurements.value.none { it.id == id }) return@write null
        withContext(io) { db.updateMeasurementImpedance(id, impedance) }
        var updated: Measurement? = null
        _measurements.value = _measurements.value.map {
            if (it.id == id) it.copy(impedance = impedance).also { u -> updated = u } else it
        }
        updated
    }

    suspend fun assignMeasurement(id: Long, profileId: Long?) = write {
        val target = profileId?.takeIf { profileExists(it) }
        withContext(io) { db.assignMeasurement(id, target) }
        _measurements.value = _measurements.value.map { if (it.id == id) it.copy(profileId = target) else it }
    }

    suspend fun deleteMeasurement(id: Long) = write {
        withContext(io) { db.deleteMeasurement(id) }
        _measurements.value = _measurements.value.filterNot { it.id == id }
    }

    /**
     * Imports parsed CSV rows: planning/dedupe off the main thread, then one DB transaction for
     * new profiles and measurements; memory is updated only after the transaction succeeds.
     */
    suspend fun importRows(rows: List<CsvImportRow>, invalid: Int): ImportResult = write {
        val currentProfiles = _profiles.value
        val currentMeasurements = _measurements.value
        val plan = withContext(compute) { ImportPlanner.plan(rows, currentProfiles, currentMeasurements, clock()) }
        val (created, inserted) = withContext(io) { db.applyImport(plan) }
        _profiles.value = currentProfiles + created
        _measurements.value = withContext(compute) { sorted(currentMeasurements + inserted) }
        ImportResult(
            imported = inserted.size,
            duplicates = plan.duplicates + (plan.measurements.size - inserted.size),
            invalid = invalid,
            createdProfiles = created.size,
        )
    }

    private fun sorted(list: List<Measurement>) = list.sortedWith(compareBy({ it.timestamp }, { it.id }))
}
