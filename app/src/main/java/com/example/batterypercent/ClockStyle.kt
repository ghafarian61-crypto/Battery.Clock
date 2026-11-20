package com.example.batterypercent

import android.content.Context

/**
 * Every look setting of the clock, chosen in the settings screen and stored in SharedPreferences.
 * [Config] is a snapshot of all of them; [ClockView.configure] only re-lays out when the snapshot changed.
 */
object ClockStyle {
    const val ANALOG = 0
    const val DIGITAL = 1

    /** Colour choices. AUTO = the original behaviour: red / green swap every 30 minutes. */
    const val C_AUTO = 0
    const val C_RED = 1
    const val C_GREEN = 2
    const val C_BLUE = 3

    /** Colours of the value ranges (battery percentage / temperature). */
    const val P_RED = 0
    const val P_YELLOW = 1
    const val P_GREEN = 2
    const val P_BLUE = 3

    // Limits shared by the settings screen and the sanity checks below.
    const val LEVEL_MIN = 5          // % of the full colour (never completely invisible)
    const val SIZE_MIN = 30          // % of the maximum clock size
    const val INFO_SIZE_MIN = 20     // % (battery / temperature digits)
    const val INFO_SIZE_MAX = 100
    const val LINE_MIN = 1           // pen width in pixels
    const val LINE_MAX = 4
    const val FLASH_MAX_SEC = 60
    const val PULSE_MIN_MAX = 10     // minutes between two pulses
    const val PULSE_SEC_MAX = 30     // seconds the clock is shown
    const val SHIFT_MIN = 10         // % of the full hourly step
    const val TOP_PAD_MAX = 150      // dp

    private const val PREFS = "clock_style"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Config(
        val digital: Boolean = false,
        val hour12: Boolean = false,
        /** Digits (digital) / numbers (analog). */
        val clockColor: Int = C_AUTO,
        /** Hands and sun / moon (analog). */
        val handsColor: Int = C_AUTO,
        /** Battery percentage and temperature (digital). */
        val infoColor: Int = C_AUTO,
        val clockLevel: Int = 100,
        val handsLevel: Int = 100,
        val infoLevel: Int = 100,
        /** % of the biggest clock that fits the screen. */
        val clockSize: Int = 100,
        /** Size of the battery / temperature digits, % (100 = as tall as fits in the colon slot). */
        val infoSize: Int = 60,
        val line: Int = 1,
        val showCharge: Boolean = true,
        val showTemp: Boolean = true,
        /** Temperature with one decimal (36.5) instead of whole degrees. */
        val tempDecimal: Boolean = true,
        /** Percentage colour by charge: <= chargeT1 -> C1, <= chargeT2 -> C2, up to 99 -> C3, 100 -> full. Colours: see PALETTE. */
        val chargeByLevel: Boolean = true,
        val chargeT1: Int = 30,
        val chargeT2: Int = 60,
        val chargeC1: Int = P_RED,
        val chargeC2: Int = P_YELLOW,
        val chargeC3: Int = P_GREEN,
        val chargeFull: Int = P_BLUE,
        /** Temperature colour (whole degrees C, the decimals are cut): <= tempT1 -> C1, <= tempT2 -> C2, above -> C3. */
        val tempByLevel: Boolean = true,
        val tempT1: Int = 30,
        val tempT2: Int = 35,
        val tempC1: Int = P_BLUE,
        val tempC2: Int = P_GREEN,
        val tempC3: Int = P_RED,
        /** Hourly shift (burn-in): even hours at the top, odd hours one step lower. Off = always at the top. */
        val shiftOn: Boolean = true,
        /** How far down the lower position is, % of the full step (100 = no pixel shared with the top position). */
        val shiftRange: Int = 100,
        /** Extra space (dp) between the camera / top edge and the clock, for phones whose camera hides the top. */
        val topPad: Int = 0,
        /** Minute of the day (0..1439) from which the SUN is shown; the sun stays until [moonStart]. Works like AM / PM. */
        val sunStart: Int = 12 * 60,
        /** Minute of the day (0..1439) from which the MOON is shown; the moon stays until [sunStart]. */
        val moonStart: Int = 0
    )

