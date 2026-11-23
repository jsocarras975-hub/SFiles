package com.superfiles.app.ui

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.superfiles.app.data.ApkEditor
import com.superfiles.app.data.ApkInspector
import com.superfiles.app.data.ManifestEdits
import com.superfiles.app.data.FileItem
import com.superfiles.app.data.FileRepository
import com.superfiles.app.data.Root
import com.superfiles.app.data.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Clipboard(val paths: List<String>, val cut: Boolean)

data class UiState(
    val dir: File,
    val items: List<FileItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val clipboard: Clipboard? = null,
    val sort: SortMode = SortMode.NAME,
    val showHidden: Boolean = false,
    val query: String? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val inTrash: Boolean = false,
    /** When true, listing and file operations go through `su` from "/" instead of normal IO. */
    val rootMode: Boolean = false,
    /** Copy/move progress: (bytes done, total bytes) plus the item being copied. */
    val progress: Pair<Long, Long>? = null,
    val progressName: String? = null,
)

class FileViewModel : ViewModel() {

    val root: File = Environment.getExternalStorageDirectory()

    private val _state = MutableStateFlow(UiState(dir = root))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var loadGen = 0

    init {
        refresh()
        // Trash older than 30 days is cleaned up automatically.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { FileRepository.cleanOldTrash(root) }
        }
    }

    private fun isRoot(f: File) = f.canonicalPath == root.canonicalPath

    fun refresh() {
        loadJob?.cancel()
        // A cancelled load must not clear the spinner of the load that replaced it.
        val gen = ++loadGen
        loadJob = viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(loading = true) }
            try {
                val items = withContext(Dispatchers.IO) {
                    val q = s.query
                    when {
                        s.rootMode && !q.isNullOrBlank() -> Root.search(s.dir, q)
                        s.rootMode -> Root.list(s.dir)
                        s.inTrash -> FileRepository.listTrash(root)
                        !q.isNullOrBlank() -> FileRepository.search(s.dir, q, s.showHidden)
                        else -> FileRepository.list(s.dir, s.showHidden, s.sort)
                    }
                }
                _state.update { it.copy(items = items, loading = false) }
            } finally {
                if (gen == loadGen) _state.update { it.copy(loading = false) }
            }
        }
    }

    fun open(dir: File) {
        _state.update { it.copy(dir = dir, selected = emptySet(), query = null, inTrash = false) }
        refresh()
    }

    fun openTrash() {
        if (_state.value.rootMode) return // no trash when browsing as root
        _state.update { it.copy(inTrash = true, selected = emptySet(), query = null) }
        refresh()
    }

    /** Switches between normal storage browsing and root browsing from "/". */
    fun toggleRoot() {
        val s = _state.value
        if (s.rootMode) {
            _state.update { it.copy(rootMode = false, dir = root, selected = emptySet(), query = null, inTrash = false) }
        } else {
            _state.update { it.copy(rootMode = true, dir = File("/"), selected = emptySet(), query = null, inTrash = false) }
        }
        refresh()
    }

    fun onBack() {
        val s = _state.value
        when {
            s.selected.isNotEmpty() -> clearSelection()
            s.query != null -> setQuery(null)
            s.inTrash -> open(root)
            !isRoot(s.dir) -> s.dir.parentFile?.let { open(it) }
        }
    }

    fun setQuery(q: String?) {
        _state.update { it.copy(query = q, selected = emptySet()) }
        refresh()
    }

    fun setSort(mode: SortMode) {
        _state.update { it.copy(sort = mode) }
        refresh()
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        refresh()
    }

    fun toggleSelect(path: String) = _state.update {
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun selectAll() = _state.update { it.copy(selected = it.items.map { i -> i.path }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun copySelection(cut: Boolean) = _state.update {
        it.copy(clipboard = Clipboard(it.selected.toList(), cut), selected = emptySet())
    }

    fun cancelPaste() = _state.update { it.copy(clipboard = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun paste() {
        val s = _state.value
        val cb = s.clipboard ?: return
        val dest = s.dir
        // The clipboard is cleared only after a successful paste, so a failed paste can be retried.
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    cb.paths.forEach { p ->
                        val f = File(p)
                        if (s.rootMode) {
                            if (cb.cut) Root.move(f, dest) else Root.copy(f, dest)
                        } else {
                            val report: (Long, Long, String) -> Unit = { done, total, name ->
                                _state.update { it.copy(progress = done to total, progressName = name) }
                            }
                            if (cb.cut) FileRepository.move(f, dest, report) else FileRepository.copy(f, dest, report)
                        }
                    }
                }.exceptionOrNull()
            }
            _state.update {
                it.copy(
                    message = if (err != null) (err.message ?: "Operation failed") else "Done",
                    clipboard = if (err == null) null else it.clipboard,
                    progress = null,
                    progressName = null,
                )
            }
            refresh()
        }
    }

    fun zipSelected() {
        if (_state.value.rootMode) {
            _state.update { it.copy(message = "Zipping isn't available in root mode") }
            return
        }
        val paths = _state.value.selected.toList()
        val dir = _state.value.dir
        _state.update { it.copy(selected = emptySet()) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { FileRepository.zipFiles(paths.map { File(it) }, dir) }
            }
            _state.update {
                it.copy(message = if (r.isSuccess) "Zipped to ${r.getOrThrow().name}"
                else (r.exceptionOrNull()?.message ?: "Zip failed"))
            }
            refresh()
        }
    }

    /** Copies every selected APK into Downloads/SuperFiles/extracted. */
    fun batchExtractApks() {
        val apks = _state.value.selected.map { File(it) }
            .filter { it.extension.equals("apk", ignoreCase = true) }
        if (apks.isEmpty()) return
        _state.update { it.copy(selected = emptySet()) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "SuperFiles/extracted",
                    ).apply { mkdirs() }
                    apks.forEach { it.copyTo(File(dest, it.name), overwrite = true) }
                }
            }
            _state.update {
                it.copy(message = if (r.isSuccess) "Extracted ${apks.size} APKs"
                else (r.exceptionOrNull()?.message ?: "Extract failed"))
            }
            refresh()
        }
    }

    /** Rebuilds and re-signs every selected APK, writing "<name>-signed.apk" next to it. */
    fun batchSignApks(ctx: Context) {
        val apks = _state.value.selected.map { File(it) }
            .filter { it.extension.equals("apk", ignoreCase = true) }
        if (apks.isEmpty()) return
        _state.update { it.copy(selected = emptySet()) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    for (apk in apks) {
                        val o = ApkInspector.overview(ctx, apk)
                        val out = File(apk.parentFile, apk.nameWithoutExtension + "-signed.apk")
                        ApkEditor.build(ctx, apk, ManifestEdits(), o.minSdk, out)
                    }
                }
            }
            _state.update {
                it.copy(message = if (r.isSuccess) "Signed ${apks.size} APKs"
                else (r.exceptionOrNull()?.message ?: "Sign failed"))
            }
            refresh()
        }
    }

    /** Copies the selection into another folder (used by the dual-pane "send to other pane"). */
    fun copySelectedTo(dest: File) {
        val paths = _state.value.selected.toList()
        val rootMode = _state.value.rootMode
        _state.update { it.copy(selected = emptySet()) }
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    paths.forEach { if (rootMode) Root.copy(File(it), dest) else FileRepository.copy(File(it), dest) }
                }.exceptionOrNull()
            }
            _state.update { it.copy(message = if (err != null) (err.message ?: "Copy failed") else "Copied") }
            refresh()
        }
    }

    /** Moves the selection into another folder (used by the dual-pane "send to other pane"). */
    fun moveSelectedTo(dest: File) {
        val paths = _state.value.selected.toList()
        val rootMode = _state.value.rootMode
        _state.update { it.copy(selected = emptySet()) }
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    paths.forEach { if (rootMode) Root.move(File(it), dest) else FileRepository.move(File(it), dest) }
                }.exceptionOrNull()
            }
            _state.update { it.copy(message = if (err != null) (err.message ?: "Move failed") else "Moved") }
            refresh()
        }
    }

    fun extractZip(item: FileItem) {
        if (_state.value.rootMode) {
            _state.update { it.copy(message = "Extracting isn't available in root mode") }
            return
        }
        val dir = _state.value.dir
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { FileRepository.unzip(item.file, dir) }
            }
            _state.update {
                it.copy(message = if (r.isSuccess) "Extracted to ${r.getOrThrow().name}"
                else (r.exceptionOrNull()?.message ?: "Extract failed"))
            }
            refresh()
        }
    }

    fun trashSelected() {
        val paths = _state.value.selected.toList()
        val rootMode = _state.value.rootMode
        _state.update { it.copy(selected = emptySet()) }
        if (rootMode) runOp("Deleted (root files skip the trash)") { paths.forEach { Root.delete(File(it)) } }
        else runOp("Moved to trash") { paths.forEach { FileRepository.trash(root, File(it)) } }
    }

    fun restoreSelected() {
        val paths = _state.value.selected.toList()
        _state.update { it.copy(selected = emptySet()) }
        runOp("Restored") { paths.forEach { FileRepository.restore(root, File(it)) } }
    }

    fun deleteForeverSelected() {
        val paths = _state.value.selected.toList()
        val rootMode = _state.value.rootMode
        _state.update { it.copy(selected = emptySet()) }
        if (rootMode) runOp("Deleted") { paths.forEach { Root.delete(File(it)) } }
        else runOp("Deleted") { paths.forEach { FileRepository.deleteForever(File(it)) } }
    }

    fun emptyTrash() {
        runOp("Trash emptied") { FileRepository.emptyTrash(root) }
    }

    fun rename(item: FileItem, newName: String) {
        _state.update { it.copy(selected = emptySet()) }
        if (_state.value.rootMode) runOp(null) { Root.rename(item.file, newName) }
        else runOp(null) { FileRepository.rename(item.file, newName) }
    }

    fun createFolder(name: String) {
        val dir = _state.value.dir
        if (_state.value.rootMode) runOp(null) { Root.mkdir(dir, name) }
        else runOp(null) { FileRepository.createFolder(dir, name) }
    }

    private fun runOp(okMessage: String?, block: () -> Unit) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            _state.update { it.copy(message = if (err != null) (err.message ?: "Operation failed") else okMessage) }
            refresh()
        }
    }
}
