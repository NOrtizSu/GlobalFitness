package com.enjoyyourtime.globalfitness.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * The title of a top app bar, readable in every language: one line in the bar's own style, a few
 * steps smaller when it is a little too long ("Entraînement en cours"), and on two lines at the
 * size of the smaller title style when even that does not fit (a long exercise name such as
 * "Développé couché à la barre"). An ellipsis only past two lines.
 */
@Composable
fun TopBarTitle(text: String, modifier: Modifier = Modifier) {
    // The bar provides its title style (titleLarge) and colour.
    val style = LocalTextStyle.current
    BasicText(
        text,
        modifier = modifier,
        // The line height follows the size, so two smaller lines stay within the bar's height.
        style = style.copy(color = LocalContentColor.current, lineHeight = relativeLineHeight(style)),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        autoSize = TopBarTitleSize(style.fontSize.takeIf { it.isSpecified } ?: TOP_BAR_TITLE_SIZE),
    )
}

/** One line from [max] down to [TOP_BAR_ONE_LINE_MIN], else two lines at [TOP_BAR_TWO_LINES]. */
private data class TopBarTitleSize(val max: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = max.value
        while (size >= TOP_BAR_ONE_LINE_MIN.value) {
            val layout = performLayout(constraints, text, size.sp)
            if (layout.lineCount == 1 && !layout.hasVisualOverflow) return size.sp
            size -= TOP_BAR_STEP
        }
        return TOP_BAR_TWO_LINES
    }
}

private fun relativeLineHeight(style: TextStyle): TextUnit =
    if (style.lineHeight.isSpecified && style.fontSize.isSpecified) {
        (style.lineHeight.value / style.fontSize.value).em
    } else {
        style.lineHeight
    }

/** M3 titleLarge, the small top app bar's title size, should the bar not provide one. */
private val TOP_BAR_TITLE_SIZE = 22.sp

/** The smallest one-line title: a few steps below titleLarge, still clearly a title. */
private val TOP_BAR_ONE_LINE_MIN = 18.sp

/** Two lines at titleMedium's size fit the 64 dp bar. */
private val TOP_BAR_TWO_LINES = 16.sp
private const val TOP_BAR_STEP = 1f

/**
 * Auto-size for short labels in narrow columns (a table's headers): the largest size from [max]
 * down to [min] at which the text fits in the text's maxLines with every line break between words,
 * so a word too long for its column ("Подход", "المجموعة") shrinks instead of being split in the
 * middle. Chinese, Japanese and Korean text, which breaks between characters, keeps [max].
 */
data class WholeWordsAutoSize(val min: TextUnit, val max: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = max.value
        while (size > min.value) {
            val layout = performLayout(constraints, text, size.sp)
            if (!layout.hasVisualOverflow && breaksBetweenWords(text.text, layout.lineEnds())) return size.sp
            size -= WHOLE_WORDS_STEP
        }
        return min
    }
}

private const val WHOLE_WORDS_STEP = 0.5f

private fun TextLayoutResult.lineEnds(): List<Int> = (0 until lineCount - 1).map { getLineEnd(it) }

/**
 * Whether every line break of [text] (each line's end offset in [lineEnds]) falls between words:
 * at a space or a hyphen, or between characters of scripts written without spaces.
 */
internal fun breaksBetweenWords(text: String, lineEnds: List<Int>): Boolean = lineEnds.all { end ->
    if (end <= 0 || end >= text.length) return@all true
    val before = text.codePointBefore(end)
    val after = text.codePointAt(end)
    !(Character.isLetterOrDigit(before) && Character.isLetterOrDigit(after)) || breaksBetweenCharacters(before) || breaksBetweenCharacters(after)
}

private fun breaksBetweenCharacters(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) in UNSPACED_SCRIPTS

private val UNSPACED_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
)
