package com.superfiles.app.data

import java.io.File
import java.util.zip.ZipFile

sealed interface SearchHit {
    val title: String
    val snippet: String
    data class Manifest(override val snippet: String) : SearchHit {
        override val title = "Manifest"
    }
    data class DexClass(val ref: SmaliTools.ClassRef, override val snippet: String) : SearchHit {
        override val title = "Class ${ref.display}"
    }
    data class DexString(val dex: String, val count: Int, override val snippet: String) : SearchHit {
        override val title = "String in $dex"
    }
    data class Resource(override val title: String) : SearchHit {
        override val snippet = ""
    }
    data class FileEntry(override val title: String) : SearchHit {
        override val snippet = ""
    }
}

/** Searches one string across the manifest, dex files, resources and file names of an APK. */
object ApkSearch {
    fun search(apk: File, query: String, limit: Int = 200): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<SearchHit>()
        ZipFile(apk).use { z ->
            z.getEntry("AndroidManifest.xml")?.let { e ->
                runCatching { AxmlDecoder.decode(z.getInputStream(e).readBytes()) }.getOrNull()
                    ?.lines()?.filter { it.contains(q, ignoreCase = true) }?.take(20)
                    ?.forEach { out += SearchHit.Manifest(it.trim()) }
            }
            val dexRegex = Regex("classes\\d*\\.dex")
            z.entries().asSequence().filter { dexRegex.matches(it.name) }.forEach { e ->
                val bytes = z.getInputStream(e).readBytes()
                val utf8 = countBytes(bytes, q.toByteArray(Charsets.UTF_8))
                val utf16 = countBytes(bytes, q.toByteArray(Charsets.UTF_16LE))
                val n = utf8 + utf16
                if (n > 0) out += SearchHit.DexString(e.name, n, "$n occurrence(s) of \"$q\"")
            }
        }
        runCatching { SmaliTools.classRefs(apk) }.getOrNull()
            ?.filter { it.display.contains(q, ignoreCase = true) }?.take(20)
            ?.forEach { out += SearchHit.DexClass(it, it.display) }
        runCatching { ResourceTable.entries(apk) }.getOrNull()
            ?.filter { it.display.contains(q, ignoreCase = true) }?.take(20)
            ?.forEach { out += SearchHit.Resource(it.display) }
        runCatching { ApkInspector.entries(apk) }.getOrNull()
            ?.filter { it.name.contains(q, ignoreCase = true) }?.take(20)
            ?.forEach { out += SearchHit.FileEntry(it.name) }
        return out.take(limit)
    }

    private fun countBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return 0
        var count = 0
        var i = 0
        while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) { count++; i += needle.size } else i++
        }
        return count
    }
}
