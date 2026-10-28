package com.enjoyyourtime.globalfitness.training.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingSettingsMusicControlsTest {
    /** SharedPreferences kept in a map (commit and apply write at once). */
    private class MemoryPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as Set<String>? ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { changes[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { changes[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    @Test
    fun theMusicControlsAreOnUntilTurnedOff() {
        val prefs = MemoryPreferences()
        val settings = TrainingSettings(prefs)
        assertTrue(settings.musicControls.value)

        settings.setMusicControls(false)
        assertFalse(settings.musicControls.value)
        assertEquals(false, prefs.values["music_controls"])
        // Read back at the next start.
        assertFalse(TrainingSettings(prefs).musicControls.value)

        settings.setMusicControls(true)
        assertTrue(TrainingSettings(prefs).musicControls.value)
    }

    @Test
    fun onceSetTheControlsDoNotFollowThePlaylistSuggestions() {
        val prefs = MemoryPreferences()
        val settings = TrainingSettings(prefs)
        settings.setMusicSuggestions(false)
        assertTrue(settings.musicControls.value)
        assertTrue(TrainingSettings(prefs).musicControls.value)
        settings.setMusicControls(false)
        settings.setMusicSuggestions(true)
        assertFalse(settings.musicControls.value)
        assertTrue(settings.musicSuggestions.value)
        assertFalse(TrainingSettings(prefs).musicControls.value)
    }

    @Test
    fun someoneWhoTurnedTheDaysMusicOffGetsTheControlsOffToo() {
        // Before the controls existed: only the suggestions' switch, turned off.
        val prefs = MemoryPreferences().apply { values["music_suggestions"] = false }
        val settings = TrainingSettings(prefs)
        assertFalse(settings.musicControls.value)
        // Kept: turning the music back on later does not turn the controls on.
        assertEquals(false, prefs.values["music_controls"])
        settings.setMusicSuggestions(true)
        assertFalse(TrainingSettings(prefs).musicControls.value)

        // The suggestions left on (or never touched): the controls are on.
        val on = MemoryPreferences().apply { values["music_suggestions"] = true }
        assertTrue(TrainingSettings(on).musicControls.value)
        assertTrue(TrainingSettings(MemoryPreferences()).musicControls.value)
    }
}
