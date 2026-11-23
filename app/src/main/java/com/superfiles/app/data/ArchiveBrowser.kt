package com.superfiles.app.data

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Opens archives like folders: list, extract, and (except RAR) add or remove
 * entries without unpacking the whole archive first. RAR is read-only — the
 * format has no free writer.
 */
object ArchiveBrowser {

    data class ArchiveEntry(val name: String, val size: Long, val isDir: Boolean)

    fun isArchive(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("zip", "rar", "7z", "tar", "tgz")
    }

    /** True for formats that support adding/removing entries. */
    fun isEditable(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("zip", "tar", "tgz", "7z")
    }

    fun listEntries(archive: File): List<ArchiveEntry> {
        return when (archive.extension.lowercase()) {
            "zip" -> ZipFile(archive).use { z ->
                z.entries().asSequence().map {
                    ArchiveEntry(it.name, it.size, it.isDirectory)
                }.toList()
            }
            "tar", "tgz" -> openTar(archive).use { tin ->
                val out = ArrayList<ArchiveEntry>()
                var e = tin.nextEntry
                while (e != null) {
                    out += ArchiveEntry(e.name, e.size, e.isDirectory)
                    e = tin.nextEntry
                }
                out
            }
            "7z" -> SevenZFile.builder().setFile(archive).get().use { sz ->
                val out = ArrayList<ArchiveEntry>()
                var e = sz.nextEntry
                while (e != null) {
                    out += ArchiveEntry(e.name, e.size, e.isDirectory)
                    e = sz.nextEntry
                }
                out
            }
            "rar" -> Archive(FileInputStream(archive)).use { rar ->
                rar.fileHeaders.map {
                    ArchiveEntry(it.fileName, it.fullUnpackSize, it.isDirectory)
                }
            }
            else -> error("Not a supported archive: ${archive.name}")
        }
    }

    /** Extracts one entry into [destDir], keeping its path inside the archive. */
    fun extractEntry(archive: File, entryName: String, destDir: File): File {
        val out = File(destDir, entryName)
        out.parentFile?.mkdirs()
        when (archive.extension.lowercase()) {
            "zip" -> ZipFile(archive).use { z ->
                val e = z.getEntry(entryName) ?: error("Entry not found: $entryName")
                z.getInputStream(e).use { input ->
                    FileOutputStream(out).use { input.copyTo(it) }
                }
            }
            "tar", "tgz" -> openTar(archive).use { tin ->
                var e = tin.nextEntry
                while (e != null) {
                    if (e.name == entryName) {
                        FileOutputStream(out).use { tin.copyTo(it) }
                        return out
                    }
                    e = tin.nextEntry
                }
                error("Entry not found: $entryName")
            }
            "7z" -> SevenZFile.builder().setFile(archive).get().use { sz ->
                var e = sz.nextEntry
                while (e != null) {
                    if (e.name == entryName) {
                        FileOutputStream(out).use { o ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (sz.read(buf).also { n = it } > 0) o.write(buf, 0, n)
                        }
                        return out
                    }
                    e = sz.nextEntry
                }
                error("Entry not found: $entryName")
            }
            "rar" -> Archive(FileInputStream(archive)).use { rar ->
                for (h in rar.fileHeaders) {
                    if (h.fileName == entryName) {
                        FileOutputStream(out).use { rar.extractFile(h, it) }
                        return out
                    }
                }
                error("Entry not found: $entryName")
            }
            else -> error("Not a supported archive: ${archive.name}")
        }
        return out
    }

