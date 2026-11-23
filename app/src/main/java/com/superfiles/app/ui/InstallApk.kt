package com.superfiles.app.ui

import android.content.Context
import com.superfiles.app.data.AppInstaller
import java.io.File

/** Installs [f] through SuperFiles' own PackageInstaller session — no system installer. */
fun installApk(ctx: Context, f: File) {
    if (!AppInstaller.canInstall(ctx)) {
        AppInstaller.openInstallSettings(ctx)
        return
    }
    AppInstaller.installAsync(ctx, listOf(f), f.name)
}
