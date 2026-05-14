package org.slothlabs.mermaidpreview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.SwingUtilities

/**
 * Adds a compact badge below each closing mermaid fence in the editor.
 * Does NOT depend on the SVG cache — shows for any block found in the file.
 */
class MermaidInlayManager(private val project: Project) : EditorFactoryListener, Disposable {

    private val editorInlays = HashMap<Editor, MutableList<Inlay<*>>>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.project != project) return
        val file = fileFor(editor) ?: return
        if (!isSupportedFile(file)) return
        ApplicationManager.getApplication().invokeLater { addInlays(editor, file) }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        editorInlays.remove(event.editor)?.forEach { Disposer.dispose(it) }
    }

    private fun addInlays(editor: Editor, file: VirtualFile) {
        if (editor.isDisposed) return
        val document = editor.document
        val text = ApplicationManager.getApplication().runReadAction<String> { document.text }
        val blocks = when {
            isStandaloneMermaid(file) -> listOf(MermaidBlock(0, 1, text.trim()))
            else -> MermaidBlockExtractor.extract(text)
        }

        editorInlays[editor]?.forEach { Disposer.dispose(it) }
        editorInlays[editor] = mutableListOf()

        for (block in blocks) {
            val closingLine = closingFenceLine(text, block) ?: continue
            if (closingLine >= document.lineCount) continue
            val offset = document.getLineEndOffset(closingLine)
            val inlay = editor.inlayModel.addBlockElement(offset, true, false, 5, BadgeRenderer()) ?: continue
            editorInlays.getOrPut(editor) { mutableListOf() }.add(inlay)
        }
    }

    private fun closingFenceLine(text: String, block: MermaidBlock): Int? {
        val lines = text.lines()
        val openLine = block.startLine - 1
        if (openLine >= lines.size) return null
        val fenceChar = lines[openLine].trimStart().firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val fenceMarker = lines[openLine].trimStart().takeWhile { it == fenceChar }
        for (i in (openLine + 1) until lines.size) {
            val t = lines[i].trim()
            if (t == fenceMarker || (t.length >= fenceMarker.length && t.all { it == fenceChar })) return i
        }
        return null
    }

    private fun fileFor(editor: Editor): VirtualFile? = FileDocumentManager.getInstance().getFile(editor.document)
    private fun isSupportedFile(f: VirtualFile) = f.extension?.lowercase() in setOf("md", "markdown", "mdx", "mmd", "mermaid")
    private fun isStandaloneMermaid(f: VirtualFile) = f.extension?.lowercase() in setOf("mmd", "mermaid")

    override fun dispose() {
        editorInlays.values.flatten().forEach { Disposer.dispose(it) }
        editorInlays.clear()
    }
}

private class BadgeRenderer : EditorCustomElementRenderer {
    private val bg = JBColor(Color(0xE0, 0xF0, 0xFF), Color(0x1A, 0x2A, 0x3A))
    private val border = JBColor(Color(0x99, 0xCC, 0xFF), Color(0x33, 0x66, 0x99))
    private val fg = JBColor(Color(0x00, 0x66, 0xCC), Color(0x66, 0xCC, 0xFF))
    private val coral = JBColor(Color(0xFF, 0x36, 0x70), Color(0xFF, 0x70, 0x90))

    override fun calcWidthInPixels(inlay: Inlay<*>) = 260
    override fun calcHeightInPixels(inlay: Inlay<*>) = 20

    override fun paint(inlay: Inlay<*>, g: Graphics, region: java.awt.Rectangle, attrs: TextAttributes) {
        val g2 = (g as Graphics2D).create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val rx = region.x + 2; val ry = region.y + 1
            val rw = calcWidthInPixels(inlay) - 4; val rh = 18

            g2.color = bg; g2.fillRoundRect(rx, ry, rw, rh, 6, 6)
            g2.color = border; g2.drawRoundRect(rx, ry, rw, rh, 6, 6)

            // Mini icon (2 nodes + arrow)
            g2.color = coral
            g2.fillRoundRect(rx + 4, ry + 4, 5, 3, 1, 1)
            g2.fillRoundRect(rx + 12, ry + 11, 5, 3, 1, 1)
            g2.drawLine(rx + 9, ry + 6, rx + 13, ry + 11)
            g2.fillPolygon(intArrayOf(rx + 11, rx + 14, rx + 12), intArrayOf(ry + 10, ry + 10, ry + 13), 3)

            g2.color = fg
            g2.font = g2.font.deriveFont(Font.PLAIN, 10.5f)
            g2.drawString("mermaid diagram — see Mermaid Preview panel", rx + 20, ry + rh - 4)
        } finally { g2.dispose() }
    }
}
