package com.example.batterypercent

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import java.util.Locale
import kotlin.math.max

/** One coloured part of the liquid: a colour from [from] % to [to] %. */
class Seg(val on: Boolean, val color: Int, val from: Int, val to: Int)

/**
 * Every user setting, read once from SharedPreferences into plain fields.
 * Activities / views / the widget call [load] when they start, so a change made in the settings screen
 * is picked up the next time the charging screen (or the widget) is drawn.
 */
class AppConfig private constructor(private val p: SharedPreferences) {

    private fun i(key: String): Int = p.getInt(key, DEFAULTS[key] ?: 0)
    private fun b(key: String): Boolean = i(key) != 0

    // ---- digits ----
    val screenTextV = i(SCREEN_TEXT)
    val screenStrokeV = i(SCREEN_STROKE)
    val widgetTextV = i(WIDGET_TEXT)
    val widgetStrokeV = i(WIDGET_STROKE)
    val widgetOutlineV = i(WIDGET_OUTLINE)

    // ---- look of the tube ----
    val shellStrokeV = i(SHELL_STROKE)
    val crestStrokeV = i(CREST_STROKE)
    val tubeWidthDp = i(TUBE_WIDTH)
    val waveOn = b(WAVE_ON)
    val waveAmpV = i(WAVE_AMP)

    // ---- brightness (0..100) and timing ----
    val bCharge = i(B_CHARGE)
    val bBoost = i(B_BOOST)
    val bAnim = i(B_ANIM)
    val bPeek = i(B_PEEK)
    val boostEvery = max(1, i(BOOST_EVERY))
    val boostSec = max(1, i(BOOST_SEC))
    val shiftOn = b(SHIFT_ON)
    val shiftEvery = max(1, i(SHIFT_EVERY))
    val peekSec = max(1, i(PEEK_SEC))
    val doubleTap = b(DOUBLE_TAP)

    // ---- the 100 % animation ----
    val animOn = b(ANIM_ON)
    val animSec = max(15, i(ANIM_SEC))
    val particles = i(PARTICLES).coerceIn(0, 200)
    val shakeV = i(SHAKE)
    val bubblesV = i(BUBBLES)

    // ---- temperature ----
    val showTemp = b(SHOW_TEMP)
    val fahrenheit = b(TEMP_F)
    val t1 = i(T1)
    val t2 = i(T2)
    val t3 = i(T3)

    // ---- liquid colours ----
    val segs: Array<Seg> = Array(3) { n ->
        val k = "seg${n + 1}"
        Seg(b(k + "_on"), COLORS[i(k + "_color").coerceIn(0, COLORS.size - 1)], i(k + "_from"), i(k + "_to"))
    }
    val capColor: Int = COLORS[i(CAP_COLOR).coerceIn(0, COLORS.size - 1)]
    val capFrom = i(CAP_FROM)

    val previewLevel = i(PREVIEW_LEVEL)

    /** Length of the slow swelling part of the 100 % animation (the burst and the black end follow). */
    val swellS: Float get() = max(5f, animSec - 7f)

    fun screenTextDp(): Float = 8f + screenTextV * 0.32f

    /** Colour of the number / liquid for a level. */
    fun levelColor(level: Int): Int {
        if (level >= capFrom) return capColor
        val n = segIndexFor(level)
        return if (n >= 0) segs[n].color else Color.WHITE
    }

    /**
     * The coloured part the level is in; when it is in a gap (or below the first part) the nearest part
     * below it, or the lowest part. -1 when every part is switched off.
     */
    fun segIndexFor(level: Int): Int {
        val lv = max(level, 1)
        var best = -1
        for (n in segs.indices) {
            val s = segs[n]
            if (!s.on) continue
            if (lv >= s.from && lv <= s.to) return n
            if (s.to < lv && (best < 0 || s.to > segs[best].to)) best = n
        }
        if (best >= 0) return best
        for (n in segs.indices) if (segs[n].on) return n
        return -1
    }

    /** The enabled part that reaches highest: it is the one that fills the tube at 100 %. */
    fun topSegIndex(): Int {
        var best = -1
        for (n in segs.indices) {
            if (!segs[n].on) continue
            if (best < 0 || segs[n].to > segs[best].to) best = n
        }
        return best
    }

    fun tempText(temp: Float): String {
        if (temp.isNaN()) return "--"
        val v = if (fahrenheit) temp * 9f / 5f + 32f else temp
        return String.format(Locale.US, "%.1f", v)
    }

