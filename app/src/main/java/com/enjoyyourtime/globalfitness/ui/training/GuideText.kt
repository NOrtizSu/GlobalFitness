package com.enjoyyourtime.globalfitness.ui.training

/**
 * Splits the texts of an exercise guide ([com.enjoyyourtime.globalfitness.training.catalog.ExerciseGuide])
 * into the lines the info sheet lists. The texts number their steps ("1. ") and start their tips with
 * "• " so they also read well as plain text; the sheet draws its own numbers and bullets instead, so
 * these are removed here, whatever digits and punctuation the translation used ("١.", "1、", "1)").
 */
internal object GuideText {
    /** The steps, one per line, without their written numbers. */
    fun steps(text: String): List<String> = lines(text).map(::withoutNumber)

    /** The tips, one per line, without their bullets. */
    fun tips(text: String): List<String> = lines(text).map(::withoutBullet)

    private fun lines(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * "1. Lie on the bench" → "Lie on the bench". A number only counts as the step's when punctuation
     * follows it and the text goes on with no other digit: "90° bend", "2-3 s down" and "1.5 times"
     * are kept as written.
     */
    internal fun withoutNumber(line: String): String {
        var i = 0
        while (i < line.length && Character.isDigit(line[i])) i++
        if (i == 0 || i >= line.length || line[i] !in NUMBER_ENDS) return line
        val rest = line.substring(i + 1)
        if (rest.firstOrNull()?.let(Character::isDigit) == true) return line
        return rest.trim().ifEmpty { line }
    }

    /** "• Keep your back flat" → "Keep your back flat"; a line without a bullet stays as it is. */
    internal fun withoutBullet(line: String): String =
        if (line.first() in BULLETS) line.substring(1).trim().ifEmpty { line } else line

    /** What may follow a step number: full stops and closing brackets, also the CJK ones. */
    private val NUMBER_ENDS = setOf('.', ')', '．', '。', '、', '）')

    private val BULLETS = setOf('•', '·', '・', '-', '–', '*', '‣', '◦')
}
