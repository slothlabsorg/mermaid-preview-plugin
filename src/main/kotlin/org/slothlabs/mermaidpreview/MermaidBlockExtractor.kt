package org.slothlabs.mermaidpreview

data class MermaidBlock(
    val index: Int,
    val startLine: Int,
    val code: String,
)

object MermaidBlockExtractor {
    private val FENCE = Regex(
        """(?m)^[ \t]{0,3}(?<fence>`{3,}|~{3,})[ \t]*mermaid[ \t]*$([\s\S]*?)^[ \t]{0,3}\k<fence>[ \t]*$""",
    )

    fun extract(markdown: String): List<MermaidBlock> {
        val out = mutableListOf<MermaidBlock>()
        var idx = 0
        for (m in FENCE.findAll(markdown)) {
            val startOffset = m.range.first
            val startLine = markdown.substring(0, startOffset).count { it == '\n' } + 1
            val body = m.groupValues[2].trim('\n', '\r')
            out.add(MermaidBlock(index = idx++, startLine = startLine, code = body))
        }
        return out
    }
}
