package com.enjoyyourtime.globalfitness.training

import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseGuides
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every built-in exercise has a how-to guide, wired to its own strings and written in the agreed shape. */
class ExerciseGuidesTest {
    private val ids = ExerciseCatalog.all.map { it.id }

    @Test
    fun everyCatalogExerciseHasAGuide() {
        val missing = ids.filter { ExerciseGuides[it] == null }
        assertTrue("exercises without a guide: $missing", missing.isEmpty())
        assertNull("custom exercises have no guide", ExerciseGuides["custom_00000000"])
    }

    @Test
    fun guideStringsAreTheOnesNamedAfterTheId() {
        val string = R.string::class.java
        for (id in ids) {
            val guide = ExerciseGuides[id]!!
            for ((key, res) in listOf("exg_${id}_steps" to guide.steps, "exg_${id}_tips" to guide.tips)) {
                val field = runCatching { string.getField(key) }.getOrNull()
                assertNotNull("missing string $key", field)
                assertEquals("$key is not the resource of the $id guide", field!!.getInt(null), res)
            }
        }
    }

    @Test
    fun masterTextsHaveNumberedStepsAndBulletedTips() {
        val texts = masterTexts()
        val expected = ids.flatMap { listOf("exg_${it}_steps", "exg_${it}_tips") }.toSet()
        assertEquals("keys of 97_exercise_guides.txt", expected, texts.keys)
        for ((key, pair) in texts) {
            for ((lang, text) in listOf("en" to pair.first, "es" to pair.second)) {
                val lines = text.split("\\n")
                val where = "$key ($lang)"
                if (key.endsWith("_steps")) {
                    assertTrue("$where: ${lines.size} steps, needs 3 to 5", lines.size in 3..5)
                    lines.forEachIndexed { i, line ->
                        assertTrue("$where: step ${i + 1} must start with \"${i + 1}. \": $line", line.startsWith("${i + 1}. "))
                        assertTrue("$where: empty step ${i + 1}", line.length > "${i + 1}. ".length)
                    }
                } else {
                    assertTrue("$where: ${lines.size} tips, needs 2 or 3", lines.size in 2..3)
                    for (line in lines) {
                        assertTrue("$where: tip must start with \"• \": $line", line.startsWith(BULLET) && line.length > BULLET.length)
                    }
                }
            }
        }
    }

    /** key -> (English, Spanish) of the master file, found from the working directory (the module) upwards. */
    private fun masterTexts(): Map<String, Pair<String, String>> {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, MASTER) }
            .firstOrNull { it.isFile }
        assertNotNull("$MASTER not found above ${System.getProperty("user.dir")}", file)
        return file!!.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (key, value) = line.split(" = ", limit = 2)
                val parts = value.split(" ||| ")
                assertEquals("$key needs exactly one English and one Spanish text", 2, parts.size)
                key.trim() to (parts[0].trim() to parts[1].trim())
            }
    }

    private companion object {
        const val MASTER = "tools/i18n/src/master/97_exercise_guides.txt"
        const val BULLET = "• "
    }
}
