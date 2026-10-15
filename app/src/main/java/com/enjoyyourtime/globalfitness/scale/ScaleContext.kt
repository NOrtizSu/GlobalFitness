package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.Sex
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** Unit shown on the scale display. Ordinal values match what most protocols expect (kg=0, lb=1, st=2). */
enum class ScaleUnit { KG, LB, ST }

/** Activity level used by a few formula libraries; the app does not ask for it, so it defaults. */
enum class ActivityLevel { SEDENTARY, MILD, MODERATE, HEAVY, EXTREME }

/**
 * The user parameters some scales need (on-scale user registration, device-side composition):
 * always the app's single profile. [userSlot] is the 1-based slot on the scale (per configured
 * scale, default 1).
 */
data class ScaleContext(
    /** Stable numeric id of the app user (embedded by some protocols in their commands). */
    val appUserId: Int,
    val name: String,
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Float,
    /** Last stored weight, used as "initial weight" by protocols that need a reference. */
    val lastWeightKg: Float?,
    val goalWeightKg: Float?,
    val userSlot: Int = 1,
    val unit: ScaleUnit = ScaleUnit.KG,
    val activityLevel: ActivityLevel = ActivityLevel.SEDENTARY,
    /** Impedance of the last stored measurement, seeded back by protocols that keep a user history on the scale. */
    val lastImpedance: Int? = null,
) {
    val isMale: Boolean get() = sex == Sex.MALE

    /** Full years at [atMillis]. */
    fun ageAt(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val date = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
        return Period.between(birthDate, date).years.coerceAtLeast(0)
    }

    val age: Int get() = ageAt(System.currentTimeMillis())

    companion object {
        /** Context for the app's single [profile]; [DEFAULT] when it does not exist yet. */
        fun from(profile: Profile?, lastWeightKg: Double?, lastImpedance: Int? = null): ScaleContext =
            if (profile == null) {
                DEFAULT
            } else {
                ScaleContext(
                    appUserId = profile.id.toInt().coerceAtLeast(1),
                    name = profile.name,
                    sex = profile.sex,
                    birthDate = profile.birthDate,
                    heightCm = profile.heightCm.toFloat(),
                    lastWeightKg = lastWeightKg?.toFloat(),
                    goalWeightKg = profile.targetWeightKg?.toFloat(),
                    lastImpedance = lastImpedance,
                )
            }

        /** Used when no profile exists yet (only weight-only protocols can work meaningfully). */
        val DEFAULT = ScaleContext(
            appUserId = 1,
            name = "",
            sex = Sex.MALE,
            birthDate = LocalDate.of(1990, 1, 1),
            heightCm = 170f,
            lastWeightKg = null,
            goalWeightKg = null,
        )
    }
}

/** Small persisted key/value storage for a driver (per configured scale). */
interface DriverStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)

    fun getInt(key: String, default: Int = -1): Int = getString(key)?.toIntOrNull() ?: default
    fun putInt(key: String, value: Int) = putString(key, value.toString())
    fun getLong(key: String, default: Long = -1L): Long = getString(key)?.toLongOrNull() ?: default
    fun putLong(key: String, value: Long) = putString(key, value.toString())
}

/** In-memory [DriverStore]; [onChange] receives the full map after every write. */
class MapDriverStore(
    initial: Map<String, String> = emptyMap(),
    private val onChange: (Map<String, String>) -> Unit = {},
) : DriverStore {
    private val values = LinkedHashMap(initial)

    val snapshot: Map<String, String> get() = LinkedHashMap(values)

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        if (values[key] == value) return
        values[key] = value
        onChange(snapshot)
    }

    override fun remove(key: String) {
        if (values.remove(key) != null) onChange(snapshot)
    }
}

/** Everything a driver session needs from the app. */
class DriverEnv(
    val ctx: ScaleContext,
    val store: DriverStore,
    val driverId: String,
    /** Address of the device being handled, when known. */
    val address: String?,
    val wallClock: () -> Long = System::currentTimeMillis,
    val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Spanish user-facing messages (e.g. "Configura la clave"). */
    val messages: (String) -> Unit = {},
)
