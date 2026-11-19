package com.example.batterypercent

import android.content.Context
import java.util.Calendar

/**
 * Day / night schedule: two time ranges, each with its own brightness (0..100 %).
 * Outside both ranges the clock stays off (the screen is simply black / switched off).
 *
 * Times are stored as minutes since midnight; the end minute is included (day 09:00 - 17:59, night 18:00 - 08:59).
 * A range may cross midnight.
 * If the two ranges overlap, the day range wins. A range whose start equals its end is switched off.
 */
object Schedule {
    const val DAY = 0
    const val NIGHT = 1

    private const val PREFS = "clock_schedule"
    private val NAMES = arrayOf("day", "night")
    private val DEFAULT_START = intArrayOf(9 * 60, 18 * 60)       // day 09:00, night 18:00
    private val DEFAULT_END = intArrayOf(17 * 60 + 59, 8 * 60 + 59)   // day ..17:59, night ..08:59
    private val DEFAULT_PERCENT = intArrayOf(70, 10)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(ctx: Context, i: Int): Int = prefs(ctx).getInt(NAMES[i] + "_start", DEFAULT_START[i])
    fun end(ctx: Context, i: Int): Int = prefs(ctx).getInt(NAMES[i] + "_end", DEFAULT_END[i])
    fun percent(ctx: Context, i: Int): Int = prefs(ctx).getInt(NAMES[i] + "_percent", DEFAULT_PERCENT[i])

    fun setStart(ctx: Context, i: Int, minutes: Int) {
        prefs(ctx).edit().putInt(NAMES[i] + "_start", minutes).apply()
    }

    fun setEnd(ctx: Context, i: Int, minutes: Int) {
        prefs(ctx).edit().putInt(NAMES[i] + "_end", minutes).apply()
    }

    fun setPercent(ctx: Context, i: Int, percent: Int) {
        prefs(ctx).edit().putInt(NAMES[i] + "_percent", percent.coerceIn(0, 100)).apply()
    }

    /** True when [now] (minutes since midnight) lies in [start, end] (the end minute is included). */
    fun contains(start: Int, end: Int, now: Int): Boolean = when {
        start == end -> false
        start < end -> now >= start && now <= end
        else -> now >= start || now <= end
    }

    /** Window brightness (0.0 .. 1.0) for the current time, or null when the time is outside both ranges. */
    fun brightnessNow(ctx: Context): Float? {
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (contains(start(ctx, DAY), end(ctx, DAY), now)) return percent(ctx, DAY) / 100f
        if (contains(start(ctx, NIGHT), end(ctx, NIGHT), now)) return percent(ctx, NIGHT) / 100f
        return null
    }

    fun isActive(ctx: Context): Boolean = brightnessNow(ctx) != null
}
