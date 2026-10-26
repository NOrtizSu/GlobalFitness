package com.enjoyyourtime.globalfitness.training.data

import android.content.Context
import androidx.core.content.edit
import com.enjoyyourtime.globalfitness.training.domain.GoalAnchor

/**
 * What the achievement celebrations remember on this phone (see
 * [com.enjoyyourtime.globalfitness.training.domain.Celebrations]): the levels already celebrated or
 * filed silently, the time of the first evaluation, and when the profile's target weight was set
 * ([GoalAnchor]). The achievements themselves are derived from the data. Not synced between
 * phones; Android Auto Backup keeps it with the databases, so a restored phone neither celebrates
 * again what it already did nor moves the start of the weight goals.
 */
interface CelebrationLedger {
    /** Null until the first evaluation. */
    fun baselineAt(): Long?

    fun handled(): Set<String>

    fun setBaseline(at: Long)

    fun addHandled(keys: Collection<String>)

    /** Null until a target weight is first seen. */
    fun goalAnchor(): GoalAnchor?

    fun setGoalAnchor(anchor: GoalAnchor)
}

/** [CelebrationLedger] in SharedPreferences. */
class CelebrationStore(context: Context) : CelebrationLedger {
    private val prefs = context.getSharedPreferences("achievements", Context.MODE_PRIVATE)

    override fun baselineAt(): Long? = if (prefs.contains(KEY_BASELINE)) prefs.getLong(KEY_BASELINE, 0L) else null

    // The returned set must not be modified (SharedPreferences contract): copied.
    override fun handled(): Set<String> = prefs.getStringSet(KEY_HANDLED, null)?.toSet().orEmpty()

    override fun setBaseline(at: Long) {
        if (!prefs.contains(KEY_BASELINE)) prefs.edit { putLong(KEY_BASELINE, at) }
    }

    override fun addHandled(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = handled()
        if (current.containsAll(keys)) return
        prefs.edit { putStringSet(KEY_HANDLED, current + keys) }
    }

    override fun goalAnchor(): GoalAnchor? =
        if (prefs.contains(KEY_GOAL_TARGET)) GoalAnchor(prefs.getLong(KEY_GOAL_TARGET, 0L), prefs.getLong(KEY_GOAL_SET_AT, 0L)) else null

    override fun setGoalAnchor(anchor: GoalAnchor) = prefs.edit {
        putLong(KEY_GOAL_TARGET, anchor.targetTenthsKg)
        putLong(KEY_GOAL_SET_AT, anchor.setAt)
    }

    private companion object {
        const val KEY_BASELINE = "baseline_at"
        const val KEY_HANDLED = "celebrated"
        const val KEY_GOAL_TARGET = "goal_target_tenths_kg"
        const val KEY_GOAL_SET_AT = "goal_set_at"
    }
}
