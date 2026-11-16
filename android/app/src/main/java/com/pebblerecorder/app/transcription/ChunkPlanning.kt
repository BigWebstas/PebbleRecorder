package com.pebblerecorder.app.transcription

import kotlin.math.abs

private const val RATE = 16_000

/** Where to cut a long recording, and how to put the pieces' transcripts back together. */
internal object ChunkPlanning {
    /** Length of each piece sent to Gemini: ~3 MB at 24 kbps, a few thousand tokens of transcript. */
    const val CHUNK_SECONDS = 15 * 60

    /** A cut is looked for in the last this-many seconds of a piece, so it can fall in a pause. */
    const val SEARCH_SECONDS = 30

    private const val WINDOW = RATE / 5 // 200 ms

    /**
     * The sample index to cut at: the middle of the quietest 200 ms stretch among the last
     * [SEARCH_SECONDS] of the first [length] samples. Cutting there rather than at a fixed time
     * keeps most cuts out of the middle of a word.
     */
    fun quietestCut(samples: ShortArray, length: Int): Int {
        val from = maxOf(0, length - SEARCH_SECONDS * RATE)
        if (length - from <= WINDOW) return length
        var bestStart = from
        var bestEnergy = Long.MAX_VALUE
        var start = from
        // Step by half a window; cheap, and a pause is far longer than that.
        while (start + WINDOW <= length) {
            var energy = 0L
            for (i in start until start + WINDOW) energy += abs(samples[i].toInt())
            if (energy < bestEnergy) {
                bestEnergy = energy
                bestStart = start
            }
            start += WINDOW / 2
        }
        return bestStart + WINDOW / 2
    }

    /** [bookmarks] (seconds into the whole recording) that fall in [startSec, endSec), as seconds into that piece. */
    fun bookmarksIn(bookmarks: List<Int>, startSec: Int, endSec: Int): List<Int> =
        bookmarks.filter { it in startSec until endSec }.map { it - startSec }

    private val BOOKMARK_MARKER = Regex("""\[Bookmark (?:(\d+):)?(\d{1,3}):(\d{2})]""")

    /**
     * Gemini marks bookmarks by the time within the piece it was given; this rewrites each
     * `[Bookmark MM:SS]` to the time within the whole recording, [offsetSec] later.
     */
    fun offsetBookmarkMarkers(text: String, offsetSec: Int): String =
        BOOKMARK_MARKER.replace(text) { m ->
            val (h, mm, ss) = m.destructured
            val seconds = (h.toIntOrNull() ?: 0) * 3600 + mm.toInt() * 60 + ss.toInt()
            "[Bookmark ${clockText(seconds + offsetSec)}]"
        }

    /** The end of what's been transcribed so far, to tell the next piece where the speakers left off. */
    fun tail(previous: String, maxChars: Int = 800): String {
        if (previous.length <= maxChars) return previous
        val cut = previous.takeLast(maxChars)
        // Start at a line boundary if there is one, so the hint doesn't open mid-sentence.
        return if ('\n' in cut) cut.substringAfter('\n') else cut
    }

    fun join(parts: List<String>): String = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
}
