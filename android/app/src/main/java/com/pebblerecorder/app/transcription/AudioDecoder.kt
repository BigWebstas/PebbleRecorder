package com.pebblerecorder.app.transcription

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

private const val TARGET_RATE = 16_000
private const val TIMEOUT_US = 10_000L

/** Decodes any recording the app can make or import into the 16 kHz mono floats whisper expects. */
internal object AudioDecoder {

    fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in ${file.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return run(extractor, codec)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun run(extractor: MediaExtractor, codec: MediaCodec): FloatArray {
        val out = FloatBuilder()
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
                    val format = codec.outputFormat
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                }
                index >= 0 -> {
                    val shorts = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val resample = resampler ?: Resampler(TARGET_RATE).also { resampler = it }
                    for (i in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += shorts.get()
                        resample.add(sum / channels / 32768f, out)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        return out.toArray()
    }

    /** Linear-interpolating resampler, fed one mono sample at a time. */
    private class Resampler(inputRate: Int) {
        private val step = inputRate.toDouble() / TARGET_RATE
        private var pos = 0.0
        private var prev = 0f

        fun add(sample: Float, out: FloatBuilder) {
            while (pos < 1.0) {
                out.add(prev + (sample - prev) * pos.toFloat())
                pos += step
            }
            pos -= 1.0
            prev = sample
        }
    }

    private class FloatBuilder {
        private var data = FloatArray(TARGET_RATE * 60)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
