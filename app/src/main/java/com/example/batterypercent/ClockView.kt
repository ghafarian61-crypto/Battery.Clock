package com.example.batterypercent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.View
import android.view.WindowInsets
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Clock for an AMOLED "always on" screen, analog or digital.
 *
 * Common rules
 *  - Everything is a PURE red or a PURE green on pure black: no white / grey / mixed colours, so no pixel ever has
 *    more than one sub-pixel lit. No blue (hard to see and the fastest to wear).
 *  - Every 30 minutes the two colours swap (minutes 0..29 and 30..59), so each sub-pixel rests half of the time.
 *  - Burn-in protection: only two positions, one hour each: the top one and one step lower, never overlapping,
 *    so the pixels of the previous hour are completely off for a whole hour. Even hours top, odd hours lower.
 *  - ALL lettering (numbers, digits, colon) and the sun / moon symbol are ONE thin line, like written with a pen
 *    tip: digits are drawn from line segments (no font, no filled or double-line shapes). Nothing is allocated inside onDraw; the view redraws once a minute.
 *
 * Analog: numbers 1..12 in one colour, both hands in the other, all with the same thin line. The hands follow the
 * real 60-minute ratio. Under the 12 a sun (day) or a hollow crescent moon (night) replaces AM/PM.
 *
 * Digital: hours and minutes, large, one line, the same thin pen as everything else. 24 h, or 12 h with the sun / moon
 * symbol instead of AM/PM. The two dots of the colon are replaced by the battery percentage (upper dot) and the battery
 * temperature (lower dot), written with the same thin single-line digits, only smaller.
 *
 * Colours, brightness levels, sizes and pen width come from [ClockStyle.Config] (settings screen).
 */
