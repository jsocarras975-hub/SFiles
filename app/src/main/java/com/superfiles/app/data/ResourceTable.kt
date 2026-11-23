package com.superfiles.app.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** One resource value in one configuration. [dataOffset] is the absolute offset of the
 *  value's 4-byte data field inside resources.arsc, so an editor can rewrite it in place. */
data class ArscEntry(
    val type: String,
    val key: String,
    val valueType: Int,
    val data: Int,
    val dataOffset: Int,
    /** Configuration label: "default", a locale like "en" or "es-rES", or a qualifier like "land". */
    val config: String = "default",
    /** A bag of values (e.g. a style): listed but not editable. */
    val complex: Boolean = false,
) {
    /** "type/key", plus "@config" when it isn't the default configuration. */
    val id: String get() = "$type/$key" + if (config == "default") "" else "@$config"
}

/** A parsed resources.arsc: its entries plus the string pool, for editors that rewrite values. */
data class ArscFile(
    val entries: List<ArscEntry>,
    val poolOffset: Int,
    val poolSize: Int,
    val utf8: Boolean,
    val styleCount: Int,
    val strings: List<String>,
)

/** A resource row for the UI: the entry plus its display text ("type/name = value") and the
 *  plain value text the edit dialog starts from. */
data class ResourceEntry(val entry: ArscEntry, val display: String, val valueText: String) {
    val type: String get() = entry.type
    val key: String get() = entry.key
    val valueType: Int get() = entry.valueType
    val data: Int get() = entry.data
}

/** Reads resources.arsc and lists "type/name = value" for every entry in the default configuration. */
object ResourceTable {

    fun entries(apk: File): List<ResourceEntry> = ZipFile(apk).use { z ->
        val e = z.getEntry("resources.arsc") ?: error("This APK has no resources.arsc")
        val arsc = scan(z.getInputStream(e).readBytes())
        arsc.entries.map { entry ->
            val value = if (entry.complex) "<complex, ${entry.data} items>"
            else format(entry.valueType, entry.data, arsc.strings)
            val suffix = if (entry.config == "default") "" else " (${entry.config})"
            ResourceEntry(entry, "${entry.type}/${entry.key} = $value$suffix", value)
        }.sortedBy { it.display }
    }

    /** Full parse of a resources.arsc, with the offsets an editor needs. */
    fun scan(data: ByteArray): ArscFile {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(data.size > 12 && u16(b, 0) == 0x0002) { "Not a resource table" }
        var pos = u16(b, 2)
        val end = minOf(data.size, b.getInt(4))
        var global: List<String> = emptyList()
        var poolOffset = -1
        var poolSize = 0
        var utf8 = false
        var styleCount = 0
        val out = ArrayList<ArscEntry>()
        while (pos + 8 <= end) {
            val type = u16(b, pos)
            val size = b.getInt(pos + 4)
            if (size < 8) break
            when (type) {
                0x0001 -> {
                    global = AxmlDecoder.readPool(b, pos)
                    poolOffset = pos
                    poolSize = size
                    utf8 = (b.getInt(pos + 16) and 0x100) != 0
                    styleCount = b.getInt(pos + 12)
                }
                0x0200 -> parsePackage(b, pos, size, global, out)
            }
            pos += size
        }
        require(poolOffset >= 0) { "No string pool in resource table" }
        return ArscFile(out, poolOffset, poolSize, utf8, styleCount, global)
    }

    private fun parsePackage(b: ByteBuffer, start: Int, size: Int, global: List<String>, out: MutableList<ArscEntry>) {
        val typeNames = AxmlDecoder.readPool(b, start + b.getInt(start + 268))
        val keyNames = AxmlDecoder.readPool(b, start + b.getInt(start + 276))
        var pos = start + u16(b, start + 2)
        val end = minOf(b.capacity(), start + size)
        while (pos + 8 <= end) {
            val csize = b.getInt(pos + 4)
            if (csize < 8) break
            if (u16(b, pos) == 0x0201) {
                try { parseType(b, pos, global, typeNames, keyNames, out) } catch (_: Exception) { }
            }
            pos += csize
        }
    }

