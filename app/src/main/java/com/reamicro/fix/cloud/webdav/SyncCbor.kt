package com.reamicro.fix.cloud.webdav

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 修改清单容器时保留其他值的原始 CBOR 字节，避免丢失宿主扩展字段。 */
internal object SyncCbor {
    sealed interface Value
    data class Atom(val raw: ByteArray) : Value
    data class Items(val values: List<Value>) : Value
    data class Fields(val values: Map<String, Value>) : Value

    fun text(value: String): Value = Atom(header(3, value.toByteArray(Charsets.UTF_8).size.toLong()) + value.toByteArray(Charsets.UTF_8))
    fun number(value: Long): Value = Atom(header(if (value >= 0) 0 else 1, if (value >= 0) value else -(value + 1)))
    fun string(value: Value?): String {
        val atom = requireNotNull(value as? Atom) { "同步清单字符串格式错误" }
        val reader = Reader(atom.raw)
        val first = reader.byte()
        require(first ushr 5 == 3)
        return reader.utf8(reader.length(first).toInt()).also { require(reader.position == atom.raw.size) }
    }
    fun long(value: Value?, default: Long = 0): Long {
        if (value == null) return default
        val reader = Reader(requireNotNull(value as? Atom).raw)
        val first = reader.byte()
        require(first ushr 5 in 0..1)
        val n = reader.length(first)
        require(reader.position == reader.bytes.size)
        return if (first ushr 5 == 0) n else -1 - n
    }
    fun map(value: Value?): Map<String, Value> = (value as? Fields)?.values ?: error("同步清单映射格式错误")
    fun list(value: Value?): List<Value> = (value as? Items)?.values ?: error("同步清单列表格式错误")
    fun decode(bytes: ByteArray): Value {
        require(bytes.size <= 40 * 1024 * 1024) { "同步清单过大" }
        val reader = Reader(bytes)
        return reader.value(0).also { require(reader.position == bytes.size) { "同步清单存在多余数据" } }
    }
    fun encode(value: Value): ByteArray = when (value) {
        is Atom -> value.raw
        is Items -> ByteArrayOutputStream().apply {
            write(header(4, value.values.size.toLong()))
            value.values.forEach { write(encode(it)) }
        }.toByteArray()
        is Fields -> ByteArrayOutputStream().apply {
            write(header(5, value.values.size.toLong()))
            value.values.forEach { (key, item) -> write(encode(text(key))); write(encode(item)) }
        }.toByteArray()
    }
    private fun header(major: Int, value: Long): ByteArray {
        require(value >= 0)
        val extra = when {
            value < 24 -> return byteArrayOf(((major shl 5) or value.toInt()).toByte())
            value <= 255 -> 1
            value <= 65535 -> 2
            value <= 0xffffffffL -> 4
            else -> 8
        }
        return byteArrayOf(((major shl 5) or when (extra) { 1 -> 24; 2 -> 25; 4 -> 26; else -> 27 }).toByte()) +
            ByteArray(extra) { index -> (value ushr ((extra - index - 1) * 8)).toByte() }
    }
    private class Reader(val bytes: ByteArray) {
        var position = 0
        private var nodes = 0
        fun byte(): Int = bytes.getOrNull(position++)?.toInt()?.and(255) ?: error("同步清单不完整")
        fun length(first: Int): Long {
            val additional = first and 31
            if (additional < 24) return additional.toLong()
            val count = when (additional) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> error("无效的 CBOR 长度") }
            var result = 0L
            repeat(count) { result = (result shl 8) or byte().toLong() }
            require(result >= 0) { "同步清单整数溢出" }
            return result
        }
        fun utf8(count: Int): String {
            require(count >= 0 && count <= bytes.size - position)
            val result = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, position, count)).toString()
            position += count
            return result
        }
        fun value(depth: Int): Value {
            require(depth <= 64 && ++nodes <= 2_000_000) { "同步清单嵌套过深或项目过多" }
            val start = position
            val first = byte()
            val major = first ushr 5
            val indefinite = first and 31 == 31
            if (major == 7) {
                val extra = when (first and 31) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; in 0..23 -> 0; else -> error("无效的 CBOR 简单值") }
                require(extra <= bytes.size - position)
                position += extra
                return Atom(bytes.copyOfRange(start, position))
            }
            val count = if (indefinite) -1 else length(first)
            fun more(index: Long): Boolean = if (indefinite) bytes.getOrNull(position)?.toInt()?.and(255) != 255 else index < count
            when (major) {
                4 -> {
                    val values = arrayListOf<Value>()
                    while (more(values.size.toLong())) values += value(depth + 1)
                    if (indefinite) byte()
                    return Items(values)
                }
                5 -> {
                    val values = linkedMapOf<String, Value>()
                    while (more(values.size.toLong())) {
                        val key = string(value(depth + 1))
                        require(!values.containsKey(key)) { "同步清单包含重复字段" }
                        values[key] = value(depth + 1)
                    }
                    if (indefinite) byte()
                    return Fields(values)
                }
                2, 3 -> {
                    require(!indefinite && count <= bytes.size - position)
                    position += count.toInt()
                }
                6 -> { require(!indefinite); value(depth + 1) }
                0, 1, 7 -> require(!indefinite)
                else -> error("无效的 CBOR 项目")
            }
            return Atom(bytes.copyOfRange(start, position))
        }
    }
}
