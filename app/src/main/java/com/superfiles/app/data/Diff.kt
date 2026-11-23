package com.superfiles.app.data

/** A line-by-line diff between two files, computed with a simple LCS table. */
object Diff {

    enum class Kind { SAME, ADDED, REMOVED }

    data class Line(val kind: Kind, val text: String, val aNo: Int?, val bNo: Int?)

    data class Result(val lines: List<Line>, val added: Int, val removed: Int)

    // The LCS table is n*m ints, so 2000 lines is the cap before memory gets silly.
    private const val MAX_LINES = 2000

    /** Returns null when either file is too large to diff. */
    fun diff(a: List<String>, b: List<String>): Result? {
        if (a.size > MAX_LINES || b.size > MAX_LINES) return null
        val n = a.size
        val m = b.size
        // LCS lengths, one row at a time is not enough for backtracking, so keep the table.
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<Line>()
        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(Line(Kind.SAME, a[i], i + 1, j + 1)); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add(Line(Kind.REMOVED, a[i], i + 1, null)); i++; removed++ }
                else -> { out.add(Line(Kind.ADDED, b[j], null, j + 1)); j++; added++ }
            }
        }
        while (i < n) { out.add(Line(Kind.REMOVED, a[i], i + 1, null)); i++; removed++ }
        while (j < m) { out.add(Line(Kind.ADDED, b[j], null, j + 1)); j++; added++ }
        return Result(out, added, removed)
    }
}