    /** Temperature colour (the limits are always in °C). */
    fun tempColor(temp: Float): Int = when {
        temp.isNaN() -> Color.WHITE
        temp <= t1 -> Color.rgb(0, 190, 255)
        temp <= t2 -> Color.rgb(0, 255, 120)
        temp <= t3 -> Color.rgb(255, 175, 0)
        else -> Color.rgb(255, 0, 0)
    }

    companion object {
        const val PREFS = "settings"

        // digits
        const val SCREEN_TEXT = "screen_text"
        const val SCREEN_STROKE = "screen_stroke"
        const val WIDGET_TEXT = "widget_text"
        const val WIDGET_STROKE = "widget_stroke"
        const val WIDGET_OUTLINE = "widget_outline"
        // tube
        const val SHELL_STROKE = "shell_stroke"
        const val CREST_STROKE = "crest_stroke"
        const val TUBE_WIDTH = "tube_width"
        const val WAVE_ON = "wave_on"
        const val WAVE_AMP = "wave_amp"
        // brightness / timing
        const val B_CHARGE = "b_charge"
        const val B_BOOST = "b_boost"
        const val B_ANIM = "b_anim"
        const val B_PEEK = "b_peek"
        const val BOOST_EVERY = "boost_every"
        const val BOOST_SEC = "boost_sec"
        const val SHIFT_ON = "shift_on"
        const val SHIFT_EVERY = "shift_every"
        const val PEEK_SEC = "peek_sec"
        const val DOUBLE_TAP = "double_tap"
        // animation
        const val ANIM_ON = "anim_on"
        const val ANIM_SEC = "anim_sec"
        const val PARTICLES = "particles"
        const val SHAKE = "shake"
        const val BUBBLES = "bubbles"
        // temperature
        const val SHOW_TEMP = "show_temp"
        const val TEMP_F = "temp_f"
        const val T1 = "t1"
        const val T2 = "t2"
        const val T3 = "t3"
        // colours
        const val CAP_COLOR = "cap_color"
        const val CAP_FROM = "cap_from"
        const val PREVIEW_LEVEL = "preview_level"

        /** The colours that can be chosen: red, green, blue use only their own sub-pixel; yellow = red + green. */
        val COLORS = intArrayOf(
            Color.rgb(255, 0, 0),
            Color.rgb(0, 255, 0),
            Color.rgb(0, 150, 255),
            Color.rgb(255, 255, 0)
        )

        val DEFAULTS: Map<String, Int> = mapOf(
            SCREEN_TEXT to 28, SCREEN_STROKE to 0, WIDGET_TEXT to 100, WIDGET_STROKE to 55, WIDGET_OUTLINE to 70,
            SHELL_STROKE to 0, CREST_STROKE to 0, TUBE_WIDTH to 56, WAVE_ON to 1, WAVE_AMP to 50,
            B_CHARGE to 1, B_BOOST to 100, B_ANIM to 100, B_PEEK to 100,
            BOOST_EVERY to 10, BOOST_SEC to 10, SHIFT_ON to 1, SHIFT_EVERY to 10, PEEK_SEC to 3, DOUBLE_TAP to 1,
            ANIM_ON to 1, ANIM_SEC to 60, PARTICLES to 110, SHAKE to 50, BUBBLES to 50,
            SHOW_TEMP to 1, TEMP_F to 0, T1 to 27, T2 to 30, T3 to 35,
            "seg1_on" to 1, "seg1_color" to 0, "seg1_from" to 1, "seg1_to" to 30,
            "seg2_on" to 1, "seg2_color" to 3, "seg2_from" to 31, "seg2_to" to 60,
            "seg3_on" to 1, "seg3_color" to 1, "seg3_from" to 61, "seg3_to" to 99,
            CAP_COLOR to 2, CAP_FROM to 100, PREVIEW_LEVEL to 55
        )

        fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(ctx: Context): AppConfig = AppConfig(prefs(ctx))

        fun default(key: String): Int = DEFAULTS[key] ?: 0

        fun reset(ctx: Context) {
            prefs(ctx).edit().clear().apply()
        }

        /** 0..100 -> window brightness; 0 is treated as the dimmest value the system accepts. */
        fun brightness(v: Int): Float = max(1, v) / 100f
    }
}
