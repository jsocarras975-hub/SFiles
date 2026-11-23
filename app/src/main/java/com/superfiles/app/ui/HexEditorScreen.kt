package com.superfiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_HEX_BYTES = 2 * 1024 * 1024

/** Raw byte editor: 16 bytes per row, tap a byte to change it, Save writes it back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HexEditorScreen(file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (file.length() > MAX_HEX_BYTES) error("File is over 2 MB — too large for the hex editor")
                else bytes = file.readBytes()
            }.onFailure { error = it.message ?: "Could not read file" }
        }
    }

    fun save() {
        val b = bytes ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { file.writeBytes(b) }.isSuccess
            }
            if (ok) {
                dirty = false
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name + if (dirty) " *" else "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = ::save, enabled = dirty) { Icon(Icons.Default.Save, "Save") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                error != null -> Text(error!!, modifier = Modifier.padding(16.dp))
                bytes == null -> Text("Loading…", modifier = Modifier.padding(16.dp))
                else -> {
                    val data = bytes!!
                    LazyColumn(Modifier.fillMaxSize()) {
                        val offsets = (data.indices step 16).toList()
                        items(offsets) { off ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) {
                                Text(
                                    "%08X".format(off),
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                for (i in 0 until 16) {
                                    val idx = off + i
                                    if (idx < data.size) {
                                        val b = data[idx].toInt() and 0xFF
                                        Text(
                                            "%02X ".format(b),
                                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                            color = if (idx == selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .background(
                                                    if (idx == selected) MaterialTheme.colorScheme.primary
                                                    else Color.Transparent,
                                                )
                                                .clickable {
                                                    selected = idx
                                                    editText = "%02X".format(b)
                                                }
                                                .padding(horizontal = 1.dp),
                                        )
                                    } else {
                                        Text("   ", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    }
                                }
                                val ascii = (off until minOf(off + 16, data.size)).joinToString("") { idx ->
                                    val c = data[idx].toInt() and 0xFF
                                    if (c in 32..126) c.toChar().toString() else "."
                                }
                                Text(
                                    "  $ascii",
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { idx ->
        val data = bytes ?: return@let
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Byte at 0x%08X".format(idx)) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }) editText = it },
                    label = { Text("Hex value (00–FF)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    editText.toIntOrNull(16)?.let { v ->
                        val copy = data.copyOf()
                        copy[idx] = v.toByte()
                        bytes = copy
                        dirty = true
                    }
                    selected = null
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
}
