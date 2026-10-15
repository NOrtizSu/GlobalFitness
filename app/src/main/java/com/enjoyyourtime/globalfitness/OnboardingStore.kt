package com.enjoyyourtime.globalfitness

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the first-launch onboarding is over. The UMP consent form and the ads wait for it, so
 * nothing covers the welcome pages. Own SharedPreferences file, excluded from backups: a restored
 * device decides again from the restored profile.
 */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _completed = MutableStateFlow(prefs.getBoolean(KEY_COMPLETED, false))

    /** True once the main sections have been shown (end of the onboarding, or an existing profile). */
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    fun markCompleted() {
        if (_completed.value) return
        _completed.value = true
        prefs.edit { putBoolean(KEY_COMPLETED, true) }
    }

    private companion object {
        const val PREFS = "onboarding"
        const val KEY_COMPLETED = "completed"
    }
}
