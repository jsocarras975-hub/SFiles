package com.superfiles.app.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes Android binary XML (AXML), e.g. AndroidManifest.xml, into readable text. */
object AxmlDecoder {

    private const val T_XML = 0x0003
    private const val T_STRING_POOL = 0x0001
    private const val T_START_NS = 0x0100
    private const val T_START_EL = 0x0102
    private const val T_END_EL = 0x0103

    fun decode(data: ByteArray): String {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(data.size >= 8 && u16(b, 0) == T_XML) { "Not binary XML" }

        var pos = u16(b, 2)
        val end = minOf(data.size, b.getInt(4))
        var strings: List<String> = emptyList()
        fun s(i: Int) = strings.getOrElse(i) { "" }

        val prefixes = HashMap<String, String>()          // uri -> prefix
        val pendingNs = ArrayList<Pair<String, String>>() // prefix to uri, emitted on next element
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var depth = 0
        var lastStartLen = -1

        while (pos + 8 <= end) {
            val type = u16(b, pos)
            val size = b.getInt(pos + 4)
            if (size < 8) break
            when (type) {
                T_STRING_POOL -> strings = readPool(b, pos)
                T_START_NS -> {
                    val prefix = s(b.getInt(pos + 16))
                    val uri = s(b.getInt(pos + 20))
                    prefixes[uri] = prefix
                    pendingNs += prefix to uri
                }
                T_START_EL -> {
                    val base = pos + 16
                    val ns = b.getInt(base)
                    val name = s(b.getInt(base + 4))
                    val attrStart = u16(b, base + 8)
                    val attrSize = u16(b, base + 10)
                    val attrCount = u16(b, base + 12)
                    val indent = "  ".repeat(depth)
                    out.append(indent).append('<').append(qualified(prefixes, s(ns), name))
                    for ((prefix, uri) in pendingNs) {
                        out.append("\n").append(indent).append("    xmlns:").append(prefix)
                            .append("=\"").append(esc(uri)).append('"')
                    }
                    pendingNs.clear()
                    for (i in 0 until attrCount) {
                        val a = base + attrStart + i * attrSize
                        val aNs = b.getInt(a)
                        val aName = s(b.getInt(a + 4))
                        val raw = b.getInt(a + 8)
                        val vType = b.get(a + 15).toInt() and 0xFF
                        val vData = b.getInt(a + 16)
                        out.append("\n").append(indent).append("    ")
                            .append(qualified(prefixes, s(aNs), aName))
                            .append("=\"").append(esc(value(raw, vType, vData, ::s))).append('"')
                    }
                    out.append(">\n")
                    lastStartLen = out.length
                    depth++
                }
                T_END_EL -> {
                    depth = maxOf(0, depth - 1)
                    val name = qualified(prefixes, s(b.getInt(pos + 16)), s(b.getInt(pos + 20)))
                    if (out.length == lastStartLen) {
                        out.setLength(out.length - 2)
                        out.append(" />\n")
                    } else {
                        out.append("  ".repeat(depth)).append("</").append(name).append(">\n")
                    }
                }
            }
            pos += size
        }
        return out.toString()
    }

    private fun qualified(prefixes: Map<String, String>, uri: String, name: String): String {
        val p = if (uri.isEmpty()) null else prefixes[uri]
        return if (p.isNullOrEmpty()) name else "$p:$name"
    }

    private fun value(raw: Int, type: Int, data: Int, s: (Int) -> String): String {
        if (raw != -1) return s(raw)
        return when (type) {
            0x03 -> s(data)
            0x12 -> if (data != 0) "true" else "false"
            0x10 -> data.toString()
            0x11 -> "0x%x".format(data)
            0x01 -> "@0x%08x".format(data)
            0x02 -> "?0x%08x".format(data)
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            in 0x1c..0x1f -> "#%08x".format(data)
            else -> "0x%x".format(data)
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun u16(b: ByteBuffer, off: Int) = b.getShort(off).toInt() and 0xFFFF

    /** Builds a string pool chunk from [strings]. Existing strings keep their indices; only
     *  appended strings get new ones. Styled strings are not preserved. */
    internal fun buildPool(strings: List<String>, utf8: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            offsets[i] = body.size()
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                len8(body, s.length)
                len8(body, bytes.size)
                body.write(bytes)
                body.write(0)
            } else {
                len16(body, s.length)
                body.write(s.toByteArray(Charsets.UTF_16LE))
                body.write(0)
                body.write(0)
            }
        }
        while (body.size() % 4 != 0) body.write(0)

        val n = strings.size
        val stringsStart = 28 + 4 * n
        val size = stringsStart + body.size()
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(T_STRING_POOL.toShort())
        bb.putShort(28)
        bb.putInt(size)
        bb.putInt(n)
        bb.putInt(0)
        bb.putInt(if (utf8) 0x100 else 0)
        bb.putInt(stringsStart)
        bb.putInt(0)
        offsets.forEach { bb.putInt(it) }
        bb.put(body.toByteArray())
        return bb.array()
    }

    private fun len8(o: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) { o.write((len shr 8) or 0x80); o.write(len and 0xFF) } else o.write(len)
    }

    private fun len16(o: ByteArrayOutputStream, len: Int) {
        if (len > 0x7FFF) {
            val hi = (len shr 16) or 0x8000
            o.write(hi and 0xFF); o.write(hi shr 8)
            o.write(len and 0xFF); o.write((len shr 8) and 0xFF)
        } else {
            o.write(len and 0xFF); o.write(len shr 8)
        }
    }

    internal fun readPool(b: ByteBuffer, start: Int): List<String> {
        val headerSize = u16(b, start + 2)
        val count = b.getInt(start + 8)
        val utf8 = (b.getInt(start + 16) and 0x100) != 0
        val stringsStart = b.getInt(start + 20)
        val bytes = b.array()
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            try {
                var p = start + stringsStart + b.getInt(start + headerSize + i * 4)
                if (utf8) {
                    p += if ((bytes[p].toInt() and 0x80) != 0) 2 else 1 // char count, unused
                    var len = bytes[p].toInt() and 0xFF
                    if ((len and 0x80) != 0) {
                        len = ((len and 0x7F) shl 8) or (bytes[p + 1].toInt() and 0xFF)
                        p += 2
                    } else {
                        p += 1
                    }
                    out += String(bytes, p, len, Charsets.UTF_8)
                } else {
                    var len = u16(b, p)
                    p += 2
                    if ((len and 0x8000) != 0) {
                        len = ((len and 0x7FFF) shl 16) or u16(b, p)
                        p += 2
                    }
                    out += String(bytes, p, len * 2, Charsets.UTF_16LE)
                }
            } catch (_: Exception) {
                out += ""
            }
        }
        return out
    }
}
