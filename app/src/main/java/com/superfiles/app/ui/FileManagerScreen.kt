package com.superfiles.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superfiles.app.data.FileItem
import com.superfiles.app.data.SortMode
import java.io.File

private sealed interface Prompt {
    data object NewFolder : Prompt
    data class Rename(val item: FileItem) : Prompt
    data object ConfirmDelete : Prompt
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(vm: FileViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    val selecting = st.selected.isNotEmpty()
    val searching = st.query != null
    val atRoot = st.dir.absolutePath == vm.root.absolutePath

    BackHandler(enabled = selecting || searching || !atRoot) { vm.onBack() }

    LaunchedEffect(st.message) {
        st.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            when {
                selecting -> TopAppBar(
                    title = { Text("${st.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") }
                    },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "Select all") }
                        IconButton(onClick = { vm.copySelection(false) }) { Icon(Icons.Default.ContentCopy, "Copy") }
                        IconButton(onClick = { vm.copySelection(true) }) { Icon(Icons.Default.ContentCut, "Cut") }
                        if (st.selected.size == 1) {
                            IconButton(onClick = {
                                st.items.firstOrNull { it.path in st.selected }?.let { prompt = Prompt.Rename(it) }
                            }) { Icon(Icons.Default.DriveFileRenameOutline, "Rename") }
                        }
                        IconButton(onClick = { prompt = Prompt.ConfirmDelete }) { Icon(Icons.Default.Delete, "Delete") }
                    },
                )
                searching -> TopAppBar(
                    title = {
                        TextField(
                            value = st.query.orEmpty(),
                            onValueChange = vm::setQuery,
                            singleLine = true,
                            placeholder = { Text("Search in this folder") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.setQuery(null) }) { Icon(Icons.Default.Close, "Close search") }
                    },
                )
                else -> {
                    var sortMenu by remember { mutableStateOf(false) }
                    TopAppBar(
                        title = { Text(if (atRoot) "Internal storage" else st.dir.name) },
                        actions = {
                            IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Search, "Search") }
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Sort") }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    SortMode.entries.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(m.name.lowercase().replaceFirstChar { it.titlecase() }) },
                                            onClick = { vm.setSort(m); sortMenu = false },
                                            trailingIcon = if (st.sort == m) {
                                                { Icon(Icons.Default.Check, null) }
                                            } else null,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = vm::toggleHidden) {
                                Icon(
                                    if (st.showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    "Toggle hidden files",
                                )
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            st.clipboard?.let { cb ->
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${cb.paths.size} item(s) to ${if (cb.cut) "move" else "copy"}")
                        Row {
                            TextButton(onClick = vm::cancelPaste) { Text("Cancel") }
                            TextButton(onClick = vm::paste) { Text("Paste here") }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selecting && !searching) {
                FloatingActionButton(onClick = { prompt = Prompt.NewFolder }) {
                    Icon(Icons.Default.CreateNewFolder, "New folder")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Breadcrumbs(root = vm.root, dir = st.dir, onOpen = vm::open)
            if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (st.items.isEmpty() && !st.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searching) "No matches" else "Empty folder")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(st.items, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            selected = item.path in st.selected,
                            showPath = searching,
                            onClick = {
                                when {
                                    selecting -> vm.toggleSelect(item.path)
                                    item.isDir -> vm.open(item.file)
                                    else -> openFile(ctx, item.file)
                                }
                            },
                            onLongClick = { vm.toggleSelect(item.path) },
                        )
                    }
                }
            }
        }
    }

    when (val p = prompt) {
        Prompt.NewFolder -> NameDialog("New folder", "", "Create",
            onConfirm = { vm.createFolder(it); prompt = null }, onDismiss = { prompt = null })
        is Prompt.Rename -> NameDialog("Rename", p.item.name, "Rename",
            onConfirm = { vm.rename(p.item, it); prompt = null }, onDismiss = { prompt = null })
        Prompt.ConfirmDelete -> AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text("Delete ${st.selected.size} item(s)?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteSelected(); prompt = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun Breadcrumbs(root: File, dir: File, onOpen: (File) -> Unit) {
    val rel = dir.relativeTo(root).path
    val parts = if (rel.isEmpty()) emptyList() else rel.split("/")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen(root) }) { Text("Internal") }
        var acc = root
        parts.forEach { part ->
            acc = File(acc, part)
            val target = acc
            Icon(Icons.Default.ChevronRight, null)
            TextButton(onClick = { onOpen(target) }) { Text(part) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    showPath: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val meta = (if (item.isDir) "Folder" else formatSize(item.size)) + " · " + formatDate(item.modified)
    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (showPath) item.file.parent.orEmpty() else meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                when {
                    selected -> Icons.Default.CheckCircle
                    item.isDir -> Icons.Default.Folder
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun openFile(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}
