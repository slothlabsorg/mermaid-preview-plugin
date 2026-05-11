package org.slothlabs.mermaidpreview

import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level in-memory cache of rendered SVG strings, keyed by filePath + block index.
 * Populated by the tool window (via JBCefJSQuery) as each block renders.
 * Consumed by the inline inlay manager to display previews in the editor.
 */
@Service(Service.Level.PROJECT)
class MermaidSvgCache {

    private val cache = ConcurrentHashMap<String, String>()
    private val listeners = ConcurrentHashMap.newKeySet<() -> Unit>()

    fun put(filePath: String, blockIndex: Int, svg: String) {
        cache[key(filePath, blockIndex)] = svg
        listeners.forEach { it() }
    }

    fun get(filePath: String, blockIndex: Int): String? = cache[key(filePath, blockIndex)]

    fun clearFile(filePath: String) {
        cache.keys.removeIf { it.startsWith("$filePath|") }
    }

    fun addListener(fn: () -> Unit) { listeners.add(fn) }
    fun removeListener(fn: () -> Unit) { listeners.remove(fn) }

    private fun key(filePath: String, blockIndex: Int) = "$filePath|$blockIndex"
}
