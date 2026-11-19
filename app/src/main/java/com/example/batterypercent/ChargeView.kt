package com.example.batterypercent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Black screen with a glass battery "cylinder" that fills from the bottom. The charge number sits on the
 * left of the cylinder and the temperature on the right, both near the bottom of the screen.
 *
 * Look (AMOLED friendly): the glass outline, the rim and the wave line are exactly 1 physical pixel wide
 * (no anti-aliasing), the digits are single-stroke 1-pixel "pencil" digits ([ThinText]). The liquid inside
 * is a full colour fill with soft glass shading, so the tube still looks 3D. The top edge of the liquid is
 * animated: it sloshes left and right with a small ripple.
 *
 * At 100 %: the liquid fills the tube up to the blue cap, the waves stop, the tube moves to the middle
 * and the blue cap sits right under the camera hole. After [startExplosion] the tube slowly swells over
 * 53 s, bursts (flash, shock rings, flying pieces) and the screen is black until the 60 s mark.
 *
 * Burn-in protection: every time the level crosses a multiple of 10 the picture moves:
 *  - the cylinder alternates between the right and the left half of its slot (never when [centerAlways]),
 *  - the numbers alternate between a lower and an upper slot that do not overlap at all. Horizontally the
 *    numbers never move: they stand just outside the farthest wall the cylinder can reach, so the
 *    cylinder never touches them.
 *
 * Nothing is allocated inside onDraw.
 */
