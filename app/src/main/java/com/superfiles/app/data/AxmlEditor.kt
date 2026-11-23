package com.superfiles.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ManifestEdits(
    val newPackage: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val removePermissions: Set<String> = emptySet(),
    val addPermissions: List<String> = emptyList(),
)

/** One string-valued attribute of a binary XML file: "element/path@attrName" to a value. */
data class XmlAttr(val id: String, val elementPath: String, val name: String, val value: String)

/**
 * Edits binary Android XML in place. New values are appended to the string pool, so
 * existing entries are never mutated and resource ids stay valid.
 */
object AxmlEditor {

    private const val T_POOL = 0x0001
    private const val T_START_EL = 0x0102
    private const val T_END_EL = 0x0103
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val CLASS_ELEMENTS = setOf(
        "application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation",
    )
    private val CLASS_ATTRS = listOf(
        "name", "targetActivity", "manageSpaceActivity", "backupAgent", "parentActivityName",
    )
    private val PERM_ELEMENTS = setOf(
        "permission", "permission-group", "permission-tree", "uses-permission", "uses-permission-sdk-23",
    )

    private data class Split(val pool: List<String>, val utf8: Boolean, val chunks: List<ByteArray>)

    /** Splits a binary XML file into its string pool and the remaining chunks. */
    private fun split(data: ByteArray): Split {
        val src = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(data.size >= 8 && u16(src, 0) == 0x0003) { "Not binary XML" }
        var strings: List<String>? = null
        var utf8 = false
        val chunks = ArrayList<ByteArray>()
        var pos = u16(src, 2)
        val end = minOf(data.size, src.getInt(4))
        while (pos + 8 <= end) {
            val type = u16(src, pos)
            val size = src.getInt(pos + 4)
            if (size < 8 || pos + size > data.size) break
            if (type == T_POOL) {
                check(src.getInt(pos + 12) == 0) { "XML has styled strings, which aren't supported" }
                utf8 = (src.getInt(pos + 16) and 0x100) != 0
                strings = AxmlDecoder.readPool(src, pos)
            } else {
                chunks += data.copyOfRange(pos, pos + size)
            }
            pos += size
        }
        return Split(strings ?: error("No string pool in XML"), utf8, chunks)
    }

    /** Reassembles a binary XML file from a (possibly extended) pool and chunks. */
    private fun join(pool: List<String>, utf8: Boolean, chunks: List<ByteArray>): ByteArray {
        val poolBytes = AxmlDecoder.buildPool(pool, utf8)
        val total = 8 + poolBytes.size + chunks.sumOf { it.size }
        val res = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        res.putShort(0x0003)
        res.putShort(8)
        res.putInt(total)
        res.put(poolBytes)
        chunks.forEach { res.put(it) }
        return res.array()
    }

