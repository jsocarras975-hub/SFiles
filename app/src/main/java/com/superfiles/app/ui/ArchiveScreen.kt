package com.superfiles.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.superfiles.app.data.ArchiveBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opens an archive like a folder: browse entries, extract, add or remove files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(archive: File, onOpenFile: (File) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prefix by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<ArchiveBrowser.ArchiveEntry?>(null) }

    val entries by produceState<List<ArchiveBrowser.ArchiveEntry>?>(null, archive, version) {
        value = withContext(Dispatchers.IO) {
            runCatching { ArchiveBrowser.listEntries(archive) }.getOrNull()
        }
    }

    fun back(): Boolean {
        if (prefix.isEmpty()) return false
        prefix = prefix.trimEnd('/').substringBeforeLast('/', "")
        if (prefix.isNotEmpty()) prefix += "/"
        return true
    }
    BackHandler(onBack = { if (!back()) onBack() })

    fun extract(entry: ArchiveBrowser.ArchiveEntry, openAfter: Boolean) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(ctx.cacheDir, "archive-view/${archive.nameWithoutExtension}")
                    ArchiveBrowser.extractEntry(archive, entry.name, dest)
                }
            }
            r.onSuccess { f ->
                if (openAfter) onOpenFile(f)
                else Toast.makeText(ctx, "Extracted to ${f.absolutePath}", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(ctx, it.message ?: "Extract failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val tmp = File(ctx.cacheDir, "archive-add-$name")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    ArchiveBrowser.addFile(archive, tmp)
                    tmp.delete()
                }
            }
            if (r.isSuccess) {
                version++
                Toast.makeText(ctx, "Added to archive", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, r.exceptionOrNull()?.message ?: "Add failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val editable = ArchiveBrowser.isEditable(archive.name)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(archive.name + if (prefix.isEmpty()) "" else " / $prefix") },
                navigationIcon = {
                    IconButton(onClick = { if (!back()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (editable) {
                        IconButton(onClick = { addPicker.launch("*/*") }) {
                            Icon(Icons.Default.Add, "Add file to archive")
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!editable) {
                Text(
                    "RAR archives are read-only here — you can browse and extract, but not add or remove files.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when (val list = entries) {
                null -> Text("Loading…", modifier = Modifier.padding(16.dp))
                else -> {
                    // Show only the entries directly under the current prefix.
                    val shown = list.filter { it.name.startsWith(prefix) }
                        .map { it to it.name.removePrefix(prefix) }
                        .filter { (_, rest) -> rest.isNotEmpty() }
                        .groupBy { (_, rest) -> rest.substringBefore('/', "") }
                        .map { (first, group) ->
                            val isDir = group.any { (_, rest) -> rest.contains('/') } ||
                                group.first().first.isDir
                            val entry = group.first().first
                            val displayName = if (isDir && !entry.name.endsWith("/")) "$first/" else first
                            ArchiveBrowser.ArchiveEntry(
                                name = if (isDir) "$prefix$first/" else entry.name,
                                size = entry.size,
                                isDir = isDir,
                            ) to displayName
                        }
                        .sortedWith(compareByDescending<Pair<ArchiveBrowser.ArchiveEntry, String>> { it.first.isDir }
                            .thenBy { it.second.lowercase() })
                    if (shown.isEmpty()) {
                        Text("Empty archive", modifier = Modifier.padding(16.dp))
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.first.name }) { (entry, displayName) ->
                            ListItem(
                                headlineContent = { Text(displayName) },
                                supportingContent = {
                                    if (!entry.isDir) Text(formatSize(entry.size))
                                },
                                leadingContent = {
                                    Icon(
                                        if (entry.isDir) Icons.Default.Folder
                                        else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        null,
                                    )
                                },
                                trailingContent = {
                                    if (!entry.isDir && editable) {
                                        IconButton(onClick = { selected = entry }) {
                                            Icon(Icons.Default.Delete, "Remove from archive")
                                        }
                                    }
                                },
                                modifier = Modifier.clickable {
                                    if (entry.isDir) {
                                        prefix = entry.name
                                    } else {
                                        extract(entry, openAfter = true)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Remove ${entry.name} from the archive?") },
            text = { Text("The archive is rewritten without it.") },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { ArchiveBrowser.removeEntry(archive, entry.name) }
                        }
                        if (r.isSuccess) version++
                        else Toast.makeText(ctx, r.exceptionOrNull()?.message ?: "Remove failed", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
}
