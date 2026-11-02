package io.github.grassified.wristprompt.ui

import io.github.grassified.wristprompt.openai.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownTableTest {
    private val projection = MarkdownProjection()
    private fun blocks(source: String) = projection.blocks(source)
    private fun text(source: String) = blocks(source).map { it.inline.text }

    @Test fun twoColumnsWithoutOuterPipesPreserveHeadersAndRowOrder() {
        val result = blocks("Name | Use\n:--- | ---:\nAlpha | Desktop\nBeta | Server")
        assertEquals(listOf("Name: Alpha", "Use: Desktop", "Name: Beta", "Use: Server"), result.map { it.inline.text })
        assertEquals(listOf(1, 0, 1, 0), result.map { it.level })
        assertTrue(result.all { it.kind == MarkdownKind.TABLE_RECORD })
    }

    @Test fun fiveDistributionThreeColumnFixtureIsStructured() {
        val source = "| Distribution | Base | Purpose |\n|:---|:---:|---:|\n" +
            listOf("Debian|Independent|Server", "Ubuntu|Debian|Desktop", "Fedora|Independent|Workstation",
                "Arch|Independent|Customization", "Mint|Ubuntu|Desktop").joinToString("\n") { "|$it|" }
        val result = blocks(source)
        assertEquals(15, result.size)
        assertEquals(listOf("Distribution: Debian", "Distribution: Ubuntu", "Distribution: Fedora",
            "Distribution: Arch", "Distribution: Mint"), result.filter { it.level == 1 }.map { it.inline.text })
    }

    @Test fun blankAndMissingCellsRemainExplicit() {
        assertEquals(listOf("Column 1: (empty)", "Value: (empty)", "Column 1: next", "Value: (empty)"),
            text("| |Value|\n|---|---|\n|||\n|next|"))
    }

    @Test fun surroundingMarkdownAndMultipleTablesKeepTheirOrder() {
        val result = blocks("# Before\n\n|A|B|\n|---|---|\n|1|2|\n\n**Between**\n\n|C|D|\n|---|---|\n|3|4|\n\n```\n|not|table|\n```\n\nAfter")
        assertEquals(listOf("Before", "A: 1", "B: 2", "Between", "C: 3", "D: 4", "|not|table|\n", "After"), result.map { it.inline.text })
        assertEquals(MarkdownKind.HEADING, result.first().kind)
        assertEquals(MarkdownKind.CODE, result[6].kind)
    }

    @Test fun extraCellsFallBackOnlyForThatTableWithExactCrLf() {
        val raw = "|A|B|\r\n|---|---|\r\n|1|2|extra|"
        val result = blocks("# Before\r\n\r\n$raw\r\n\r\n**After**")
        assertEquals(listOf("Before", raw, "After"), result.map { it.inline.text })
        assertEquals(listOf(MarkdownKind.HEADING, MarkdownKind.PLAIN, MarkdownKind.PARAGRAPH), result.map { it.kind })
    }

    @Test fun escapedPipesAndBackslashParityDoNotDiscardExtraData() {
        assertEquals(listOf("A: x|y", "B: z"), text("|A|B|\n|---|---|\n|x\\|y|z|"))
        val raw = "|A|B|\n|---|---|\n|x\\\\|y|extra|"
        assertEquals(listOf(raw), text(raw))
    }

    @Test fun longCellsStaySegmentedAndFaithful() {
        val long = "x".repeat(5000)
        val result = blocks("|Name|Value|\n|---|---|\n|first|$long|")
        assertEquals("Name: firstValue: $long", result.joinToString("") { it.inline.text })
        assertTrue(result.all { it.inline.length <= MarkdownProjection.SEGMENT })
        assertTrue(result.drop(2).all { it.continuation })
    }

    @Test fun rowAndColumnBoundsUseReadableFallback() {
        val boundary = "|A|B|\n|---|---|\n" + (1..100).joinToString("\n") { "|$it|v|" }
        assertEquals(200, blocks(boundary).size)
        val oversized = boundary + "\n|101|v|"
        assertEquals(oversized, blocks(oversized).joinToString("") { it.inline.text })
        val wide = "|" + (1..9).joinToString("|") { "H$it" } + "|\n|" + "---|".repeat(9) + "\n|" + "v|".repeat(9)
        assertEquals(listOf(wide), text(wide))
    }

    @Test fun tableSourceBudgetDoesNotStripSurroundingFormatting() {
        val raw = "|A|B|\n|---|---|\n|1|${"x".repeat(17000)}|"
        val result = blocks("# Before\n\n$raw\n\n# After")
        assertEquals(MarkdownKind.HEADING, result.first().kind)
        assertEquals(MarkdownKind.HEADING, result.last().kind)
        assertEquals(raw, result.drop(1).dropLast(1).joinToString("") { it.inline.text })
    }

    @Test fun malformedTableRemainsReadable() {
        val result = text("A | B\n-- invalid --\n1 | 2")
        assertTrue(result.joinToString("").contains("1 | 2"))
        assertTrue(result.joinToString("").contains("-- invalid --"))
    }

    @Test fun completedProjectionPreservesCanonicalTextAndStableKeys() {
        val source = "|A|B|\n|---|---|\n|1|2|"
        val chat = ChatState(listOf(ChatMessage(1, ChatRole.USER, "table"), ChatMessage(2, ChatRole.ASSISTANT, source)), stage = ChatStage.COMPLETED)
        val rich = projection.project(chat, PlainTextProjection().project(chat))
        assertEquals(listOf("user-1-0", "answer-1-0", "answer-1-1"), rich.rows.map { it.key })
        assertEquals(listOf("table", "A: 1", "B: 2"), rich.rows.map { it.text })
        assertEquals(source, chat.messages.last().text)
    }
}
