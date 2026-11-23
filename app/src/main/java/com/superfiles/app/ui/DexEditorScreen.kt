package com.superfiles.app.ui

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.superfiles.app.data.ApkEditor
import com.superfiles.app.data.ApkInspector
import com.superfiles.app.data.DexEditor
import com.superfiles.app.data.ManifestEdits
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jf.dexlib2.immutable.ImmutableDexFile

/** Structured DEX editing: browse classes, add/remove methods and fields, rename a class. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexEditorScreen(apk: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dexName by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<String?>(null) }
    var dex by remember { mutableStateOf<ImmutableDexFile?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var showAddMethod by remember { mutableStateOf(false) }
    var showAddField by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var dexMenu by remember { mutableStateOf(false) }

    val dexNames by produceState<List<String>?>(null, apk) {
        value = withContext(Dispatchers.IO) { runCatching { DexEditor.dexNames(apk) }.getOrNull() }
    }
    val names = dexNames.orEmpty()
    if (dexName == null && names.isNotEmpty()) dexName = names.first()

    val loaded by produceState<ImmutableDexFile?>(null, apk, dexName) {
        value = dexName?.let { name ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val loaded = DexEditor.loadDex(apk, name)
                    ImmutableDexFile(org.jf.dexlib2.Opcodes.forApi(34), loaded.classes)
                }.getOrNull()
            }
        }
        dex = value
        dirty = false
        selectedType = null
    }
    val currentDex = dex ?: loaded

    BackHandler(onBack = {
        if (selectedType != null) selectedType = null else onBack()
    })

    fun edit(transform: (ImmutableDexFile) -> ImmutableDexFile) {
        val d = currentDex ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { transform(d) } }
            r.onSuccess { dex = it; dirty = true }
                .onFailure { Toast.makeText(ctx, it.message ?: "Edit failed", Toast.LENGTH_SHORT).show() }
        }
    }

    fun save() {
        val d = currentDex ?: return
        val name = dexName ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val work = File(ctx.cacheDir, "dex-edit").apply { deleteRecursively(); mkdirs() }
                    val newDex = File(work, name)
                    DexEditor.writeDex(d, newDex)
                    val outDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "SuperFiles",
                    ).apply { mkdirs() }
                    val out = File(outDir, apk.nameWithoutExtension + "-dexedit.apk")
                    val overview = ApkInspector.overview(ctx, apk)
                    ApkEditor.build(
                        ctx, apk, ManifestEdits(), overview.minSdk, out,
                        dexReplacements = mapOf(name to newDex),
                    )
                    out
                }
            }
            r.onSuccess { out ->
                dirty = false
                Toast.makeText(ctx, "Saved to ${out.name}", Toast.LENGTH_LONG).show()
                installApk(ctx, out)
            }.onFailure {
                Toast.makeText(ctx, it.message ?: "Save failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedType?.let { it.removePrefix("L").removeSuffix(";").replace('/', '.') } ?: "DEX editor") },
                navigationIcon = {
                    IconButton(onClick = { if (selectedType != null) selectedType = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (names.size > 1) {
                        TextButton(onClick = { dexMenu = true }) { Text(dexName ?: "") }
                        DropdownMenu(expanded = dexMenu, onDismissRequest = { dexMenu = false }) {
                            names.forEach { n ->
                                DropdownMenuItem(text = { Text(n) }, onClick = { dexName = n; dexMenu = false })
                            }
                        }
                    }
                    if (dirty) {
                        TextButton(onClick = ::save) { Text("Save APK") }
                    }
                },
            )
        },
    ) { pad ->
        val d = currentDex
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 8.dp)) {
            when {
                d == null -> Text("Loading…", modifier = Modifier.padding(16.dp))
                selectedType == null -> {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search classes") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    val classes = DexEditor.classes(d)
                        .filter { query.isBlank() || it.type.contains(query, ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(classes, key = { it.type }) { c ->
                            ListItem(
                                headlineContent = {
                                    Text(c.type.removePrefix("L").removeSuffix(";").replace('/', '.'))
                                },
                                supportingContent = { Text("${c.methods} methods · ${c.fields} fields") },
                                modifier = Modifier.clickable { selectedType = c.type },
                            )
                        }
                    }
                }
                else -> {
                    val type = selectedType!!
                    val methods = DexEditor.methods(d, type)
                    val fields = DexEditor.fields(d, type)
                    LazyColumn(Modifier.fillMaxSize().weight(1f)) {
                        item {
                            Text("Fields", style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(fields, key = { "f:${it.name}" }) { f ->
                            ListItem(
                                headlineContent = { Text("${f.type} ${f.name}") },
                                trailingContent = {
                                    IconButton(onClick = { edit { DexEditor.removeField(it, type, f.name) } }) {
                                        Icon(Icons.Default.Delete, "Remove field")
                                    }
                                },
                            )
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Methods", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(vertical = 8.dp))
                                TextButton(onClick = { showAddMethod = true }) {
                                    Icon(Icons.Default.Add, "Add method")
                                    Text("Add method")
                                }
                            }
                        }
                        items(methods, key = { "m:${it.name}(${it.params.joinToString(",")})" }) { m ->
                            ListItem(
                                headlineContent = { Text("${m.returnType} ${m.name}(${m.params.joinToString(", ")})") },
                                trailingContent = {
                                    IconButton(onClick = {
                                        edit { DexEditor.removeMethod(it, type, m.name, m.params) }
                                    }) { Icon(Icons.Default.Delete, "Remove method") }
                                },
                            )
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showAddField = true }) { Text("Add field") }
                                TextButton(onClick = { showRename = true }) { Text("Rename class") }
                            }
                            Text(
                                "Renaming only changes this class's own definition — " +
                                    "calls to it from other classes are not updated.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddMethod) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddMethod = false },
            title = { Text("Add method") },
            text = {
                Column {
                    Text("Adds an empty public void method.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        singleLine = true, label = { Text("Method name") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAddMethod = false
                    val t = selectedType
                    if (t != null) edit { DexEditor.addMethod(it, t, name) }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddMethod = false }) { Text("Cancel") } },
        )
    }
    if (showAddField) {
        var name by remember { mutableStateOf("") }
        var typeDesc by remember { mutableStateOf("I") }
        AlertDialog(
            onDismissRequest = { showAddField = false },
            title = { Text("Add field") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        singleLine = true, label = { Text("Field name") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("I" to "int", "Z" to "boolean", "Ljava/lang/String;" to "String").forEach { (desc, label) ->
                            TextButton(onClick = { typeDesc = desc }) {
                                Text(if (typeDesc == desc) "● $label" else label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAddField = false
                    val t = selectedType
                    if (t != null) edit { DexEditor.addField(it, t, name, typeDesc) }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddField = false }) { Text("Cancel") } },
        )
    }
    if (showRename) {
        var newName by remember { mutableStateOf(selectedType.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename class") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    singleLine = true, label = { Text("New type, e.g. Lcom/example/Foo;") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRename = false
                    val old = selectedType
                    if (old != null) {
                        edit { DexEditor.renameClass(it, old, newName.trim()) }
                        selectedType = newName.trim()
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
}
