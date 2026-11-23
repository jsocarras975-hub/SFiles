package com.superfiles.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superfiles.app.data.ArchiveBrowser
import com.superfiles.app.data.Bookmarks
import com.superfiles.app.data.Favorites
import com.superfiles.app.data.FileItem
import com.superfiles.app.data.FileRepository
import com.superfiles.app.data.Root
import com.superfiles.app.data.SortMode
import com.superfiles.app.data.SplitInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Prompt {
    data object NewFolder : Prompt
    data class Rename(val item: FileItem) : Prompt
    data object ConfirmDelete : Prompt
    data object ConfirmDeleteForever : Prompt
    data object ConfirmEmptyTrash : Prompt
}

/** Extensions that open in the built-in text editor instead of another app. */
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "kt", "kts", "java", "xml", "json", "gradle", "properties", "sh", "py",
    "csv", "log", "html", "css", "js", "yml", "yaml", "toml",
)

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "wav", "m4a", "flac")
private val FONT_EXTENSIONS = setOf("ttf", "otf")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    onOpenApk: (File) -> Unit,
    onShowApps: () -> Unit,
    onShowHistory: () -> Unit,
    vm: FileViewModel = viewModel(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val vm2: FileViewModel = viewModel(key = "pane2")
    val st2 by vm2.state.collectAsStateWithLifecycle()
    var dualPane by rememberSaveable { mutableStateOf(false) }
    var activePane by remember { mutableIntStateOf(0) }
    val activeVm = if (activePane == 0) vm else vm2
    val activeSt by activeVm.state.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var promptVm by remember { mutableStateOf<FileViewModel?>(null) }
    val dialogVm = promptVm ?: activeVm
    val dialogSt by dialogVm.state.collectAsStateWithLifecycle()
    var props by remember { mutableStateOf<FileItem?>(null) }
    var propsVm by remember { mutableStateOf<FileViewModel?>(null) }
    var textFile by remember { mutableStateOf<File?>(null) }
    var hexFile by remember { mutableStateOf<File?>(null) }
    var diffPair by remember { mutableStateOf<Pair<File, File>?>(null) }
    var showGrep by remember { mutableStateOf(false) }
    var showFtp by remember { mutableStateOf(false) }
    var archiveFile by remember { mutableStateOf<File?>(null) }
    var imageFile by remember { mutableStateOf<File?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var fontFile by remember { mutableStateOf<File?>(null) }
    var favVersion by remember { mutableIntStateOf(0) }
    val favorites = remember(favVersion) { Favorites.list(ctx) }
    var bookmarkVersion by remember { mutableIntStateOf(0) }
    val bookmarks = remember(bookmarkVersion) { Bookmarks.list(ctx) }
    val selecting = st.selected.isNotEmpty()
    val searching = st.query != null
    val inTrash = st.inTrash
    val atRoot = st.dir.absolutePath == vm.root.absolutePath
    val subScreenOpen = textFile != null || hexFile != null || diffPair != null || showGrep ||
        showFtp || archiveFile != null || imageFile != null || audioFile != null || fontFile != null
    val backSt = if (dualPane) activeSt else st

    BackHandler(enabled = !subScreenOpen && (backSt.selected.isNotEmpty() || backSt.query != null ||
        backSt.inTrash || backSt.dir.absolutePath != vm.root.absolutePath)) { activeVm.onBack() }

    LaunchedEffect(st.message) {
        st.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    LaunchedEffect(activeSt.message, dualPane, activePane) {
        // Pane 1's messages come through the main effect above; only pane 2 needs its own.
        if (dualPane && activePane == 1) {
            activeSt.message?.let { snackbar.showSnackbar(it); activeVm.consumeMessage() }
        }
    }

    /** Opens a file in the right built-in viewer, or another app when there isn't one. */
    fun openViewerFor(f: File) {
        when (f.extension.lowercase()) {
            in TEXT_EXTENSIONS -> textFile = f
            in IMAGE_EXTENSIONS -> imageFile = f
            in AUDIO_EXTENSIONS -> audioFile = f
            in FONT_EXTENSIONS -> fontFile = f
            else -> openFile(ctx, f)
        }
    }

    /** APK inspector, archive browser, or a viewer — whichever fits the file. */
    fun openAnyFile(f: File) {
        when {
            f.extension.equals("apk", ignoreCase = true) -> onOpenApk(f)
            ArchiveBrowser.isArchive(f.name) -> archiveFile = f
            else -> openViewerFor(f)
        }
    }

    /** Root toggle: asks su first, since most devices don't have it. */
    fun toggleRootMode() {
        if (st.rootMode) {
            vm.toggleRoot()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Root.available() }
            if (ok) vm.toggleRoot()
            else Toast.makeText(ctx, "This device has no root (su)", Toast.LENGTH_SHORT).show()
        }
    }

    val editingFile = textFile
    if (editingFile != null) {
        TextEditorScreen(editingFile) { textFile = null; vm.refresh() }
        return
    }
    val hex = hexFile
    if (hex != null) {
        HexEditorScreen(hex) { hexFile = null; vm.refresh() }
        return
    }
    val diff = diffPair
    if (diff != null) {
        DiffScreen(diff.first, diff.second) { diffPair = null }
        return
    }
    if (showGrep) {
        GrepScreen(st.dir, { f -> showGrep = false; openViewerFor(f) }, { showGrep = false })
        return
    }
    if (showFtp) {
        FtpScreen(vm.root) { showFtp = false }
        return
    }
    val arch = archiveFile
    if (arch != null) {
        ArchiveScreen(arch, { f -> openViewerFor(f) }, { archiveFile = null; vm.refresh() })
        return
    }
    val img = imageFile
    if (img != null) {
        ImageViewerScreen(img) { imageFile = null }
        return
    }
    val aud = audioFile
    if (aud != null) {
        AudioPlayerScreen(aud) { audioFile = null }
        return
    }
    val font = fontFile
    if (font != null) {
        FontPreviewScreen(font) { fontFile = null }
        return
    }

    Scaffold(
        topBar = {
            if (dualPane) {
                var moreMenu by remember { mutableStateOf(false) }
                TopAppBar(
                    title = { Text("Dual pane") },
                    navigationIcon = {
                        IconButton(onClick = { dualPane = false }) { Icon(Icons.Default.Close, "Single pane") }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("FTP server") },
                                    onClick = { moreMenu = false; showFtp = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Installed apps") },
                                    onClick = { moreMenu = false; onShowApps() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Build history") },
                                    onClick = { moreMenu = false; onShowHistory() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Search in files") },
                                    onClick = { moreMenu = false; showGrep = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("New text file") },
                                    onClick = {
                                        moreMenu = false
                                        textFile = createTextFile(activeVm.state.value.dir)
                                    },
                                )
                            }
                        }
                    },
                )
            } else when {
                selecting && inTrash -> TopAppBar(
                    title = { Text("${st.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") }
                    },
                    actions = {
                        IconButton(onClick = vm::restoreSelected) { Icon(Icons.Default.Restore, "Restore") }
                        IconButton(onClick = { prompt = Prompt.ConfirmDeleteForever }) {
                            Icon(Icons.Default.DeleteForever, "Delete forever")
                        }
                    },
                )
                selecting -> TopAppBar(
                    title = { Text("${st.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") }
                    },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "Select all") }
                        IconButton(onClick = { vm.copySelection(false) }) { Icon(Icons.Default.ContentCopy, "Copy") }
                        IconButton(onClick = { vm.copySelection(true) }) { Icon(Icons.Default.ContentCut, "Cut") }
                        IconButton(onClick = vm::zipSelected) { Icon(Icons.Default.Archive, "Zip") }
                        IconButton(onClick = { shareFiles(ctx, st.selected.map { File(it) }) }) {
                            Icon(Icons.Default.Share, "Share")
                        }
                        var batchMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { batchMenu = true }) { Icon(Icons.Default.MoreVert, "Batch actions") }
                            DropdownMenu(expanded = batchMenu, onDismissRequest = { batchMenu = false }) {
                                val apks = st.selected.map { File(it) }
                                    .filter { it.extension.equals("apk", ignoreCase = true) }
                                val twoFiles = st.selected.map { File(it) }.count { !it.isDirectory } == 2
                                DropdownMenuItem(
                                    text = { Text("Extract APKs") },
                                    onClick = { batchMenu = false; vm.batchExtractApks() },
                                    enabled = apks.isNotEmpty(),
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign APKs") },
                                    onClick = { batchMenu = false; vm.batchSignApks(ctx) },
                                    enabled = apks.isNotEmpty(),
                                )
                                DropdownMenuItem(
                                    text = { Text("Install splits (${apks.size} APKs)") },
                                    onClick = {
                                        batchMenu = false
                                        runCatching { SplitInstaller.installSplits(ctx, apks) }
                                            .onFailure {
                                                Toast.makeText(ctx, it.message ?: "Install failed", Toast.LENGTH_SHORT).show()
                                            }
                                    },
                                    enabled = apks.size >= 2,
                                )
                                DropdownMenuItem(
                                    text = { Text("Bookmark") },
                                    onClick = {
                                        batchMenu = false
                                        st.selected.forEach { Bookmarks.toggle(ctx, it) }
                                        bookmarkVersion++
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Compare") },
                                    onClick = {
                                        batchMenu = false
                                        val files = st.selected.map { File(it) }.filter { !it.isDirectory }
                                        if (files.size == 2) diffPair = files[0] to files[1]
                                    },
                                    enabled = twoFiles,
                                )
                            }
                        }
                        if (st.selected.size == 1) {
                            IconButton(onClick = {
                                st.items.firstOrNull { it.path in st.selected }?.let { prompt = Prompt.Rename(it) }
                            }) { Icon(Icons.Default.DriveFileRenameOutline, "Rename") }
                            IconButton(onClick = {
                                st.items.firstOrNull { it.path in st.selected }?.let { props = it; propsVm = vm }
                            }) { Icon(Icons.Default.Info, "Properties") }
                        }
                        IconButton(onClick = { prompt = Prompt.ConfirmDelete }) { Icon(Icons.Default.Delete, "Delete") }
                    },
                )
                inTrash -> TopAppBar(
                    title = { Text("Trash") },
                    navigationIcon = {
                        IconButton(onClick = vm::onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { prompt = Prompt.ConfirmEmptyTrash }) {
                            Icon(Icons.Default.DeleteForever, "Empty trash")
                        }
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
                    var moreMenu by remember { mutableStateOf(false) }
                    TopAppBar(
                        title = {
                            Text(
                                when {
                                    st.rootMode && st.dir.absolutePath == "/" -> "Root /"
                                    atRoot -> "Internal storage"
                                    else -> st.dir.name
                                },
                            )
                        },
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
                            if (!atRoot) {
                                IconButton(onClick = {
                                    Favorites.toggle(ctx, st.dir.absolutePath)
                                    favVersion++
                                }) {
                                    Icon(
                                        if (Favorites.isFavorite(ctx, st.dir.absolutePath)) Icons.Default.Star
                                        else Icons.Default.StarBorder,
                                        "Favorite this folder",
                                    )
                                }
                            }
                            IconButton(onClick = vm::openTrash) { Icon(Icons.Default.Delete, "Trash") }
                            Box {
                                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Dual pane") },
                                        onClick = { moreMenu = false; dualPane = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (st.rootMode) "✓ Root explorer" else "Root explorer") },
                                        onClick = { moreMenu = false; toggleRootMode() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("FTP server") },
                                        onClick = { moreMenu = false; showFtp = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Installed apps") },
                                        onClick = { moreMenu = false; onShowApps() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Build history") },
                                        onClick = { moreMenu = false; onShowHistory() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("New text file") },
                                        onClick = {
                                            moreMenu = false
                                            textFile = createTextFile(st.dir)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Search in files") },
                                        onClick = { moreMenu = false; showGrep = true },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            val clipSt = if (dualPane) activeSt else st
            val clipVm = if (dualPane) activeVm else vm
            clipSt.clipboard?.let { cb ->
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${cb.paths.size} item(s) to ${if (cb.cut) "move" else "copy"}")
                        Row {
                            TextButton(onClick = clipVm::cancelPaste) { Text("Cancel") }
                            TextButton(onClick = clipVm::paste) { Text("Paste here") }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            val fabSt = if (dualPane) activeSt else st
            if (fabSt.selected.isEmpty() && fabSt.query == null && !fabSt.inTrash) {
                FloatingActionButton(onClick = { promptVm = activeVm; prompt = Prompt.NewFolder }) {
                    Icon(Icons.Default.CreateNewFolder, "New folder")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (dualPane) {
            Row(Modifier.padding(pad).fillMaxSize()) {
                FilePane(
                    vm = vm,
                    isActive = activePane == 0,
                    otherDir = st2.dir,
                    onActivate = { activePane = 0 },
                    onOpenFile = ::openAnyFile,
                    onShowProps = { item, paneVm -> props = item; propsVm = paneVm },
                    onPrompt = { p, paneVm -> promptVm = paneVm; prompt = p },
                    onCompare = { diffPair = it },
                    onBookmark = { paths ->
                        paths.forEach { Bookmarks.toggle(ctx, it) }
                        bookmarkVersion++
                    },
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.fillMaxHeight().width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                FilePane(
                    vm = vm2,
                    isActive = activePane == 1,
                    otherDir = st.dir,
                    onActivate = { activePane = 1 },
                    onOpenFile = ::openAnyFile,
                    onShowProps = { item, paneVm -> props = item; propsVm = paneVm },
                    onPrompt = { p, paneVm -> promptVm = paneVm; prompt = p },
                    onCompare = { diffPair = it },
                    onBookmark = { paths ->
                        paths.forEach { Bookmarks.toggle(ctx, it) }
                        bookmarkVersion++
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (!inTrash) Breadcrumbs(root = vm.root, dir = st.dir, onOpen = vm::open)
                if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                st.progress?.let { (done, total) ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        LinearProgressIndicator(
                            progress = if (total > 0) done.toFloat() / total else 0f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Copying ${st.progressName.orEmpty()} · ${formatSize(done)} of ${formatSize(total)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (st.items.isEmpty() && !st.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (inTrash) " Trash is empty" else if (searching) "No matches" else "Empty folder")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (atRoot && favorites.isNotEmpty() && !searching && !inTrash) {
                            item {
                                Text("Favorites", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                            items(favorites, key = { "fav:$it" }) { path ->
                                val f = File(path)
                                ListItem(
                                    headlineContent = { Text(f.name) },
                                    leadingContent = { Icon(Icons.Default.Star, null) },
                                    modifier = Modifier.clickable { vm.open(f) },
                                )
                            }
                        }
                        if (atRoot && bookmarks.isNotEmpty() && !searching && !inTrash) {
                            item {
                                Text("Bookmarks", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                            items(bookmarks, key = { "bm:${it.path}" }) { f ->
                                ListItem(
                                    headlineContent = { Text(f.name) },
                                    supportingContent = { Text(f.parent.orEmpty()) },
                                    leadingContent = { Icon(Icons.Default.Star, null) },
                                    modifier = Modifier.clickable {
                                        if (f.isDirectory) vm.open(f) else openViewerFor(f)
                                    },
                                )
                            }
                        }
                        items(st.items, key = { it.path }) { item ->
                            FileRow(
                                item = item,
                                selected = item.path in st.selected,
                                showPath = searching,
                                onClick = {
                                    when {
                                        inTrash -> vm.toggleSelect(item.path)
                                        selecting -> vm.toggleSelect(item.path)
                                        item.isDir -> vm.open(item.file)
                                        else -> openAnyFile(item.file)
                                    }
                                },
                                onLongClick = { vm.toggleSelect(item.path) },
                            )
                        }
                    }
                }
            }
        }
    }

    props?.let { item ->
        val pvm = propsVm ?: vm
        PropertiesDialog(
            item = item,
            onDismiss = { props = null },
            onExtractZip = { pvm.extractZip(item) },
            onEditText = { textFile = item.file },
            onHexEdit = { hexFile = item.file },
        )
    }

    when (val p = prompt) {
        Prompt.NewFolder -> NameDialog("New folder", "", "Create",
            onConfirm = { dialogVm.createFolder(it); prompt = null; promptVm = null },
            onDismiss = { prompt = null; promptVm = null })
        is Prompt.Rename -> NameDialog("Rename", p.item.name, "Rename",
            onConfirm = { dialogVm.rename(p.item, it); prompt = null; promptVm = null },
            onDismiss = { prompt = null; promptVm = null })
        Prompt.ConfirmDelete -> AlertDialog(
            onDismissRequest = { prompt = null; promptVm = null },
            title = { Text("Move ${dialogSt.selected.size} item(s) to trash?") },
            text = { Text("You can restore them from Trash.") },
            confirmButton = { TextButton(onClick = { dialogVm.trashSelected(); prompt = null; promptVm = null }) { Text("Move to trash") } },
            dismissButton = { TextButton(onClick = { prompt = null; promptVm = null }) { Text("Cancel") } },
        )
        Prompt.ConfirmDeleteForever -> AlertDialog(
            onDismissRequest = { prompt = null; promptVm = null },
            title = { Text("Delete ${dialogSt.selected.size} item(s) forever?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { dialogVm.deleteForeverSelected(); prompt = null; promptVm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { prompt = null; promptVm = null }) { Text("Cancel") } },
        )
        Prompt.ConfirmEmptyTrash -> AlertDialog(
            onDismissRequest = { prompt = null; promptVm = null },
            title = { Text("Empty trash?") },
            text = { Text("Everything in the trash will be deleted forever.") },
            confirmButton = { TextButton(onClick = { dialogVm.emptyTrash(); prompt = null; promptVm = null }) { Text("Empty trash") } },
            dismissButton = { TextButton(onClick = { prompt = null; promptVm = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

/** One side of the dual-pane view: its own folder, selection, and cross-pane actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilePane(
    vm: FileViewModel,
    isActive: Boolean,
    otherDir: File,
    onActivate: () -> Unit,
    onOpenFile: (File) -> Unit,
    onShowProps: (FileItem, FileViewModel) -> Unit,
    onPrompt: (Prompt, FileViewModel) -> Unit,
    onCompare: (Pair<File, File>) -> Unit,
    onBookmark: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val selecting = st.selected.isNotEmpty()
    val atRoot = st.dir.absolutePath == vm.root.absolutePath
    Column(
        modifier.fillMaxHeight().border(
            width = if (isActive) 2.dp else 0.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = !atRoot,
                onClick = { onActivate(); vm.onBack() },
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                when {
                    st.rootMode && st.dir.absolutePath == "/" -> "Root /"
                    atRoot -> "Internal storage"
                    else -> st.dir.name
                },
                modifier = Modifier.weight(1f).clickable { onActivate() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${st.items.size}", style = MaterialTheme.typography.bodySmall)
        }
        Breadcrumbs(root = vm.root, dir = st.dir, onOpen = { onActivate(); vm.open(it) })
        if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = { vm.copySelection(false) }) { Icon(Icons.Default.ContentCopy, "Copy") }
                IconButton(onClick = { vm.copySelection(true) }) { Icon(Icons.Default.ContentCut, "Cut") }
                IconButton(onClick = vm::zipSelected) { Icon(Icons.Default.Archive, "Zip") }
                IconButton(onClick = { vm.copySelectedTo(otherDir) }) { Text("→") }
                IconButton(onClick = { vm.moveSelectedTo(otherDir) }) { Text("⇉") }
                IconButton(onClick = { onBookmark(st.selected.toList()) }) { Icon(Icons.Default.Star, "Bookmark") }
                if (st.selected.size == 1) {
                    IconButton(onClick = {
                        st.items.firstOrNull { it.path in st.selected }?.let { onShowProps(it, vm) }
                    }) { Icon(Icons.Default.Info, "Properties") }
                }
                if (st.selected.map { File(it) }.count { !it.isDirectory } == 2) {
                    IconButton(onClick = {
                        val files = st.selected.map { File(it) }.filter { !it.isDirectory }
                        if (files.size == 2) onCompare(files[0] to files[1])
                    }) { Text("Diff") }
                }
                IconButton(onClick = { onPrompt(Prompt.ConfirmDelete, vm) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(st.items, key = { it.path }) { item ->
                FileRow(
                    item = item,
                    selected = item.path in st.selected,
                    showPath = false,
                    onClick = {
                        onActivate()
                        when {
                            selecting -> vm.toggleSelect(item.path)
                            item.isDir -> vm.open(item.file)
                            else -> onOpenFile(item.file)
                        }
                    },
                    onLongClick = { onActivate(); vm.toggleSelect(item.path) },
                )
            }
        }
    }
}

@Composable
private fun Breadcrumbs(root: File, dir: File, onOpen: (File) -> Unit) {
    // relativeTo throws when the folder isn't under the root (e.g. after following a
    // symlink), so fall back to showing the plain path instead of crashing.
    val parts = runCatching {
        val rel = dir.relativeTo(root).path
        if (rel.isEmpty()) emptyList() else rel.split("/")
    }.getOrNull()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen(root) }) { Text("Internal") }
        if (parts == null) {
            Icon(Icons.Default.ChevronRight, null)
            Text(dir.absolutePath, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp))
        } else {
            var acc = root
            parts.forEach { part ->
                acc = File(acc, part)
                val target = acc
                Icon(Icons.Default.ChevronRight, null)
                TextButton(onClick = { onOpen(target) }) { Text(part) }
            }
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
    val meta = (if (item.isDir) "Folder" + (item.childCount?.let { " · $it items" } ?: "")
        else formatSize(item.size)) + " · " + formatDate(item.modified)
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
            val thumb = rememberThumb(item)
            if (thumb != null && !selected) {
                Image(thumb.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                Icon(
                    when {
                        selected -> Icons.Default.CheckCircle
                        item.isDir -> Icons.Default.Folder
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                )
            }
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

private fun shareFiles(ctx: Context, files: List<File>) {
    val uris = files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share"))
}

private fun createTextFile(dir: File): File {
    var f = File(dir, "note.txt")
    var n = 1
    while (f.exists()) {
        f = File(dir, "note ($n).txt")
        n++
    }
    f.createNewFile()
    return f
}

/** A downsampled thumbnail for an image or video file, or null for anything else. */
@Composable
private fun rememberThumb(item: FileItem): android.graphics.Bitmap? {
    val ext = item.file.extension.lowercase()
    val isImage = !item.isDir && ext in setOf("png", "jpg", "jpeg", "webp", "gif")
    val isVideo = !item.isDir && ext in setOf("mp4", "mkv", "webm", "mov")
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.path, item.modified) {
        value = if (!isImage && !isVideo) null else withContext(Dispatchers.IO) {
            runCatching {
                if (isImage) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeFile(item.path, opts)
                } else {
                    ThumbnailUtils.createVideoThumbnail(item.file, Size(96, 96), null)
                }
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
private fun PropertiesDialog(
    item: FileItem,
    onDismiss: () -> Unit,
    onExtractZip: () -> Unit,
    onEditText: () -> Unit,
    onHexEdit: () -> Unit,
) {
    val size by produceState<Long?>(null, item.path) {
        value = withContext(Dispatchers.IO) { FileRepository.folderSize(item.file) }
    }
    val md5 by produceState<String?>(null, item.path) {
        value = if (!item.isDir) withContext(Dispatchers.IO) {
            runCatching { FileRepository.fileHash(item.file, "MD5") }.getOrNull()
        } else null
    }
    val sha256 by produceState<String?>(null, item.path) {
        value = if (!item.isDir) withContext(Dispatchers.IO) {
            runCatching { FileRepository.fileHash(item.file, "SHA-256") }.getOrNull()
        } else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.path, style = MaterialTheme.typography.bodySmall)
                Text("Size ${size?.let { formatSize(it) } ?: "…"}", style = MaterialTheme.typography.bodySmall)
                Text("Modified ${formatDate(item.modified)}", style = MaterialTheme.typography.bodySmall)
                if (!item.isDir) {
                    md5?.let { Text("MD5 $it", style = MaterialTheme.typography.bodySmall) }
                    sha256?.let { Text("SHA-256 $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            Row {
                if (item.file.extension.equals("zip", ignoreCase = true)) {
                    TextButton(onClick = { onExtractZip(); onDismiss() }) { Text("Extract") }
                }
                if (!item.isDir && item.file.extension.lowercase() in TEXT_EXTENSIONS) {
                    TextButton(onClick = { onEditText(); onDismiss() }) { Text("Edit") }
                }
                if (!item.isDir) {
                    TextButton(onClick = { onHexEdit(); onDismiss() }) { Text("Hex edit") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
