package com.enjoyyourtime.globalfitness.training.data

import com.enjoyyourtime.globalfitness.training.domain.AchievementBoard
import com.enjoyyourtime.globalfitness.training.domain.Celebration
import com.enjoyyourtime.globalfitness.training.domain.Celebrations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Turns each new [AchievementBoard] into the celebrations still to show ([pending]), following
 * [Celebrations.reconcile]. A level is written to the [ledger] once it is shown (with the lower ones
 * it settles, [Celebration.keys]) or filed silently, so a level waiting to be shown when the app is
 * killed comes back on the next start (while it is still fresh) instead of being lost.
 *
 * While levels wait (e.g. held until a workout is finished), each board updates them: one no longer
 * reached (a typo corrected, a workout deleted) is dropped without being filed, and a higher level of
 * the same achievement replaces the one waiting, so one card shows the highest. Call it from one
 * coroutine at a time.
 */
class CelebrationTracker(private val ledger: CelebrationLedger, private val clock: () -> Long = System::currentTimeMillis) {
    private val _pending = MutableStateFlow<List<Celebration>>(emptyList())

    /** Levels to congratulate the user on, oldest first. */
    val pending: StateFlow<List<Celebration>> = _pending.asStateFlow()

    /** Called with every board computed from fully loaded data. */
    fun onBoard(board: AchievementBoard) {
        val reached = board.states.flatMapTo(HashSet()) { s -> s.unlocked.map { s.key(it.level) } }
        val waiting = _pending.value.filter { it.key in reached }
        val baseline = ledger.baselineAt()
        // What the waiting levels settle is not news again.
        val settled = waiting.flatMapTo(HashSet()) { it.keys }
        val outcome = Celebrations.reconcile(board, baseline, ledger.handled() + settled, clock())
        if (baseline == null) ledger.setBaseline(outcome.baselineAt)
        ledger.addHandled(outcome.silent)
        val higher = outcome.show.mapTo(HashSet()) { it.achievement }
        _pending.value = waiting.filterNot { it.achievement in higher } + outcome.show
    }

    /** The celebration of [shown] is on screen: those levels (and the lower ones) are never celebrated again. */
    fun shown(shown: List<Celebration>) = ledger.addHandled(shown.flatMap { it.keys })

    /** The user closed the celebration of [shown]. */
    fun dismiss(shown: List<Celebration>) {
        ledger.addHandled(shown.flatMap { it.keys })
        val gone = shown.mapTo(HashSet()) { it.key }
        _pending.update { list -> list.filterNot { it.key in gone } }
    }
}
