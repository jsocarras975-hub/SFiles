package com.superfiles.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