    private fun parseType(
        b: ByteBuffer, start: Int, global: List<String>,
        typeNames: List<String>, keyNames: List<String>, out: MutableList<ArscEntry>,
    ) {
        val headerSize = u16(b, start + 2)
        val typeId = b.get(start + 8).toInt() and 0xFF
        // flags is a uint16 at offset 10 (offset 9 is the reserved res0 byte).
        val sparse = (u16(b, start + 10) and 1) != 0
        val count = b.getInt(start + 12)
        val entriesStart = b.getInt(start + 16)
        val config = configLabel(b, start, headerSize)

        val typeName = typeNames.getOrElse(typeId - 1) { "type$typeId" }
        for (i in 0 until count) {
            val raw = b.getInt(start + headerSize + i * 4)
            val off: Int
            if (sparse) {
                off = ((raw ushr 16) and 0xFFFF) * 4
            } else {
                if (raw == -1) continue
                off = raw
            }
            val e = start + entriesStart + off
            val flags = u16(b, e + 2)
            if ((flags and 0x8) != 0) continue // compact entries aren't supported
            val key = keyNames.getOrElse(b.getInt(e + 4)) { "?" }
            if ((flags and 0x1) != 0) {
                // Complex (bag) entries: listed for reference, not editable.
                out += ArscEntry(typeName, key, -1, b.getInt(e + 12), -1, config, complex = true)
                continue
            }
            out += ArscEntry(
                type = typeName,
                key = key,
                valueType = b.get(e + 11).toInt() and 0xFF,
                data = b.getInt(e + 12),
                dataOffset = e + 12,
                config = config,
            )
        }
    }

    /** A short label for a type chunk's configuration (locale, orientation, density, SDK).
     *  The config struct starts at [start] + 20: language at +8, country at +10, orientation
     *  at +12, density at +14, SDK version at +24 (all relative to the struct). */
    private fun configLabel(b: ByteBuffer, start: Int, headerSize: Int): String {
        val cfgSize = minOf(b.getInt(start + 20), headerSize - 20)
        val parts = ArrayList<String>()
        if (cfgSize >= 10) {
            val lang0 = b.get(start + 28).toInt() and 0xFF
            val lang1 = b.get(start + 29).toInt() and 0xFF
            if (lang0 != 0) {
                var lang = String(byteArrayOf(lang0.toByte(), lang1.toByte()), Charsets.UTF_8)
                    .trimEnd('\u0000')
                if (cfgSize >= 12) {
                    val c0 = b.get(start + 30).toInt() and 0xFF
                    val c1 = b.get(start + 31).toInt() and 0xFF
                    if (c0 != 0) {
                        lang += "-r" + String(byteArrayOf(c0.toByte(), c1.toByte()), Charsets.UTF_8)
                            .trimEnd('\u0000')
                    }
                }
                parts += lang
            }
        }
        if (cfgSize >= 13) {
            when (b.get(start + 32).toInt() and 0xFF) {
                1 -> parts += "port"
                2 -> parts += "land"
            }
        }
        if (cfgSize >= 16) {
            val density = u16(b, start + 34)
            if (density != 0) {
                parts += when (density) {
                    120 -> "ldpi"
                    160 -> "mdpi"
                    240 -> "hdpi"
                    320 -> "xhdpi"
                    480 -> "xxhdpi"
                    640 -> "xxxhdpi"
                    else -> "${density}dpi"
                }
            }
        }
        if (cfgSize >= 26) {
            val sdk = u16(b, start + 44)
            if (sdk != 0) parts += "v$sdk"
        }
        return if (parts.isEmpty()) "default" else parts.joinToString("-")
    }

    /** The text shown (and typed) for a value of [type] with raw [data]. */
    fun format(type: Int, data: Int, global: List<String>): String = when (type) {
        0x03 -> global.getOrElse(data) { "" }.replace("\n", "\\n").take(200)
        0x12 -> if (data != 0) "true" else "false"
        0x10 -> data.toString()
        0x11 -> "0x%x".format(data)
        0x01 -> "@0x%08x".format(data)
        0x02 -> "?0x%08x".format(data)
        0x04 -> java.lang.Float.intBitsToFloat(data).toString()
        in 0x1c..0x1f -> "#%08x".format(data)
        else -> "0x%x".format(data)
    }

    private fun u16(b: ByteBuffer, off: Int) = b.getShort(off).toInt() and 0xFFFF
}
