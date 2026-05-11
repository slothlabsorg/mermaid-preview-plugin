package org.slothlabs.mermaidpreview

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
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
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.messages.MessageBusConnection
import java.awt.BorderLayout
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
    private val connection: MessageBusConnection = project.messageBus.connect(this)

    // JBCefJSQuery lets JS call back into Kotlin — used for click-to-jump.
    private val navigateQuery: JBCefJSQuery? = browser?.let { b ->
        JBCefJSQuery.create(b as com.intellij.ui.jcef.JBCefBrowserBase).also { q ->
            q.addHandler { indexStr ->
                val idx = indexStr.trim().toIntOrNull() ?: return@addHandler null
                val block = currentBlocks.getOrNull(idx) ?: return@addHandler null
                val file = currentFile ?: return@addHandler null
                ApplicationManager.getApplication().invokeLater {
                    OpenFileDescriptor(project, file, block.startLine - 1, 0).navigate(true)
                }
                null
            }
        }
    }

    // JBCefJSQuery to receive rendered SVG strings from the webview for the inlay cache.
    private val svgCaptureQuery: JBCefJSQuery? = browser?.let { b ->
        JBCefJSQuery.create(b as com.intellij.ui.jcef.JBCefBrowserBase).also { q ->
            q.addHandler { payload ->
                // payload = "<blockIndex>|<svgContent>"
                val sep = payload.indexOf('|')
                if (sep < 0) return@addHandler null
                val idx = payload.substring(0, sep).toIntOrNull() ?: return@addHandler null
                val svg = payload.substring(sep + 1)
                val file = currentFile ?: return@addHandler null
                service<MermaidSvgCache>().put(file.path, idx, svg)
                null
            }
        }
    }

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            scheduleRefresh()
        }
    }

    init {
        if (browser == null) {
            add(
                JLabel(
                    "<html>JCEF is disabled in this IDE. Enable it via Help → Find Action → " +
                        "'Choose Boot Runtime for the IDE' → pick a JBR with JCEF.</html>",
                    SwingConstants.CENTER,
                ),
                BorderLayout.CENTER,
            )
        } else {
            Disposer.register(this, browser)
            navigateQuery?.let { Disposer.register(this, it) }
            svgCaptureQuery?.let { Disposer.register(this, it) }

            browser.jbCefClient.addLoadHandler(
                object : org.cef.handler.CefLoadHandlerAdapter() {
                    override fun onLoadEnd(
                        b: org.cef.browser.CefBrowser?,
                        f: org.cef.browser.CefFrame?,
                        httpStatusCode: Int,
                    ) {
                        injectCallbacks()
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

    /** Injects Kotlin←→JS callback functions after the page loads. */
    private fun injectCallbacks() {
        val b = browser ?: return
        val navJs = navigateQuery?.inject("index") ?: "/* nav unavailable */"
        val svgJs = svgCaptureQuery?.inject("payload") ?: "/* svg unavailable */"
        b.cefBrowser.executeJavaScript(
            """
            window._navigateToIde = function(index) { $navJs };
            window._captureSvg    = function(payload) { $svgJs };
            """.trimIndent(),
            b.cefBrowser.url,
            0,
        )
    }

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
        val escaped = json
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        b.cefBrowser.executeJavaScript(
            "window.setPayload && window.setPayload(JSON.parse('$escaped'));",
            b.cefBrowser.url,
            0,
        )
    }

    private fun isSupportedFile(file: VirtualFile) = isMarkdown(file) || isStandaloneMermaid(file)

    private fun isMarkdown(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext in setOf("md", "markdown", "mdx")
    }

    private fun isStandaloneMermaid(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext in setOf("mmd", "mermaid")
    }

    override fun dispose() {
        detachDocumentListener()
    }

    private data class Payload(
        val status: String,
        val fileName: String,
        val blocks: List<MermaidBlock>,
    )
}
