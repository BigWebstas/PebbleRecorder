package com.pebblerecorder.app.recording

import android.content.Context
import android.net.Uri
import com.pebblerecorder.app.transcription.TranscriptFiles
import java.util.Calendar
import java.util.TimeZone

/**
 * The "delete recordings automatically after N days" rule. It counts calendar days, not 24-hour
 * spans: with 7 days, a recording made on day 1 (at any hour) is deleted just after midnight at the
 * start of day 8. Only files this app delivered (and tracked in [RecordingLog]) are ever deleted.
 */
object RecordingRetention {

    /** Recordings made before this moment are expired under a [days]-day rule at [nowMs]. */
    fun cutoffMs(nowMs: Long, days: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        startOfDay(nowMs, zone).apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }.timeInMillis

    /** The entries that have outlived a [days]-day rule (none when [days] is 0 = never). */
    fun expired(
        entries: List<RecordingEntry>,
        days: Int,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<RecordingEntry> {
        if (days <= 0) return emptyList()
        val cutoff = cutoffMs(nowMs, days, zone)
        return entries.filter { it.recordedAtMs < cutoff && (it.audioUri != null || it.transcriptUri != null) }
    }

    fun millisUntilNextMidnight(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        startOfDay(nowMs, zone).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis - nowMs

    /** Deletes what the saved rule has expired, from the recording folder and the list; returns how many. */
    fun sweep(context: Context): Int {
        val days = RecordingSettings.autoDeleteDays(context)
        if (days <= 0) return 0
        RecordingLog.load(context)
        val old = expired(RecordingLog.entries.value, days, System.currentTimeMillis())
        old.forEach { entry ->
            listOfNotNull(entry.audioUri, entry.transcriptUri).forEach { TranscriptFiles.delete(context, Uri.parse(it)) }
        }
        if (old.isNotEmpty()) RecordingLog.remove(context, old.map { it.name }.toSet())
        return old.size
    }

    private fun startOfDay(nowMs: Long, zone: TimeZone): Calendar =
        Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
