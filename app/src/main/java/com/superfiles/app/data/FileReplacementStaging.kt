package com.superfiles.app.data

import android.content.Context
import java.io.File

/** Replacement files waiting for the next "Build and sign": APK path -> zip entry name -> cached file. */
object FileReplacementStaging {
    private val byApk = HashMap<String, LinkedHashMap<String, File>>()

    /** Copies [bytes] into the cache and stages them as the replacement for [entryName]. */
    fun put(ctx: Context, apk: File, entryName: String, bytes: ByteArray): File {
        val dir = File(ctx.cacheDir, "replacements").apply { mkdirs() }
        val f = File(dir, "${apk.absolutePath.hashCode()}_${entryName.replace('/', '_')}")
        f.writeBytes(bytes)
        byApk.getOrPut(apk.absolutePath) { LinkedHashMap() }[entryName] = f
        return f
    }

    fun list(apk: File): Map<String, File> = byApk[apk.absolutePath]?.toMap().orEmpty()
    fun has(apk: File, entryName: String) = byApk[apk.absolutePath]?.containsKey(entryName) == true
    fun count(apk: File) = byApk[apk.absolutePath]?.size ?: 0
    fun clear(apk: File) { byApk.remove(apk.absolutePath) }
}
