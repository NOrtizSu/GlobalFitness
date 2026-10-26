package com.enjoyyourtime.globalfitness.training.data

import android.content.Context
import androidx.core.content.edit
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** How the body map colors the muscles. */
enum class MuscleMapMode(val code: String) {
    WEEKLY_SETS("weekly_sets"),
    STRENGTH("strength"),
    RECOVERY("recovery"),
    ;

    companion object {
        fun fromCode(code: String?): MuscleMapMode = entries.firstOrNull { it.code == code } ?: WEEKLY_SETS
    }
}

/** Training preferences (SharedPreferences, mirrored in StateFlows). */
class TrainingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("training", Context.MODE_PRIVATE)

    private val _loadUnit = MutableStateFlow(LoadUnit.fromCode(prefs.getString(KEY_UNIT, null)) ?: defaultUnit(Locale.getDefault()))

    /** Unit of the weights on the bar and the machines (stored values are always kg). */
    val loadUnit: StateFlow<LoadUnit> = _loadUnit.asStateFlow()

    private val _restSeconds = MutableStateFlow(prefs.getInt(KEY_REST, DEFAULT_REST_SECONDS))

    /** Rest timer started after each completed set; 0 = off. */
    val restSeconds: StateFlow<Int> = _restSeconds.asStateFlow()

    private val _mapMode = MutableStateFlow(MuscleMapMode.fromCode(prefs.getString(KEY_MAP_MODE, null)))

    /** Last mode chosen on the body map. */
    val mapMode: StateFlow<MuscleMapMode> = _mapMode.asStateFlow()

    private val _healthExport = MutableStateFlow(if (prefs.contains(KEY_HC_EXPORT)) prefs.getBoolean(KEY_HC_EXPORT, true) else null)

    /** The user's choice about writing workouts to Health Connect (null: never chosen, on when permitted). */
    val healthExportChoice: StateFlow<Boolean?> = _healthExport.asStateFlow()

    private val _lastRoutineId = MutableStateFlow(prefs.getString(KEY_LAST_ROUTINE, null))

    /** The routine a workout was last started from or filled with (offered first; may be deleted since). */
    val lastRoutineId: StateFlow<String?> = _lastRoutineId.asStateFlow()

    fun setLoadUnit(unit: LoadUnit) {
        _loadUnit.value = unit
        prefs.edit { putString(KEY_UNIT, unit.code) }
    }

    fun setRestSeconds(seconds: Int) {
        val value = seconds.coerceIn(0, MAX_REST_SECONDS)
        _restSeconds.value = value
        prefs.edit { putInt(KEY_REST, value) }
    }

    fun setMapMode(mode: MuscleMapMode) {
        _mapMode.value = mode
        prefs.edit { putString(KEY_MAP_MODE, mode.code) }
    }

    fun setHealthExport(enabled: Boolean) {
        _healthExport.value = enabled
        prefs.edit { putBoolean(KEY_HC_EXPORT, enabled) }
    }

    fun setLastRoutine(id: String) {
        if (_lastRoutineId.value == id) return
        _lastRoutineId.value = id
        prefs.edit { putString(KEY_LAST_ROUTINE, id) }
    }

    companion object {
        const val DEFAULT_REST_SECONDS = 90
        const val MAX_REST_SECONDS = 600
        private const val KEY_UNIT = "load_unit"
        private const val KEY_REST = "rest_seconds"
        private const val KEY_MAP_MODE = "muscle_map_mode"
        private const val KEY_HC_EXPORT = "health_export"
        private const val KEY_LAST_ROUTINE = "last_routine"

        /** Countries whose gyms load plates in pounds. */
        private val POUND_COUNTRIES = setOf("US", "LR", "MM")

        fun defaultUnit(locale: Locale): LoadUnit = if (locale.country in POUND_COUNTRIES) LoadUnit.LB else LoadUnit.KG
    }
}
