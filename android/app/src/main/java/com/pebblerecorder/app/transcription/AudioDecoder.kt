package com.pebblerecorder.app.transcription

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.roundToInt

private const val TARGET_RATE = 16_000
private const val TIMEOUT_US = 10_000L

/**
 * 16 kHz mono 16-bit samples: the first [size] entries of [data] are the audio. [data] can be
 * longer than that, so the audio is never copied just to cut it to length.
 */
internal class Pcm(val data: ShortArray, val size: Int)

/** Where decoded 16 kHz mono samples go. */
internal fun interface SampleSink {
    fun add(value: Short)
}

/**
 * Decodes any recording the app can make or import into the 16 kHz mono samples whisper expects.
 * Kept as 16-bit shorts, not floats: an hour is 115 MB rather than 230 MB, and the app's Java heap
 * is only 256 MB. The conversion to floats happens in native memory (see whisper_jni.cpp).
 */
internal object AudioDecoder {

    fun decode(file: File): Pcm {
        var buffer: SampleBuffer? = null
        decodeStream({ it.setDataSource(file.path) }, file.name) { durationUs ->
            SampleBuffer(estimatedSamples(durationUs)).also { buffer = it }
        }
        return buffer!!.let { Pcm(it.data, it.size) }
    }

    /**
     * Decodes the recording that [setSource] points the extractor at, handing 16 kHz mono samples
     * to the sink [sinkFor] makes (given the duration in microseconds, 0 if unknown) as they come,
     * so a long recording never has to be in memory all at once.
     */
    fun decodeStream(setSource: (MediaExtractor) -> Unit, name: String, sinkFor: (Long) -> SampleSink) {
        val extractor = MediaExtractor()
        try {
            setSource(extractor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in $name")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                run(extractor, codec, sinkFor(durationUs))
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun run(extractor: MediaExtractor, codec: MediaCodec, out: SampleSink) {
        var resampler: Resampler? = null
        var channels = 1
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = codec.outputFormat
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    resampler = Resampler(outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                }
                index >= 0 -> {
                    val shorts = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val resample = resampler ?: Resampler(TARGET_RATE).also { resampler = it }
                    for (i in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += shorts.get()
                        resample.add(sum / channels, out)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    }

    /** Samples for a recording of [durationUs], plus a little slack for the codec's padding. */
    internal fun estimatedSamples(durationUs: Long): Int =
        if (durationUs <= 0) TARGET_RATE * 60 else (durationUs * TARGET_RATE / 1_000_000 + TARGET_RATE).toInt()

    /** Linear-interpolating resampler, fed one mono sample (in 16-bit units) at a time. */
    internal class Resampler(inputRate: Int) {
        private val step = inputRate.toDouble() / TARGET_RATE
        private var pos = 0.0
        private var prev = 0f

        fun add(sample: Float, out: SampleSink) {
            while (pos < 1.0) {
                out.add((prev + (sample - prev) * pos.toFloat()).roundToInt().coerceIn(-32768, 32767).toShort())
                pos += step
            }
            pos -= 1.0
            prev = sample
        }
    }

    /**
     * Sample storage sized up front from the recording's duration. If that turns out too small it
     * grows by a fixed step, never by doubling: doubling a long recording's array needs the old and
     * new copies in memory at once, which is what ran the app out of heap on a 30+ minute recording.
     */
    internal class SampleBuffer(initialCapacity: Int) : SampleSink {
        var data = ShortArray(initialCapacity)
            private set
        var size = 0
            private set

        override fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size + GROW_STEP)
            data[size++] = value
        }

        companion object {
            const val GROW_STEP = TARGET_RATE * 60
        }
    }
}
