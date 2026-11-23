package com.superfiles.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superfiles.app.data.AppInstaller

/** Global install progress/result dialog, driven by [AppInstaller.state]. */
@Composable
fun InstallStatusDialog() {
    val state by AppInstaller.state.collectAsStateWithLifecycle()
    if (!state.active) return
    val installing = state.success == null
    AlertDialog(
        onDismissRequest = { if (!installing) AppInstaller.dismiss() },
        title = { Text(state.label) },
        text = {
            Column {
                Text(state.detail)
                if (installing) {
                    CircularProgressIndicator(Modifier.padding(top = 16.dp))
                } else if (state.success == true) {
                    Text(
                        "Installed by SuperFiles.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (installing) {
                TextButton(onClick = { AppInstaller.dismiss() }) { Text("Hide") }
            } else {
                TextButton(onClick = { AppInstaller.dismiss() }) { Text("Close") }
            }
        },
    )
}
