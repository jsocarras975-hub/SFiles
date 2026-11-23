package com.superfiles.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.superfiles.app.data.Decompiler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decompile an APK into a readable project folder, edit the smali in place,
 * and rebuild the APK from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecompileScreen(apk: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<Decompiler.Project?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var editFile by remember { mutableStateOf<File?>(null) }
    var prefix by remember { mutableStateOf("") }

    fun back(): Boolean {
        if (prefix.isEmpty()) return false
        prefix = prefix.trimEnd('/').substringBeforeLast('/', "")
        if (prefix.isNotEmpty()) prefix += "/"
        return true
    }
    BackHandler(onBack = { if (!back()) onBack() })

    val editing = editFile
    if (editing != null) {
        TextEditorScreen(editing) { editFile = null }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Decompile — ${apk.name}") },
                navigationIcon = {
                    IconButton(onClick = { if (!back()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            val p = project
            when {
                working != null -> Text(working!!)
                p == null -> {
                    Text(
                        "Decodes the manifest and every resource XML to readable text, " +
                            "disassembles all dex files to smali/, and extracts res/ and assets/ as-is. " +
                            "Rebuild reassembles your edited smali back into an APK. " +
                            "Edits to decoded XML files are not recompiled yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            working = "Decompiling…"
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { Decompiler.decompile(ctx, apk) }
                                }
                                working = null
                                r.onSuccess { project = it }
                                    .onFailure {
                                        Toast.makeText(ctx, it.message ?: "Decompile failed", Toast.LENGTH_LONG).show()
                                    }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) { Text("Decompile") }
                }
                else -> {
                    Text(
                        "${p.smaliFiles} smali files · ${p.xmlFiles} decoded XML files\n" +
                            "Folder: ${p.dir.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            working = "Rebuilding…"
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { Decompiler.rebuild(ctx, apk, p) }
                                }
                                working = null
                                r.onSuccess { out ->
                                    Toast.makeText(ctx, "Rebuilt: ${out.name}", Toast.LENGTH_LONG).show()
                                    installApk(ctx, out)
                                }.onFailure {
                                    Toast.makeText(ctx, it.message ?: "Rebuild failed", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    ) { Text("Rebuild APK") }
                    // Browse the project like a folder; tap a text file to edit it.
                    val all = p.dir.walkTopDown()
                        .filter { it != p.dir }
                        .map { it.relativeTo(p.dir).path.replace('\\', '/') }
                        .toList()
                    val shown = all.filter { it.startsWith(prefix) }
                        .map { it to it.removePrefix(prefix) }
                        .filter { (_, rest) -> rest.isNotEmpty() }
                        .groupBy { (_, rest) -> rest.substringBefore('/', "") }
                        .map { (first, group) ->
                            val isDir = group.any { (_, rest) -> rest.contains('/') }
                            Triple(if (isDir) "$prefix$first/" else group.first().first, first, isDir)
                        }
                        .sortedWith(compareByDescending<Triple<String, String, Boolean>> { it.third }
                            .thenBy { it.second.lowercase() })
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.first }) { (path, name, isDir) ->
                            ListItem(
                                headlineContent = { Text(if (isDir) "$name/" else name) },
                                leadingContent = {
                                    Icon(
                                        if (isDir) Icons.Default.Folder
                                        else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        null,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    if (isDir) prefix = path
                                    else {
                                        val f = File(p.dir, path)
                                        val ext = f.extension.lowercase()
                                        if (ext in setOf("smali", "xml", "txt", "json", "md", "properties")) {
                                            editFile = f
                                        } else {
                                            Toast.makeText(ctx, "Binary file — view it from the file manager", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