    fun edit(data: ByteArray, e: ManifestEdits): ByteArray {
        val (pool0, utf8, chunks) = split(data)
        val pool = pool0.toMutableList()

        fun str(i: Int) = pool.getOrElse(i) { "" }
        fun intern(s: String): Int {
            val i = pool.indexOf(s)
            if (i >= 0) return i
            pool.add(s)
            return pool.lastIndex
        }

        var oldPkg = ""
        var renaming = false
        fun swapPrefix(v: String): String {
            val np = e.newPackage ?: return v
            return if (v == oldPkg || v.startsWith("$oldPkg.")) np + v.removePrefix(oldPkg) else v
        }
        fun qualify(v: String): String = when {
            v.startsWith(".") -> oldPkg + v
            !v.contains('.') -> "$oldPkg.$v"
            else -> v
        }

        val out = ArrayList<ByteArray>()
        var skip = 0
        var manifestStartIndex = -1
        val declaredPerms = HashSet<String>()
        for (chunk in chunks) {
            val b = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            when (u16(b, 0)) {
                T_START_EL -> {
                    if (skip > 0) { skip++; continue }
                    val el = str(b.getInt(20))
                    val attrBase = 16 + u16(b, 24)
                    val attrSize = u16(b, 26)
                    val attrs = (0 until u16(b, 28)).map { attrBase + it * attrSize }

                    fun find(name: String, android: Boolean): Int? = attrs.firstOrNull { a ->
                        str(b.getInt(a + 4)) == name && (str(b.getInt(a)) == ANDROID_NS) == android
                    }
                    fun value(a: Int): String? {
                        val raw = b.getInt(a + 8)
                        return when {
                            raw != -1 -> str(raw)
                            (b.get(a + 15).toInt() and 0xFF) == 0x03 -> str(b.getInt(a + 16))
                            else -> null
                        }
                    }
                    fun setStr(a: Int, s: String) {
                        val i = intern(s)
                        b.putInt(a + 8, i)
                        b.put(a + 15, 0x03)
                        b.putInt(a + 16, i)
                    }

                    if (el == "uses-permission" || el == "uses-permission-sdk-23") {
                        val name = find("name", true)?.let { value(it) }
                        if (name != null) declaredPerms += name
                        if (name != null && name in e.removePermissions) { skip = 1; continue }
                    }

                    if (el == "manifest") {
                        manifestStartIndex = out.size
                        find("package", false)?.let { a ->
                            oldPkg = value(a).orEmpty()
                            if (e.newPackage != null && e.newPackage != oldPkg) {
                                renaming = oldPkg.isNotEmpty()
                                setStr(a, e.newPackage)
                            }
                        }
                        e.versionCode?.let { v ->
                            find("versionCode", true)?.let { a ->
                                b.putInt(a + 8, -1)
                                b.put(a + 15, 0x10)
                                b.putInt(a + 16, v.toInt())
                            }
                        }
                        e.versionName?.let { v -> find("versionName", true)?.let { setStr(it, v) } }
                    }

                    if (renaming) {
                        if (el in CLASS_ELEMENTS) {
                            // Relative class names resolve against the package, so pin them to the old one.
                            for (n in CLASS_ATTRS) find(n, true)?.let { a -> value(a)?.let { setStr(a, qualify(it)) } }
                            for (n in listOf("permission", "process", "taskAffinity")) {
                                find(n, true)?.let { a -> value(a)?.let { setStr(a, swapPrefix(it)) } }
                            }
                        }
                        if (el == "provider") {
                            find("authorities", true)?.let { a ->
                                value(a)?.let { v -> setStr(a, v.split(";").joinToString(";") { swapPrefix(it) }) }
                            }
                        }
                        if (el in PERM_ELEMENTS) {
                            find("name", true)?.let { a -> value(a)?.let { setStr(a, swapPrefix(it)) } }
                        }
                    }
                    out += chunk
                }
                T_END_EL -> {
                    if (skip > 0) { skip--; continue }
                    out += chunk
                }
                else -> if (skip == 0) out += chunk
            }
        }

        // New <uses-permission> elements go right after the <manifest> start tag.
        if (e.addPermissions.isNotEmpty() && manifestStartIndex >= 0) {
            val extra = ArrayList<ByteArray>()
            e.addPermissions.distinct().filter { it !in declaredPerms }.forEach { perm ->
                extra += usesPermissionChunks(perm, ::intern)
            }
            out.addAll(manifestStartIndex + 1, extra)
        }

        return join(pool, utf8, out)
    }

