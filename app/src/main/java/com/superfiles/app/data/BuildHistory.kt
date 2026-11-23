package com.superfiles.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BuildRecord(val path: String, val sourceName: String, val time: Long, val size: Long)

object BuildHistory {
    private const val PREFS = "build_history"
    private const val KEY = "records"
    private const val MAX = 50

    fun add(ctx: Context, out: File, sourceName: String) {
        val recs = list(ctx).toMutableList()
        recs.removeAll { it.path == out.absolutePath }
        recs.add(0, BuildRecord(out.absolutePath, sourceName, System.currentTimeMillis(), out.length()))
        save(ctx, recs.take(MAX))
    }

    /** Only records whose output file still exists. */
    fun list(ctx: Context): List<BuildRecord> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BuildRecord(o.getString("path"), o.getString("source"), o.getLong("time"), o.getLong("size"))
            }.filter { File(it.path).exists() }
        }.getOrDefault(emptyList())
    }

    fun remove(ctx: Context, path: String) {
        save(ctx, list(ctx).filter { it.path != path })
    }

    private fun save(ctx: Context, recs: List<BuildRecord>) {
        val arr = JSONArray()
        recs.forEach { r ->
            arr.put(JSONObject().apply {
                put("path", r.path); put("source", r.sourceName); put("time", r.time); put("size", r.size)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