    /** Adds (or replaces) [file] at the top level of the archive. */
    fun addFile(archive: File, file: File) {
        when (archive.extension.lowercase()) {
            "zip" -> {
                val tmp = File(archive.parentFile, archive.name + ".tmp")
                ZipFile(archive).use { zin ->
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zout ->
                        for (e in zin.entries().asSequence()) {
                            if (e.name == file.name) continue
                            zout.putNextEntry(ZipEntry(e.name))
                            zin.getInputStream(e).use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                        zout.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                }
                tmp.renameTo(archive)
            }
            "tar", "tgz" -> {
                val tmp = File(archive.parentFile, archive.name + ".tmp")
                openTar(archive).use { tin ->
                    TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { tout ->
                        var e = tin.nextEntry
                        while (e != null) {
                            if (e.name != file.name) {
                                tout.putArchiveEntry(TarArchiveEntry(e.name).apply { size = e.size })
                                tin.copyTo(tout)
                                tout.closeArchiveEntry()
                            }
                            e = tin.nextEntry
                        }
                        val entry = TarArchiveEntry(file.name).apply { size = file.length() }
                        tout.putArchiveEntry(entry)
                        file.inputStream().use { it.copyTo(tout) }
                        tout.closeArchiveEntry()
                    }
                }
                tmp.renameTo(archive)
            }
            "7z" -> {
                val tmp = File(archive.parentFile, archive.name + ".tmp")
                SevenZFile.builder().setFile(archive).get().use { sz ->
                    SevenZOutputFile(tmp).use { out ->
                        var e = sz.nextEntry
                        while (e != null) {
                            if (e.name != file.name) {
                                val copy = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
                                copy.name = e.name
                                copy.isDirectory = e.isDirectory
                                copy.size = e.size
                                out.putArchiveEntry(copy)
                                if (!e.isDirectory) {
                                    val buf = ByteArray(64 * 1024)
                                    var n: Int
                                    while (sz.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                                }
                                out.closeArchiveEntry()
                            }
                            e = sz.nextEntry
                        }
                        val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
                        entry.name = file.name
                        entry.size = file.length()
                        out.putArchiveEntry(entry)
                        file.inputStream().use { input -> out.write(input) }
                        out.closeArchiveEntry()
                    }
                }
                tmp.renameTo(archive)
            }
            else -> error("Adding files isn't supported for ${archive.extension} archives")
        }
    }

    /** Removes one entry from the archive. */
    fun removeEntry(archive: File, entryName: String) {
        when (archive.extension.lowercase()) {
            "zip" -> {
                val tmp = File(archive.parentFile, archive.name + ".tmp")
                ZipFile(archive).use { zin ->
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zout ->
                        for (e in zin.entries().asSequence()) {
                            if (e.name == entryName) continue
                            zout.putNextEntry(ZipEntry(e.name))
                            zin.getInputStream(e).use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                    }
                }
                tmp.renameTo(archive)
            }
            "tar", "tgz" -> {
                val tmp = File(archive.parentFile, archive.name + ".tmp")
                openTar(archive).use { tin ->
                    TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { tout ->
                        var e = tin.nextEntry
                        while (e != null) {
                            if (e.name != entryName) {
                                tout.putArchiveEntry(TarArchiveEntry(e.name).apply { size = e.size })
                                tin.copyTo(tout)
                                tout.closeArchiveEntry()
                            }
                            e = tin.nextEntry
                        }
                    }
                }
                tmp.renameTo(archive)
            }
            "7z" -> {
                val tmp = File(archive.parentFile, archive.name + ".tmp")
                SevenZFile.builder().setFile(archive).get().use { sz ->
                    SevenZOutputFile(tmp).use { out ->
                        var e = sz.nextEntry
                        while (e != null) {
                            if (e.name != entryName) {
                                val copy = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
                                copy.name = e.name
                                copy.isDirectory = e.isDirectory
                                copy.size = e.size
                                out.putArchiveEntry(copy)
                                if (!e.isDirectory) {
                                    val buf = ByteArray(64 * 1024)
                                    var n: Int
                                    while (sz.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                                }
                                out.closeArchiveEntry()
                            }
                            e = sz.nextEntry
                        }
                    }
                }
                tmp.renameTo(archive)
            }
            else -> error("Removing entries isn't supported for ${archive.extension} archives")
        }
    }

    private fun openTar(archive: File): TarArchiveInputStream {
        val raw = BufferedInputStream(FileInputStream(archive))
        val input = if (archive.extension.lowercase() == "tgz") GzipCompressorInputStream(raw) else raw
        return TarArchiveInputStream(input)
    }
}
