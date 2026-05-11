package org.slothlabs.mermaidpreview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
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
import com.intellij.util.ui.ImageUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Listens for editors opening .md/.mmd files and injects SVG previews as block inlays
 * below the closing ``` fence of each mermaid block.
 *
 * The SVG comes from MermaidSvgCache, which is populated by the tool window.
 * Inlays appear once the tool window has rendered the corresponding block.
 */
class MermaidInlayManager(private val project: Project) : EditorFactoryListener, Disposable {

    /** Tracks which inlays are active per editor so we can remove stale ones. */
    private val editorInlays = HashMap<Editor, MutableList<Inlay<*>>>()

    private val cacheListener: () -> Unit = {
        SwingUtilities.invokeLater { refreshAllEditors() }
    }

    init {
        project.service<MermaidSvgCache>().addListener(cacheListener)
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.project != project) return
        val file = fileFor(editor) ?: return
        if (!isSupportedFile(file)) return
        refreshEditor(editor, file)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        editorInlays.remove(event.editor)?.forEach { Disposer.dispose(it) }
    }

    private fun refreshAllEditors() {
        editorInlays.keys.toList().forEach { editor ->
            val file = fileFor(editor) ?: return@forEach
            refreshEditor(editor, file)
        }
    }

    private fun refreshEditor(editor: Editor, file: VirtualFile) {
        val cache = project.service<MermaidSvgCache>()
        val document = editor.document
        val text = ApplicationManager.getApplication().runReadAction<String> { document.text }
        val blocks = when {
            isStandaloneMermaid(file) -> listOf(MermaidBlock(0, 1, text.trim()))
            else -> MermaidBlockExtractor.extract(text)
        }

        // Remove existing inlays for this editor
        editorInlays[editor]?.forEach { Disposer.dispose(it) }
        editorInlays[editor] = mutableListOf()

        for (block in blocks) {
            val svg = cache.get(file.path, block.index) ?: continue
            val image = svgToImage(svg) ?: continue

            // Offset of the line AFTER the closing fence (startLine + block line count + 2 for fences)
            val targetLine = closingFenceLine(text, block) ?: continue
            if (targetLine >= document.lineCount) continue
            val offset = document.getLineEndOffset(targetLine)

            ApplicationManager.getApplication().invokeLater {
                if (editor.isDisposed) return@invokeLater
                val inlay = editor.inlayModel.addBlockElement(
                    offset,
                    true,
                    false,
                    10,
                    SvgInlayRenderer(image),
                ) ?: return@invokeLater
                editorInlays.getOrPut(editor) { mutableListOf() }.add(inlay)
            }
        }
    }

    /** Finds the 0-based line index of the closing ``` fence for this block. */
    private fun closingFenceLine(text: String, block: MermaidBlock): Int? {
        val lines = text.lines()
        // startLine is 1-based, the opening fence is on that line.
        // Scan forward to find the matching closing fence.
        val openLine = block.startLine - 1
        if (openLine >= lines.size) return null
        val fenceMarker = lines[openLine].trimStart().takeWhile { it == '`' || it == '~' }
        for (i in (openLine + 1) until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed == fenceMarker || trimmed.startsWith(fenceMarker) && trimmed.all { it == fenceMarker[0] }) {
                return i
            }
        }
        return null
    }

    private fun svgToImage(svg: String): BufferedImage? {
        return try {
            // IntelliJ's SVGLoader handles most SVG; fall back to ImageIO for simple ones.
            val bytes = svg.toByteArray(Charsets.UTF_8)
            val loader = Class.forName("com.intellij.util.SVGLoader")
            val method = loader.getMethod("load", java.io.InputStream::class.java, Float::class.javaPrimitiveType)
            val img = method.invoke(null, ByteArrayInputStream(bytes), 1.5f) as? java.awt.Image
            img?.let { ImageUtil.toBufferedImage(it) }
        } catch (_: Exception) {
            // SVGLoader not available — try ImageIO (won't work for SVG but graceful fallback)
            null
        }
    }

    private fun fileFor(editor: Editor) =
        FileDocumentManager.getInstance().getFile(editor.document)

    private fun isSupportedFile(file: VirtualFile) =
        file.extension?.lowercase() in setOf("md", "markdown", "mdx", "mmd", "mermaid")

    private fun isStandaloneMermaid(file: VirtualFile) =
        file.extension?.lowercase() in setOf("mmd", "mermaid")

    override fun dispose() {
        project.service<MermaidSvgCache>().removeListener(cacheListener)
        editorInlays.values.flatten().forEach { Disposer.dispose(it) }
        editorInlays.clear()
    }
}

private class SvgInlayRenderer(private val image: BufferedImage) : EditorCustomElementRenderer {
    private val maxWidth = 800
    private val scale get() = minOf(1f, maxWidth.toFloat() / image.width)
    private val w get() = (image.width * scale).toInt()
    private val h get() = (image.height * scale).toInt()

    override fun calcWidthInPixels(inlay: Inlay<*>) = w
    override fun calcHeightInPixels(inlay: Inlay<*>) = h + 8

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: java.awt.Rectangle, textAttributes: TextAttributes) {
        g.drawImage(image, targetRegion.x + 8, targetRegion.y + 4, w, h, null)
    }
}
