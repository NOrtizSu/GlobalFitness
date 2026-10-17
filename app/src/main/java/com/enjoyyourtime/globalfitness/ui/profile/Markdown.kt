package com.enjoyyourtime.globalfitness.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Block of the small Markdown subset used by docs/privacy-policy.md. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** A run of inline text with its emphasis; [url] marks a link. */
data class MdRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

/**
 * Minimal Markdown reader: headings, paragraphs, bullet / numbered items (with indented
 * continuation lines), block quotes, horizontal rules, and inline bold, italic, code and links.
 */
object MarkdownLite {
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^[-*]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\d+\.)\s+(.*)$""")
    private val RULE = Regex("""^-{3,}\s*$""")

    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        var open: MdBlock? = null

        fun flush() {
            open?.let { blocks += it }
            open = null
        }

        for (raw in source.lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()
                RULE.matches(trimmed) -> {
                    flush()
                    blocks += MdBlock.Rule
                }
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                }
                trimmed.startsWith(">") -> {
                    val text = trimmed.removePrefix(">").trim()
                    val current = open
                    if (current is MdBlock.Quote) {
                        open = current.copy(text = join(current.text, text))
                    } else {
                        flush()
                        open = MdBlock.Quote(text)
                    }
                }
                !line.startsWith(" ") && BULLET.matches(trimmed) -> {
                    flush()
                    open = MdBlock.Bullet("•", BULLET.find(trimmed)!!.groupValues[1])
                }
                !line.startsWith(" ") && NUMBERED.matches(trimmed) -> {
                    flush()
                    val m = NUMBERED.find(trimmed)!!
                    open = MdBlock.Bullet(m.groupValues[1], m.groupValues[2])
                }
                else -> open = when (val current = open) {
                    is MdBlock.Paragraph -> current.copy(text = join(current.text, trimmed))
                    is MdBlock.Bullet -> current.copy(text = join(current.text, trimmed))
                    is MdBlock.Quote -> current.copy(text = join(current.text, trimmed))
                    else -> MdBlock.Paragraph(trimmed)
                }
            }
        }
        flush()
        return blocks
    }

    private fun join(a: String, b: String): String = if (a.isEmpty()) b else "$a $b"

    /** Splits [text] into runs: `**bold**`, `_italic_` / `*italic*`, `` `code` `` and `[label](url)`. */
    fun inline(text: String): List<MdRun> {
        val runs = mutableListOf<MdRun>()
        val plain = StringBuilder()
        var bold = false
        var italic = false
        var i = 0

        fun emit() {
            if (plain.isNotEmpty()) runs += MdRun(plain.toString(), bold = bold, italic = italic)
            plain.clear()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("**", i) -> {
                    emit()
                    bold = !bold
                    i += 2
                }
                (c == '_' || c == '*') && isEmphasisMarker(text, i, italic) -> {
                    emit()
                    italic = !italic
                    i += 1
                }
                c == '`' && text.indexOf('`', i + 1) > i -> {
                    emit()
                    val end = text.indexOf('`', i + 1)
                    runs += MdRun(text.substring(i + 1, end), bold = bold, italic = italic, code = true)
                    i = end + 1
                }
                c == '[' -> {
                    // The first ']' after '[' must open the "(url)" part, or it is plain text.
                    val bracket = text.indexOf(']', i)
                    val close = if (bracket > i && text.getOrNull(bracket + 1) == '(') bracket else -1
                    val end = if (close > i) text.indexOf(')', close + 2) else -1
                    if (close > i && end > close) {
                        emit()
                        runs += MdRun(text.substring(i + 1, close), bold = bold, italic = italic, url = text.substring(close + 2, end))
                        i = end + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        emit()
        return runs
    }

    /** `_` / `*` toggles italic only at word boundaries (so snake_case and "a * b" stay literal). */
    private fun isEmphasisMarker(text: String, i: Int, open: Boolean): Boolean {
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + 1)
        return if (open) {
            before != null && !before.isWhitespace() && (after == null || !after.isLetterOrDigit())
        } else {
            after != null && !after.isWhitespace() && (before == null || !before.isLetterOrDigit())
        }
    }
}

/** Renders [blocks]; links open in the browser. */
@Composable
fun MarkdownDocument(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    annotated(block.text, linkColor, codeBackground),
                    style = if (block.level == 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = if (block.level == 1) 8.dp else 6.dp).semantics { heading() },
                )
                is MdBlock.Paragraph -> Text(annotated(block.text, linkColor, codeBackground), style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Bullet -> Row {
                    Text(block.marker, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                    Text(annotated(block.text, linkColor, codeBackground), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(annotated(block.text, linkColor, codeBackground), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

private fun annotated(text: String, linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (run in MarkdownLite.inline(text)) {
        val style = SpanStyle(
            fontWeight = if (run.bold) FontWeight.SemiBold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) codeBackground else Color.Unspecified,
        )
        if (run.url != null) {
            val linkStyle = TextLinkStyles(style.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))
            withLink(LinkAnnotation.Url(run.url, linkStyle)) { append(run.text) }
        } else {
            withStyle(style) { append(run.text) }
        }
    }
}
