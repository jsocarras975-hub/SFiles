package com.superfiles.app.data

import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/** Content search across a folder tree ("grep"). Cancels when the caller's coroutine is cancelled. */
object FileSearch {

    data class Match(val file: File, val lineNo: Int, val line: String)

    private const val MAX_FILE_BYTES = 1024 * 1024
    private const val MAX_RESULTS = 500

    private val BINARY_EXTENSIONS = setOf(
        "apk", "zip", "dex", "arsc", "so", "png", "jpg", "jpeg", "webp", "gif", "mp4", "mkv",
        "webm", "mov", "mp3", "ogg", "ttf", "otf", "pdf", "gz", "tar", "7z", "rar",
    )

    suspend fun search(root: File, query: String, regex: Boolean = false): List<Match> {
        val pattern = if (regex) runCatching { Regex(query) }.getOrNull() ?: return emptyList()
        else null
        val needle = query.lowercase()
        val out = ArrayList<Match>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                coroutineContext.ensureActive()
                if (f.isDirectory) {
                    if (f.name != ".Trash") stack.add(f)
                } else if (f.length() <= MAX_FILE_BYTES && f.extension.lowercase() !in BINARY_EXTENSIONS) {
                    scanFile(f, pattern, needle, out)
                    if (out.size >= MAX_RESULTS) return out
                }
            }
        }
        return out
    }

    private fun scanFile(f: File, pattern: Regex?, needle: String, out: MutableList<Match>) {
        runCatching {
            f.bufferedReader().useLines { lines ->
                var n = 0
                for (line in lines) {
                    n++
                    val hit = if (pattern != null) pattern.containsMatchIn(line)
                    else line.lowercase().contains(needle)
                    if (hit) {
                        out.add(Match(f, n, line.trim().take(200)))
                        if (out.size >= MAX_RESULTS) return
                    }
                }
            }
        }
    }
}
