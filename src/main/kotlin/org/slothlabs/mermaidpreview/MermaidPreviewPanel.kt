package org.slothlabs.mermaidpreview

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import com.intellij.util.messages.MessageBusConnection
import java.awt.BorderLayout
import java.util.Base64
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class MermaidPreviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val gson = Gson()
    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private var browserReady = false
    private var pendingPayload: String? = null
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var currentDocument: Document? = null
    private var currentBlocks: List<MermaidBlock> = emptyList()
    private var currentFile: VirtualFile? = null
    // Pending export: (filename, ext, isPng) — set when a download is requested,
    // consumed when the SVG/PNG data arrives back via title change.
    private var pendingExport: Triple<String, String, Boolean>? = null
    private val connection: MessageBusConnection = project.messageBus.connect(this)

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) { scheduleRefresh() }
    }

    init {
        if (browser == null) {
            add(
                JLabel(
                    "<html>JCEF is disabled. Enable it via Help → Find Action → " +
                        "'Choose Boot Runtime for the IDE' → pick a JBR with JCEF.</html>",
                    SwingConstants.CENTER,
                ),
                BorderLayout.CENTER,
            )
        } else {
            Disposer.register(this, browser)

            // ── Title-based communication channel (reliable, no JBCefJSQuery) ──────
            // JS sets document.title = "mermaid://<action>/..." to signal Kotlin.
            browser.jbCefClient.addDisplayHandler(
                object : org.cef.handler.CefDisplayHandlerAdapter() {
                    override fun onTitleChange(b: org.cef.browser.CefBrowser?, title: String?) {
                        handleTitle(b ?: return, title ?: return)
                    }
                },
                browser.cefBrowser,
            )

            browser.jbCefClient.addLoadHandler(
                object : org.cef.handler.CefLoadHandlerAdapter() {
                    override fun onLoadEnd(
                        b: org.cef.browser.CefBrowser?,
                        f: org.cef.browser.CefFrame?,
                        httpStatusCode: Int,
                    ) {
                        browserReady = true
                        pendingPayload?.let { pushToBrowser(it) }
                        pendingPayload = null
                    }
                },
                browser.cefBrowser,
            )
            add(browser.component, BorderLayout.CENTER)
            browser.loadURL(service<MermaidResourceManager>().previewHtmlUrl())

            connection.subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        updateFor(event.newFile)
                    }
                },
            )
            ApplicationManager.getApplication().invokeLater {
                updateFor(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
            }
        }
    }

    // ── Title-change protocol ──────────────────────────────────────────────────
    private fun handleTitle(cefBrowser: org.cef.browser.CefBrowser, title: String) {
        when {
            // mermaid://navigate/<blockIndex>/<timestamp>
            title.startsWith("mermaid://navigate/") -> {
                val idx = title.removePrefix("mermaid://navigate/")
                    .split("/").getOrNull(0)?.toIntOrNull() ?: return
                val block = currentBlocks.getOrNull(idx) ?: return
                val file = currentFile ?: return
                ApplicationManager.getApplication().invokeLater {
                    OpenFileDescriptor(project, file, block.startLine - 1, 0).navigate(true)
                }
            }

            // mermaid://download/<ext>/<blockIndex>/<safeFilename>
            // → Kotlin asks JS to send back the stored SVG/PNG via the data channel.
            title.startsWith("mermaid://download/") -> {
                val parts = title.removePrefix("mermaid://download/").split("/")
                val ext = parts.getOrNull(0) ?: return
                val idx = parts.getOrNull(1)?.toIntOrNull() ?: return
                val filename = "${parts.drop(2).joinToString("/")}"
                pendingExport = Triple(filename, ext, ext == "png")
                val jsVar = if (ext == "png") "window.__pngs[$idx]" else "window.__svgs[$idx]"
                cefBrowser.executeJavaScript(
                    "var _d=$jsVar; document.title=_d?('mermaid://data/'+_d):'mermaid://data/null';",
                    cefBrowser.url, 0,
                )
            }

            // mermaid://data/<base64urlOrDataUrl>
            // → Kotlin receives the content and shows the save dialog.
            title.startsWith("mermaid://data/") -> {
                val raw = title.removePrefix("mermaid://data/")
                val (filename, ext, isPng) = pendingExport ?: return
                pendingExport = null
                if (raw == "null" || raw.isEmpty()) return
                val bytes = try {
                    if (isPng) Base64.getDecoder().decode(raw.substringAfter("base64,"))
                    else Base64.getDecoder().decode(raw.replace('-', '+').replace('_', '/').let {
                        when (it.length % 4) { 2 -> "$it=="; 3 -> "$it=" else -> it }
                    })
                } catch (_: Exception) { return }
                ApplicationManager.getApplication().invokeLater {
                    val descriptor = FileSaverDescriptor("Save Diagram", "", ext)
                    val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                    dialog.save(null as VirtualFile?, filename)?.let { wrapper ->
                        val vf = wrapper.getVirtualFile(true) ?: return@invokeLater
                        ApplicationManager.getApplication().runWriteAction { vf.setBinaryContent(bytes) }
                    }
                }
            }

            // mermaid://svgcache/<blockIndex>/<base64urlSvg>
            // → Populate SVG cache for inline inlays.
            title.startsWith("mermaid://svgcache/") -> {
                val parts = title.removePrefix("mermaid://svgcache/").split("/", limit = 2)
                val idx = parts.getOrNull(0)?.toIntOrNull() ?: return
                val b64 = parts.getOrNull(1) ?: return
                val svg = try {
                    String(Base64.getDecoder().decode(b64.replace('-', '+').replace('_', '/')))
                } catch (_: Exception) { return }
                val file = currentFile ?: return
                service<MermaidSvgCache>().put(file.path, idx, svg)
            }
        }
    }

    // ── File management ────────────────────────────────────────────────────────

    fun updateFor(file: VirtualFile?) {
        detachDocumentListener()
        currentFile = file
        if (file == null || !isSupportedFile(file)) {
            currentBlocks = emptyList()
            sendPayload(
                Payload(
                    status = if (file == null) "no-file" else "not-markdown",
                    fileName = file?.name ?: "",
                    blocks = emptyList(),
                ),
            )
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document != null) {
            currentDocument = document
            document.addDocumentListener(documentListener)
        }
        scheduleRefresh(immediate = true)
    }

    private fun detachDocumentListener() {
        currentDocument?.removeDocumentListener(documentListener)
        currentDocument = null
    }

    private fun scheduleRefresh(immediate: Boolean = false) {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, if (immediate) 0 else 250)
    }

    private fun refresh() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (file == null || !isSupportedFile(file)) {
            currentBlocks = emptyList()
            sendPayload(Payload(status = "no-file", fileName = "", blocks = emptyList()))
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val text = ApplicationManager.getApplication().runReadAction<String> { document.text }
        val blocks = when {
            isStandaloneMermaid(file) -> listOf(MermaidBlock(index = 0, startLine = 1, code = text.trim()))
            else -> MermaidBlockExtractor.extract(text)
        }
        currentBlocks = blocks
        sendPayload(Payload(status = "ok", fileName = file.name, blocks = blocks))
    }

    private fun sendPayload(payload: Payload) {
        val json = gson.toJson(payload)
        if (browserReady) pushToBrowser(json) else pendingPayload = json
    }

    private fun pushToBrowser(json: String) {
        val b = browser ?: return
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        b.cefBrowser.executeJavaScript(
            "window.setPayload && window.setPayload(JSON.parse('$escaped'));",
            b.cefBrowser.url, 0,
        )
    }

    private fun isSupportedFile(f: VirtualFile) = isMarkdown(f) || isStandaloneMermaid(f)
    private fun isMarkdown(f: VirtualFile) = f.extension?.lowercase() in setOf("md", "markdown", "mdx")
    private fun isStandaloneMermaid(f: VirtualFile) = f.extension?.lowercase() in setOf("mmd", "mermaid")

    override fun dispose() { detachDocumentListener() }

    private data class Payload(val status: String, val fileName: String, val blocks: List<MermaidBlock>)
}
