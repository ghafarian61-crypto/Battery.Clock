package com.example.batterypercent

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Single-stroke ("pencil") digits: every stroke is exactly 1 physical pixel wide, no bold, no anti-aliasing,
 * so on an AMOLED screen only one pixel (one R/G/B triplet) lights up across the line.
 *
 * Used by the charging screen and by the widget bitmap. Supports 0-9 . - /
 * All glyph paths are built once in the constructor; [draw] allocates nothing.
 *
 * With [strokePx] above 1 the same digits are drawn with a thicker, anti-aliased pen (used by the widget and
 * by the "pen thickness" setting).
 *
 * @param heightPx height of the digits in physical pixels.
 * @param strokePx pen width in physical pixels (1 = a single pixel).
 */
class ThinText(heightPx: Float, strokePx: Float = 1f) {

    /** Pen width in pixels. */
    val stroke: Float = Math.max(1f, strokePx)

    private val smooth = stroke > 1.5f

    /** Digit height in pixels (whole number, so the strokes land on the pixel grid). */
    val h: Float = Math.max(6, Math.round(heightPx)).toFloat()

    private val cw: Float = Math.round(h * DIGIT_W).toFloat()        // digit cell width
    private val slashW: Float = Math.round(h * SLASH_W).toFloat()
    private val dotW = 1f

    /** Clear space between two characters. */
    val gap: Float = Math.max(3f, Math.round(h * GAP + stroke - 1f).toFloat())

    private val glyphs = arrayOfNulls<Path>(128)
    private val rect = RectF()

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        isAntiAlias = smooth
        if (smooth) {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    init {
        for (c in "0123456789.-/") glyphs[c.code] = build(c)
    }

    fun advance(c: Char): Float = when (c) {
        '.' -> dotW + gap
        '/' -> slashW + gap
        in '0'..'9', '-' -> cw + gap
        else -> gap * 2f
    }

    /** Width of [text] without the trailing gap. */
    fun width(text: String): Float {
        if (text.isEmpty()) return 0f
        var sum = 0f
        for (c in text) sum += advance(c)
        return sum - gap
    }

    /**
     * Draws [text] with its left edge at [x] and its bottom (baseline) at [bottomY].
     * [outlinePx] / [outlineAlpha]: an optional soft black halo behind the digits (for coloured wallpapers).
     */
    fun draw(
        canvas: Canvas,
        text: String,
        x: Float,
        bottomY: Float,
        color: Int,
        outlinePx: Float = 0f,
        outlineAlpha: Int = 0
    ) {
        // +0.5 puts the centre of a 1-pixel line exactly in the middle of a pixel row/column.
        val left = Math.floor(x.toDouble()).toFloat() + 0.5f + stroke / 2f
        val top = Math.floor((bottomY - h).toDouble()).toFloat() + 0.5f
        if (outlinePx > 0f && outlineAlpha > 0) {
            paint.isAntiAlias = true
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = stroke + 2f * outlinePx
            pass(canvas, text, left, top, android.graphics.Color.argb(outlineAlpha, 0, 0, 0))
            paint.strokeWidth = stroke
            paint.isAntiAlias = smooth
            if (!smooth) paint.strokeCap = Paint.Cap.BUTT
        }
        pass(canvas, text, left, top, color)
    }

    private fun pass(canvas: Canvas, text: String, left: Float, top: Float, color: Int) {
        paint.color = color
        val save = canvas.save()
        canvas.translate(left, top)
        for (c in text) {
            val p = if (c.code < 128) glyphs[c.code] else null
            if (p != null) canvas.drawPath(p, paint)
            canvas.translate(advance(c), 0f)
        }
        canvas.restoreToCount(save)
    }

    private fun build(c: Char): Path {
        val w = cw
        val hh = h
        val p = Path()
        when (c) {
            '0' -> {
                rect.set(0f, 0f, w, hh)
                p.addOval(rect, Path.Direction.CW)
            }
            '1' -> {
                p.moveTo(w * 0.5f, 0f)
                p.lineTo(w * 0.5f, hh)
                p.moveTo(w * 0.5f, 0f)
                p.lineTo(w * 0.1f, hh * 0.2f)
            }
            '2' -> {
                rect.set(0f, 0f, w, hh * 0.5f)
                p.arcTo(rect, 180f, 200f, true)
                p.lineTo(0f, hh)
                p.lineTo(w, hh)
            }
            '3' -> {
                rect.set(0f, 0f, w, hh * 0.5f)
                p.arcTo(rect, 200f, 250f, true)
                rect.set(0f, hh * 0.5f, w, hh)
                p.arcTo(rect, 270f, 250f, false)
            }
            '4' -> {
                p.moveTo(w * 0.72f, hh)
                p.lineTo(w * 0.72f, 0f)
                p.lineTo(0f, hh * 0.68f)
                p.lineTo(w, hh * 0.68f)
            }
            '5' -> {
                p.moveTo(w * 0.95f, 0f)
                p.lineTo(w * 0.1f, 0f)
                p.lineTo(w * 0.06f, hh * 0.47f)
                rect.set(0f, hh * 0.38f, w, hh)
                p.arcTo(rect, 220f, 270f, false)
            }
            '6' -> {
                p.moveTo(w * 0.88f, hh * 0.02f)
                p.quadTo(0f, hh * 0.06f, 0f, hh * 0.7f)
                rect.set(0f, hh * 0.4f, w, hh)
                p.addOval(rect, Path.Direction.CW)
            }
            '7' -> {
                p.moveTo(0f, 0f)
                p.lineTo(w, 0f)
                p.lineTo(w * 0.28f, hh)
            }
            '8' -> {
                rect.set(w * 0.07f, 0f, w * 0.93f, hh * 0.5f)
                p.addOval(rect, Path.Direction.CW)
                rect.set(0f, hh * 0.47f, w, hh)
                p.addOval(rect, Path.Direction.CW)
            }
            '9' -> {
                p.moveTo(w * 0.12f, hh * 0.98f)
                p.quadTo(w, hh * 0.94f, w, hh * 0.3f)
                rect.set(0f, 0f, w, hh * 0.6f)
                p.addOval(rect, Path.Direction.CW)
            }
            '.' -> {
                p.moveTo(0f, hh - 2f)
                p.lineTo(0f, hh)
            }
            '-' -> {
                p.moveTo(w * 0.1f, hh * 0.5f)
                p.lineTo(w * 0.9f, hh * 0.5f)
            }
            '/' -> {
                p.moveTo(slashW, 0f)
                p.lineTo(0f, hh)
            }
        }
        return p
    }

    private companion object {
        const val DIGIT_W = 0.56f
        const val SLASH_W = 0.38f
        const val GAP = 0.22f
    }
}
