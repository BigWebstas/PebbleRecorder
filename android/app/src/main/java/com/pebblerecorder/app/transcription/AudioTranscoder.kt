package com.pebblerecorder.app.transcription

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

private const val RATE = 16_000
private const val BIT_RATE = 24_000 // plenty for speech: Gemini itself works from ~16 kbps audio
private const val TIMEOUT_US = 10_000L
private const val MIN_CHUNK_SAMPLES = RATE // a piece under a second isn't worth a request

/** One piece of a long recording, re-encoded small enough to send to a cloud engine. */
internal class AudioChunk(val file: File, val startSec: Int, val endSec: Int)

/**
 * Cuts a long recording into ~15-minute pieces of 16 kHz mono AAC at 24 kbps (an hour is about
 * 11 MB instead of the original's tens), so recordings over a cloud engine's upload limit can still
 * be sent. The audio is decoded and re-encoded as a stream; only one piece is in memory at a time.
 */
internal object AudioTranscoder {

    /** [onProgress] gets 0-100, how far through the recording's audio the re-encoding has got. */
    fun split(context: Context, source: Uri, outDir: File, onProgress: (Int) -> Unit = {}): List<AudioChunk> {
        val chunks = mutableListOf<AudioChunk>()
        var sink: ChunkSink? = null
        AudioDecoder.decodeStream({ it.setDataSource(context, source, null) }, source.lastPathSegment ?: "recording") { durationUs ->
            ChunkSink(outDir, chunks, durationUs, onProgress).also { sink = it }
        }
        sink?.finish()
        return chunks
    }

    private class ChunkSink(
        private val dir: File,
        private val out: MutableList<AudioChunk>,
        durationUs: Long,
        private val onProgress: (Int) -> Unit,
    ) : SampleSink {
        // Zero when the duration is unknown, which leaves the bar where it is.
        private val totalSamples = durationUs * RATE / 1_000_000L
        private var lastPercent = -1

        private val target = ChunkPlanning.CHUNK_SECONDS * RATE
        private val buffer = ShortArray(target + 1)
        private var size = 0
        private var startSample = 0L

        override fun add(value: Short) {
            buffer[size++] = value
            if (totalSamples > 0 && (startSample + size) % RATE == 0L) {
                val percent = ((startSample + size) * 100 / totalSamples).toInt().coerceIn(0, 99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
            if (size >= target) flush(ChunkPlanning.quietestCut(buffer, size))
        }

        fun finish() {
            if (size >= MIN_CHUNK_SAMPLES) flush(size)
        }

        /** Encodes the first [cut] samples as the next piece and keeps the rest for the one after. */
        private fun flush(cut: Int) {
            val file = File(dir, "part-%03d.m4a".format(out.size + 1))
            encodeAac(buffer, cut, file)
            out += AudioChunk(file, (startSample / RATE).toInt(), ((startSample + cut) / RATE).toInt())
            System.arraycopy(buffer, cut, buffer, 0, size - cut)
            size -= cut
            startSample += cut
        }
    }

    /** Writes the first [length] 16 kHz mono samples of [samples] to [file] as AAC in an MP4 container. */
    private fun encodeAac(samples: ShortArray, length: Int, file: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        input.clear()
                        val count = minOf(length - fed, input.capacity() / 2)
                        val ptsUs = fed * 1_000_000L / RATE
                        if (count <= 0) {
                            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            input.asShortBuffer().put(samples, fed, count)
                            codec.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
                            fed += count
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val encoded = codec.getOutputBuffer(index)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            encoded.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
