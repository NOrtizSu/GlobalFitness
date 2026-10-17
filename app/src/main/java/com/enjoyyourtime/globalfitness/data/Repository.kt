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

/** What [com.enjoyyourtime.globalfitness.domain.MeasurementRecorder] needs from the storage. */
interface MeasurementStore {
    val measurements: StateFlow<List<Measurement>>

    suspend fun awaitLoaded()

    suspend fun insertMeasurement(m: Measurement): Measurement?

    suspend fun updateScaleData(updated: Measurement): Measurement?

    /** True when the user deleted the measurement with this scale key. */
    fun isDeletedScaleKey(key: String): Boolean
}

data class ImportResult(
    val imported: Int,
    val duplicates: Int,
    val invalid: Int,
    val createdProfile: Boolean,
    /** Rows of other people skipped from an old multi-profile export. */
    val skippedOtherProfiles: Int,
)

/**
 * Single source of truth. The app has exactly one user profile: every measurement belongs to it.
 * Everything is loaded into memory at start; writes go to SQLite on the IO dispatcher and then
 * update the in-memory StateFlows.
 *
 * Databases from multi-profile versions keep all their rows; the profile kept is the one that was
 * selected (else the one with most measurements), and only its measurements are loaded.
 */
class Repository(
    private val db: AppDatabase,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Profile to keep when an old database has several. */
    private val preferredProfileId: () -> Long? = { null },
) : MeasurementStore {
    private val mutex = Mutex()

    @Volatile private var deletedKeys: Set<String> = emptySet()

    private val _profile = MutableStateFlow<Profile?>(null)

    /** The single user profile (null until onboarding creates it). */
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())

    /** [profile] as a list (0 or 1 element), for screens written for lists. */
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _measurements = MutableStateFlow<List<Measurement>>(emptyList())

    /** All measurements of the profile, sorted by timestamp ascending. */
    override val measurements: StateFlow<List<Measurement>> = _measurements.asStateFlow()

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
                        val all = db.loadProfiles()
                        val primary = choosePrimary(all, db.countMeasurementsByProfile(), preferredProfileId())
                        if (primary != null) db.adoptMeasurements(primary.id)
                        deletedKeys = db.loadDeletedScaleKeys()
                        primary to db.loadMeasurements(primary?.id)
                    }
                    setProfile(p)
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

    override fun isDeletedScaleKey(key: String): Boolean = key in deletedKeys

    override suspend fun awaitLoaded() {
        loaded.first { it }
    }

    private suspend fun <T> write(block: suspend () -> T): T {
        awaitLoaded()
        return mutex.withLock { block() }
    }

    private fun setProfile(p: Profile?) {
        _profile.value = p
        _profiles.value = listOfNotNull(p)
    }

    // ---- Profile ---------------------------------------------------------------------------

    /**
     * Creates the profile, or updates it when one already exists (single-profile app).
     * Measurements stored before the profile existed are adopted by it.
     */
    suspend fun saveProfile(draft: ProfileDraft): Profile = write {
        val current = _profile.value
        if (current != null) {
            val updated = current.copy(
                name = draft.name.trim(),
                sex = draft.sex,
                birthDate = draft.birthDate,
                heightCm = draft.heightCm,
                targetWeightKg = draft.targetWeightKg,
            )
            withContext(io) { db.updateProfile(updated) }
            setProfile(updated)
            updated
        } else {
            val profile = Profile(
                id = 0,
                name = draft.name.trim(),
                sex = draft.sex,
                birthDate = draft.birthDate,
                heightCm = draft.heightCm,
                targetWeightKg = draft.targetWeightKg,
                colorIndex = 0,
                createdAt = clock(),
            )
            val (created, adopted) = withContext(io) {
                val id = db.insertProfile(profile)
                db.adoptMeasurements(id)
                profile.copy(id = id) to db.loadMeasurements(id)
            }
            setProfile(created)
            _measurements.value = adopted
            created
        }
    }

    // ---- Measurements ---------------------------------------------------------------------

    /**
     * Inserts and returns the stored measurement (owned by the profile), or null if its
     * scale_key already exists.
     */
    override suspend fun insertMeasurement(m: Measurement): Measurement? = write {
        val owned = m.copy(profileId = _profile.value?.id)
        val id = withContext(io) { db.insertMeasurement(owned) }
        if (id < 0) return@write null
        val stored = owned.copy(id = id)
        _measurements.value = sorted(_measurements.value + stored)
        stored
    }

    /**
     * Replaces the scale-provided data (impedance, composition, provenance) of an existing row,
     * e.g. when the impedance arrives after a weight-only commit. Null when the row is gone.
     */
    override suspend fun updateScaleData(updated: Measurement): Measurement? = write {
        if (_measurements.value.none { it.id == updated.id }) return@write null
        withContext(io) { db.updateMeasurementScaleData(updated) }
        _measurements.value = _measurements.value.map { if (it.id == updated.id) updated else it }
        updated
    }

    /** Deletes a measurement; a scale reading with its key is never stored again. */
    suspend fun deleteMeasurement(id: Long) = write {
        val key = withContext(io) { db.deleteMeasurement(id, clock()) }
        if (key != null) deletedKeys = deletedKeys + key
        _measurements.value = _measurements.value.filterNot { it.id == id }
    }

    /**
     * Imports parsed CSV rows: planning/dedupe off the main thread, then one DB transaction;
     * memory is updated only after the transaction succeeds.
     */
    suspend fun importRows(rows: List<CsvImportRow>, invalid: Int): ImportResult = write {
        val currentProfile = _profile.value
        val currentMeasurements = _measurements.value
        val plan = withContext(compute) { ImportPlanner.plan(rows, currentProfile, currentMeasurements, clock()) }
        val (created, inserted) = withContext(io) { db.applyImport(plan) }
        if (created != null) setProfile(created)
        _measurements.value = withContext(compute) { sorted(currentMeasurements + inserted) }
        ImportResult(
            imported = inserted.size,
            duplicates = plan.duplicates + (plan.measurements.size - inserted.size),
            invalid = invalid,
            createdProfile = created != null,
            skippedOtherProfiles = plan.skippedOtherProfiles,
        )
    }

    private fun sorted(list: List<Measurement>) = list.sortedWith(compareBy({ it.timestamp }, { it.id }))

    companion object {
        /** Selected profile if it still exists, else the one with most measurements, else the oldest. */
        fun choosePrimary(profiles: List<Profile>, counts: Map<Long, Int>, preferredId: Long?): Profile? {
            if (profiles.isEmpty()) return null
            profiles.firstOrNull { it.id == preferredId }?.let { return it }
            return profiles.maxWithOrNull(compareBy<Profile> { counts[it.id] ?: 0 }.thenByDescending { it.createdAt })
        }
    }
}
