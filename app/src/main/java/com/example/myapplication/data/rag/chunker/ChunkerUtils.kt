package com.example.myapplication.data.rag.chunker

internal fun snapToWordBoundary(text: String, pos: Int, range: Int = 20): Int {
    val start = maxOf(0, pos - range)
    val end = minOf(text.length, pos + range)
    var best = pos
    var bestDist = Int.MAX_VALUE
    for (i in start until end) {
        if (text[i] == ' ' || text[i] == '\n') {
            val dist = kotlin.math.abs(i - pos)
            if (dist < bestDist) {
                bestDist = dist
                best = i + 1
            }
        }
    }
    return minOf(best, text.length)
}
