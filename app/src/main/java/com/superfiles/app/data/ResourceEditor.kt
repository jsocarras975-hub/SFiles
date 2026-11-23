package com.superfiles.app.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Rewrites resource values in resources.arsc (default configuration only). String values are
 * appended to the string pool, so existing indices stay valid; other values are patched in
 * place. The pool is rebuilt and spliced back in only when a string was added.
 */
object ResourceEditor {

    sealed interface ParsedValue {
        data class IntValue(val v: Int) : ParsedValue
        data class StringValue(val s: String) : ParsedValue
    }

    /** Parses [text] as a new value for a resource of [valueType]. Throws with a readable
     *  message when the text doesn't fit the type. Accepts the display format (e.g. "@0x…"
     *  for references, "#AARRGGBB" for colors) so a shown value round-trips. */
    fun parseValue(valueType: Int, text: String): ParsedValue {
        val t = text.trim()
        return when (valueType) {
            0x03 -> ParsedValue.StringValue(t)
            0x10 -> ParsedValue.IntValue(t.toIntOrNull() ?: error("Enter a whole number"))
            0x11 -> ParsedValue.IntValue(
                t.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                    ?: error("Enter a hex number, e.g. 0x1F")
            )
            0x12 -> when (t.lowercase()) {
                "true" -> ParsedValue.IntValue(1)
                "false" -> ParsedValue.IntValue(0)
                else -> error("Enter true or false")
            }
            0x04 -> ParsedValue.IntValue(
                t.toFloatOrNull()?.toBits() ?: error("Enter a decimal number")
            )
            in 0x1c..0x1f -> ParsedValue.IntValue(parseColor(t))
            0x01, 0x02 -> ParsedValue.IntValue(
                t.removePrefix("@").removePrefix("?").removePrefix("0x").removePrefix("0X")
                    .toIntOrNull(16) ?: error("Enter a hex resource id, e.g. 0x7f010001")
            )
            else -> error("This value type can't be edited")
        }
    }

    private fun parseColor(t: String): Int {
        val hex = t.removePrefix("#")
        val v = hex.toLongOrNull(16) ?: error("Enter a color like #RRGGBB or #AARRGGBB")
        return when (hex.length) {
            6 -> (0xFF000000L or v).toInt()
            8 -> v.toInt()
            else -> error("Enter a color like #RRGGBB or #AARRGGBB")
        }
    }

    /** Applies [edits] ("type/key" to new text) to the resources.arsc inside [apk]. */
    fun apply(apk: File, edits: Map<String, String>): ByteArray = ZipFile(apk).use { z ->
        val e = z.getEntry("resources.arsc") ?: error("This APK has no resources.arsc")
        applyBytes(z.getInputStream(e).readBytes(), edits)
    }

    fun applyBytes(data: ByteArray, edits: Map<String, String>): ByteArray {
        val arsc = ResourceTable.scan(data)
        val byId = arsc.entries.associateBy { it.id }
        val strings = arsc.strings.toMutableList()
        val patched = data.copyOf()
        val buf = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)
        var poolGrew = false
        for ((id, text) in edits) {
            val entry = byId[id] ?: error("Resource $id not found")
            require(!entry.complex && entry.dataOffset >= 0) { "Resource $id can't be edited" }
            when (val v = parseValue(entry.valueType, text)) {
                is ParsedValue.IntValue -> buf.putInt(entry.dataOffset, v.v)
                is ParsedValue.StringValue -> {
                    val existing = strings.indexOf(v.s)
                    val idx = if (existing >= 0) {
                        existing
                    } else {
                        poolGrew = true
                        strings.add(v.s)
                        strings.size - 1
                    }
                    buf.putInt(entry.dataOffset, idx)
                }
            }
        }
        if (!poolGrew) return patched
        require(arsc.styleCount == 0) {
            "Can't edit: this APK's resource table has styled strings"
        }
        val newPool = AxmlDecoder.buildPool(strings, arsc.utf8)
        val oldPoolEnd = arsc.poolOffset + arsc.poolSize
        val total = patched.size - arsc.poolSize + newPool.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put(patched, 0, arsc.poolOffset)
        out.put(newPool)
        out.put(patched, oldPoolEnd, patched.size - oldPoolEnd)
        val res = out.array()
        ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN).putInt(4, total)
        return res
    }
}
