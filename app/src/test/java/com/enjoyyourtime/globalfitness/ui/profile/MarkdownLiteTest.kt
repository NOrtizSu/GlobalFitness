package com.enjoyyourtime.globalfitness.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownLiteTest {

    @Test
    fun blocksCoverHeadingsQuotesBulletsAndRules() {
        val md = """
            # Título

            > **BORRADOR.** Primera línea
            > segunda línea.

            Párrafo que
            sigue aquí.

            - Uno con
              continuación.
            - Dos
            1. Numerado

            ---
            ## Sección
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Título"),
                MdBlock.Quote("**BORRADOR.** Primera línea segunda línea."),
                MdBlock.Paragraph("Párrafo que sigue aquí."),
                MdBlock.Bullet("•", "Uno con continuación."),
                MdBlock.Bullet("•", "Dos"),
                MdBlock.Bullet("1.", "Numerado"),
                MdBlock.Rule,
                MdBlock.Heading(2, "Sección"),
            ),
            MarkdownLite.parse(md),
        )
    }

    @Test
    fun inlineEmphasisCodeAndLinks() {
        assertEquals(
            listOf(
                MdRun("Hola "),
                MdRun("mundo", bold = true),
                MdRun(", "),
                MdRun("pendiente", italic = true),
                MdRun(" y "),
                MdRun("premium", code = true),
                MdRun(": "),
                MdRun("política", url = "https://policies.google.com/privacy"),
                MdRun("."),
            ),
            MarkdownLite.inline("Hola **mundo**, _pendiente_ y `premium`: [política](https://policies.google.com/privacy)."),
        )
    }

    @Test
    fun underscoresInsideWordsAndLoneBracketsStayLiteral() {
        assertEquals(listOf(MdRun("snake_case [sin enlace] 2 * 3")), MarkdownLite.inline("snake_case [sin enlace] 2 * 3"))
        assertEquals(
            listOf(MdRun("[nota] ver "), MdRun("x", url = "https://a.b")),
            MarkdownLite.inline("[nota] ver [x](https://a.b)"),
        )
    }

    @Test
    fun theBundledPrivacyPolicyParsesAndLinksItsWebVersion() {
        val file = listOf(File("../docs/privacy-policy.md"), File("docs/privacy-policy.md")).first { it.isFile }
        val blocks = MarkdownLite.parse(file.readText())
        assertEquals(MdBlock.Heading(1, "Política de privacidad de Fortia"), blocks.first())
        val header = blocks[1] as MdBlock.Paragraph
        assertTrue(header.text, header.text.startsWith("Última actualización:"))
        assertTrue(MarkdownLite.inline(header.text).any { it.url == PRIVACY_POLICY_URL })
        assertTrue(blocks.none { it is MdBlock.Quote })
    }
}
