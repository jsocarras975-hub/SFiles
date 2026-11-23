package com.superfiles.app.ui

import android.graphics.BitmapFactory
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superfiles.app.data.ApkInspector
import com.superfiles.app.data.ApkSearch
import com.superfiles.app.data.AxmlEditor
import com.superfiles.app.data.BuildHistory
import com.superfiles.app.data.FileReplacementStaging
import com.superfiles.app.data.SearchHit
import com.superfiles.app.data.XmlAttr
import com.superfiles.app.data.XmlStaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import com.superfiles.app.data.ApkEditor
import com.superfiles.app.data.ApkOverview
import com.superfiles.app.data.ManifestEdits
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.superfiles.app.data.ResourceEditor
import com.superfiles.app.data.ResourceEntry
import com.superfiles.app.data.ResourceStaging
import com.superfiles.app.data.ResourceTable
import com.superfiles.app.data.SigningKeys
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import com.superfiles.app.data.SmaliEdit
import com.superfiles.app.data.SmaliStaging
import com.superfiles.app.data.SmaliTools
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkInspectorScreen(apk: File, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Manifest", "Files", "Classes", "Resources", "Smali", "Search", "Edit")
    // Hoisted so the Search tab can jump straight to a class or a filtered resource list.
    var openClass by remember { mutableStateOf<SmaliTools.ClassRef?>(null) }
    var resQuery by remember { mutableStateOf("") }
    var showTranslate by remember { mutableStateOf(false) }
    if (showTranslate) {
        TranslationScreen(apk) { showTranslate = false }
        return
    }
    var showDexEditor by remember { mutableStateOf(false) }
    if (showDexEditor) {
        DexEditorScreen(apk) { showDexEditor = false }
        return
    }
    var showDecompile by remember { mutableStateOf(false) }
    if (showDecompile) {
        DecompileScreen(apk) { showDecompile = false }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(apk.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> OverviewTab(apk)
                1 -> ManifestTab(apk)
                2 -> FilesTab(apk)
                3 -> ClassesTab(apk)
                4 -> ResourcesTab(apk, resQuery)
                5 -> SmaliTab(apk, openClass) { openClass = it }
                6 -> SearchTab(apk,
                    onOpenClass = { openClass = it; tab = 5 },
                    onOpenResources = { resQuery = it; tab = 4 })
                else -> EditTab(apk, { showTranslate = true }, { showDexEditor = true }, { showDecompile = true })
            }
        }
    }
}

@Composable
private fun <T> Loaded(key: Any, load: () -> T, content: @Composable (T) -> Unit) {
    val result by produceState<Result<T>?>(null, key) {
        value = withContext(Dispatchers.IO) { runCatching(load) }
    }
    val r = result
    when {
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        r.isSuccess -> content(r.getOrThrow())
        else -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Couldn't read this APK: ${r.exceptionOrNull()?.message}")
        }
    }
}

private fun LazyListScope.section(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    item { Text("$title (${lines.size})", style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp)) }
    items(lines) { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun OverviewTab(apk: File) {
    val ctx = LocalContext.current
    Loaded(apk, { ApkInspector.overview(ctx, apk) }) { o ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(o.label.ifEmpty { o.pkg }, style = MaterialTheme.typography.headlineSmall) }
            item { Text(o.pkg, style = MaterialTheme.typography.bodyMedium) }
            item { Text("Version ${o.versionName} (${o.versionCode})") }
            item { Text("Min SDK ${o.minSdk} · Target SDK ${o.targetSdk}") }
            item { Text("Size ${formatSize(apk.length())}") }
            item {
                val s = o.sig
                Text("Signature: v1 ${if (s.v1) "✓" else "✗"} · v2 ${if (s.v2) "✓" else "✗"} · " +
                    "v3 ${if (s.v3) "✓" else "✗"}" + if (s.v31) " · v3.1 ✓" else "")
            }
            item { SignatureVerificationLine(apk, o.sig) }
            section("Signing certificates", o.certs.map {
                "${it.subject}\nSHA-256 ${it.sha256}\n${it.validFrom} to ${it.validTo}"
            })
            section("Permissions", o.permissions)
            section("Activities", o.activities)
            section("Services", o.services)
            section("Receivers", o.receivers)
            section("Providers", o.providers)
        }
    }
}

