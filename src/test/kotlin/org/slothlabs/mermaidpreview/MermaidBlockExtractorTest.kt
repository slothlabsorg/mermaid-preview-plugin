package org.slothlabs.mermaidpreview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MermaidBlockExtractorTest {

    private fun extract(md: String) = MermaidBlockExtractor.extract(md)

    @Test
    fun `returns empty list for file with no mermaid blocks`() {
        assertEquals(emptyList<MermaidBlock>(), extract("# Hello\n\nJust text."))
    }

    @Test
    fun `extracts a single block`() {
        val md = """
            # Doc

            ```mermaid
            flowchart LR
                A --> B
            ```
        """.trimIndent()
        val blocks = extract(md)
        assertEquals(1, blocks.size)
        assertEquals(0, blocks[0].index)
        assertEquals("flowchart LR\n    A --> B", blocks[0].code)
    }

    @Test
    fun `extracts multiple blocks with correct indices and start lines`() {
        val md = "```mermaid\nA --> B\n```\n\ntext\n\n```mermaid\nC --> D\n```"
        val blocks = extract(md)
        assertEquals(2, blocks.size)
        assertEquals(0, blocks[0].index)
        assertEquals(1, blocks[0].startLine)
        assertEquals(1, blocks[1].index)
        assertEquals(7, blocks[1].startLine)
    }

    @Test
    fun `ignores non-mermaid fenced blocks`() {
        val md = "```kotlin\nval x = 1\n```\n\n```mermaid\nA --> B\n```"
        val blocks = extract(md)
        assertEquals(1, blocks.size)
        assertEquals("A --> B", blocks[0].code)
    }

    @Test
    fun `handles tilde fences`() {
        val md = "~~~mermaid\nA --> B\n~~~"
        val blocks = extract(md)
        assertEquals(1, blocks.size)
        assertEquals("A --> B", blocks[0].code)
    }

    @Test
    fun `handles fence with trailing whitespace after language tag`() {
        val md = "```mermaid   \nA --> B\n```"
        val blocks = extract(md)
        assertEquals(1, blocks.size)
    }

    @Test
    fun `handles CRLF line endings`() {
        val md = "```mermaid\r\nA --> B\r\n```"
        val blocks = extract(md)
        assertEquals(1, blocks.size)
        assertEquals("A --> B", blocks[0].code)
    }

    @Test
    fun `trims leading and trailing blank lines from code body`() {
        // trim() removes \n and \r at start/end; internal whitespace and indentation is preserved
        val md = "```mermaid\n\nflowchart LR\n    A --> B\n\n```"
        val blocks = extract(md)
        assertEquals("flowchart LR\n    A --> B", blocks[0].code)
    }

    @Test
    fun `startLine is 1-based line of the opening fence`() {
        val md = "line1\nline2\n```mermaid\nA --> B\n```"
        val blocks = extract(md)
        assertEquals(3, blocks[0].startLine)
    }

    @Test
    fun `handles longer fence markers (4+ backticks)`() {
        val md = "````mermaid\nA --> B\n````"
        val blocks = extract(md)
        assertEquals(1, blocks.size)
    }

    @Test
    fun `does not extract unclosed fence`() {
        val md = "```mermaid\nA --> B"
        val blocks = extract(md)
        assertEquals(0, blocks.size)
    }
}
