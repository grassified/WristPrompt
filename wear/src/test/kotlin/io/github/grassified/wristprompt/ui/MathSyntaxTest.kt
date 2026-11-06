package io.github.grassified.wristprompt.ui

import org.junit.Assert.*
import org.junit.Test

class MathSyntaxTest {
    private fun blocks(source: String) = MarkdownProjection().blocks(source)
    private fun formulas(source: String) = blocks(source).flatMap { block ->
        block.inline.getStringAnnotations(MATH_TAG, 0, block.inline.length).map { block.inline.text.substring(it.start, it.end) }
    }
    @Test fun allDelimitersKeepExactSourceAndMarkdown() {
        val source = "**Answer** \\(\\frac{1}{2}\\), \\[\\boxed{x^2}\\], \$x_i\$ and \$\$\\sqrt{2}\$\$."
        assertEquals(listOf("\\(\\frac{1}{2}\\)", "\\[\\boxed{x^2}\\]", "\$x_i\$", "\$\$\\sqrt{2}\$\$"), formulas(source))
        assertTrue(blocks(source).first().inline.spanStyles.isNotEmpty())
    }
    @Test fun scientificFixturesAreAdmittedWithoutRewriting() {
        val bodies = listOf("\\frac{a}{b}", "\\boxed{x=2}", "\\int_0^1 x^2\\,dx", "\\sum_{i=1}^{n} i",
            "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}", "\\begin{aligned}x&=1\\\\y&=2\\end{aligned}",
            "\\alpha + \\beta = \\Omega", "F=3\\,\\text{N}", "\\ce{2H2 + O2 -> 2H2O}", "\\pu{9.81 m/s2}", "α + β ≠ ∞", "\\text{İstanbul, ışık}")
        bodies.forEach { body ->
            assertTrue(body, MathSafety.accepts(body))
            val raw = "\\($body\\)"
            assertEquals(listOf(raw), formulas(raw))
            assertEquals(body, MathSafety.body(raw))
        }
    }
    @Test fun codeNeverBecomesMath() {
        assertTrue(formulas("`\\(\\frac{1}{2}\\)` and `\$x\$`\n\n```tex\n\\[x^2\\]\n\$\$x\$\$\n```\n\n    \\(x\\)").isEmpty())
    }
    @Test fun currencyAndEscapesStayOrdinary() {
        for (source in listOf("Cost \$5 and \$10", "\$20\$", "US\$5", "\\\$x\\\$", "\$ x \$", "\$5.00\$", "\\\\(x\\\\)"))
            assertTrue(source, formulas(source).isEmpty())
        assertEquals(listOf("\$x+1\$"), formulas("Pay \$5; use \$x+1\$."))
    }
    @Test fun listsAndStackedTableCellsRetainMathAndCode() {
        val b = blocks("- \\(x_i\\) and `\$code\$`\n\n|Name|Value|\n|---|---|\n|Force|\$F=ma\$|")
        assertEquals(MarkdownKind.LIST_ITEM, b.first().kind)
        assertEquals(listOf("Name: Force", "Value: \$F=ma\$"), b.drop(1).map { it.inline.text })
        assertEquals(2, b.sumOf { it.inline.getStringAnnotations(MATH_TAG, 0, it.inline.length).size })
    }
    @Test fun unfinishedAndMalformedAreReadableAndNeverCorrected() {
        for (source in listOf("\\(\\frac{a}{", "\\[x^2", "\$\$\\sqrt{", "\\(\\frac{a}{b\\)")) {
            assertEquals(source, blocks(source).joinToString("") { it.inline.text })
        }
        assertFalse(MathSafety.accepts("\\frac{a}{b"))
        assertFalse(MathSafety.accepts("}"))
    }
    @Test fun unsafeOrUnknownCommandsRemainSource() {
        for (body in listOf("\\input{secret}", "\\includegraphics{https://example.invalid}", "\\href{file:x}{a}",
            "\\htmlClass{x}{y}", "\\def\\x{\\x}\\x", "\\newcommand{\\a}{a}", "\\rule{999em}{999em}",
            "\\unknown{x}", "x%hidden", "\\begin{unknown}x\\end{unknown}")) assertFalse(body, MathSafety.accepts(body))
    }
    @Test fun complexityBoundsPreserveAllText() {
        assertFalse(MathSafety.accepts("{".repeat(13) + "x" + "}".repeat(13)))
        assertFalse(MathSafety.accepts("x".repeat(1025)))
        val source = List(40) { "\\(x_$it\\)" }.joinToString(" ")
        assertEquals(24, formulas(source).size)
        assertEquals(source, blocks(source).joinToString("") { it.inline.text })
        val split = "a".repeat(2040) + " \\(\\frac{1}{2}\\) end"
        assertEquals(listOf("\\(\\frac{1}{2}\\)"), formulas(split))
        assertEquals(split, blocks(split).joinToString("") { it.inline.text })
    }
    @Test fun multilineFormulaKeepsOriginalCrLf() {
        val source = "\\[\\begin{aligned}x&=1\\\\\r\ny&=2\\end{aligned}\\]"
        assertEquals(listOf(source), formulas(source))
    }
    @Test fun oversizeFormulaIsExactSegmentedSource() {
        val source = "\\[" + "x+".repeat(2000) + "x\\]"
        assertEquals(source, blocks(source).joinToString("") { it.inline.text })
        assertTrue(formulas(source).isEmpty())
    }
    @Test fun unicodeIsPreservedExactly() {
        val source = "İı Şş Ğğ Çç Öö Üü αβγ Ω ∫ ∑ √ ≠ ≤ ≥ ∞ H₂O x² 𝑥 𝛼"
        assertEquals(source, blocks(source).single().inline.text)
    }
}
