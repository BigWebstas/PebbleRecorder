package com.pebblerecorder.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.pebblerecorder.app.recording.SILENCE_THRESHOLD_LEVEL
import com.pebblerecorder.app.recording.SPECTRUM_ROWS
import com.pebblerecorder.app.recording.SpectrumFrame

private const val COLUMNS = 240

// Dark blue -> purple -> orange -> pale yellow, indexed by 0..255 intensity.
private val PALETTE = IntArray(256) { i ->
    val t = i / 255f
    val stops = arrayOf(0xFF05051E.toInt(), 0xFF5B1E8C.toInt(), 0xFFF2762E.toInt(), 0xFFFFF3B0.toInt())
    val pos = t * (stops.size - 1)
    val lo = pos.toInt().coerceAtMost(stops.size - 2)
    val f = pos - lo
    fun lerp(shift: Int) = (((stops[lo] shr shift and 0xFF) * (1 - f) + (stops[lo + 1] shr shift and 0xFF) * f)).toInt()
    Color.rgb(lerp(16), lerp(8), lerp(0))
}

/**
 * A scrolling spectrogram with a live mic level bar and silence-threshold marker right next to it:
 * time runs left to right, newest column on the right, low frequencies at the bottom.
 */
class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val cornerRadius = 12f * density
    private val meterGap = 8f * density
    private val meterWidth = 10f * density
    private val tickOverhang = 3f * density

    private val bitmap = Bitmap.createBitmap(COLUMNS, SPECTRUM_ROWS, Bitmap.Config.ARGB_8888)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val meterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val rectF = RectF()
    private val pixels = IntArray(SPECTRUM_ROWS)
    private var next = 0 // ring-buffer column the next addColumn() writes
    private var currentLevel = 0f

    init {
        clear()
    }

    fun addColumn(frame: SpectrumFrame) {
        val column = frame.bins
        for (row in 0 until SPECTRUM_ROWS) {
            pixels[SPECTRUM_ROWS - 1 - row] = PALETTE[(column[row] * 255f).toInt().coerceIn(0, 255)]
        }
        bitmap.setPixels(pixels, 0, 1, next, 0, 1, SPECTRUM_ROWS)
        next = (next + 1) % COLUMNS
        currentLevel = frame.level
        invalidate()
    }

    fun clear() {
        bitmap.eraseColor(PALETTE[0])
        next = 0
        currentLevel = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val specWidth = (width - meterGap - meterWidth - tickOverhang).toInt().coerceAtLeast(1)

        // Draw the rounded spectrogram on the left.
        val saveCount = canvas.save()
        clipPath.reset()
        rectF.set(0f, 0f, specWidth.toFloat(), height.toFloat())
        clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        val oldestWidth = (COLUMNS - next) * specWidth / COLUMNS
        canvas.drawBitmap(bitmap, Rect(next, 0, COLUMNS, SPECTRUM_ROWS), Rect(0, 0, oldestWidth, height), bitmapPaint)
        if (next > 0) {
            canvas.drawBitmap(bitmap, Rect(0, 0, next, SPECTRUM_ROWS), Rect(oldestWidth, 0, specWidth, height), bitmapPaint)
        }
        canvas.restoreToCount(saveCount)

        // Draw the vertical level bar and silence-threshold line right next to the spectrogram.
        val meterLeft = specWidth + meterGap
        val meterRight = meterLeft + meterWidth
        val barRadius = meterWidth / 2f

        // Background track
        meterPaint.style = Paint.Style.FILL
        meterPaint.color = PALETTE[0]
        rectF.set(meterLeft, 0f, meterRight, height.toFloat())
        canvas.drawRoundRect(rectF, barRadius, barRadius, meterPaint)

        // Live level fill (bottom-up)
        if (currentLevel > 0f) {
            val fillTop = height * (1f - currentLevel.coerceIn(0f, 1f))
            meterPaint.color = if (currentLevel >= SILENCE_THRESHOLD_LEVEL) {
                0xFFF2762E.toInt()
            } else {
                0xFF5B1E8C.toInt()
            }
            val fillSave = canvas.save()
            clipPath.reset()
            rectF.set(meterLeft, 0f, meterRight, height.toFloat())
            clipPath.addRoundRect(rectF, barRadius, barRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawRect(meterLeft, fillTop, meterRight, height.toFloat(), meterPaint)
            canvas.restoreToCount(fillSave)
        }

        // Silence auto-stop threshold horizontal line across the meter
        val thresholdY = (height * (1f - SILENCE_THRESHOLD_LEVEL)).coerceIn(1f, height - 1f)
        meterPaint.style = Paint.Style.STROKE
        meterPaint.strokeWidth = 2f * density
        meterPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        canvas.drawLine(meterLeft - tickOverhang, thresholdY, meterRight + tickOverhang, thresholdY, meterPaint)
    }
}