    /** A <uses-permission android:name="..."/> element as start + end chunks. */
    private fun usesPermissionChunks(perm: String, intern: (String) -> Int): List<ByteArray> {
        val elIdx = intern("uses-permission")
        val nsIdx = intern(ANDROID_NS)
        val nameIdx = intern("name")
        val permIdx = intern(perm)
        val start = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        start.putShort(T_START_EL.toShort())
        start.putShort(16)
        start.putInt(56)
        start.putInt(0) // lineNumber
        start.putInt(-1) // comment
        start.putInt(-1) // ns
        start.putInt(elIdx) // name
        start.putShort(20) // attributeStart
        start.putShort(20) // attributeSize
        start.putShort(1) // attributeCount
        start.putShort((-1).toShort()) // idIndex
        start.putShort((-1).toShort()) // classIndex
        start.putShort((-1).toShort()) // styleIndex
        start.putInt(nsIdx)
        start.putInt(nameIdx)
        start.putInt(-1) // raw
        start.putShort(8) // typed value size
        start.put(0) // res0
        start.put(0x03.toByte()) // dataType STRING
        start.putInt(permIdx)
        val end = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        end.putShort(T_END_EL.toShort())
        end.putShort(16)
        end.putInt(24)
        end.putInt(0)
        end.putInt(-1)
        end.putInt(-1)
        end.putInt(elIdx)
        return listOf(start.array(), end.array())
    }

    /** Lists the string-valued attributes of any binary XML file, for editing. */
    fun listStringAttrs(data: ByteArray): List<XmlAttr> {
        val (pool, _, chunks) = split(data)
        fun str(i: Int) = pool.getOrElse(i) { "" }
        val out = ArrayList<XmlAttr>()
        val stack = ArrayList<String>()
        for (chunk in chunks) {
            val b = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            when (u16(b, 0)) {
                T_START_EL -> {
                    val el = str(b.getInt(20))
                    stack += el
                    val path = stack.joinToString("/")
                    val attrBase = 16 + u16(b, 24)
                    val attrSize = u16(b, 26)
                    val count = u16(b, 28)
                    for (i in 0 until count) {
                        val a = attrBase + i * attrSize
                        if ((b.get(a + 15).toInt() and 0xFF) != 0x03) continue
                        val ns = str(b.getInt(a))
                        val name = str(b.getInt(a + 4))
                        val display = if (ns == ANDROID_NS) "android:$name" else name
                        val raw = b.getInt(a + 8)
                        val value = if (raw != -1) str(raw) else str(b.getInt(a + 16))
                        out += XmlAttr("$path@$display", path, display, value)
                    }
                }
                T_END_EL -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            }
        }
        return out
    }

    /** Rewrites the string attributes in [edits] ("element/path@attrName" to new value). */
    fun editAny(data: ByteArray, edits: Map<String, String>): ByteArray {
        val (pool0, utf8, chunks) = split(data)
        val pool = pool0.toMutableList()
        fun str(i: Int) = pool.getOrElse(i) { "" }
        fun intern(s: String): Int {
            val i = pool.indexOf(s)
            if (i >= 0) return i
            pool.add(s)
            return pool.lastIndex
        }
        val out = ArrayList<ByteArray>()
        val stack = ArrayList<String>()
        for (chunk in chunks) {
            val b = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            when (u16(b, 0)) {
                T_START_EL -> {
                    val el = str(b.getInt(20))
                    stack += el
                    val path = stack.joinToString("/")
                    val attrBase = 16 + u16(b, 24)
                    val attrSize = u16(b, 26)
                    val count = u16(b, 28)
                    for (i in 0 until count) {
                        val a = attrBase + i * attrSize
                        if ((b.get(a + 15).toInt() and 0xFF) != 0x03) continue
                        val ns = str(b.getInt(a))
                        val name = str(b.getInt(a + 4))
                        val display = if (ns == ANDROID_NS) "android:$name" else name
                        val newVal = edits["$path@$display"] ?: continue
                        val idx = intern(newVal)
                        b.putInt(a + 8, idx)
                        b.put(a + 15, 0x03)
                        b.putInt(a + 16, idx)
                    }
                    out += chunk
                }
                T_END_EL -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    out += chunk
                }
                else -> out += chunk
            }
        }
        return join(pool, utf8, out)
    }

    private fun u16(b: ByteBuffer, off: Int) = b.getShort(off).toInt() and 0xFFFF
}
