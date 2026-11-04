package com.pebblerecorder.app.recording

/**
 * Audio that arrives from outside (the local webhook) rather than from our own recorder: which
 * containers we accept, and how to tell which one a file is. The sender's declared type is only a
 * fallback - Index and friends don't always label uploads reliably - so the file's own header wins.
 */
object ImportedAudio {
    /** Extensions beyond [RecordingFormat]'s own, with the MIME type Gemini needs for each. */
    val extraMimeTypes = mapOf(
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "aac" to "audio/aac",
    )

    /** File extension for [head] (the first bytes of the file), else from [filename] or [mime]; null if unsupported. */
    fun extensionOf(head: ByteArray, filename: String?, mime: String?): String? {
        sniff(head)?.let { return it }
        val fromName = filename?.substringAfterLast('.', "")?.lowercase()
        if (fromName != null && isSupported(fromName)) return fromName
        return when (mime?.lowercase()) {
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/aac" -> "aac"
            else -> null
        }
    }

    fun isSupported(extension: String): Boolean =
        extension in extraMimeTypes || RecordingFormat.entries.any { it.extension == extension }

    private fun sniff(h: ByteArray): String? {
        fun at(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it] == s[it].code.toByte() }
        return when {
            at(4, "ftyp") -> "m4a"
            at(0, "OggS") -> "ogg"
            at(0, "RIFF") && at(8, "WAVE") -> "wav"
            at(0, "fLaC") -> "flac"
            at(0, "ID3") -> "mp3"
            h.size >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xF6) == 0xF0 -> "aac"  // ADTS
            h.size >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0 -> "mp3"  // bare MPEG frames
            else -> null
        }
    }
}
