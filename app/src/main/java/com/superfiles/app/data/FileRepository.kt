package com.superfiles.app.data

import java.io.File

enum class SortMode { NAME, SIZE, DATE }

class FileItem(val file: File) {
    val name: String = file.name
    val path: String = file.absolutePath
    val isDir: Boolean = file.isDirectory
    val size: Long = if (isDir) 0L else file.length()
    val modified: Long = file.lastModified()
}

object FileRepository {

    fun list(dir: File, showHidden: Boolean, sort: SortMode): List<FileItem> {
        val children = dir.listFiles() ?: return emptyList()
        val items = children
            .filter { showHidden || !it.name.startsWith(".") }
            .map { FileItem(it) }
        val order: Comparator<FileItem> = when (sort) {
            SortMode.NAME -> compareBy<FileItem> { it.name.lowercase() }
            SortMode.SIZE -> compareByDescending<FileItem> { it.size }
            SortMode.DATE -> compareByDescending<FileItem> { it.modified }
        }
        return items.sortedWith(compareByDescending<FileItem> { it.isDir }.then(order))
    }

    fun search(root: File, query: String, showHidden: Boolean, limit: Int = 500): List<FileItem> =
        root.walkTopDown()
            .onEnter { showHidden || !it.name.startsWith(".") }
            .filter { it != root && it.name.contains(query, ignoreCase = true) }
            .take(limit)
            .map { FileItem(it) }
            .toList()

    fun createFolder(parent: File, name: String) {
        validateName(name)
        val target = File(parent, name)
        require(!target.exists()) { "\"$name\" already exists" }
        check(target.mkdirs()) { "Couldn't create folder" }
    }

    fun rename(file: File, newName: String) {
        validateName(newName)
        val target = File(file.parentFile, newName)
        require(!target.exists()) { "\"$newName\" already exists" }
        check(file.renameTo(target)) { "Couldn't rename" }
    }

    fun copy(src: File, destDir: File) {
        guardSelfNesting(src, destDir)
        src.copyRecursively(uniqueTarget(destDir, src.name), overwrite = false)
    }

    fun move(src: File, destDir: File) {
        if (src.parentFile?.canonicalPath == destDir.canonicalPath) return
        guardSelfNesting(src, destDir)
        val target = uniqueTarget(destDir, src.name)
        if (!src.renameTo(target)) {
            src.copyRecursively(target, overwrite = false)
            check(src.deleteRecursively()) { "Copied, but couldn't remove the original" }
        }
    }

    fun delete(file: File) {
        check(file.deleteRecursively()) { "Couldn't delete ${file.name}" }
    }

    private fun validateName(name: String) {
        require(name.isNotBlank()) { "Name can't be empty" }
        require('/' !in name) { "Name can't contain /" }
    }

    private fun guardSelfNesting(src: File, destDir: File) {
        if (!src.isDirectory) return
        val s = src.canonicalPath
        val d = destDir.canonicalPath
        require(d != s && !d.startsWith(s + File.separator)) { "Can't put a folder inside itself" }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.', name).ifEmpty { name }
        val ext = if (base.length < name.length) name.substring(base.length) else ""
        var n = 1
        while (target.exists()) {
            target = File(dir, "$base ($n)$ext")
            n++
        }
        return target
    }
}
