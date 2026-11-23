package com.superfiles.app.data

import java.io.File

/** String-attribute edits for binary XML files, waiting for the next "Build and sign".
 *  APK path -> zip entry name -> attribute id ("element/path@attrName") -> new value. */
object XmlStaging {
    private val byApk = HashMap<String, LinkedHashMap<String, LinkedHashMap<String, String>>>()

    fun put(apk: File, entry: String, id: String, value: String) {
        byApk.getOrPut(apk.absolutePath) { LinkedHashMap() }
            .getOrPut(entry) { LinkedHashMap() }[id] = value
    }

    fun list(apk: File): Map<String, Map<String, String>> =
        byApk[apk.absolutePath]?.mapValues { it.value.toMap() }.orEmpty()

    fun count(apk: File): Int = byApk[apk.absolutePath]?.values?.sumOf { it.size } ?: 0
    fun has(apk: File, entry: String, id: String) =
        byApk[apk.absolutePath]?.get(entry)?.containsKey(id) == true
    fun clear(apk: File) { byApk.remove(apk.absolutePath) }
}
