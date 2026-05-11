package org.slothlabs.mermaidpreview

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.Function

/**
 * Shows a diagram icon in the gutter on every ` ```mermaid ` opening fence.
 * Clicking the icon opens / focuses the Mermaid Preview tool window.
 */
class MermaidLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        if (element !is LeafPsiElement) return null
        val text = element.text.trim()
        // Match the language token of a fenced code block that says "mermaid"
        if (text.lowercase() != "mermaid") return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.FileTypes.Diagram,
            Function { "Open in Mermaid Preview" },
            { _, elt ->
                ToolWindowManager.getInstance(elt.project)
                    .getToolWindow("Mermaid")
                    ?.activate(null)
            },
            GutterIconRenderer.Alignment.CENTER,
            { "Mermaid block" },
        )
    }
}