class ChargeView(context: Context, private val cfg: AppConfig = AppConfig.load(context)) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var percent = 0
    private var percentText = "0"
    private var tempText = "--"
    private var tempTextColor = Color.WHITE
    private var percentWidth = 0f

    /** Widget peek: the cylinder always stays in the middle (the 10 % left/right jump is off). */
    var centerAlways = false
        set(value) {
            field = value
            invalidate()
        }

    /** Bottom edge of the camera cut-out (px), 0 when the phone has none / it is unknown. */
    private var cutoutBottom = 0f

    // ---- explosion state ----
    private var exploding = false
    private var explodeStartMs = 0L

    // Pen widths (px): 1 = exactly one pixel, anti-aliasing off. Larger values come from the settings screen.
    private val textStroke = (1f + cfg.screenStrokeV / 100f * (0.2f * dp(cfg.screenTextDp()) - 1f)).coerceAtLeast(1f)
    private val shellStroke = 1f + cfg.shellStrokeV / 100f * dp(6f)
    private val crestStroke = 1f + cfg.crestStrokeV / 100f * dp(6f)

    /** Pencil digits. */
    private val thin = ThinText(dp(cfg.screenTextDp()), textStroke)

    private val swellS = cfg.swellS
    private val waveK = cfg.waveAmpV / 50f

    // Gradients are built once on a 0..1 horizontal unit and moved/scaled with a matrix at draw time.
    private val unitMatrix = Matrix()
    // The glass shading is never moved with setLocalMatrix: it is drawn through the canvas matrix instead
    // (translate + scale), so every draw of a frame keeps its own position.
    private val glassShader = LinearGradient(0f, 0f, 1f, 0f, GLASS_COLORS, GLASS_POS, Shader.TileMode.CLAMP)
    private val capShader = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(darken(cfg.capColor, 0.45f), tint(cfg.capColor, 0.45f), cfg.capColor, darken(cfg.capColor, 0.55f)),
        CAP_POS, Shader.TileMode.CLAMP
    )
    private val glowShader = RadialGradient(0f, 0f, 1f, GLOW_COLORS, GLOW_POS, Shader.TileMode.CLAMP)

    /** White glass outline and rim: one pixel wide by default (no anti-aliasing). */
    private val shellPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = shellStroke
        color = Color.WHITE
        isAntiAlias = shellStroke > 1.5f
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = glassShader
    }

    /** Same shading without anti-aliasing: stacked slices of the swelling body show no seams. */
    private val slicePaint = Paint().apply {
        style = Paint.Style.FILL
        shader = glassShader
    }
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = capShader
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The wave line on top of the liquid: one pixel by default. */
    private val crestPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = crestStroke
        isAntiAlias = crestStroke > 1.5f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = glowShader
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = false
    }

    private val shellPath = Path()
    private val crestPath = Path()
    private val airPath = Path()
    private val capPath = Path()
    private val shardPath = Path()
    private val streakPath = Path()
    private val ovalRect = RectF()
    private val capRect = RectF()

    // ---- flying pieces of the explosion (fixed pseudo-random set, built once) ----
    private val pCos = FloatArray(MAX_PARTICLES)
    private val pSin = FloatArray(MAX_PARTICLES)
    private val pSpeed = FloatArray(MAX_PARTICLES)      // dp per second
    private val pSize = FloatArray(MAX_PARTICLES)       // dp
    private val pLife = FloatArray(MAX_PARTICLES)       // fraction of the burst the piece stays visible
    private val pColor = IntArray(MAX_PARTICLES)
    private val pKind = IntArray(MAX_PARTICLES)         // 0 dot, 1 streak, 2 shard

    // ---- boiling bubbles inside the swelling tube ----
    private val bPos = FloatArray(MAX_BUBBLES)      // start phase 0..1 (0 = bottom, 1 = surface)
    private val bSpd = FloatArray(MAX_BUBBLES)      // relative rise speed
    private val bX = FloatArray(MAX_BUBBLES)        // position across the tube, -1..1
    private val bR = FloatArray(MAX_BUBBLES)        // radius in dp

    private val startMs = SystemClock.uptimeMillis()

    /** One shared timer: redraws ~30 fps while something moves on the screen. */
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val animating = (percent < 100 && cfg.waveOn) ||
                (exploding && now - explodeStartMs < ((swellS + BURST_S) * 1000f).toLong() + 200L)
            if (animating) invalidate()     // at 100 % (not exploding) the picture is still
            postDelayed(this, FRAME_MS)
        }
    }

    init {
        val rnd = java.util.Random(20240607L)
        // The flying pieces use the colours of the liquid parts that are switched on, plus the cap colour.
        var nc = 0
        val pal = IntArray(4)
        for (sg in cfg.segs) if (sg.on) pal[nc++] = sg.color
        pal[nc++] = cfg.capColor
        for (i in 0 until MAX_PARTICLES) {
            val a = rnd.nextFloat() * TAU
            pCos[i] = cos(a)
            pSin[i] = sin(a)
            pSpeed[i] = 120f + rnd.nextFloat() * 620f
            pSize[i] = 1.2f + rnd.nextFloat() * 2.8f
            pLife[i] = 0.55f + rnd.nextFloat() * 0.45f
            pColor[i] = pal[rnd.nextInt(nc)]
            pKind[i] = rnd.nextInt(3)
        }
        for (i in 0 until MAX_BUBBLES) {
            bPos[i] = rnd.nextFloat()
            bSpd[i] = 0.7f + rnd.nextFloat() * 0.6f
            bX[i] = (rnd.nextFloat() * 2f - 1f) * 0.8f
            bR[i] = 2.2f + rnd.nextFloat() * 3.8f
        }
    }

    fun updateData(percent: Int, temp: Float) {
        this.percent = percent.coerceIn(0, 100)
        percentText = this.percent.toString()
        percentWidth = thin.width(percentText)
        tempText = if (!cfg.showTemp) "" else cfg.tempText(temp)
        tempTextColor = cfg.tempColor(temp)
        invalidate()
    }

    /**
     * Starts the animation: the full tube swells, bursts and the screen goes black (call at 100 %).
     * [offsetSec] skips the first seconds (used by the settings preview).
     */
    fun startExplosion(offsetSec: Float = 0f) {
        if (exploding) return
        exploding = true
        explodeStartMs = SystemClock.uptimeMillis() - (offsetSec * 1000f).toLong()
        invalidate()
    }

    private fun startTicker() {
        removeCallbacks(ticker)
        post(ticker)
    }

    private fun stopTicker() {
        removeCallbacks(ticker)
    }

    private fun readCutout(insets: WindowInsets?) {
        if (insets == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val cut = insets.displayCutout
        cutoutBottom = (cut?.safeInsetTop ?: 0).toFloat()
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        readCutout(insets)
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        readCutout(rootWindowInsets)
        if (windowVisibility == VISIBLE) startTicker()
    }

    override fun onDetachedFromWindow() {
        stopTicker()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startTicker() else stopTicker()
    }

    /** Puts the unit gradient [shader] on the horizontal span [x0, x0 + span]. */
    private fun placeShader(shader: Shader, x0: Float, span: Float) {
        unitMatrix.setScale(span, 1f)
        unitMatrix.postTranslate(x0, 0f)
        shader.setLocalMatrix(unitMatrix)
    }

    private fun darken(c: Int, f: Float): Int =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    /** Mixes [c] with white by [f] (0 = c, 1 = white). */
    private fun tint(c: Int, f: Float): Int = Color.rgb(
        Color.red(c) + ((255 - Color.red(c)) * f).toInt(),
        Color.green(c) + ((255 - Color.green(c)) * f).toInt(),
        Color.blue(c) + ((255 - Color.blue(c)) * f).toInt()
    )

    /** Bulge of the swelling body: 0 at the top and the bottom, widest about 42 % down from the top. */
    private fun bulge(u: Float): Float = sin(PI_F * u.coerceIn(0f, 1f).pow(0.8f))

    /** Half-width of the body (px) at height [y]: the straight tube plus the swelling bulge. */
    private fun halfWidthAt(y: Float, rimY: Float, yBot: Float, rx: Float, b: Float): Float {
        val u = ((y - rimY) / (yBot - rimY)).coerceIn(0f, 1f)
        return rx + b * bulge(u)
    }

    /**
     * The glass shading (dark walls, bright reflection on the left). While the tube swells it is cut into
     * thin horizontal slices, each one stretched to the width of the body at that height, so the shading
     * bends with the curved walls instead of staying a straight vertical band.
     */
    private fun drawShading(
        canvas: Canvas, cx: Float, rx: Float, b: Float,
        rimY: Float, yBot: Float, bottom: Float, bandTop: Float
    ) {
        val yEnd = bottom + dp(2f)
        if (b < 0.5f) {
            val save = canvas.save()
            canvas.translate(cx - rx, 0f)
            canvas.scale(2f * rx, 1f)
            canvas.drawRect(-0.1f, bandTop, 1.1f, yEnd, slicePaint)
            canvas.restoreToCount(save)
            return
        }
        val yStart = max(bandTop, rimY)
        var prev = yStart
        for (i in 1..SHADE_SLICES) {
            val yy = if (i == SHADE_SLICES) yEnd
            else Math.round(yStart + (yBot - yStart) * i / SHADE_SLICES).toFloat()
            if (yy <= prev) continue
            val hw = halfWidthAt((prev + yy) / 2f, rimY, yBot, rx, b)
            val save = canvas.save()
            canvas.translate(cx - hw, 0f)
            canvas.scale(2f * hw, 1f)
            canvas.drawRect(-0.1f, prev, 1.1f, yy, slicePaint)
            canvas.restoreToCount(save)
            prev = yy
        }
    }

    /** A soft white reflection that follows the curved wall: it sits at fraction [f] (-1..1) of the half-width. */
    private fun drawStreak(
        canvas: Canvas, cx: Float, f: Float, y0: Float, y1: Float,
        rimY: Float, yBot: Float, rx: Float, b: Float, widthPx: Float, color: Int
    ) {
        streakPath.reset()
        for (i in 0..STREAK_STEPS) {
            val y = y0 + (y1 - y0) * i / STREAK_STEPS
            val x = cx + f * halfWidthAt(y, rimY, yBot, rx, b)
            if (i == 0) streakPath.moveTo(x, y) else streakPath.lineTo(x, y)
        }
        streakPaint.strokeWidth = widthPx
        streakPaint.color = color
        canvas.drawPath(streakPath, streakPaint)
    }

    /** White boiling bubbles that rise through the liquid; there are more of them, and faster, the closer the burst. */
    private fun drawBubbles(
        canvas: Canvas, expT: Float, swellP: Float, cx: Float, rx: Float, b: Float,
        rimY: Float, yBot: Float
    ) {
        val maxActive = cfg.bubblesV / 100f * MAX_BUBBLES
        val active = (maxActive * (0.15f + 0.85f * swellP)).toInt()
        if (active <= 0) return
        val yLow = yBot - dp(6f)
        val yTop = rimY + dp(10f)
        // Integral of the rising speed (it grows 2.5x towards the burst) so the motion never jumps.
        val cum = (expT + 1.5f * expT * expT / swellS) * BUBBLE_RATE
        for (i in 0 until min(active, MAX_BUBBLES)) {
            val pos = (bPos[i] + cum * bSpd[i]) % 1f
            val y = yLow - pos * (yLow - yTop)
            val hw = halfWidthAt(y, rimY, yBot, rx, b)
            val x = cx + bX[i] * hw * 0.85f + sin(expT * 1.7f + i * 2.1f) * dp(2f)
            val r = dp(bR[i]) * (1f + 0.9f * pos) * (1f + swellP * 0.6f)
            val a = min(1f, min(pos / 0.08f, (1f - pos) / 0.06f))
            bubblePaint.color = Color.argb((220 * a).toInt(), 255, 255, 255)
            canvas.drawCircle(x, y, r, bubblePaint)
            if (i % 3 == 0) {
                // Two bubbles that have melted into one.
                canvas.drawCircle(x + r * 0.95f, y - r * 0.35f, r * 0.7f, bubblePaint)
            }
            fillPaint.color = Color.argb((200 * a).toInt(), 255, 255, 255)
            canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, max(1f, r * 0.18f), fillPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val t = (now - startMs) / 1000f
        val full = percent >= 100

        // Seconds since the animation started (-1 when it has not).
        val expT = if (exploding) (now - explodeStartMs) / 1000f else -1f
        val swelling = expT >= 0f && expT < swellS
        val bursting = expT >= swellS

        // Changes every time the level crosses a multiple of the "shift every" setting.
        val shifting = cfg.shiftOn && !full && !centerAlways
        val rightSide = if (cfg.shiftOn) (percent / cfg.shiftEvery) % 2 == 0 else true

        // ---- vertical layout: the cylinder and the numbers go as low as the screen allows ----
        val bottom = h - dp(BOTTOM_MARGIN_DP)           // lowest point of the cylinder
        val lowBase = bottom                            // bottom line of the lower number slot
        val highBase = lowBase - (thin.h + dp(14f))     // upper slot sits above it, 14dp of clear black between

        // ---- horizontal layout: the tube sits in the right or the left half of its slot ----
        val fullW = min(w * 0.60f, dp(130f))
        val halfW = fullW / 2f
        val slotLeft = (w - fullW) / 2f + if (rightSide) halfW else 0f
        val tubeW = min(halfW, dp(cfg.tubeWidthDp.toFloat()))
        val rx = tubeW / 2f
        val ry = rx * ELLIPSE_RATIO

        // Swelling: the walls bulge out (slowly at first), and the whole tube trembles more and more.
        val swellP = if (swelling) expT / swellS else 0f
        val bulgeMax = max(0f, w / 2f - dp(6f) - rx)
        val b = bulgeMax * swellP.pow(1.5f)
        val tremble = if (swelling) {
            val kk = ((expT - (swellS - 14f)) / 14f).coerceIn(0f, 1f)
            dp(2.5f) * (cfg.shakeV / 50f) * kk * kk * sin(expT * 43f)
        } else 0f

        val cx = (if (shifting) slotLeft + halfW / 2f else w / 2f) + tremble
        val x0 = cx - rx
        val x1 = cx + rx

        // ---- top of the cylinder: at 100 % the blue cap sits right under the camera hole ----
        val camBottom = if (cutoutBottom > 0f) cutoutBottom else dp(FALLBACK_CAMERA_DP)
        val capRy = dp(3.5f)
        val capH = dp(13f)
        val rimFull = camBottom + dp(CAMERA_GAP_DP) + capH + capRy   // centre of the rim ellipse at 100 %

        // ---- level geometry (measured on the liquid column) ----
        val head = ry + dp(HEAD_EXTRA_DP)               // empty glass above the liquid: clears the rim's front arc + waves
        val yBot = bottom - ry                          // centre of the bottom ellipse
        val yFull = rimFull + head                      // liquid level at the top of the scale
        val total = yBot - yFull

        if (bursting) {
            drawBurst(canvas, expT - swellS, w / 2f, rimFull + (yBot - rimFull) * 0.42f, w, h)
            return
        }

        val fillH = max(total * percent / 100f, dp(4f))
        val surfaceScale = yBot - fillH
        // At 100 % the liquid fills the whole glass up to the cap, flat.
        val surfaceY = if (full) rimFull else surfaceScale   // mean liquid surface
        val rimY = if (full) rimFull else surfaceScale - head // centre of the rim ellipse (top of the glass)

        // Outline of the glass: left wall, rounded bottom, right wall (open at the top). The walls are
        // straight, except while the tube swells before it bursts.
        shellPath.reset()
        for (i in 0..BODY_STEPS) {
            val u = i.toFloat() / BODY_STEPS
            val y = rimY + (yBot - rimY) * u
            val x = x0 - b * bulge(u)
            if (i == 0) shellPath.moveTo(x, y) else shellPath.lineTo(x, y)
        }
        ovalRect.set(x0, yBot - ry, x1, yBot + ry)
        shellPath.arcTo(ovalRect, 180f, -180f, false)
        for (i in BODY_STEPS downTo 0) {
            val u = i.toFloat() / BODY_STEPS
            val y = rimY + (yBot - rimY) * u
            shellPath.lineTo(x1 + b * bulge(u), y)
        }

        val bodyL = x0 - b
        val bodyR = x1 + b

        // ---- liquid, seen through the glass ----
        val save = canvas.save()
        canvas.clipPath(shellPath)

        // Wave: the whole surface tilts left/right (slosh) plus a small travelling ripple. Off at 100 %.
        val amp = if (full || !cfg.waveOn) 0f else min(1f, fillH / dp(14f))
        val tilt = dp(TILT_DP) * waveK * amp * sin(TAU * t / TILT_PERIOD_S)
        val ripple = dp(RIPPLE_DP) * waveK * amp
        val ph1 = TAU * t / RIPPLE_PERIOD1_S
        val ph2 = -TAU * t / RIPPLE_PERIOD2_S
        val k = TAU / tubeW
        crestPath.reset()
        for (i in 0..WAVE_STEPS) {
            val x = x0 - 1f + (tubeW + 2f) * i / WAVE_STEPS
            val dx = x - cx
            val y = surfaceY + tilt * (dx / rx) +
                ripple * (0.65f * sin(k * dx + ph1) + 0.35f * sin(2.3f * k * dx + ph2))
            if (i == 0) crestPath.moveTo(x, y) else crestPath.lineTo(x, y)
        }

        // Coloured parts: only the part that contains the level runs up to the top of the wave, so the
        // highest coloured part is the one that waves. At 100 % the highest part fills the whole glass.
        val bandTop = if (full) rimY else surfaceY - dp((TILT_DP + RIPPLE_DP) * max(1f, waveK)) - dp(1f)
        val lv = max(percent, 1)
        val topSeg = cfg.segIndexFor(percent)
        val fullSeg = cfg.topSegIndex()
        for (n in cfg.segs.indices) {
            val sg = cfg.segs[n]
            if (!sg.on || sg.from > lv) continue
            val yLow = if (sg.from <= 1) bottom + dp(2f) else yBot - total * (sg.from - 1) / 100f
            val yHigh = if (lv <= sg.to || (full && n == fullSeg)) bandTop else yBot - total * sg.to / 100f
            if (yLow > yHigh) {
                fillPaint.color = sg.color
                canvas.drawRect(bodyL - 2f, yHigh, bodyR + 2f, yLow, fillPaint)
            }
        }

        // Glass shading across the liquid: this is what makes the tube look round.
        drawShading(canvas, cx, rx, b, rimY, yBot, bottom, bandTop)

        // Boiling bubbles while the tube swells.
        if (swelling && cfg.bubblesV > 0) drawBubbles(canvas, expT, swellP, cx, rx, b, rimY, yBot)

        if (!full) {
            // Empty glass above the wave: paint it black again, then the wave line itself.
            airPath.set(crestPath)
            airPath.lineTo(x1 + 1f, bandTop - dp(2f))
            airPath.lineTo(x0 - 1f, bandTop - dp(2f))
            airPath.close()
            fillPaint.color = Color.BLACK
            canvas.drawPath(airPath, fillPaint)
            crestPaint.color = if (topSeg >= 0) tint(cfg.segs[topSeg].color, 0.6f) else Color.WHITE
            canvas.drawPath(crestPath, crestPaint)
        }

        canvas.restoreToCount(save)

        // Full: the top face of the liquid closes the glass right under the cap.
        if (full) {
            ovalRect.set(0f, rimY - ry, 1f, rimY + ry)
            fillPaint.color = if (fullSeg >= 0) cfg.segs[fullSeg].color else cfg.capColor
            val s2 = canvas.save()
            canvas.translate(x0, 0f)
            canvas.scale(tubeW, 1f)
            canvas.drawOval(ovalRect, fillPaint)
            canvas.drawOval(ovalRect, glassPaint)
            canvas.restoreToCount(s2)
        }

        // ---- glass shell: white outline and the elliptical rim ----
        canvas.drawPath(shellPath, shellPaint)
        ovalRect.set(x0, rimY - ry, x1, rimY + ry)
        canvas.drawOval(ovalRect, shellPaint)

        // ---- glass reflections (they follow the curved walls) ----
        val sy0 = max(rimY + ry + dp(8f), surfaceY + dp(6f))   // reflections only over the liquid
        val sy1 = bottom - dp(10f)
        if (sy1 - sy0 > dp(30f)) {
            drawStreak(canvas, cx, -0.61f, sy0 + dp(2f), sy1 - dp(2f), rimY, yBot, rx, b, dp(4f), STREAK_STRONG)
            drawStreak(canvas, cx, 0.48f, sy0 + dp(11f), sy1 - dp(21f), rimY, yBot, rx, b, dp(2.5f), STREAK_SOFT)
        }

        // ---- cap (+) terminal, blue at 100% ----
        if (full) {
            val capW = tubeW * 0.48f
            val capL = cx - capW / 2f
            val capTopY = rimY - capH
            placeShader(capShader, capL, capW)
            capPath.reset()
            capPath.moveTo(capL, capTopY)
            capPath.lineTo(capL, rimY)
            capRect.set(capL, rimY - capRy, capL + capW, rimY + capRy)
            capPath.arcTo(capRect, 180f, -180f, false)
            capPath.lineTo(capL + capW, capTopY)
            capPath.close()
            canvas.drawPath(capPath, capPaint)
            capRect.set(capL, capTopY - capRy, capL + capW, capTopY + capRy)
            fillPaint.color = tint(cfg.capColor, 0.45f)
            canvas.drawOval(capRect, fillPaint)
        }

        // ---- numbers: charge on the left, temperature on the right of the cylinder ----
        // With the shift on, they stand just outside the farthest wall the cylinder can reach in either
        // half (only the height changes: lower / upper slot); with it off they stand next to the tube.
        val baseline = if (rightSide) lowBase else highBase
        val outerHalf = if (cfg.shiftOn) halfW / 2f + rx else rx
        val gap = dp(NUMBER_GAP_DP)
        val mid = w / 2f

        thin.draw(canvas, percentText, mid - outerHalf - gap - percentWidth, baseline, cfg.levelColor(percent))
        if (tempText.isNotEmpty()) {
            thin.draw(canvas, tempText, mid + outerHalf + gap, baseline, tempTextColor)
        }
    }

    /** The burst: white flash, fire glow, shock rings and flying pieces. [tb] = seconds since the burst. */
    private fun drawBurst(canvas: Canvas, tb: Float, ex: Float, ey: Float, w: Float, h: Float) {
        if (tb >= BURST_S) return
        val nPart = min(cfg.particles, MAX_PARTICLES)

        // Very short white flash.
        if (tb < 0.12f) {
            fillPaint.color = Color.argb((255 * 0.85f * (1f - tb / 0.12f)).toInt(), 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, fillPaint)
        }

        // Fire glow: grows quickly, then fades.
        val gp = tb / 1.8f
        if (gp < 1f) {
            val r = w * (0.25f + 1.05f * easeOut(min(1f, tb / 1.0f)))
            glowPaint.alpha = (255 * (1f - gp) * (1f - gp)).toInt()
            val save = canvas.save()
            canvas.translate(ex, ey)
            canvas.scale(r, r)
            canvas.drawCircle(0f, 0f, 1f, glowPaint)
            canvas.restoreToCount(save)
        }

        // Two 1-pixel shock rings.
        for (i in 0..1) {
            val tr = tb - i * 0.15f
            if (tr <= 0f) continue
            val a = 1f - tr / 1.6f
            if (a <= 0f) continue
            val rr = w * (0.08f + 0.95f * easeOut(min(1f, tr / 1.4f)))
            linePaint.color = Color.argb((200 * a).toInt(), 255, 235, 200)
            canvas.drawCircle(ex, ey, rr, linePaint)
        }

        // Flying pieces: they slow down (drag) and fall a little (gravity).
        val g = dp(500f)
        for (i in 0 until nPart) {
            val fade = 1f - tb / (BURST_S * pLife[i])
            if (fade <= 0f) continue
            val dist = dp(pSpeed[i]) * (1f - exp(-DRAG * tb)) / DRAG
            val px = ex + pCos[i] * dist
            val py = ey + pSin[i] * dist + 0.5f * g * tb * tb * 0.5f
            val s = dp(pSize[i])
            val col = Color.argb((255 * fade).toInt(), Color.red(pColor[i]), Color.green(pColor[i]), Color.blue(pColor[i]))
            when (pKind[i]) {
                0 -> {
                    fillPaint.color = col
                    canvas.drawCircle(px, py, s * 0.6f, fillPaint)
                }
                1 -> {
                    linePaint.color = col
                    canvas.drawLine(px, py, px - pCos[i] * s * 5f, py - pSin[i] * s * 5f, linePaint)
                }
                else -> {
                    fillPaint.color = col
                    shardPath.reset()
                    shardPath.moveTo(px, py)
                    shardPath.lineTo(px + s * 2f, py + s * 0.8f)
                    shardPath.lineTo(px + s * 0.6f, py + s * 2f)
                    shardPath.close()
                    canvas.drawPath(shardPath, fillPaint)
                }
            }
        }
    }

    private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x)

    private companion object {
        // ---- layout ----
        const val BOTTOM_MARGIN_DP = 14f      // keeps the round screen corners from clipping the bottom
        const val NUMBER_GAP_DP = 6f          // clear black between a number and the farthest tube wall
        const val FALLBACK_CAMERA_DP = 28f    // used when the phone reports no camera cut-out
        const val CAMERA_GAP_DP = 3f          // gap between the camera hole and the blue cap

        // ---- look ----
        const val ELLIPSE_RATIO = 0.35f       // rim ellipse height / its half width
        const val HEAD_EXTRA_DP = 8f          // extra empty glass above the liquid (on top of the rim height)
        const val BODY_STEPS = 48             // points per wall of the (possibly swelling) tube

        // ---- wave animation (the wave and its strength are settings) ----
        const val FRAME_MS = 33L              // ~30 fps
        const val WAVE_STEPS = 14
        const val TILT_DP = 3.5f              // left/right slosh amplitude
        const val TILT_PERIOD_S = 3.2f
        const val RIPPLE_DP = 1.5f            // small travelling ripple
        const val RIPPLE_PERIOD1_S = 1.7f
        const val RIPPLE_PERIOD2_S = 2.6f
        const val TAU = 6.2831855f
        const val PI_F = 3.1415927f

        // ---- explosion timeline ----
        // (the slow swelling takes AppConfig.swellS seconds; the burst is followed by black until the end)
        const val BURST_S = 2.6f              // flash, rings, flying pieces
        const val MAX_PARTICLES = 200
        const val MAX_BUBBLES = 60
        const val BUBBLE_RATE = 0.12f         // rising cycles per second at the start
        const val SHADE_SLICES = 56
        const val STREAK_STEPS = 16
        const val DRAG = 2.2f

        // Glass shading drawn over the liquid: dark edges, bright reflection near the left edge.
        val GLASS_COLORS = intArrayOf(
            Color.argb(128, 0, 0, 0),
            Color.argb(20, 0, 0, 0),
            Color.argb(77, 255, 255, 255),
            Color.argb(5, 255, 255, 255),
            Color.argb(26, 0, 0, 0),
            Color.argb(140, 0, 0, 0)
        )
        val GLASS_POS = floatArrayOf(0f, 0.14f, 0.24f, 0.40f, 0.78f, 1f)

        // Cap terminal.
        val CAP_POS = floatArrayOf(0f, 0.25f, 0.55f, 1f)

        val STREAK_STRONG = Color.argb(128, 255, 255, 255)
        val STREAK_SOFT = Color.argb(46, 255, 255, 255)

        val GLOW_COLORS = intArrayOf(
            Color.argb(255, 255, 250, 220),
            Color.argb(230, 255, 170, 40),
            Color.argb(150, 200, 80, 0),
            Color.argb(0, 120, 40, 0)
        )
        val GLOW_POS = floatArrayOf(0f, 0.25f, 0.6f, 1f)
    }
}
