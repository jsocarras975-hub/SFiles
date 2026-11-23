package com.superfiles.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Installs APKs, split APKs, and app bundles (XAPK/APKS/APKM) directly through
 * PackageInstaller sessions — SuperFiles does the install itself, with its own
 * progress UI, instead of handing the file to the system installer.
 *
 * On Android 12+ the session is committed with user action not required, so a
 * granted "install unknown apps" permission is enough and no system prompt
 * appears. On Android 11 the system still shows its own confirmation once.
 */
object AppInstaller {

    const val ACTION_STATUS = "com.superfiles.app.INSTALL_STATUS"

    /** What the install dialog shows. */
    data class UiState(
        val active: Boolean = false,
        val label: String = "",
        val detail: String = "",
        val success: Boolean? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val nextId = AtomicInteger(1)

    private data class Outcome(val id: Int, val success: Boolean, val message: String)
    // replay = 1 so an install that finishes just before its awaiter subscribes is not lost;
    // awaiters filter by install id, so a stale replayed outcome is skipped.
    private val outcomes = MutableSharedFlow<Outcome>(replay = 1, extraBufferCapacity = 16)

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun dismiss() {
        _state.value = UiState()
    }

    /** Fire-and-forget install of one or more APKs in a single session (base first). */
    fun installAsync(ctx: Context, apks: List<File>, label: String) {
        scope.launch { install(ctx, apks, label) }
    }

    /** Installs each APK in its own session, one after another. */
    fun installManyAsync(ctx: Context, apks: List<File>) {
        scope.launch {
            for (apk in apks) install(ctx, listOf(apk), apk.name)
        }
    }

    /**
     * Installs [apks] in one PackageInstaller session and suspends until the
     * system reports the result. Returns true on success.
     */
    suspend fun install(ctx: Context, apks: List<File>, label: String): Boolean {
        return installMutex.withLock {
            val id = nextId.getAndIncrement()
            _state.value = UiState(active = true, label = label, detail = "Installing…")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = ctx.packageManager
                    val installer = pm.packageInstaller
                    val basePkg = apks.firstOrNull()?.let {
                        runCatching {
                            pm.getPackageArchiveInfo(it.absolutePath, 0)?.packageName
                        }.getOrNull()
                    }
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    if (Build.VERSION.SDK_INT >= 31) {
                        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                    basePkg?.let { params.setAppPackageName(it) }
                    val sessionId = installer.createSession(params)
                    val session = installer.openSession(sessionId)
                    try {
                        apks.forEachIndexed { i, apk ->
                            session.openWrite("apk$i", 0, apk.length()).use { out ->
                                apk.inputStream().use { it.copyTo(out) }
                                session.fsync(out)
                            }
                        }
                        val statusIntent = Intent(ctx, InstallStatusReceiver::class.java).apply {
                            action = ACTION_STATUS
                            putExtra("install_id", id)
                        }
                        val pi = PendingIntent.getBroadcast(
                            ctx, id, statusIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                        session.commit(pi.intentSender)
                    } finally {
                        session.close()
                    }
                    val outcome = withTimeoutOrNull(10 * 60 * 1000L) {
                        outcomes.first { it.id == id }
                    }
                    outcome?.success == true
                }.getOrElse {
                    _state.value = UiState(active = true, label = label, detail = it.message ?: "Install failed", success = false)
                    false
                }
            }
            if (ok) {
                _state.value = UiState(active = true, label = label, detail = "Installed", success = true)
            }
            ok
        }
    }

    // ---- App bundles (XAPK / APKS / APKM) ----

    data class BundleInfo(
        val packageName: String?,
        val versionName: String?,
        val apks: List<File>,
        val obbs: List<File>,
    )

    /** Unzips [bundle] into the cache dir and lists its APKs (base first) and OBB files. */
    fun inspectBundle(ctx: Context, bundle: File): BundleInfo {
        val dir = File(ctx.cacheDir, "bundle-install/${bundle.nameWithoutExtension}").apply {
            deleteRecursively(); mkdirs()
        }
        ZipFile(bundle).use { z ->
            for (e in z.entries().asSequence()) {
                if (e.isDirectory) continue
                val out = File(dir, e.name).apply { parentFile?.mkdirs() }
                z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        val all = dir.walkTopDown().filter { it.isFile }.toList()
        val apks = all.filter { it.extension.equals("apk", ignoreCase = true) }
        val obbs = all.filter { it.extension.equals("obb", ignoreCase = true) }

        // XAPK (APKPure) and APKM (APKMirror) ship a manifest with the order and package.
        var packageName: String? = null
        var versionName: String? = null
        var manifestOrder: List<String>? = null
        val manifest = all.firstOrNull { it.name == "manifest.json" || it.name == "meta.json" }
        manifest?.let {
            runCatching {
                val json = JSONObject(it.readText())
                packageName = json.optString("package_name").ifEmpty { null }
                versionName = json.optString("version_name").ifEmpty { null }
                if (json.has("apks")) {
                    val arr = json.getJSONArray("apks")
                    manifestOrder = (0 until arr.length()).map { i ->
                        arr.getJSONObject(i).optString("file")
                    }.filter { it.isNotEmpty() }
                }
            }
        }
        if (packageName == null) {
            packageName = apks.firstOrNull()?.let {
                ctx.packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.packageName
            }
        }
        if (versionName == null) {
            versionName = apks.firstOrNull()?.let {
                ctx.packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.versionName
            }
        }
        val ordered = when {
            manifestOrder != null -> apks.sortedBy { a ->
                manifestOrder!!.indexOf(a.name).let { if (it < 0) Int.MAX_VALUE else it }
            }
            else -> apks.sortedWith(
                compareByDescending<File> { it.name.startsWith("base", ignoreCase = true) }
                    .thenByDescending { it.length() },
            )
        }
        return BundleInfo(packageName, versionName, ordered, obbs)
    }

    /** Installs a bundle's APKs, then copies its OBB files into Android/obb. */
    suspend fun installBundle(ctx: Context, bundle: File): Boolean {
        val info = withContext(Dispatchers.IO) { inspectBundle(ctx, bundle) }
        if (info.apks.isEmpty()) {
            _state.value = UiState(active = true, label = bundle.name, detail = "No APKs found in this bundle", success = false)
            return false
        }
        val ok = install(ctx, info.apks, bundle.name)
        if (ok && info.obbs.isNotEmpty()) {
            val pkg = info.packageName
            if (pkg != null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val dest = File(Environment.getExternalStorageDirectory(), "Android/obb/$pkg")
                            .apply { mkdirs() }
                        info.obbs.forEach { obb -> obb.copyTo(File(dest, obb.name), overwrite = true) }
                    }
                }
            }
        }
        return ok
    }

    internal fun onStatus(id: Int, status: Int, message: String?) {
        val success = status == PackageInstaller.STATUS_SUCCESS
        val detail = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Installed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Install was cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked: ${message ?: "unknown reason"}"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with an installed app: ${message ?: ""}"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Incompatible with this device: ${message ?: ""}"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid package: ${message ?: ""}"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
            else -> message ?: "Install failed (code $status)"
        }
        _state.value = _state.value.copy(detail = detail, success = success)
        outcomes.tryEmit(Outcome(id, success, detail))
    }
}

/** Receives PackageInstaller session results and hands them to [AppInstaller]. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != AppInstaller.ACTION_STATUS) return
        val id = intent.getIntExtra("install_id", -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android 11 or a device that insists on a confirmation: show the system's prompt.
            val pending = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            pending?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            return
        }
        AppInstaller.onStatus(id, status, message)
    }
}
