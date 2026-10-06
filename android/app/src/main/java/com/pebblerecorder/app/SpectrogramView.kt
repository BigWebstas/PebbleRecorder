package com.pebblerecorder.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

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
 * A scrolling spectrogram: time runs left to right, newest column on the right, low frequencies at
 * the bottom. Feed it one [SPECTRUM_ROWS]-tall column at a time with [addColumn].
 */
class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bitmap = Bitmap.createBitmap(COLUMNS, SPECTRUM_ROWS, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(SPECTRUM_ROWS)
    private var next = 0 // ring-buffer column the next addColumn() writes

    init {
        clear()
    }

    fun addColumn(column: FloatArray) {
        for (row in 0 until SPECTRUM_ROWS) {
            pixels[SPECTRUM_ROWS - 1 - row] = PALETTE[(column[row] * 255f).toInt().coerceIn(0, 255)]
        }
        bitmap.setPixels(pixels, 0, 1, next, 0, 1, SPECTRUM_ROWS)
        next = (next + 1) % COLUMNS
        invalidate()
    }

    fun clear() {
        bitmap.eraseColor(PALETTE[0])
        next = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Oldest column is at `next`; draw [next, COLUMNS) then [0, next) so newest ends up right.
        val oldestWidth = (COLUMNS - next) * width / COLUMNS
        canvas.drawBitmap(bitmap, Rect(next, 0, COLUMNS, SPECTRUM_ROWS), Rect(0, 0, oldestWidth, height), paint)
        if (next > 0) {
            canvas.drawBitmap(bitmap, Rect(0, 0, next, SPECTRUM_ROWS), Rect(oldestWidth, 0, width, height), paint)
        }
    }
}
