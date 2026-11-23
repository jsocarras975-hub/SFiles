package com.superfiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superfiles.app.data.FtpServer
import java.io.File

/** Start/stop the FTP server and show how to reach it from a computer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpScreen(root: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val running by FtpServer.running.collectAsStateWithLifecycle()
    val log by FtpServer.log.collectAsStateWithLifecycle()
    val ip = remember { FtpServer.localIp(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FTP server") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Text(
                "Shares your internal storage over the local Wi-Fi network only. " +
                    "It stops when you leave this screen or tap Stop — nothing is exposed to the internet.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (ip == null) {
                Text(
                    "No Wi-Fi connection found. Connect to Wi-Fi first.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Text(
                    "ftp://$ip:${FtpServer.PORT}/",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Open that address in a file manager or FTP client on your computer. " +
                        "Any username and password work.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (running) {
                OutlinedButton(
                    onClick = { FtpServer.stop() },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text("Stop server") }
            } else {
                Button(
                    onClick = { FtpServer.start(root) },
                    enabled = ip != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text("Start server") }
            }
            Text(
                "Status: ${if (running) "running" else "stopped"}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(log) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
