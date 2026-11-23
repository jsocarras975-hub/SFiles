package com.superfiles.app.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SortMode { NAME, SIZE, DATE }

class FileItem(val file: File) {
    val name: String = file.name
    val path: String = file.absolutePath
    val isDir: Boolean = file.isDirectory
    val size: Long = if (isDir) 0L else file.length()
    /** Number of direct children for folders (null when the folder can't be read). */
    val childCount: Int? = if (isDir) file.list()?.size else null
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

    /** Recursive search. Cancels promptly when the calling coroutine is cancelled. */
    suspend fun search(root: File, query: String, showHidden: Boolean, limit: Int = 500): List<FileItem> {
        val job = currentCoroutineContext()[Job]
        return root.walkTopDown()
            .onEnter { job?.ensureActive(); showHidden || !it.name.startsWith(".") }
            .filter { it != root && it.name.contains(query, ignoreCase = true) }
            .take(limit)
            .map { FileItem(it) }
            .toList()
    }

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

    /** [onProgress] gets (bytes done, total bytes, top-level name) as the copy proceeds. */
    fun copy(src: File, destDir: File, onProgress: ((Long, Long, String) -> Unit)? = null) {
        guardSelfNesting(src, destDir)
        val target = uniqueTarget(destDir, src.name)
        val total = totalBytes(src)
        var done = 0L
        copyRecursive(src, target) { n -> done += n; onProgress?.invoke(done, total, src.name) }
    }

    fun move(src: File, destDir: File, onProgress: ((Long, Long, String) -> Unit)? = null) {
        if (src.parentFile?.canonicalPath == destDir.canonicalPath) return
        guardSelfNesting(src, destDir)
        val target = uniqueTarget(destDir, src.name)
        if (src.renameTo(target)) {
            val total = totalBytes(target)
            onProgress?.invoke(total, total, src.name)
            return
        }
        val total = totalBytes(src)
        var done = 0L
        copyRecursive(src, target) { n -> done += n; onProgress?.invoke(done, total, src.name) }
        check(src.deleteRecursively()) { "Copied, but couldn't remove the original" }
    }

    private fun totalBytes(f: File): Long =
        if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun copyRecursive(src: File, target: File, onBytes: (Long) -> Unit) {
        if (src.isDirectory) {
            target.mkdirs()
            src.listFiles()?.forEach { child -> copyRecursive(child, File(target, child.name), onBytes) }
        } else {
            target.parentFile?.mkdirs()
            src.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        }
    }

    /** Zips [files] into a new archive.zip in [destDir]. */
    fun zipFiles(files: List<File>, destDir: File): File {
        val dest = uniqueTarget(destDir, "archive.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zout ->
            files.forEach { f ->
                if (f.isFile) {
                    zout.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zout) }
                    zout.closeEntry()
                } else {
                    f.walkTopDown().filter { it.isFile }.forEach { file ->
                        zout.putNextEntry(ZipEntry("${f.name}/${file.relativeTo(f).path}"))
                        file.inputStream().use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                }
            }
        }
        return dest
    }

    /** Extracts [zip] into a new folder next to it. Refuses entries that escape the folder. */
    fun unzip(zip: File, destDir: File): File {
        val target = uniqueTarget(destDir, zip.nameWithoutExtension).apply { mkdirs() }
        ZipFile(zip).use { z ->
            z.entries().asSequence().forEach { e ->
                val out = File(target, e.name)
                require(out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    "Bad zip entry: ${e.name}"
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { it.copyTo(out.outputStream()) }
                }
            }
        }
        return target
    }

    /** Hex digest of a file ("MD5" or "SHA-256"). */
    fun fileHash(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Total size of a file or folder, recursively. */
    fun folderSize(file: File): Long =
        if (file.isFile) file.length()
        else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // --- Trash ---------------------------------------------------------------------------
    // Deleted items are moved to a hidden ".Trash" folder on the same storage so the move is a
    // fast rename, and their original folder is remembered so they can be restored.

    fun trashDir(root: File): File = File(root, ".Trash").apply { if (!exists()) mkdirs() }

    fun trash(root: File, file: File) {
        val dir = trashDir(root)
        require(file.canonicalPath != dir.canonicalPath) { "Can't trash the Trash folder" }
        val target = uniqueTarget(dir, file.name)
        if (!file.renameTo(target)) {
            file.copyRecursively(target, overwrite = false)
            check(file.deleteRecursively()) { "Couldn't move ${file.name} to trash" }
        }
        TrashMeta.put(dir, target.name, file.parentFile?.absolutePath ?: root.absolutePath)
    }

    fun listTrash(root: File): List<FileItem> {
        val dir = trashDir(root)
        val children = dir.listFiles() ?: return emptyList()
        return children.filter { it.name != TrashMeta.FILE }
            .map { FileItem(it) }
            .sortedByDescending { it.modified }
    }

    fun restore(root: File, file: File) {
        val dir = trashDir(root)
        val destDir = TrashMeta.get(dir, file.name)?.let { File(it) }?.takeIf { it.isDirectory } ?: root
        val target = uniqueTarget(destDir, file.name)
        if (!file.renameTo(target)) {
            file.copyRecursively(target, overwrite = false)
            check(file.deleteRecursively()) { "Couldn't restore ${file.name}" }
        }
        TrashMeta.remove(dir, file.name)
    }

    fun deleteForever(file: File) {
        check(file.deleteRecursively()) { "Couldn't delete ${file.name}" }
    }

    fun emptyTrash(root: File) {
        val dir = trashDir(root)
        dir.listFiles()?.forEach { if (it.name != TrashMeta.FILE) it.deleteRecursively() }
        TrashMeta.clear(dir)
    }

    /** Deletes trashed items older than [maxAgeDays] (default 30). */
    fun cleanOldTrash(root: File, maxAgeDays: Int = 30) {
        val dir = trashDir(root)
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach {
            if (it.name != TrashMeta.FILE && it.lastModified() < cutoff) {
                it.deleteRecursively()
                TrashMeta.remove(dir, it.name)
            }
        }
    }

    /** Original folders of trashed items, kept in a properties file inside the trash folder. */
    private object TrashMeta {
        const val FILE = ".trash-meta"

        private fun load(dir: File): Properties =
            Properties().apply {
                File(dir, FILE).takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }

        private fun save(dir: File, props: Properties) {
            File(dir, FILE).outputStream().use { props.store(it, null) }
        }

        fun put(dir: File, name: String, parent: String) {
            val props = load(dir).apply { setProperty(name, parent) }
            save(dir, props)
        }

        fun get(dir: File, name: String): String? = load(dir).getProperty(name)

        fun remove(dir: File, name: String) {
            val props = load(dir).apply { remove(name) }
            save(dir, props)
        }

        fun clear(dir: File) {
            File(dir, FILE).delete()
        }
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