    fun load(ctx: Context): Config {
        val p = prefs(ctx)
        val d = Config()
        val t1 = p.getInt("charge_t1", d.chargeT1).coerceIn(0, 99)
        val u1 = p.getInt("temp_t1", d.tempT1).coerceIn(0, 99)
        return Config(
            digital = p.getInt("mode", ANALOG) == DIGITAL,
            hour12 = p.getBoolean("hour12", d.hour12),
            clockColor = p.getInt("clock_color", d.clockColor).coerceIn(C_AUTO, C_BLUE),
            handsColor = p.getInt("hands_color", d.handsColor).coerceIn(C_AUTO, C_BLUE),
            infoColor = p.getInt("info_color", d.infoColor).coerceIn(C_AUTO, C_BLUE),
            clockLevel = p.getInt("clock_level", d.clockLevel).coerceIn(LEVEL_MIN, 100),
            handsLevel = p.getInt("hands_level", d.handsLevel).coerceIn(LEVEL_MIN, 100),
            infoLevel = p.getInt("info_level", d.infoLevel).coerceIn(LEVEL_MIN, 100),
            clockSize = p.getInt("clock_size", d.clockSize).coerceIn(SIZE_MIN, 100),
            infoSize = p.getInt("info_size", d.infoSize).coerceIn(INFO_SIZE_MIN, INFO_SIZE_MAX),
            line = p.getInt("line", d.line).coerceIn(LINE_MIN, LINE_MAX),
            showCharge = p.getBoolean("show_charge", d.showCharge),
            showTemp = p.getBoolean("show_temp", d.showTemp),
            tempDecimal = p.getBoolean("temp_decimal", d.tempDecimal),
            chargeByLevel = p.getBoolean("charge_by_level", d.chargeByLevel),
            chargeT1 = t1,
            chargeT2 = maxOf(t1, p.getInt("charge_t2", d.chargeT2).coerceIn(0, 99)),
            chargeC1 = pal(p.getInt("charge_c1", d.chargeC1)),
            chargeC2 = pal(p.getInt("charge_c2", d.chargeC2)),
            chargeC3 = pal(p.getInt("charge_c3", d.chargeC3)),
            chargeFull = pal(p.getInt("charge_full", d.chargeFull)),
            tempByLevel = p.getBoolean("temp_by_level", d.tempByLevel),
            tempT1 = u1,
            tempT2 = maxOf(u1, p.getInt("temp_t2", d.tempT2).coerceIn(0, 99)),
            tempC1 = pal(p.getInt("temp_c1", d.tempC1)),
            tempC2 = pal(p.getInt("temp_c2", d.tempC2)),
            tempC3 = pal(p.getInt("temp_c3", d.tempC3)),
            shiftOn = p.getBoolean("shift_on", d.shiftOn),
            shiftRange = p.getInt("shift_range", d.shiftRange).coerceIn(SHIFT_MIN, 100),
            topPad = p.getInt("top_pad", d.topPad).coerceIn(0, TOP_PAD_MAX),
            sunStart = p.getInt("sun_start", d.sunStart).coerceIn(0, 1439),
            moonStart = p.getInt("moon_start", d.moonStart).coerceIn(0, 1439)
        )
    }

    // ---- plain getters / setters used by the settings screen
    private fun pal(v: Int) = v.coerceIn(P_RED, P_BLUE)

    fun getInt(ctx: Context, key: String, default: Int): Int = prefs(ctx).getInt(key, default)
    fun setInt(ctx: Context, key: String, value: Int) {
        prefs(ctx).edit().putInt(key, value).apply()
    }

    fun getBool(ctx: Context, key: String, default: Boolean): Boolean = prefs(ctx).getBoolean(key, default)
    fun setBool(ctx: Context, key: String, value: Boolean) {
        prefs(ctx).edit().putBoolean(key, value).apply()
    }

    fun mode(ctx: Context): Int = prefs(ctx).getInt("mode", ANALOG)
    fun setMode(ctx: Context, value: Int) = setInt(ctx, "mode", value)

    fun hour12(ctx: Context): Boolean = prefs(ctx).getBoolean("hour12", false)
    fun setHour12(ctx: Context, value: Boolean) = setBool(ctx, "hour12", value)

    // ---- hourly flash (full brightness at minute 0)
    fun flashEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("flash_on", true)
    fun flashSeconds(ctx: Context): Int = prefs(ctx).getInt("flash_sec", 10).coerceIn(1, FLASH_MAX_SEC)

    // ---- pulse mode: the screen wakes briefly every few minutes instead of staying on all the time
    fun pulseEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("pulse_on", true)

    /** Pulse mode is on AND the screen can really be switched off; without that permission the old always-on clock runs. */
    fun pulseActive(ctx: Context): Boolean = pulseEnabled(ctx) && ScreenLock.isGranted(ctx)

    /** Also show the clock at once when the screen goes off (power button / timeout); off = only on the exact minutes. */
    fun pulseFirst(ctx: Context): Boolean = prefs(ctx).getBoolean("pulse_first", false)

    fun pulseMinutes(ctx: Context): Int = prefs(ctx).getInt("pulse_min", 1).coerceIn(1, PULSE_MIN_MAX)

    /** Seconds the clock is shown; always at least 5 s shorter than the interval so the cycles never overlap. */
    fun pulseSeconds(ctx: Context): Int {
        val limit = minOf(PULSE_SEC_MAX, pulseMinutes(ctx) * 60 - 5)
        return prefs(ctx).getInt("pulse_sec", 5).coerceIn(2, limit)
    }

    /** Forgets every look setting (the day / night schedule is kept). */
    fun reset(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
