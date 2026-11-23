package com.superfiles.app.data

import android.content.Context
import java.io.File

/** Pinned folders, kept in SharedPreferences. */
object Favorites {
    private const val PREFS = "favorites"
    private const val KEY = "paths"

    fun list(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())
            ?.filter { File(it).isDirectory }.orEmpty().sorted()

    fun isFavorite(ctx: Context, path: String) = path in list(ctx)

    fun toggle(ctx: Context, path: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!set.add(path)) set.remove(path)
        prefs.edit().putStringSet(KEY, set).apply()
    }
}