class ClockView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---- what to show
    private var cfg = ClockStyle.Config()
    private var digital = false
    private var hour12 = false
    private var line = 1f
    private var batteryPercent = -1
    private var batteryTempTenths = Int.MIN_VALUE
    private var chargeText = ""
    private var tempText = ""

    private var hour24 = 0
    private var minute = 0
    private var timeSet = false
    private var hourAngle = 0f      // degrees clockwise from 12
    private var minuteAngle = 0f
    private var position = 0        // 0 = top, 1 = one step lower (hour parity), both modes
    private var isDay = true
    private var timeText = ""       // plain ASCII digits and ":"
    private var primary = Color.GREEN     // numbers / digits
    private var secondary = Color.RED     // hands / the sun-moon symbol in analog

    /** True: draw nothing but black (outside the schedule the screen looks switched off). */
    var blank = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Bottom edge of the camera cut-out (px), 0 when the phone has none / it is unknown. */
    private var cutoutTop = 0f

    // ---- glyph geometry of the active mode (digit cell width / height, space between items, colon width)
    private var gw = 0f
    private var gh = 0f
    private var gap = 0f
    private var colonW = 0f
    private val glyphs = Array(10) { Path() }

    // ---- small digits (battery percentage / temperature) that stand in the colon slot
    private val smallGlyphs = Array(10) { Path() }
    private var sw = 0f
    private var sh = 0f
    private var sgap = 0f
    private var showInfo = false

    // ---- analog layout
    private var cx = 0f
    private var radius = 0f
    private var minuteLen = 0f            // minute hand length in px, fitted to the numbers in relayout()
    private var analogIconDy = 0f         // symbol centre, relative to the clock centre (negative = up)
    private val slotCy = FloatArray(SLOTS)
    private val labels = Array(12) { "" }
    private val numX = FloatArray(12)     // top-left of each number relative to the clock centre
    private val numY = FloatArray(12)

    // ---- digital layout
    private var digitalReady = false
    private var boxLeft = 0f
    private var boxW = 0f
    private var symbolX = 0f
    private val digitTop = FloatArray(2)
    private val symbolY = FloatArray(2)

    // ---- sun / moon symbol, built for the size in use (centre at 0,0)
    private val sunRays = FloatArray(32)
    private var sunRadius = 0f
    private val moonPath = Path()

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val minutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val chargePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        rebuildLabels()
        updateText()
        applyColors()
    }

    /** Applies every look setting. Cheap when nothing changed. */
    fun configure(config: ClockStyle.Config) {
        if (config == cfg) return
        cfg = config
        digital = config.digital
        hour12 = config.hour12
        line = config.line.toFloat()
        updateText()
        updateInfoText()
        applyColors()
        relayout()
    }

    /** Battery percentage (0..100, -1 unknown) and temperature in tenths of a degree Celsius. */
    fun setBattery(percent: Int, tempTenthsC: Int) {
        if (percent == batteryPercent && tempTenthsC == batteryTempTenths) return
        batteryPercent = percent
        batteryTempTenths = tempTenthsC
        updateInfoText()
        updateInfoColors()
        invalidate()
    }

    private fun updateInfoText() {
        chargeText = if (batteryPercent in 0..100) batteryPercent.toString() else ""
        tempText = if (batteryTempTenths == Int.MIN_VALUE) {
            ""
        } else {
            val c = (batteryTempTenths / 10f).coerceIn(0f, 99.9f)
            if (cfg.tempDecimal) String.format(Locale.US, "%.1f", c) else Math.round(c).toString()
        }
    }

    /** One pure colour of the range palette (red / yellow / green / blue) at [level] percent. */
    private fun rangeColor(idx: Int, level: Int): Int {
        val v = (255 * level.coerceIn(0, 100) / 100f + 0.5f).toInt()
        return when (idx) {
            ClockStyle.P_RED -> Color.rgb(v, 0, 0)
            ClockStyle.P_YELLOW -> Color.rgb(v, v, 0)
            ClockStyle.P_GREEN -> Color.rgb(0, v, 0)
            else -> Color.rgb(0, 0, v)
        }
    }

    /** Percentage and temperature colours follow their value when the ranges are on, else the fixed info colour. */
    private fun updateInfoColors() {
        val fixed = pick(cfg.infoColor, if (minute >= 30) 1 else 0, cfg.infoLevel)
        val p = batteryPercent
        chargePaint.color = if (cfg.chargeByLevel && p >= 0) {
            val idx = when {
                p >= 100 -> cfg.chargeFull
                p <= cfg.chargeT1 -> cfg.chargeC1
                p <= cfg.chargeT2 -> cfg.chargeC2
                else -> cfg.chargeC3
            }
            rangeColor(idx, cfg.infoLevel)
        } else fixed
        tempPaint.color = if (cfg.tempByLevel && batteryTempTenths != Int.MIN_VALUE) {
            val deg = Math.floor(batteryTempTenths / 10.0).toInt()
            val idx = when {
                deg <= cfg.tempT1 -> cfg.tempC1
                deg <= cfg.tempT2 -> cfg.tempC2
                else -> cfg.tempC3
            }
            rangeColor(idx, cfg.infoLevel)
        } else fixed
    }

    /** Sets the time to show. Call it again every minute. */
    fun setTime(hour24: Int, minute: Int) {
        if (timeSet && hour24 == this.hour24 && minute == this.minute) return     // nothing changed: no redraw
        timeSet = true
        this.hour24 = hour24
        this.minute = minute
        hourAngle = ((hour24 % 12) * 60 + minute) * 0.5f      // 360 degrees over 720 minutes
        minuteAngle = minute * 6f                              // 360 degrees over 60 minutes
        position = hour24 % 2
        isDay = hour24 >= DAY_START_HOUR && hour24 < DAY_END_HOUR
        updateText()
        applyColors()
        invalidate()
    }

    private fun rebuildLabels() {
        for (i in 0 until 12) labels[i] = (i + 1).toString()
    }

    private fun updateText() {
        val hh = if (hour12) {
            val x = hour24 % 12
            if (x == 0) 12 else x
        } else {
            hour24
        }
        val hs = if (hour12) hh.toString() else String.format(Locale.US, "%02d", hh)
        timeText = hs + ":" + String.format(Locale.US, "%02d", minute)
    }

    /** Pure single-channel colour: [channel] 0 = red, 1 = green, 2 = blue, at [level] percent. */
    private fun pure(channel: Int, level: Int): Int {
        val v = (255 * level.coerceIn(0, 100) / 100f + 0.5f).toInt()
        return when (channel) {
            0 -> Color.rgb(v, 0, 0)
            1 -> Color.rgb(0, v, 0)
            else -> Color.rgb(0, 0, v)
        }
    }

    private fun pick(choice: Int, autoChannel: Int, level: Int): Int =
        pure(if (choice == ClockStyle.C_AUTO) autoChannel else choice - 1, level)

    /**
     * AUTO colours: minutes 0..29 numbers / digits green, the rest red; minutes 30..59 swapped.
     * A colour chosen by hand stays as it is.
     */
    private fun applyColors() {
        val swapped = minute >= 30
        val first = if (swapped) 0 else 1          // red / green
        val second = if (swapped) 1 else 0
        primary = pick(cfg.clockColor, first, cfg.clockLevel)
        secondary = pick(cfg.handsColor, second, cfg.handsLevel)
        numberPaint.color = primary
        hourPaint.color = secondary
        minutePaint.color = secondary
        updateInfoColors()
        symbolPaint.color = if (digital) primary else secondary
    }

    // ------------------------------------------------------------------ glyphs (single-line digits)

    /** Builds the ten digit paths for a cell of [w] x [h] px, in the script in use. */
    private fun buildGlyphs(w: Float, h: Float) {
        gw = w
        gh = h
        gap = h * GLYPH_GAP
        colonW = h * COLON_RATIO
        for (d in 0 until 10) fillPath(glyphs[d], EN_GLYPHS[d], w, h)
    }

    /** Small digits for the battery percentage / temperature, [h] px tall. */
    private fun buildSmallGlyphs(h: Float) {
        sh = h
        sw = h * GLYPH_ASPECT
        sgap = h * GLYPH_GAP
        for (d in 0 until 10) fillPath(smallGlyphs[d], EN_GLYPHS[d], sw, sh)
    }

    private fun smallWidth(s: String): Float {
        if (s.isEmpty()) return 0f
        var w = 0f
        for (c in s) w += if (c == '.') sh * DOT_RATIO else sw
        return w + (s.length - 1) * sgap
    }

    /** Draws small digits (and the decimal point) centred on ([cx], [cy]). */
    private fun drawSmall(canvas: Canvas, s: String, cx: Float, cy: Float, paint: Paint) {
        if (s.isEmpty()) return
        var x = cx - smallWidth(s) / 2f
        val y = cy - sh / 2f
        for (c in s) {
            if (c == '.') {
                val dw = sh * DOT_RATIO
                canvas.drawPoint(x + dw / 2f, y + sh, paint)
                x += dw + sgap
            } else {
                canvas.save()
                canvas.translate(x, y)
                canvas.drawPath(smallGlyphs[c - '0'], paint)
                canvas.restore()
                x += sw + sgap
            }
        }
    }

    /** Spec: M x y | L x y | C x1 y1 x2 y2 x y | O left top right bottom (oval) | Z; coordinates in 0..1. */
    private fun fillPath(p: Path, spec: String, w: Float, h: Float) {
        p.reset()
        val t = spec.split(' ')
        var i = 0
        while (i < t.size) {
            when (t[i]) {
                "M" -> { p.moveTo(t[i + 1].toFloat() * w, t[i + 2].toFloat() * h); i += 3 }
                "L" -> { p.lineTo(t[i + 1].toFloat() * w, t[i + 2].toFloat() * h); i += 3 }
                "C" -> {
                    p.cubicTo(
                        t[i + 1].toFloat() * w, t[i + 2].toFloat() * h,
                        t[i + 3].toFloat() * w, t[i + 4].toFloat() * h,
                        t[i + 5].toFloat() * w, t[i + 6].toFloat() * h
                    )
                    i += 7
                }
                "O" -> {
                    p.addOval(
                        t[i + 1].toFloat() * w, t[i + 2].toFloat() * h,
                        t[i + 3].toFloat() * w, t[i + 4].toFloat() * h,
                        Path.Direction.CW
                    )
                    i += 5
                }
                "Z" -> { p.close(); i += 1 }
                else -> i++
            }
        }
    }

    /** Width of [s] drawn with the current glyph cell (no stroke). */
    private fun runWidth(s: String): Float {
        var x = 0f
        for (k in s.indices) {
            x += if (s[k] == ':') colonW else gw
            if (k < s.length - 1) x += gap
        }
        return x
    }

    /** Draws [s] with its top-left corner at ([x], [y]) using [paint]'s colour and line width. */
    private fun drawRun(canvas: Canvas, s: String, x: Float, y: Float, paint: Paint) {
        var cur = x
        for (c in s) {
            if (c == ':') {
                if (showInfo) {
                    // upper dot -> battery percentage, lower dot -> battery temperature
                    drawSmall(canvas, if (cfg.showCharge) chargeText else "", cur + colonW / 2f, y + gh * INFO_TOP_Y, chargePaint)
                    drawSmall(canvas, if (cfg.showTemp) tempText else "", cur + colonW / 2f, y + gh * INFO_BOTTOM_Y, tempPaint)
                } else {
                    canvas.drawPoint(cur + colonW / 2f, y + gh * 0.3f, paint)
                    canvas.drawPoint(cur + colonW / 2f, y + gh * 0.7f, paint)
                }
                cur += colonW + gap
            } else {
                canvas.save()
                canvas.translate(cur, y)
                canvas.drawPath(glyphs[c - '0'], paint)
                canvas.restore()
                cur += gw + gap
            }
        }
    }

    // ------------------------------------------------------------------ layout

    private fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (digital) layoutDigital(w, h) else layoutAnalog(w, h)
        invalidate()
    }

    /** Radius the analog clock would have on this screen (also the reference for the digital line weight). */
    private fun analogRadius(w: Float, h: Float): Float {
        val top = min(cutoutTop, h * 0.2f)
        return min(w, (h - top) / SLOTS) * SIZE_FACTOR / 2f * (cfg.clockSize / 100f)
    }


    private fun layoutAnalog(w: Float, h: Float) {
        val top = min(cutoutTop, h * 0.2f)
        val slotH = (h - top) / SLOTS
        radius = analogRadius(w, h)                     // circle is smaller than its band: bands never overlap
        cx = w / 2f
        for (k in 0 until SLOTS) slotCy[k] = top + slotH * (k + 0.5f)

        hourPaint.strokeWidth = line
        minutePaint.strokeWidth = line
        val numLine = line
        numberPaint.strokeWidth = numLine

        val cellH = radius * NUMBER_HEIGHT
        buildGlyphs(cellH * GLYPH_ASPECT, cellH)

        var nearest = Float.MAX_VALUE
        for (i in 0 until 12) {
            val a = (i + 1) * 30.0 * PI / 180.0
            val px = (radius * NUMBER_RADIUS * sin(a)).toFloat()
            val py = (-radius * NUMBER_RADIUS * cos(a)).toFloat()
            val rw = runWidth(labels[i])
            numX[i] = px - rw / 2f
            numY[i] = py - gh / 2f

            // Distance from the clock centre to the nearest ink of this number.
            val l = numX[i] - numLine / 2f
            val r = numX[i] + rw + numLine / 2f
            val t = numY[i] - numLine / 2f
            val b = numY[i] + gh + numLine / 2f
            val dx = if (l > 0f) l else if (r < 0f) -r else 0f
            val dy = if (t > 0f) t else if (b < 0f) -b else 0f
            nearest = min(nearest, hypot(dx, dy))
        }
        // The minute hand ends just short of the nearest number, so it can never touch any of them.
        minuteLen = max(nearest - dp(MINUTE_GAP_DP) - line / 2f, 0f)

        // Sun / moon symbol under the 12, same thin line.
        analogIconDy = -radius * ANALOG_ICON_Y
        buildSymbol(radius * ANALOG_ICON_SIZE, line)
    }

    private fun layoutDigital(w: Float, h: Float) {
        // One thin line for digits, colon and symbol.
        val stroke = line
        numberPaint.strokeWidth = stroke
        chargePaint.strokeWidth = stroke
        tempPaint.strokeWidth = stroke

        // The colon slot holds up to three small digits (100 %) when the battery values replace the dots.
        showInfo = cfg.showCharge || cfg.showTemp
        val infoRatio = INFO_MAX_RATIO * cfg.infoSize / 100f                  // small digit height / big digit height
        // widest content: "100" (3 digits) or "36.5" (3 digits + point)
        val widest = if (cfg.showTemp && cfg.tempDecimal) 3f * GLYPH_ASPECT + DOT_RATIO + 3f * GLYPH_GAP
        else 3f * GLYPH_ASPECT + 2f * GLYPH_GAP
        val slotRatio = if (showInfo) infoRatio * widest else COLON_RATIO

        val perCell = 4f * GLYPH_ASPECT + slotRatio + 4f * GLYPH_GAP         // 4 digits + colon slot + 4 gaps, in cell heights
        val symbolPerCell = if (hour12) SYMBOL_RATIO + SYMBOL_GAP_RATIO else 0f

        val topY = cutoutTop + dp(DIGITAL_TOP_MARGIN_DP)
        val availW = w * (1f - 2f * DIGITAL_MARGIN) - stroke

        // Cell height: as wide as the screen allows, and small enough for both positions to fit under each other.
        var cellH = availW / (perCell + symbolPerCell)
        val verticalRoom = h - topY - dp(24f) - dp(DIGITAL_MIN_GAP_DP) - 2f * stroke
        cellH = min(cellH, verticalRoom / (2f + DIGITAL_GAP_RATIO))
        cellH *= cfg.clockSize / 100f
        buildGlyphs(cellH * GLYPH_ASPECT, cellH)
        colonW = cellH * slotRatio
        buildSmallGlyphs(cellH * infoRatio)

        val blockH = gh + stroke
        val vgap = max(dp(DIGITAL_MIN_GAP_DP), blockH * DIGITAL_GAP_RATIO)

        // Block tops: the second position starts below the first one, so the two never share a pixel.
        val blockTop0 = topY
        val blockTop1 = topY + blockH + vgap
        digitTop[0] = blockTop0 + stroke / 2f
        digitTop[1] = blockTop1 + stroke / 2f
        symbolY[0] = blockTop0 + blockH / 2f
        symbolY[1] = blockTop1 + blockH / 2f

        // Centre the text box (and the symbol beside it) horizontally.
        boxW = 4f * gw + colonW + 4f * gap
        val textW = boxW + stroke
        val symbolSize = if (hour12) SYMBOL_RATIO * blockH else 0f
        val symbolGap = if (hour12) SYMBOL_GAP_RATIO * blockH else 0f
        val groupW = textW + (if (hour12) symbolGap + symbolSize else 0f)
        val left = (w - groupW) / 2f
        boxLeft = left + stroke / 2f
        symbolX = left + textW + symbolGap + symbolSize / 2f
        if (hour12) buildSymbol(symbolSize, stroke)
        digitalReady = true
    }

    /** Builds the sun (hollow circle with rays touching it) and the hollow crescent moon for a square of [size]. */
    private fun buildSymbol(size: Float, stroke: Float) {
        symbolPaint.strokeWidth = stroke
        val outer = size / 2f - stroke / 2f

        // Sun: a hollow circle, 8 rays that start on the circle and go outwards.
        sunRadius = outer * SUN_CORE
        for (k in 0 until 8) {
            val a = k * PI / 4.0
            sunRays[4 * k] = (sunRadius * cos(a)).toFloat()
            sunRays[4 * k + 1] = (sunRadius * sin(a)).toFloat()
            sunRays[4 * k + 2] = (outer * cos(a)).toFloat()
            sunRays[4 * k + 3] = (outer * sin(a)).toFloat()
        }

        // Moon: outer circle minus a second, shifted circle = a crescent; only its outline is drawn.
        val disc = Path()
        disc.addCircle(0f, 0f, outer, Path.Direction.CW)
        val bite = Path()
        bite.addCircle(outer * 0.37f, -outer * 0.26f, outer * 0.95f, Path.Direction.CW)
        moonPath.reset()
        if (!moonPath.op(disc, bite, Path.Op.DIFFERENCE)) moonPath.set(disc)
    }

    /** False for the small preview in the settings: it is not under the camera. */
    var respectCutout = true

    private fun readCutout(insets: WindowInsets?) {
        if (!respectCutout || insets == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        cutoutTop = (insets.displayCutout?.safeInsetTop ?: 0).toFloat()
        relayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        readCutout(insets)
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        readCutout(rootWindowInsets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (blank) return
        if (digital) drawDigital(canvas) else drawAnalog(canvas)
    }

    private fun drawSymbol(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        if (isDay) {
            canvas.drawCircle(0f, 0f, sunRadius, symbolPaint)
            canvas.drawLines(sunRays, symbolPaint)
        } else {
            canvas.drawPath(moonPath, symbolPaint)
        }
        canvas.restore()
    }

    private fun drawDigital(canvas: Canvas) {
        if (!digitalReady) return
        // Fewer characters (12 h, one-digit hour) are centred in the same box, so the symbol never moves.
        val x = boxLeft + (boxW - runWidth(timeText)) / 2f
        drawRun(canvas, timeText, x, digitTop[position], numberPaint)
        if (hour12) drawSymbol(canvas, symbolX, symbolY[position])
    }

    private fun drawAnalog(canvas: Canvas) {
        if (radius <= 0f) return
        val cy = slotCy[position]

        for (i in 0 until 12) {
            drawRun(canvas, labels[i], cx + numX[i], cy + numY[i], numberPaint)
        }

        drawSymbol(canvas, cx, cy + analogIconDy)

        val ha = hourAngle * PI / 180.0
        val hl = radius * HOUR_LENGTH
        canvas.drawLine(
            cx, cy,
            cx + (hl * sin(ha)).toFloat(), cy - (hl * cos(ha)).toFloat(),
            hourPaint
        )

        // Both hands have the same colour, so where they cross nothing mixes.
        val ma = minuteAngle * PI / 180.0
        val ml = minuteLen
        canvas.drawLine(
            cx, cy,
            cx + (ml * sin(ma)).toFloat(), cy - (ml * cos(ma)).toFloat(),
            minutePaint
        )
    }

    companion object {
        /** Number of vertical bands the analog clock is sized for (only the first two are used: top and middle). */
        const val SLOTS = 3

        /** Circle diameter as a share of its band (< 1 leaves a clear gap between two positions). */
        const val SIZE_FACTOR = 0.97f

        /** Where the numbers stand (share of the radius) and their height. */
        const val NUMBER_RADIUS = 0.82f
        const val NUMBER_HEIGHT = 0.14f

        /** Hour hand length (share of the radius). The minute hand is fitted to the numbers automatically. */
        const val HOUR_LENGTH = 0.35f

        /** Gap (dp) between the end of the minute hand and the nearest number. Smaller = closer to the numbers. */
        const val MINUTE_GAP_DP = 4f

        /** Pen width in pixels for EVERYTHING: hands, numbers, digits, colon, sun and moon. 1 = a single-pixel line. */
        const val LINE_PX = 1f     // default; the real width is chosen in the settings

        /** Battery values in the colon slot: height of the small digits at 100 % size (share of the big digit height)
         *  and the vertical centres of the upper (percentage) and lower (temperature) one. */
        const val INFO_MAX_RATIO = 0.45f
        const val DOT_RATIO = 0.2f      // width of the decimal point (share of the small digit height)
        const val INFO_TOP_Y = 0.25f
        const val INFO_BOTTOM_Y = 0.75f

        /** Sun / moon symbol under the 12 of the analog clock: size and distance from the centre (share of the radius). */
        const val ANALOG_ICON_SIZE = 0.20f
        const val ANALOG_ICON_Y = 0.60f

        /** Digital: free space on each side (share of the width), and distance below the camera (dp). */
        const val DIGITAL_MARGIN = 0.025f
        const val DIGITAL_TOP_MARGIN_DP = 12f

        /** Digit shape: cell width / height, space between characters (share of the height), colon width. */
        const val GLYPH_ASPECT = 0.62f
        const val GLYPH_GAP = 0.22f
        const val COLON_RATIO = 0.30f

        /** Digital: space between the two positions (share of the digit height, at least [DIGITAL_MIN_GAP_DP]). */
        const val DIGITAL_GAP_RATIO = 0.14f
        const val DIGITAL_MIN_GAP_DP = 24f

        /** Digital 12 h: symbol size and its distance from the digits (share of the digit height). */
        const val SYMBOL_RATIO = 0.60f
        const val SYMBOL_GAP_RATIO = 0.20f

        /** The sun's core (share of the sun's radius). */
        const val SUN_CORE = 0.45f

        /** Sun from this hour (inclusive) until the end hour (exclusive); moon the rest of the day. */
        const val DAY_START_HOUR = 6
        const val DAY_END_HOUR = 18

        /** Digit shapes 0..9 (see ClockView.fillPath for the notation). */
        private val EN_GLYPHS = arrayOf(
            "O 0.04 0 0.96 1",
            "M 0.25 0.2 L 0.62 0 L 0.62 1",
            "M 0.05 0.2 C 0.1 -0.08 0.95 -0.08 0.95 0.28 C 0.95 0.55 0.4 0.7 0.05 1 L 1 1",
            "M 0.05 0.12 C 0.3 -0.06 0.95 -0.02 0.9 0.25 C 0.88 0.45 0.55 0.5 0.4 0.5 C 0.75 0.5 1 0.65 0.95 0.78 C 0.9 1.06 0.3 1.06 0 0.85",
            "M 0.75 1 L 0.75 0 L 0 0.7 L 1 0.7",
            "M 0.9 0 L 0.15 0 L 0.08 0.45 C 0.4 0.35 0.95 0.4 0.95 0.7 C 0.95 1.05 0.3 1.08 0 0.88",
            "M 0.9 0.05 C 0.4 -0.02 0.05 0.35 0.05 0.7 O 0.05 0.4 0.95 1",
            "M 0 0 L 1 0 L 0.35 1",
            "O 0.15 0 0.85 0.5 O 0.05 0.5 0.95 1",
            "O 0.05 0 0.95 0.6 M 0.95 0.3 C 0.95 0.7 0.7 1 0.2 0.98"
        )
    }
}
