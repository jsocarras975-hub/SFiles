package com.superfiles.app.ui

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.superfiles.app.data.FileItem
import com.superfiles.app.data.FileRepository
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
)

class FileViewModel : ViewModel() {

    val root: File = Environment.getExternalStorageDirectory()

    private val _state = MutableStateFlow(UiState(dir = root))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init { refresh() }

    private fun isRoot(f: File) = f.canonicalPath == root.canonicalPath

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(loading = true) }
            val items = withContext(Dispatchers.IO) {
                val q = s.query
                if (!q.isNullOrBlank()) FileRepository.search(s.dir, q, s.showHidden)
                else FileRepository.list(s.dir, s.showHidden, s.sort)
            }
            _state.update { it.copy(items = items, loading = false) }
        }
    }

    fun open(dir: File) {
        _state.update { it.copy(dir = dir, selected = emptySet(), query = null) }
        refresh()
    }

    fun onBack() {
        val s = _state.value
        when {
            s.selected.isNotEmpty() -> clearSelection()
            s.query != null -> setQuery(null)
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
        _state.update { it.copy(clipboard = null) }
        runOp("Done") {
            cb.paths.forEach { p ->
                val f = File(p)
                if (cb.cut) FileRepository.move(f, dest) else FileRepository.copy(f, dest)
            }
        }
    }

    fun deleteSelected() {
        val paths = _state.value.selected.toList()
        _state.update { it.copy(selected = emptySet()) }
        runOp("Deleted") { paths.forEach { FileRepository.delete(File(it)) } }
    }

    fun rename(item: FileItem, newName: String) {
        _state.update { it.copy(selected = emptySet()) }
        runOp(null) { FileRepository.rename(item.file, newName) }
    }

    fun createFolder(name: String) {
        val dir = _state.value.dir
        runOp(null) { FileRepository.createFolder(dir, name) }
    }

    private fun runOp(okMessage: String?, block: () -> Unit) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            _state.update { it.copy(message = if (err != null) (err.message ?: "Operation failed") else okMessage) }
            refresh()
        }
    }
}
