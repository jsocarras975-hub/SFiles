package com.superfiles.app.data

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root access through `su`. Everything runs through the su binary, so it only
 * works on rooted devices — [available] checks that first. All paths are
 * single-quoted before they reach the shell, so spaces and quotes can't
 * break out of the command.
 */
object Root {

    data class ShellResult(val stdout: String, val stderr: String, val exit: Int)

    private var cachedAvailable: Boolean? = null

    /** True when `su` works on this device. The result is cached for the session. */
    fun available(): Boolean {
        cachedAvailable?.let { return it }
        val r = run("id", timeoutMs = 5000)
        val ok = r.exit == 0 && r.stdout.contains("uid=0")
        cachedAvailable = ok
        return ok
    }

    fun run(cmd: String, timeoutMs: Long = 10_000): ShellResult {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return ShellResult("", "Timed out", -1)
            }
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            ShellResult(out, err, proc.exitValue())
        } catch (e: Exception) {
            ShellResult("", e.message ?: "Failed to run su", -1)
        }
    }

    private fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private val LS_REGEX =
        Regex("""^([dlbcps-][rwxst-]{9})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d+)\s+(.+)$""")

    /** Lists a directory through `ls -la`, parsing permissions, size, date and name. */
    fun list(dir: File): List<FileItem> {
        val r = run("ls -la --time-style=+%s ${q(dir.absolutePath)}")
        if (r.exit != 0) return emptyList()
        return r.stdout.lines().mapNotNull { line ->
            val m = LS_REGEX.matchEntire(line) ?: return@mapNotNull null
            val perms = m.groupValues[1]
            val size = m.groupValues[2].toLongOrNull() ?: 0L
            val epoch = (m.groupValues[3].toLongOrNull() ?: 0L) * 1000
            var name = m.groupValues[4]
            if (name == "." || name == "..") return@mapNotNull null
            if (perms.startsWith("l")) name = name.substringBefore(" -> ")
            val isDir = perms.startsWith("d")
            FileItem(
                File(dir, name),
                sizeOverride = if (isDir) 0L else size,
                modifiedOverride = epoch,
                isDirOverride = isDir,
                childCountOverride = null,
            )
        }.sortedWith(compareByDescending<FileItem> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Name search under [root] through `find -iname`. Capped at 500 results. */
    fun search(root: File, query: String, limit: Int = 500): List<FileItem> {
        val pattern = "*${query.replace("*", "").replace("?", "")}*"
        val r = run("find ${q(root.absolutePath)} -iname ${q(pattern)} 2>/dev/null | head -n $limit")
        if (r.exit != 0) return emptyList()
        return r.stdout.lines().filter { it.isNotBlank() && it != root.absolutePath }.map { path ->
            val f = File(path)
            // Size/date/type come from a stat call per file; failures fall back to zeros.
            val s = run("stat -c '%s %Y %F' ${q(path)}")
            val parts = s.stdout.trim().split(" ")
            val size = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val epoch = (parts.getOrNull(1)?.toLongOrNull() ?: 0L) * 1000
            val isDir = parts.getOrNull(2) == "directory"
            FileItem(f, sizeOverride = size, modifiedOverride = epoch, isDirOverride = isDir)
        }
    }

    fun copy(src: File, destDir: File) {
        val r = run("cp -r ${q(src.absolutePath)} ${q(destDir.absolutePath)}/")
        check(r.exit == 0) { (r.stderr.ifBlank { "Copy failed" }).trim() }
    }

    fun move(src: File, destDir: File) {
        val r = run("mv ${q(src.absolutePath)} ${q(destDir.absolutePath)}/")
        check(r.exit == 0) { (r.stderr.ifBlank { "Move failed" }).trim() }
    }

    fun delete(file: File) {
        val r = run("rm -rf ${q(file.absolutePath)}")
        check(r.exit == 0) { (r.stderr.ifBlank { "Delete failed" }).trim() }
    }

    fun mkdir(parent: File, name: String) {
        val r = run("mkdir -p ${q(File(parent, name).absolutePath)}")
        check(r.exit == 0) { (r.stderr.ifBlank { "Couldn't create folder" }).trim() }
    }

    fun rename(file: File, newName: String) {
        val target = File(file.parentFile, newName)
        val r = run("mv ${q(file.absolutePath)} ${q(target.absolutePath)}")
        check(r.exit == 0) { (r.stderr.ifBlank { "Rename failed" }).trim() }
    }

    fun chmod(file: File, mode: String) {
        val r = run("chmod $mode ${q(file.absolutePath)}")
        check(r.exit == 0) { (r.stderr.ifBlank { "chmod failed" }).trim() }
    }

    fun chown(file: File, owner: String) {
        val r = run("chown $owner ${q(file.absolutePath)}")
        check(r.exit == 0) { (r.stderr.ifBlank { "chown failed" }).trim() }
    }
}
