package com.superfiles.app.data

import android.content.Context
import java.io.File

/**
 * Installs a base APK plus its splits as one package. Delegates to
 * [AppInstaller], which runs the PackageInstaller session itself with its own
 * progress UI instead of handing off to the system installer.
 */
object SplitInstaller {

    /** Starts the install and returns the number of APKs given. */
    fun installSplits(ctx: Context, apks: List<File>): Int {
        require(apks.isNotEmpty()) { "No APKs given" }
        AppInstaller.installAsync(ctx, apks, "${apks.size} split APKs")
        return apks.size
    }
}
