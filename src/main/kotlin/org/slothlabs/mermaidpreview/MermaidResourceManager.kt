package org.slothlabs.mermaidpreview

import com.intellij.openapi.components.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Extracts bundled resources (mermaid.min.js, preview.html) into a temp directory on first use,
 * so that JCEF can load them via a `file://` URL with relative script src paths.
 */
@Service(Service.Level.APP)
class MermaidResourceManager {
    private val extractDir: Path by lazy {
        val base = Path.of(System.getProperty("java.io.tmpdir"), "slothlabs-mermaid-preview", VERSION)
        Files.createDirectories(base)
        copy("/mermaid/mermaid.min.js", base.resolve("mermaid.min.js"))
        copy("/web/preview.html", base.resolve("preview.html"))
        base
    }

    fun previewHtmlUrl(): String = "file://${extractDir.resolve("preview.html").toAbsolutePath()}"

    private fun copy(classpath: String, target: Path) {
        MermaidResourceManager::class.java.getResourceAsStream(classpath)?.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        } ?: error("Bundled resource not found in plugin jar: $classpath")
    }

    companion object {
        const val VERSION = "0.1.2"
    }
}
