package com.superfiles.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.superfiles.app.data.FileSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Search file contents under a folder, with results that jump to the file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrepScreen(root: File, onOpenFile: (File) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FileSearch.Match>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search in files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Text("Searching under ${root.absolutePath}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Text to find") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useRegex, onCheckedChange = { useRegex = it })
                Text("Regular expression")
            }
            Button(
                onClick = {
                    job?.cancel()
                    if (query.isBlank()) return@Button
                    searching = true
                    results = null
                    job = scope.launch {
                        val r = withContext(Dispatchers.IO) { FileSearch.search(root, query, useRegex) }
                        results = r
                        searching = false
                    }
                },
                enabled = query.isNotBlank() && !searching,
            ) { Text("Search") }
            when {
                searching -> Text("Searching…", modifier = Modifier.padding(top = 12.dp))
                results != null -> {
                    Text(
                        "${results!!.size} matches" + if (results!!.size >= 500) " (capped at 500)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results!!, key = { "${it.file.path}:${it.lineNo}" }) { m ->
                            ListItem(
                                headlineContent = { Text(m.line, fontFamily = FontFamily.Monospace, maxLines = 1) },
                                supportingContent = { Text("${m.file.name} : ${m.lineNo}") },
                                leadingContent = { Icon(Icons.Default.Search, null) },
                                modifier = Modifier.clickable { onOpenFile(m.file) },
                            )
                        }
                    }
                }
            }
        }
    }
}
