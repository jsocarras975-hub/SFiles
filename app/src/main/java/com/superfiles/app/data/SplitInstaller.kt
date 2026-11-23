package com.superfiles.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.superfiles.app.MainActivity
import java.io.File

/** Installs a base APK plus its splits (APKS/XAPK) as one package, via a PackageInstaller session. */
object SplitInstaller {

    /** Starts the install and returns the session id. The system shows its own install prompt. */
    fun installSplits(ctx: Context, apks: List<File>): Int {
        require(apks.isNotEmpty()) { "No APKs given" }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apks.forEachIndexed { i, apk ->
                session.openWrite("split$i", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra("install_session", sessionId)
            val sender = PendingIntent.getActivity(
                ctx, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ).intentSender
            session.commit(sender)
        } finally {
            session.close()
        }
        return sessionId
    }
}
