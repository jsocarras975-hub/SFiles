package com.superfiles.app.ui

import android.graphics.Typeface
import android.media.MediaPlayer
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen image viewer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, file) {
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            }
        }
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Couldn't load this image.")
            }
        }
    }
}

/** Minimal audio player: play/pause, seek, elapsed time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val player = remember {
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    val duration = remember { player.duration }

    LaunchedEffect(playing) {
        while (isActive && playing) {
            position = player.currentPosition
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = {
                    if (playing) player.pause() else player.start()
                    playing = !playing
                },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play",
                    modifier = Modifier.size(56.dp),
                )
            }
            Slider(
                value = position.toFloat(),
                onValueChange = { player.seekTo(it.toInt()); position = it.toInt() },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(position))
                Text(formatTime(duration), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun formatTime(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Font preview: the same sample text at several sizes, rendered in the font file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontPreviewScreen(file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val typeface = remember {
        runCatching { Typeface.createFromFile(file) }.getOrNull()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            if (typeface == null) {
                Text("This file couldn't be loaded as a font.")
            } else {
                Text("Sample text in this font:", style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            this.typeface = typeface
                            text = "The quick brown fox jumps over the lazy dog\n0123456789"
                            textSize = 24f
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            this.typeface = typeface
                            text = "AaBbCcDdEeFfGg 12345"
                            textSize = 40f
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        }
    }
}
