package com.superfiles.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.superfiles.app.data.AppInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Details and install for an app bundle: .xapk, .apks or .apkm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleInstallScreen(bundle: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    val info by produceState<AppInstaller.BundleInfo?>(null, bundle) {
        value = withContext(Dispatchers.IO) {
            runCatching { AppInstaller.inspectBundle(ctx, bundle) }.getOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bundle.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            when (val b = info) {
                null -> Text("Reading bundle…")
                else -> {
                    Text(
                        listOfNotNull(
                            b.packageName,
                            b.versionName?.let { "v$it" },
                            "${b.apks.size} APK(s)" + if (b.obbs.isNotEmpty()) " · ${b.obbs.size} OBB file(s)" else "",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Installs every APK in the bundle in one go, then copies any OBB data " +
                            "into Android/obb. No system installer is involved.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = {
                            if (!AppInstaller.canInstall(ctx)) {
                                Toast.makeText(ctx, "Enable \"Install unknown apps\" for SuperFiles first", Toast.LENGTH_LONG).show()
                                AppInstaller.openInstallSettings(ctx)
                                return@Button
                            }
                            scope.launch { AppInstaller.installBundle(ctx, bundle) }
                        },
                        enabled = b.apks.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    ) { Text("Install bundle") }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(b.apks, key = { "apk:${it.absolutePath}" }) { apk ->
                            ListItem(
                                headlineContent = { Text(apk.name) },
                                supportingContent = { Text(formatSize(apk.length())) },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            )
                        }
                        items(b.obbs, key = { "obb:${it.absolutePath}" }) { obb ->
                            ListItem(
                                headlineContent = { Text(obb.name) },
                                supportingContent = { Text("OBB data · ${formatSize(obb.length())}") },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            )
                        }
                    }
                }
            }
        }
    }
}
