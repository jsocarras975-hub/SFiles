package com.superfiles.app.data

import android.content.Context
import java.io.File

/** Named shortcuts to files and folders, kept in SharedPreferences. */
object Bookmarks {
    private const val PREFS = "superfiles_bookmarks"
    private const val KEY = "paths"

    fun list(ctx: Context): List<File> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet()).orEmpty()
            .map { File(it) }
            .filter { it.exists() }
            .sortedBy { it.name.lowercase() }
    }

    fun isBookmarked(ctx: Context, path: String): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet()).orEmpty().contains(path)
    }

    fun toggle(ctx: Context, path: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (!set.add(path)) set.remove(path)
        prefs.edit().putStringSet(KEY, set).apply()
    }
}
