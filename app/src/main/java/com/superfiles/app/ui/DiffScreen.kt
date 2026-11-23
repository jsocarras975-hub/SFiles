package com.superfiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superfiles.app.data.Diff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shows what changed between two text files, line by line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(a: File, b: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val result by produceState<Diff.Result?>(null, a, b) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Diff.diff(a.readLines(), b.readLines())
            }.getOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${a.name} → ${b.name}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (val r = result) {
                null -> Text("Comparing… (files over 2000 lines can't be diffed)", modifier = Modifier.padding(16.dp))
                else -> {
                    Text(
                        "+${r.added} lines  −${r.removed} lines",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(r.lines) { line ->
                            val bg = when (line.kind) {
                                Diff.Kind.ADDED -> Color(0xFF1B5E20).copy(alpha = 0.35f)
                                Diff.Kind.REMOVED -> Color(0xFFB71C1C).copy(alpha = 0.35f)
                                Diff.Kind.SAME -> Color.Transparent
                            }
                            val sign = when (line.kind) {
                                Diff.Kind.ADDED -> "+"
                                Diff.Kind.REMOVED -> "−"
                                Diff.Kind.SAME -> " "
                            }
                            Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 1.dp)) {
                                Text(
                                    sign, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                Text(
                                    line.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