/** Cryptographically verifies the APK's signatures in the background and shows
 *  whether they actually check out — presence alone (the line above) can't tell
 *  a valid signature from a tampered one. */
@Composable
private fun SignatureVerificationLine(apk: File, sig: SignatureStatus) {
    val check by produceState<SigVerification?>(null, apk) {
        value = withContext(Dispatchers.IO) { runCatching { SignatureCheck.verify(apk) }.getOrNull() }
    }
    val text = when {
        check == null -> "Verifying signatures…"
        !sig.v1 && !sig.v2 && !sig.v3 && !sig.v31 -> "Not signed"
        check.verified -> "Signatures verified ✓ (v1 ${if (check.v1Verified) "✓" else "✗"} · " +
            "v2 ${if (check.v2Verified) "✓" else "✗"} · v3 ${if (check.v3Verified) "✓" else "✗"})"
        else -> "Signature INVALID: ${check.errors.firstOrNull() ?: "verification failed"}"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ManifestTab(apk: File) {
    Loaded(apk, { ApkInspector.manifest(apk).lines() }) { lines ->
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.padding(horizontal = 8.dp)) {
                items(lines) {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun FilesTab(apk: File) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var pendingReplace by remember { mutableStateOf<String?>(null) }
    var xmlFile by remember { mutableStateOf<String?>(null) }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val entry = pendingReplace
        pendingReplace = null
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Couldn't read the file")
                    FileReplacementStaging.put(ctx, apk, entry, bytes)
                }
            }
            status = if (r.isSuccess) "$entry will be replaced in the next build"
            else "Replace failed: ${r.exceptionOrNull()?.message}"
        }
    }
    val openXml = xmlFile
    if (openXml != null) {
        XmlEditor(apk, openXml) { xmlFile = null }
        return
    }
    Loaded(apk, { ApkInspector.entries(apk) }) { rows ->
        var q by remember { mutableStateOf("") }
        val shown = remember(rows, q) { rows.filter { it.name.contains(q, ignoreCase = true) } }
        Column(Modifier.fillMaxSize()) {
            SearchBox(q, "Filter ${rows.size} files") { q = it }
            status?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.name }) { r ->
                    val isXml = r.name.endsWith(".xml", ignoreCase = true) &&
                        r.name != "AndroidManifest.xml"
                    val isImage = r.name.endsWith(".png", ignoreCase = true) ||
                        r.name.endsWith(".jpg", ignoreCase = true) ||
                        r.name.endsWith(".jpeg", ignoreCase = true) ||
                        r.name.endsWith(".webp", ignoreCase = true)
                    ListItem(
                        headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${formatSize(r.size)} · packed ${formatSize(r.compressed)}" +
                                if (FileReplacementStaging.has(apk, r.name)) " · replaced in next build" else "")
                        },
                        leadingContent = if (isImage) {
                            { ApkImageThumb(apk, r.name) }
                        } else null,
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val dir = File(
                                                    Environment.getExternalStoragePublicDirectory(
                                                        Environment.DIRECTORY_DOWNLOADS
                                                    ),
                                                    "SuperFiles/extracted",
                                                ).apply { mkdirs() }
                                                ApkInspector.extractEntry(apk, r.name, dir)
                                            }
                                        }
                                        status = if (result.isSuccess) {
                                            "Saved to Downloads/SuperFiles/extracted/${r.name}"
                                        } else {
                                            "Extract failed: ${result.exceptionOrNull()?.message}"
                                        }
                                    }
                                }) { Icon(Icons.Default.Download, "Extract") }
                                IconButton(onClick = {
                                    if (r.name == "AndroidManifest.xml" || r.name == "resources.arsc" ||
                                        r.name.matches(Regex("classes\\d*\\.dex"))
                                    ) {
                                        status = "Use the Edit, Resources or Smali tab for ${r.name}"
                                    } else {
                                        pendingReplace = r.name
                                        replacePicker.launch("*/*")
                                    }
                                }) { Icon(Icons.Default.SwapHoriz, "Replace") }
                            }
                        },
                        modifier = Modifier.clickable(enabled = isXml) { xmlFile = r.name },
                    )
                }
            }
        }
    }
}

/** A small thumbnail for an image entry inside the APK. */
@Composable
private fun ApkImageThumb(apk: File, name: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, apk, name) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ApkInspector.readEntry(apk, name)
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
    }
}

