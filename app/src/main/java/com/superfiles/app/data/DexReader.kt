package com.superfiles.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lists the classes defined in a .dex file by reading its string, type and class_def tables. */
object DexReader {

    fun classNames(dex: ByteArray): List<String> {
        try {
            require(dex.size > 0x70 && dex[0] == 'd'.code.toByte() && dex[1] == 'e'.code.toByte() &&
                dex[2] == 'x'.code.toByte()) { "Not a DEX file" }
            val b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
            val stringIds = b.getInt(0x3C)
            val typeIds = b.getInt(0x44)
            val classCount = b.getInt(0x60)
            val classDefs = b.getInt(0x64)
            require(stringIds in 0 until dex.size && typeIds in 0 until dex.size &&
                classDefs in 0 until dex.size && classCount >= 0) { "Malformed DEX header" }

            fun tableOff(base: Int, idx: Int): Int {
                val off = base + idx * 4
                require(off in 0 until dex.size - 3) { "Malformed DEX: table index out of range" }
                return off
            }

            fun string(idx: Int): String {
                var p = b.getInt(tableOff(stringIds, idx))
                require(p in 0 until dex.size) { "Malformed DEX: string offset out of range" }
                while (p < dex.size && (dex[p].toInt() and 0x80) != 0) p++ // skip uleb128 utf16 length
                require(p < dex.size) { "Malformed DEX: truncated string" }
                p++
                var e = p
                while (e < dex.size && dex[e] != 0.toByte()) e++
                return String(dex, p, e - p, Charsets.UTF_8)
            }

            return (0 until classCount).map { i ->
                val defOff = classDefs + i * 32
                require(defOff + 32 <= dex.size) { "Malformed DEX: class def out of range" }
                val typeIdx = b.getInt(defOff)
                string(b.getInt(tableOff(typeIds, typeIdx)))
                    .removePrefix("L").removeSuffix(";").replace('/', '.')
            }
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("Malformed DEX file", e)
        }
    }
}
