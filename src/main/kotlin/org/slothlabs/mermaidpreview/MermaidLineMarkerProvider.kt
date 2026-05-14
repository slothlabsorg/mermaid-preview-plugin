package org.slothlabs.mermaidpreview

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.ui.JBColor
import com.intellij.util.Function
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

class MermaidLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        if (element !is LeafPsiElement) return null
        if (element.text.trim().lowercase() != "mermaid") return null

        return LineMarkerInfo(
            element,
            element.textRange,
            ICON,
            Function { "Open in Mermaid Preview" },
            { _, elt -> ToolWindowManager.getInstance(elt.project).getToolWindow("Mermaid")?.activate(null) },
            GutterIconRenderer.Alignment.CENTER,
            { "Mermaid block" },
        )
    }

    companion object {
        // Two rounded-rect nodes connected by an arrow, in mermaid brand coral.
        val ICON: Icon = object : Icon {
            private val coral = JBColor(Color(0xFF, 0x36, 0x70), Color(0xFF, 0x70, 0x90))
            override fun getIconWidth() = 16
            override fun getIconHeight() = 16
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = (g as Graphics2D).create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = coral
                    g2.fillRoundRect(x + 1, y + 2, 6, 4, 2, 2)   // top-left node
                    g2.fillRoundRect(x + 9, y + 10, 6, 4, 2, 2)  // bottom-right node
                    g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(x + 7, y + 4, x + 11, y + 10)    // connector line
                    // arrowhead
                    g2.fillPolygon(intArrayOf(x + 9, x + 13, x + 11), intArrayOf(y + 9, y + 9, y + 13), 3)
                } finally { g2.dispose() }
            }
        }
    }
}