/** Edits the string attributes of one binary XML file inside the APK. */
@Composable
private fun XmlEditor(apk: File, entryName: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var editing by remember { mutableStateOf<XmlAttr?>(null) }
    var editText by remember { mutableStateOf("") }
    Loaded(apk to entryName, {
        AxmlEditor.listStringAttrs(ApkInspector.readEntry(apk, entryName))
    }) { attrs ->
        var q by remember { mutableStateOf("") }
        val shown = remember(attrs, q) { attrs.filter { it.id.contains(q, ignoreCase = true) } }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text(entryName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
            SearchBox(q, "Filter ${attrs.size} attributes") { q = it }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { a ->
                    ListItem(
                        headlineContent = {
                            Text(a.id, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        },
                        supportingContent = {
                            Text(a.value + if (XmlStaging.has(apk, entryName, a.id)) " · edited" else "",
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.clickable {
                            editing = a
                            editText = a.value
                        },
                    )
                }
            }
        }
    }
    editing?.let { a ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(a.id) },
            text = {
                OutlinedTextField(editText, { editText = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    XmlStaging.put(apk, entryName, a.id, editText)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClassesTab(apk: File) {
    Loaded(apk, { ApkInspector.classes(apk) }) { classes ->
        var q by remember { mutableStateOf("") }
        val shown = remember(classes, q) { classes.filter { it.contains(q, ignoreCase = true) } }
        Column(Modifier.fillMaxSize()) {
            SearchBox(q, "Filter ${classes.size} classes") { q = it }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { name ->
                    ListItem(headlineContent = {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp)
                    })
                }
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(hint) },
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    )
}


private val PKG_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

@Composable
private fun EditTab(apk: File, onTranslate: () -> Unit, onDexEditor: () -> Unit, onDecompile: () -> Unit) {
    val ctx = LocalContext.current
    Loaded(apk, { ApkInspector.overview(ctx, apk) }) { o -> EditForm(apk, o, onTranslate, onDexEditor, onDecompile) }
}

@Composable
private fun EditForm(apk: File, o: ApkOverview, onTranslate: () -> Unit, onDexEditor: () -> Unit, onDecompile: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pkg by remember { mutableStateOf(o.pkg) }
    var vName by remember { mutableStateOf(o.versionName) }
    var vCode by remember { mutableStateOf(o.versionCode.toString()) }
    var removed by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var smaliCount by remember { mutableIntStateOf(SmaliStaging.count(apk)) }
    var resCount by remember { mutableIntStateOf(ResourceStaging.count(apk)) }
    var fileCount by remember { mutableIntStateOf(FileReplacementStaging.count(apk)) }
    var xmlCount by remember { mutableIntStateOf(XmlStaging.count(apk)) }
    var newPerm by remember { mutableStateOf("") }
    var addedPerms by remember { mutableStateOf(listOf<String>()) }
    var showQuick by remember { mutableStateOf(false) }
    var quickAttrs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var quickMinSdk by remember { mutableStateOf<Int?>(null) }
    var quickTargetSdk by remember { mutableStateOf<Int?>(null) }
    var keyInfo by remember { mutableStateOf("Loading...") }
    var keyVersion by remember { mutableIntStateOf(0) }
    var pendingKey by remember { mutableStateOf<Uri?>(null) }
    var keyPass by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pendingKey = it }
    var pendingInstall by remember { mutableStateOf<File?>(null) }
    val settingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (ctx.packageManager.canRequestPackageInstalls()) {
                pendingInstall?.let { installApk(ctx, it); pendingInstall = null }
            }
        }

    /** Installs [f], opening the unknown-sources setting first if needed and resuming
     *  automatically when the user comes back with it allowed. */
    fun install(f: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            pendingInstall = f
            settingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            )
            status = "Allow installing apps from SuperFiles, then it will continue."
            return
        }
        installApk(ctx, f)
    }

    /** Builds and signs the APK. With [clone], the package gets ".clone" appended and the
     *  result is installed right away, so the copy runs side-by-side with the original. */
    fun doBuild(clone: Boolean) {
        val code = vCode.toLongOrNull()
        val targetPkg = if (clone) {
            val base = pkg.trim()
            if (base.endsWith(".clone")) base else "$base.clone"
        } else pkg
        when {
            !PKG_REGEX.matches(targetPkg) -> status = "Invalid package name"
            code == null || code !in 0..Int.MAX_VALUE.toLong() ->
                status = "Version code must be a whole number"
            else -> {
                val edits = ManifestEdits(
                    newPackage = targetPkg.takeIf { it != o.pkg },
                    versionName = vName.takeIf { it != o.versionName },
                    versionCode = code.takeIf { it != o.versionCode },
                    removePermissions = removed,
                    addPermissions = addedPerms,
                    manifestAttrs = quickAttrs,
                    minSdk = quickMinSdk,
                    targetSdk = quickTargetSdk,
                )
                val out = File(apk.parentFile, apk.nameWithoutExtension + "-mod.apk")
                busy = true
                output = null
                status = "Building and signing..."
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val smali = SmaliStaging.list(apk)
                            val dex = if (smali.isEmpty()) emptyMap()
                            else SmaliTools.buildDexes(ctx, apk, smali)
                            val resEdits = ResourceStaging.list(apk)
                            val arsc = if (resEdits.isEmpty()) null
                            else ResourceEditor.apply(apk, resEdits)
                            val xml = XmlStaging.list(apk).mapValues { (entry, edits) ->
                                AxmlEditor.editAny(ApkInspector.readEntry(apk, entry), edits)
                            }
                            val files = FileReplacementStaging.list(apk)
                            ApkEditor.build(ctx, apk, edits, o.minSdk, out, dex + files, arsc, xml)
                        }
                    }
                    busy = false
                    if (r.isSuccess) {
                        status = "Saved ${out.name}"
                        output = out
                        BuildHistory.add(ctx, out, apk.name)
                        if (clone) install(out)
                    } else {
                        status = "Failed: ${r.exceptionOrNull()?.message}"
                    }
                }
            }
        }
    }

    /** Strips the old signatures and re-signs with this device's key, so the APK
     *  installs straight away — the plain strip leaves an unsigned file. */
    fun stripAndSign() {
        val out = File(apk.parentFile, apk.nameWithoutExtension + "-resigned.apk")
        busy = true
        output = null
        status = "Stripping & signing..."
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(ctx.cacheDir, "stripped-unsigned.apk")
                    try {
                        ApkEditor.stripSignatures(apk, tmp)
                        ApkEditor.signApk(ctx, tmp, out, o.minSdk)
                    } finally {
                        tmp.delete()
                    }
                }
            }
            busy = false
            if (r.isSuccess) {
                status = "Saved ${out.name} (${formatSize(out.length())}) — signed, ready to install"
                output = out
                BuildHistory.add(ctx, out, apk.name)
            } else {
                status = "Failed: ${r.exceptionOrNull()?.message}"
            }
        }
    }

    /** Writes a copy of the APK with signatures stripped, or optimized (smaller, deduped). */
    fun stripOrOptimize(optimize: Boolean) {
        val suffix = if (optimize) "-optimized.apk" else "-stripped.apk"
        val out = File(apk.parentFile, apk.nameWithoutExtension + suffix)
        busy = true
        output = null
        status = if (optimize) "Optimizing..." else "Stripping signatures..."
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (optimize) ApkEditor.optimize(apk, out) else ApkEditor.stripSignatures(apk, out)
                }
            }
            busy = false
            if (r.isSuccess) {
                status = "Saved ${out.name} (${formatSize(out.length())})"
                output = out
            } else {
                status = "Failed: ${r.exceptionOrNull()?.message}"
            }
        }
    }

    LaunchedEffect(keyVersion) {
        keyInfo = withContext(Dispatchers.IO) {
            runCatching { SigningKeys.describe(ctx) }.getOrElse { "Error: ${it.message}" }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Replace the signing key?") },
            text = {
                Text("APKs you already built with the current key can't be updated in place " +
                    "with the new one — they would need a fresh install. The old key is deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    SigningKeys.reset(ctx); keyVersion++
                    status = "Signing key replaced"
                }) { Text("Replace key") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    pendingKey?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingKey = null; keyPass = "" },
            title = { Text("Keystore password") },
            text = {
                OutlinedTextField(keyPass, { keyPass = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            },
            confirmButton = {
                TextButton(onClick = {
                    val pass = keyPass
                    pendingKey = null
                    keyPass = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: error("Couldn't read the file")
                                SigningKeys.import(ctx, bytes, pass.toCharArray())
                            }
                        }
                        status = if (r.isSuccess) "Signing key imported"
                        else "Import failed: ${r.exceptionOrNull()?.message}"
                        keyVersion++
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingKey = null; keyPass = "" }) { Text("Cancel") } },
        )
    }

    if (showQuick) {
        QuickManifestDialog(
            apk = apk,
            onApply = { attrs, min, target ->
                quickAttrs = attrs
                quickMinSdk = min
                quickTargetSdk = target
                showQuick = false
            },
            onStaged = {
                resCount = ResourceStaging.count(apk)
                fileCount = FileReplacementStaging.count(apk)
            },
            onDismiss = { showQuick = false },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Signing key", style = MaterialTheme.typography.titleSmall) }
        item { Text(keyInfo, style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickKey.launch(arrayOf("*/*")) }) { Text("Import keystore") }
                TextButton(onClick = { confirmReset = true }) { Text("Use generated key") }
            }
        }
        item {
            OutlinedTextField(pkg, { pkg = it }, label = { Text("Package name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(vName, { vName = it }, label = { Text("Version name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(vCode, { vCode = it }, label = { Text("Version code") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        item {
            Text("Remove permissions", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp))
        }
        items(o.permissions) { p ->
            val on = p in removed
            Row(
                Modifier.fillMaxWidth().clickable { removed = if (on) removed - p else removed + p },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = { removed = if (it) removed + p else removed - p })
                Text(p, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text("Add permissions", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newPerm, { newPerm = it }, label = { Text("Permission name") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    val p = newPerm.trim()
                    when {
                        !PKG_REGEX.matches(p) -> status = "Invalid permission name"
                        p in o.permissions || p in addedPerms -> status = "Already declared"
                        else -> { addedPerms = addedPerms + p; newPerm = "" }
                    }
                }) { Text("Add") }
            }
        }
        items(addedPerms) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { addedPerms = addedPerms - p }) { Text("Remove") }
            }
        }
        if (smaliCount > 0) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$smaliCount smali edit(s) will be included", Modifier.weight(1f))
                    TextButton(onClick = { SmaliStaging.clear(apk); smaliCount = 0 }) { Text("Discard") }
                }
            }
        }
        if (resCount > 0) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$resCount resource edit(s) will be included", Modifier.weight(1f))
                    TextButton(onClick = { ResourceStaging.clear(apk); resCount = 0 }) { Text("Discard") }
                }
            }
        }
        if (fileCount > 0) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$fileCount file replacement(s) will be included", Modifier.weight(1f))
                    TextButton(onClick = { FileReplacementStaging.clear(apk); fileCount = 0 }) { Text("Discard") }
                }
            }
        }
        if (xmlCount > 0) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$xmlCount XML edit(s) will be included", Modifier.weight(1f))
                    TextButton(onClick = { XmlStaging.clear(apk); xmlCount = 0 }) { Text("Discard") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { doBuild(false) }) { Text("Build and sign") }
                Button(enabled = !busy, onClick = { doBuild(true) }) { Text("Clone app") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showQuick = true }) { Text("Quick manifest edits") }
                Button(onClick = onTranslate) { Text("Translate strings") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { stripOrOptimize(false) }) { Text("Strip signatures") }
                Button(enabled = !busy, onClick = { stripOrOptimize(true) }) { Text("Optimize APK") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { stripAndSign() }) { Text("Strip & sign") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDexEditor) { Text("DEX editor") }
                Button(onClick = onDecompile) { Text("Decompile / rebuild") }
            }
        }
        if (quickAttrs.isNotEmpty() || quickMinSdk != null || quickTargetSdk != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick manifest edits will be included", Modifier.weight(1f))
                    TextButton(onClick = { quickAttrs = emptyMap(); quickMinSdk = null; quickTargetSdk = null }) {
                        Text("Discard")
                    }
                }
            }
        }
        status?.let { item { Text(it) } }
        output?.let { f -> item { Button(onClick = { install(f) }) { Text("Install") } } }
    }
}

