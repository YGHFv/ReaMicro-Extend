package com.reamicro.fix.epub.editor

import java.io.File
import java.net.URLDecoder
import java.util.Locale

internal object EpubStructureOrder {
    private const val MAX_PACKAGE_BYTES = 4L * 1024L * 1024L
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun spineOrder(root: File, inventory: List<File>): Map<String, Int> {
        val byPath = inventory.associateBy { it.relativeTo(root).invariantSeparatorsPath }

        fun resolve(base: File, href: String): String? = runCatching {

            val raw = href.substringBefore('#').substringBefore('?')
            val path = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace('\\', '/')
            if (path.isBlank() || path.startsWith("/") || scheme.containsMatchIn(path) ||
                path.any { it < ' ' }) return@runCatching null
            val file = File(base, path).canonicalFile
            if (file == root || !file.toPath().startsWith(root.toPath())) return@runCatching null
            file.relativeTo(root).invariantSeparatorsPath.takeIf { it in byPath }
        }.getOrNull()

        fun read(file: File): String = EpubTextFiles.load(file, maxBytes = MAX_PACKAGE_BYTES).text

        val declaredPath = byPath["META-INF/container.xml"]?.let { container ->
            runCatching { EpubOpfMetadata.packagePath(read(container)) }.getOrNull()
                ?.let { if (it in byPath) it else resolve(root, it) }
        }

        val packageFile = declaredPath?.let(byPath::get)
            ?: byPath.entries.filter { it.value.extension.equals("opf", ignoreCase = true) }
                .minByOrNull { it.key }?.value
            ?: return emptyMap()
        val hrefs = runCatching { EpubOpfMetadata.spineHrefs(read(packageFile)) }
            .getOrDefault(emptyList())
        val order = linkedMapOf<String, Int>()
        for (href in hrefs) {
            val path = resolve(requireNotNull(packageFile.parentFile), href) ?: continue
            if (path !in order) order[path] = order.size
        }
        return order
    }

    fun compareNames(left: String, right: String): Int {
        val a = left.lowercase(Locale.ROOT)
        val b = right.lowercase(Locale.ROOT)
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                val aStart = i
                val bStart = j
                while (i < a.length && a[i] in '0'..'9') i++
                while (j < b.length && b[j] in '0'..'9') j++
                var aSignificant = aStart
                var bSignificant = bStart
                while (aSignificant < i && a[aSignificant] == '0') aSignificant++
                while (bSignificant < j && b[bSignificant] == '0') bSignificant++
                val lengthOrder = (i - aSignificant).compareTo(j - bSignificant)
                if (lengthOrder != 0) return lengthOrder
                val digitOrder = a.substring(aSignificant, i).compareTo(b.substring(bSignificant, j))
                if (digitOrder != 0) return digitOrder
                val zeroOrder = (i - aStart).compareTo(j - bStart)
                if (zeroOrder != 0) return zeroOrder
            } else {
                val characterOrder = a[i].compareTo(b[j])
                if (characterOrder != 0) return characterOrder
                i++
                j++
            }
        }
        val remaining = (a.length - i).compareTo(b.length - j)
        return if (remaining != 0) remaining else left.compareTo(right)
    }
}
