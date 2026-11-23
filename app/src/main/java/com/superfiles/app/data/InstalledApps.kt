package com.superfiles.app.data

import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File

data class InstalledApp(
    val label: String,
    val pkg: String,
    val versionName: String,
    val apkPath: String,
    val splitCount: Int,
)

object InstalledApps {
    /** Launchable apps on the device, via the launcher intent (no special permission needed). */
    fun list(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, 0)
        return activities.mapNotNull { ri ->
            val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val version = runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull().orEmpty()
            InstalledApp(
                label = ai.loadLabel(pm).toString(),
                pkg = ai.packageName,
                versionName = version,
                apkPath = ai.sourceDir,
                splitCount = ai.splitSourceDirs?.size ?: 0,
            )
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    /** Copies the app's base APK to Downloads/SuperFiles/extracted. */
    fun extract(app: InstalledApp): File {
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SuperFiles/extracted",
        ).apply { mkdirs() }
        val dest = File(destDir, "${app.pkg}.apk")
        File(app.apkPath).copyTo(dest, overwrite = true)
        return dest
    }
}
