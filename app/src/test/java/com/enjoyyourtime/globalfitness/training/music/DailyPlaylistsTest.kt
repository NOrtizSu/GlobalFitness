package com.enjoyyourtime.globalfitness.training.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyPlaylistsTest {
    private val start = LocalDate.of(2026, 10, 26).toEpochDay()
    private val days = start - 400..start + 400

    @Test
    fun theCatalogueHoldsValidDistinctPlaylistsOfEveryMood() {
        val all = WorkoutPlaylists.all
        assertTrue("about forty playlists", all.size in 36..48)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (p in all) {
            assertTrue(p.id, SpotifyLinks.isPlaylistId(p.id))
            assertTrue(p.id, p.name.isNotBlank())
        }
        for (mood in PlaylistMood.entries) {
            val size = all.count { it.mood == mood }
            val window = DailyPlaylists.window(mood)
            // What makes consecutive days never repeat (see DailyPlaylists).
            assertTrue("$mood: $size playlists for a window of $window", size % window == 0 && size >= 3 * window)
        }
    }

    @Test
    fun everyDayGetsFiveDistinctPlaylistsInTheMixOfItsTraining() {
        for (type in TrainingDay.entries) {
            val mix = DailyPlaylists.mix(type)
            assertEquals(DailyPlaylists.COUNT, mix.values.sum())
            for (d in days) {
                val picked = DailyPlaylists.pick(d, type)
                assertEquals("$type day $d", DailyPlaylists.COUNT, picked.map { it.id }.toSet().size)
                assertEquals("$type day $d", mix, picked.groupingBy { it.mood }.eachCount())
                // Shown in the mix's order: the day's main mood first.
                assertEquals(mix.keys.first(), picked.first().mood)
            }
        }
    }

    @Test
    fun theStrengthDaysGetIntenseMusicAndTheRestDaysCalmMusic() {
        assertEquals(4, DailyPlaylists.mix(TrainingDay.LEGS)[PlaylistMood.INTENSE])
        assertEquals(4, DailyPlaylists.mix(TrainingDay.STRENGTH)[PlaylistMood.INTENSE])
        assertEquals(4, DailyPlaylists.mix(TrainingDay.CARDIO)[PlaylistMood.CARDIO])
        assertEquals(4, DailyPlaylists.mix(TrainingDay.REST)[PlaylistMood.CALM])
        assertEquals(4, DailyPlaylists.mix(TrainingDay.RECOVERY)[PlaylistMood.CALM])
        val notStarted = DailyPlaylists.mix(TrainingDay.TRAINING_DAY)
        assertEquals(mapOf(PlaylistMood.INTENSE to 3, PlaylistMood.CARDIO to 1, PlaylistMood.GENERAL to 1), notStarted)
    }

    @Test
    fun theSameDayAndTrainingAlwaysGiveTheSameFive() {
        for (type in TrainingDay.entries) {
            for (d in days) assertEquals(DailyPlaylists.pick(d, type), DailyPlaylists.pick(d, type, WorkoutPlaylists.all.toList()))
        }
    }

    @Test
    fun consecutiveDaysNeverShareAPlaylistWhateverTheTraining() {
        for (d in days) {
            for (a in TrainingDay.entries) {
                val today = DailyPlaylists.pick(d, a).toSet()
                for (b in TrainingDay.entries) {
                    val tomorrow = DailyPlaylists.pick(d + 1, b)
                    assertTrue("day $d $a / $b: ${tomorrow.filter { it in today }}", tomorrow.none { it in today })
                }
            }
        }
    }

    @Test
    fun theWholePoolRotatesAndTheGroupsChange() {
        val intense = WorkoutPlaylists.all.filter { it.mood == PlaylistMood.INTENSE }.toSet()
        // Three strength days use the twelve intense playlists once each.
        val cycleStart = (start..start + 10).first { d -> (0L..2L).flatMap { DailyPlaylists.pick(d + it, TrainingDay.STRENGTH) }.filter { it.mood == PlaylistMood.INTENSE }.toSet() == intense }
        assertTrue(cycleStart <= start + 2)
        // The next cycles group them differently.
        val groups = (0 until 20).map { c -> DailyPlaylists.pick(cycleStart + 3L * c, TrainingDay.STRENGTH).filter { it.mood == PlaylistMood.INTENSE }.toSet() }
        assertTrue(groups.toSet().size > 10)
    }

    @Test
    fun tinyPoolsStillGiveDistinctPlaylists() {
        fun p(i: Int, mood: PlaylistMood) = WorkoutPlaylist("tiny".padEnd(18, 'x') + i.toString().padStart(4, '0'), "P$i", mood)
        // Fewer than five in all: every one of them, once.
        val three = listOf(p(1, PlaylistMood.INTENSE), p(2, PlaylistMood.CALM), p(3, PlaylistMood.GENERAL))
        for (d in days) assertEquals(three.toSet(), DailyPlaylists.pick(d, TrainingDay.STRENGTH, three).toSet())
        assertEquals(emptyList<WorkoutPlaylist>(), DailyPlaylists.pick(start, TrainingDay.REST, emptyList()))
        // A mood short of playlists is completed with others.
        val short = listOf(p(1, PlaylistMood.INTENSE), p(2, PlaylistMood.INTENSE)) + (3..8).map { p(it, PlaylistMood.CALM) }
        for (type in TrainingDay.entries) {
            for (d in days) {
                val picked = DailyPlaylists.pick(d, type, short)
                assertEquals(DailyPlaylists.COUNT, picked.toSet().size)
                assertEquals(picked, DailyPlaylists.pick(d, type, short))
            }
        }
        // Only one mood: still five different ones.
        val general = (1..5).map { p(it, PlaylistMood.GENERAL) }
        assertEquals(general.toSet(), DailyPlaylists.pick(start, TrainingDay.CARDIO, general).toSet())
    }
}
