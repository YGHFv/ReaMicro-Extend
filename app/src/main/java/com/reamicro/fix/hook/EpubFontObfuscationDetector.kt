package com.reamicro.fix.hook

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object EpubFontObfuscationDetector {
    private val cache = HashMap<String, DetectionResult>()

    fun detect(file: File): DetectionResult {
        if (!file.isFile || file.length() <= 0L) {
            return DetectionResult.unsuspicious("missing")
        }
        val key = buildString {
            append(file.absolutePath)
            append('|')
            append(file.length())
            append('|')
            append(file.lastModified())
        }
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val result = runCatching { detectInternal(file) }
            .getOrElse { DetectionResult.unsuspicious("parse-failed:${it.javaClass.simpleName}") }
        synchronized(cache) {
            cache[key] = result
        }
        return result
    }

    private fun detectInternal(file: File): DetectionResult {
        val ext = file.extension.lowercase()
        if (ext != "ttf" && ext != "otf") {
            return DetectionResult.unsuspicious("unsupported:$ext")
        }
        val parser = SfntFont(file.readBytes())
        val mappings = parser.cjkMappings()
        if (mappings.size < MIN_MAPPINGS_FOR_ANALYSIS) {
            return DetectionResult.unsuspicious("too-few-mappings:${mappings.size}")
        }
        val postNames = parser.postNames()
        if (postNames != null) {
            var checked = 0
            var mismatches = 0
            for ((codePoint, glyphId) in mappings) {
                val glyphName = postNames.getOrNull(glyphId) ?: continue
                val expected = parseUnicodeGlyphName(glyphName) ?: continue
                checked++
                if (expected != codePoint) mismatches++
            }
            if (checked >= MIN_POST_NAME_CHECKED) {
                val mismatchRatio = mismatches.toDouble() / checked.toDouble()
                if (mismatchRatio >= POST_MISMATCH_THRESHOLD) {
                    return DetectionResult(
                        suspicious = true,
                        reason = "post-name-mismatch",
                        detail = "checked=$checked mismatches=$mismatches ratio=${formatRatio(mismatchRatio)}",
                    )
                }
            }
        }
        val glyphs = mappings.map { it.second }
        val increasingSteps = glyphs.zipWithNext().count { (a, b) -> b > a }
        val monotonicRatio = if (glyphs.size > 1) {
            increasingSteps.toDouble() / (glyphs.size - 1).toDouble()
        } else {
            1.0
        }
        val uniqueRatio = glyphs.toSet().size.toDouble() / glyphs.size.toDouble()
        val zeroGlyphRatio = glyphs.count { it == 0 }.toDouble() / glyphs.size.toDouble()
        if (glyphs.size >= MIN_MAPPINGS_FOR_DISORDER && monotonicRatio < MONOTONIC_THRESHOLD) {
            return DetectionResult(
                suspicious = true,
                reason = "cmap-disorder",
                detail = "glyphs=${glyphs.size} monotonic=${formatRatio(monotonicRatio)}",
            )
        }
        if (glyphs.size >= MIN_MAPPINGS_FOR_DISORDER && uniqueRatio < UNIQUE_THRESHOLD) {
            return DetectionResult(
                suspicious = true,
                reason = "glyph-reuse",
                detail = "glyphs=${glyphs.size} unique=${formatRatio(uniqueRatio)}",
            )
        }
        if (zeroGlyphRatio > ZERO_GLYPH_THRESHOLD) {
            return DetectionResult(
                suspicious = true,
                reason = "zero-glyph-overuse",
                detail = "glyphs=${glyphs.size} zero=${formatRatio(zeroGlyphRatio)}",
            )
        }
        return DetectionResult.unsuspicious(
            "normal:monotonic=${formatRatio(monotonicRatio)},unique=${formatRatio(uniqueRatio)}",
        )
    }

    private fun parseUnicodeGlyphName(name: String): Int? {
        val normalized = name.substringBefore('.')
        val uni = UNI_NAME_REGEX.matchEntire(normalized)?.groupValues?.getOrNull(1)
        if (uni != null) return uni.toIntOrNull(16)
        val u = U_NAME_REGEX.matchEntire(normalized)?.groupValues?.getOrNull(1)
        if (u != null) return u.toIntOrNull(16)
        return null
    }

    private fun formatRatio(value: Double): String = "%.3f".format(value)

    data class DetectionResult(
        val suspicious: Boolean,
        val reason: String,
        val detail: String = "",
    ) {
        companion object {
            fun unsuspicious(reason: String): DetectionResult =
                DetectionResult(suspicious = false, reason = reason)
        }
    }

    private class SfntFont(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        private val tables = HashMap<String, Table>()

        init {
            require(bytes.size >= 12) { "font too small" }
            val numTables = u16(4)
            var cursor = 12
            repeat(numTables) {
                require(cursor + 16 <= buffer.limit()) { "invalid table directory" }
                val tag = ascii(cursor, 4)
                val offset = u32(cursor + 8).toInt()
                val length = u32(cursor + 12).toInt()
                if (offset >= 0 && length >= 0 && offset + length <= buffer.limit()) {
                    tables[tag] = Table(offset, length)
                }
                cursor += 16
            }
        }

        fun cjkMappings(): List<Pair<Int, Int>> {
            val cmap = tables["cmap"] ?: return emptyList()
            require(cmap.offset + 4 <= buffer.limit()) { "invalid cmap header" }
            val numTables = u16(cmap.offset + 2)
            var bestOffset: Int? = null
            var bestFormat = -1
            var cursor = cmap.offset + 4
            repeat(numTables) {
                if (cursor + 8 > cmap.offset + cmap.length) return@repeat
                val platformId = u16(cursor)
                val encodingId = u16(cursor + 2)
                val subtableOffset = cmap.offset + u32(cursor + 4).toInt()
                if (subtableOffset + 2 > buffer.limit()) {
                    cursor += 8
                    return@repeat
                }
                val format = u16(subtableOffset)
                val score = when {
                    format == 12 && platformId == 3 && encodingId == 10 -> 4
                    format == 12 -> 3
                    format == 4 && platformId == 3 && encodingId == 1 -> 2
                    format == 4 -> 1
                    else -> 0
                }
                if (score > 0 && score > bestFormat) {
                    bestOffset = subtableOffset
                    bestFormat = score
                }
                cursor += 8
            }
            val offset = bestOffset ?: return emptyList()
            return when (u16(offset)) {
                12 -> parseFormat12(offset)
                4 -> parseFormat4(offset)
                else -> emptyList()
            }
        }

        fun postNames(): List<String?>? {
            val post = tables["post"] ?: return null
            if (u32(post.offset) != POST_FORMAT_2) return null
            if (post.offset + 34 > buffer.limit()) return null
            val glyphCount = u16(post.offset + 32)
            var cursor = post.offset + 34
            if (cursor + glyphCount * 2 > post.offset + post.length) return null
            val indices = IntArray(glyphCount) {
                val value = u16(cursor)
                cursor += 2
                value
            }
            val customCount = max(0, indices.maxOrNull()?.minus(257) ?: 0)
            val customNames = ArrayList<String>(customCount)
            repeat(customCount) {
                if (cursor >= post.offset + post.length) return null
                val nameLength = u8(cursor)
                cursor += 1
                if (cursor + nameLength > post.offset + post.length) return null
                customNames += ascii(cursor, nameLength)
                cursor += nameLength
            }
            return List(glyphCount) { glyphId ->
                val index = indices[glyphId]
                when {
                    index <= 257 -> null
                    index - 258 < customNames.size -> customNames[index - 258]
                    else -> null
                }
            }
        }

        private fun parseFormat12(offset: Int): List<Pair<Int, Int>> {
            if (offset + 16 > buffer.limit()) return emptyList()
            val nGroups = u32(offset + 12).toInt()
            var cursor = offset + 16
            val result = ArrayList<Pair<Int, Int>>()
            repeat(nGroups) {
                if (cursor + 12 > buffer.limit()) return@repeat
                val startChar = u32(cursor).toInt()
                val endChar = u32(cursor + 4).toInt()
                val startGlyph = u32(cursor + 8).toInt()
                addRangeMappings(result, startChar, endChar) { cp -> startGlyph + (cp - startChar) }
                cursor += 12
            }
            return result
        }

        private fun parseFormat4(offset: Int): List<Pair<Int, Int>> {
            if (offset + 16 > buffer.limit()) return emptyList()
            val segCount = u16(offset + 6) / 2
            val endCodeOffset = offset + 14
            val startCodeOffset = endCodeOffset + segCount * 2 + 2
            val idDeltaOffset = startCodeOffset + segCount * 2
            val idRangeOffsetOffset = idDeltaOffset + segCount * 2
            if (idRangeOffsetOffset + segCount * 2 > buffer.limit()) return emptyList()
            val result = ArrayList<Pair<Int, Int>>()
            for (segment in 0 until segCount) {
                val endCode = u16(endCodeOffset + segment * 2)
                val startCode = u16(startCodeOffset + segment * 2)
                val idDelta = s16(idDeltaOffset + segment * 2)
                val idRangeOffset = u16(idRangeOffsetOffset + segment * 2)
                addRangeMappings(result, startCode, endCode) { codePoint ->
                    if (idRangeOffset == 0) {
                        (codePoint + idDelta) and 0xFFFF
                    } else {
                        val glyphAddress = idRangeOffsetOffset + segment * 2 + idRangeOffset + (codePoint - startCode) * 2
                        if (glyphAddress + 2 > buffer.limit()) {
                            0
                        } else {
                            val glyphId = u16(glyphAddress)
                            if (glyphId == 0) 0 else (glyphId + idDelta) and 0xFFFF
                        }
                    }
                }
            }
            return result
        }

        private fun addRangeMappings(
            out: MutableList<Pair<Int, Int>>,
            startCode: Int,
            endCode: Int,
            glyphProvider: (Int) -> Int,
        ) {
            if (endCode < startCode) return
            for ((rangeStart, rangeEnd) in CJK_RANGES) {
                val start = max(startCode, rangeStart)
                val end = min(endCode, rangeEnd)
                if (start > end) continue
                for (codePoint in start..end) {
                    out += codePoint to glyphProvider(codePoint)
                }
            }
        }

        private fun u8(offset: Int): Int = buffer.get(offset).toInt() and 0xFF

        private fun u16(offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF

        private fun s16(offset: Int): Int = buffer.getShort(offset).toInt()

        private fun u32(offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL

        private fun ascii(offset: Int, length: Int): String {
            val slice = ByteArray(length)
            val duplicate = buffer.duplicate()
            duplicate.position(offset)
            duplicate.get(slice)
            return slice.toString(Charsets.ISO_8859_1)
        }

        private data class Table(
            val offset: Int,
            val length: Int,
        )
    }

    private val CJK_RANGES = listOf(
        0x3400 to 0x4DBF,
        0x4E00 to 0x9FFF,
    )
    private val UNI_NAME_REGEX = Regex("""uni([0-9A-Fa-f]{4})""")
    private val U_NAME_REGEX = Regex("""u([0-9A-Fa-f]{4,6})""")
    private const val POST_FORMAT_2 = 0x00020000L
    private const val MIN_MAPPINGS_FOR_ANALYSIS = 50
    private const val MIN_POST_NAME_CHECKED = 50
    private const val MIN_MAPPINGS_FOR_DISORDER = 200
    private const val POST_MISMATCH_THRESHOLD = 0.05
    private const val MONOTONIC_THRESHOLD = 0.70
    private const val UNIQUE_THRESHOLD = 0.90
    private const val ZERO_GLYPH_THRESHOLD = 0.02
}