/** One dialog for the common manifest tweaks: app name, icon, install location, SDK levels. */
@Composable
private fun QuickManifestDialog(
    apk: File,
    onApply: (attrs: Map<String, String>, minSdk: Int?, targetSdk: Int?) -> Unit,
    onStaged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val manifest by produceState<String?>(null, apk) {
        value = withContext(Dispatchers.IO) { runCatching { ApkInspector.manifest(apk) }.getOrNull() }
    }
    fun attr(name: String): String? =
        manifest?.let { Regex("""$name="([^"]*)"""").find(it)?.groupValues?.get(1) }
    val curLabel = attr("android:label")
    val curIcon = attr("android:icon")
    val curInstall = attr("android:installLocation") ?: "auto"
    val curMin = attr("android:minSdkVersion") ?: ""
    val curTarget = attr("android:targetSdkVersion") ?: ""

    var appName by remember(curLabel) { mutableStateOf(curLabel ?: "") }
    var installLoc by remember(curInstall) { mutableStateOf(curInstall) }
    var minSdkText by remember(curMin) { mutableStateOf(curMin) }
    var targetSdkText by remember(curTarget) { mutableStateOf(curTarget) }
    var iconName by remember { mutableStateOf<String?>(null) }
    var iconBytes by remember { mutableStateOf<ByteArray?>(null) }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null) {
                iconBytes = bytes
                iconName = uri.lastPathSegment ?: "image"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick manifest edits") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (manifest == null) {
                    Text("Loading manifest…", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("App name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (curLabel?.startsWith("@string/") == true) {
                    Text(
                        "The label is $curLabel — editing it changes that string resource.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("auto", "internalOnly", "preferExternal").forEach { loc ->
                        FilterChip(
                            selected = installLoc == loc,
                            onClick = { installLoc = loc },
                            label = { Text(loc) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minSdkText,
                        onValueChange = { minSdkText = it.filter { c -> c.isDigit() } },
                        label = { Text("Min SDK") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = targetSdkText,
                        onValueChange = { targetSdkText = it.filter { c -> c.isDigit() } },
                        label = { Text("Target SDK") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { pickIcon.launch("image/*") }) { Text("Pick icon") }
                    iconName?.let { Text(it, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall) }
                    curIcon?.let { Text("Current: $it", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val attrs = mutableMapOf<String, String>()
                val label = appName.trim()
                if (label.isNotEmpty() && label != curLabel) {
                    if (curLabel?.startsWith("@string/") == true) {
                        ResourceStaging.put(apk, "string/" + curLabel.removePrefix("@string/"), label)
                        onStaged()
                    } else {
                        attrs["manifest/application@android:label"] = label
                    }
                }
                if (installLoc != curInstall) attrs["manifest@android:installLocation"] = installLoc
                val min = minSdkText.toIntOrNull()?.takeIf { it.toString() != curMin }
                val target = targetSdkText.toIntOrNull()?.takeIf { it.toString() != curTarget }
                val bytes = iconBytes
                if (bytes != null && curIcon?.startsWith("@") == true) {
                    val ref = curIcon.removePrefix("@")
                    val parts = ref.split("/")
                    if (parts.size == 2) {
                        val (type, name) = parts
                        val entries = runCatching { ApkInspector.entries(apk) }.getOrNull().orEmpty()
                            .filter { it.name.matches(Regex("res/$type[^/]*/$name\\.(png|webp|jpg|jpeg)")) }
                        entries.forEach { FileReplacementStaging.put(ctx, apk, it.name, bytes) }
                        if (entries.isNotEmpty()) onStaged()
                    }
                }
                onApply(attrs, min, target)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ResourcesTab(apk: File, initialQuery: String = "") {
    var editing by remember { mutableStateOf<ResourceEntry?>(null) }
    var editText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    Loaded(apk, { ResourceTable.entries(apk) }) { rows ->
        var q by remember(initialQuery) { mutableStateOf(initialQuery) }
        var config by remember { mutableStateOf("All") }
        val configs = remember(rows) { listOf("All") + rows.map { it.entry.config }.distinct().sorted() }
        val shown = remember(rows, q, config) {
            rows.filter {
                (config == "All" || it.entry.config == config) && it.display.contains(q, ignoreCase = true)
            }
        }
        Column(Modifier.fillMaxSize()) {
            SearchBox(q, "Filter ${rows.size} resources") { q = it }
            if (configs.size > 2) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    configs.forEach { c ->
                        FilterChip(selected = config == c, onClick = { config = c }, label = { Text(c) })
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { e ->
                    ListItem(
                        headlineContent = {
                            Text(e.display, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        },
                        supportingContent = {
                            when {
                                e.entry.complex -> Text("Not editable")
                                ResourceStaging.has(apk, e.entry.id) ->
                                    Text("Edited — included in the next build")
                            }
                        },
                        modifier = Modifier.clickable(
                            enabled = !e.entry.complex,
                            onClick = {
                                editing = e
                                editText = e.valueText
                                editError = null
                            },
                        ),
                    )
                }
            }
        }
    }
    editing?.let { e ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(e.entry.id) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(editText, { editText = it; editError = null }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Text(hintFor(e.valueType), style = MaterialTheme.typography.bodySmall)
                    editError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = runCatching { ResourceEditor.parseValue(e.valueType, editText) }
                    if (r.isSuccess) {
                        ResourceStaging.put(apk, e.entry.id, editText.trim())
                        editing = null
                    } else {
                        editError = r.exceptionOrNull()?.message
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun hintFor(valueType: Int): String = when (valueType) {
    0x03 -> "Text"
    0x10 -> "Whole number"
    0x11 -> "Hex number, e.g. 0x1F"
    0x12 -> "true or false"
    0x04 -> "Decimal number"
    in 0x1c..0x1f -> "Color like #RRGGBB or #AARRGGBB"
    0x01, 0x02 -> "Hex resource id, e.g. 0x7f010001"
    else -> "This value type can't be edited"
}


@Composable
private fun SmaliTab(apk: File, openRef: SmaliTools.ClassRef?, onOpen: (SmaliTools.ClassRef?) -> Unit) {
    val ref = openRef
    if (ref == null) SmaliList(apk) { onOpen(it) } else SmaliEditor(apk, ref) { onOpen(null) }
}

@Composable
private fun SearchTab(
    apk: File,
    onOpenClass: (SmaliTools.ClassRef) -> Unit,
    onOpenResources: (String) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    var query by remember { mutableStateOf<String?>(null) }
    val results by produceState<List<SearchHit>?>(null, query) {
        value = query?.let {
            withContext(Dispatchers.IO) { runCatching { ApkSearch.search(apk, it) }.getOrNull() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q, { q = it }, singleLine = true,
                placeholder = { Text("Search the whole APK") }, modifier = Modifier.weight(1f))
            Button(onClick = { query = q }, enabled = q.isNotBlank()) { Text("Search") }
        }
        val hits = results
        when {
            query == null -> Text("Searches the manifest, dex files, resources and file names.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            hits == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            hits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches")
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(hits) { h ->
                    ListItem(
                        headlineContent = { Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = h.snippet.takeIf { it.isNotEmpty() }?.let { s ->
                            { Text(s, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        },
                        modifier = Modifier.clickable {
                            when (h) {
                                is SearchHit.DexClass -> onOpenClass(h.ref)
                                is SearchHit.Resource -> onOpenResources(q)
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmaliList(apk: File, onOpen: (SmaliTools.ClassRef) -> Unit) {
    Loaded(apk, { SmaliTools.classRefs(apk) }) { refs ->
        var q by remember { mutableStateOf("") }
        val shown = remember(refs, q) { refs.filter { it.display.contains(q, ignoreCase = true) } }
        Column(Modifier.fillMaxSize()) {
            SearchBox(q, "Search ${refs.size} classes to edit") { q = it }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { r ->
                    ListItem(
                        headlineContent = {
                            Text(r.display, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        },
                        supportingContent = {
                            Text(r.dex + if (SmaliStaging.has(apk, r.type)) " · edited" else "")
                        },
                        modifier = Modifier.clickable { onOpen(r) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmaliEditor(apk: File, ref: SmaliTools.ClassRef, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf<TextFieldValue?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var findIndex by remember { mutableIntStateOf(-1) }
    var lineText by remember { mutableStateOf("") }
    var showMethods by remember { mutableStateOf(false) }

    LaunchedEffect(ref) {
        loadError = null
        val r = withContext(Dispatchers.IO) {
            runCatching { SmaliStaging.get(apk, ref.type)?.text ?: SmaliTools.disassemble(apk, ref) }
        }
        field = r.getOrNull()?.let { TextFieldValue(it) }
        if (r.isFailure) loadError = "Couldn't disassemble: ${r.exceptionOrNull()?.message}"
    }

    val t = field?.text
    val matches = remember(t, findText) {
        if (t.isNullOrEmpty() || findText.isEmpty()) emptyList()
        else {
            val list = ArrayList<IntRange>()
            var i = t.indexOf(findText)
            while (i >= 0 && list.size < 1000) {
                list += i until i + findText.length
                i = t.indexOf(findText, i + findText.length)
            }
            list
        }
    }
    val methods = remember(t) {
        t?.lines()?.mapIndexedNotNull { i, line ->
            val s = line.trim()
            if (s.startsWith(".method")) i to s else null
        }.orEmpty()
    }

    fun jumpToMatch(next: Boolean) {
        val f = field ?: return
        if (matches.isEmpty()) return
        val idx = if (findIndex < 0) 0
        else if (next) (findIndex + 1) % matches.size
        else (findIndex - 1 + matches.size) % matches.size
        findIndex = idx
        val r = matches[idx]
        field = f.copy(selection = TextRange(r.first, r.last + 1))
    }

    fun jumpToLine(n: Int) {
        val f = field ?: return
        val text = f.text
        if (text.isEmpty()) return
        val lines = text.split('\n')
        val target = n.coerceIn(1, lines.size)
        var offset = 0
        for (i in 0 until target - 1) offset += lines[i].length + 1
        field = f.copy(selection = TextRange(offset.coerceAtMost(text.length)))
    }

    fun replaceCurrent() {
        val f = field ?: return
        if (findIndex < 0 || findIndex >= matches.size) return
        val r = matches[findIndex]
        val newText = f.text.replaceRange(r, replaceText)
        field = f.copy(text = newText, selection = TextRange(r.first + replaceText.length))
        findIndex = -1
    }

    fun replaceAll() {
        val f = field ?: return
        if (findText.isEmpty() || matches.isEmpty()) return
        val count = matches.size
        field = f.copy(text = f.text.replace(findText, replaceText))
        findIndex = -1
        status = "Replaced $count occurrence(s)"
    }

    if (showMethods) {
        AlertDialog(
            onDismissRequest = { showMethods = false },
            title = { Text("Methods (${methods.size})") },
            text = {
                LazyColumn {
                    items(methods) { (line, name) ->
                        TextButton(onClick = {
                            showMethods = false
                            jumpToLine(line + 1)
                        }) {
                            Text("L${line + 1}: ${name.take(80)}", maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMethods = false }) { Text("Close") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(ref.display, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !busy && field != null && loadError == null,
                onClick = {
                    val text = field?.text
                    if (text != null) {
                        busy = true
                        status = "Assembling..."
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { SmaliTools.check(ctx, text, ref.type) }
                            }
                            busy = false
                            if (r.isSuccess) {
                                SmaliStaging.put(apk, SmaliEdit(ref.dex, ref.type, text))
                                status = "Saved. It goes into the next build from the Edit tab."
                            } else {
                                status = "Error: ${r.exceptionOrNull()?.message}"
                            }
                        }
                    }
                },
            ) { Text("Save") }
        }
        status?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        }
        val lines = t?.count { it == '\n' }?.plus(1) ?: 0
        if (lines > 3000) {
            Text("Large class ($lines lines) — editing may be slow.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        if (loadError != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(loadError!!)
            }
        } else if (field == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(findText, { findText = it; findIndex = -1 }, singleLine = true,
                        placeholder = { Text("Find") }, modifier = Modifier.weight(1f))
                    TextButton(enabled = matches.isNotEmpty(), onClick = { jumpToMatch(false) }) { Text("◀") }
                    TextButton(enabled = matches.isNotEmpty(), onClick = { jumpToMatch(true) }) { Text("▶") }
                    if (findText.isNotEmpty()) {
                        Text("${if (findIndex >= 0) findIndex + 1 else 0}/${matches.size}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(replaceText, { replaceText = it }, singleLine = true,
                        placeholder = { Text("Replace with") }, modifier = Modifier.weight(1f))
                    TextButton(enabled = findIndex >= 0, onClick = { replaceCurrent() }) { Text("Replace") }
                    TextButton(enabled = matches.isNotEmpty(), onClick = { replaceAll() }) { Text("All") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(lineText, { lineText = it }, singleLine = true,
                        placeholder = { Text("Line") }, modifier = Modifier.width(96.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    TextButton(onClick = { lineText.toIntOrNull()?.let { jumpToLine(it) } }) { Text("Go") }
                    TextButton(onClick = { showMethods = true }) { Text("Methods (${methods.size})") }
                }
            }
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                BasicTextField(
                    value = field ?: TextFieldValue(),
                    onValueChange = { field = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
