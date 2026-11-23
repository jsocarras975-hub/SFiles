package com.superfiles.app.data

import java.io.File

/** Resource edits waiting for the next "Build and sign", kept in memory per APK. */
object ResourceStaging {
    private val byApk = HashMap<String, LinkedHashMap<String, String>>()

    fun put(apk: File, id: String, value: String) {
        byApk.getOrPut(apk.absolutePath) { LinkedHashMap() }[id] = value
    }

    fun has(apk: File, id: String) = byApk[apk.absolutePath]?.containsKey(id) == true
    fun list(apk: File): Map<String, String> = byApk[apk.absolutePath]?.toMap().orEmpty()
    fun count(apk: File) = byApk[apk.absolutePath]?.size ?: 0
    fun clear(apk: File) { byApk.remove(apk.absolutePath) }
}
