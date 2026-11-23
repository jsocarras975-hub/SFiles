package com.superfiles.app.ui

import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.superfiles.app.data.ResourceEntry
import com.superfiles.app.data.ResourceStaging
import com.superfiles.app.data.ResourceTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Side-by-side string translation: every string resource, editable in place,
 *  with tab-separated export/import. Edits are staged until the next build. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScreen(apk: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var stagedVersion by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<ResourceEntry?>(null) }
    var editText by remember { mutableStateOf("") }

    val strings by produceState<List<ResourceEntry>?>(null, apk) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ResourceTable.entries(apk).filter { it.valueType == 3 && !it.entry.complex }
            }.getOrNull()
        }
    }
    val staged = remember(stagedVersion) { ResourceStaging.list(apk) }

    fun exportTsv(): File? {
        val list = strings ?: return null
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SuperFiles/translations",
        ).apply { mkdirs() }
        val out = File(dir, apk.nameWithoutExtension + "-strings.tsv")
        val clean: (String) -> String = { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
        out.writeText(
            list.joinToString("\n") { e ->
                "${e.entry.id}\t${clean(e.valueText)}\t${clean(staged[e.entry.id] ?: e.valueText)}"
            },
        )
        return out
    }

    fun importTsv(uri: Uri) {
        scope.launch {
            val applied = withContext(Dispatchers.IO) {
                runCatching {
                    var n = 0
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                        for (line in lines) {
                            val parts = line.split('\t')
                            if (parts.size >= 3 && parts[2].isNotBlank()) {
                                ResourceStaging.put(apk, parts[0], parts[2])
                                n++
                            }
                        }
                    }
                    n
                }.getOrNull() ?: -1
            }
            if (applied >= 0) {
                stagedVersion++
                Toast.makeText(ctx, "Imported $applied translations", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Import failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importTsv(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Translate strings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { runCatching { exportTsv() }.getOrNull() }
                            Toast.makeText(
                                ctx,
                                if (f != null) "Exported to ${f.absolutePath}" else "Export failed",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }) { Icon(Icons.Default.Download, "Export strings") }
                    IconButton(onClick = { importPicker.launch("text/*") }) {
                        Icon(Icons.Default.Upload, "Import strings")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "Edits are staged — build the APK to apply them. Export writes a .tsv (id, original, translated) to Downloads/SuperFiles/translations.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter strings") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val list = strings
            when {
                list == null -> Text("Loading…", modifier = Modifier.padding(16.dp))
                list!!.isEmpty() -> Text("No string resources in this APK", modifier = Modifier.padding(16.dp))
                else -> {
                    val shown = list!!.filter {
                        query.isBlank() || it.entry.key.contains(query, ignoreCase = true) ||
                            it.valueText.contains(query, ignoreCase = true)
                    }
                    Text(
                        "${shown.size} strings · ${staged.size} edited",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.entry.id }) { e ->
                            val newValue = staged[e.entry.id]
                            ListItem(
                                headlineContent = { Text(e.entry.key) },
                                supportingContent = {
                                    Column {
                                        Text(e.valueText, maxLines = 2)
                                        if (newValue != null && newValue != e.valueText) {
                                            Text(
                                                "→ $newValue",
                                                maxLines = 2,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable {
                                    editing = e
                                    editText = staged[e.entry.id] ?: e.valueText
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(e.entry.key) },
            text = {
                Column {
                    Text("Original: ${e.valueText}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        label = { Text("Translation") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    ResourceStaging.put(apk, e.entry.id, editText)
                    stagedVersion++
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (ResourceStaging.has(apk, e.entry.id)) {
                        TextButton(onClick = {
                            ResourceStaging.remove(apk, e.entry.id)
                            stagedVersion++
                            editing = null
                        }) { Text("Revert") }
                    }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
