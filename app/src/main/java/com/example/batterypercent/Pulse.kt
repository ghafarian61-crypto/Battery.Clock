package com.example.batterypercent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.TimeZone

/**
 * Pulse mode: instead of keeping the screen on all the time, the screen is switched on for a few seconds every
 * few minutes (an exact alarm wakes the phone), the clock is shown, and the screen is switched off again
 * ([ScreenLock]). Between two pulses nothing holds the CPU or the display awake, so the hardware really sleeps.
 */
object PulseState {
    /** The screen-on that happens before this time was caused by us, not by the user. */
    @Volatile var ourWakeUntil = 0L

    /** The screen-off that happens before this time was caused by us (the end of a pulse), not by the user. */
    @Volatile var expectOffUntil = 0L

    fun markOurWake() {
        ourWakeUntil = System.currentTimeMillis() + 6000L
    }

    fun isOurWake(): Boolean = System.currentTimeMillis() < ourWakeUntil

    /** [locked] = we switched the screen off ourselves; otherwise it will go off with the system timeout. */
    fun markExpectedOff(ctx: Context, locked: Boolean) {
        val extra = if (locked) 3000L else systemTimeoutMs(ctx) + 3000L
        expectOffUntil = System.currentTimeMillis() + extra
    }

    /** True once (and clears itself) when a screen-off was announced by the end of a pulse. */
    fun consumeExpectedOff(): Boolean {
        if (System.currentTimeMillis() < expectOffUntil) {
            expectOffUntil = 0L
            return true
        }
        return false
    }

    private fun systemTimeoutMs(ctx: Context): Long = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000).toLong()
    } catch (e: Exception) {
        30_000L
    }
}

object PulseScheduler {
    const val ACTION = "com.example.batterypercent.PULSE"
    private const val REQUEST_CODE = 4711

    /** The alarm fires a little after the minute changes, so the clock reads the NEW minute. */
    private const val LATENCY_MS = 300L

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST_CODE,
        Intent(ctx, PulseReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Next pulse: the next multiple of the interval on the local clock (e.g. every minute at :00 seconds). */
    fun scheduleNext(ctx: Context) {
        val interval = ClockStyle.pulseMinutes(ctx) * 60_000L
        val now = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(now).toLong()
        scheduleAt(ctx, ((now + offset) / interval + 1) * interval - offset + LATENCY_MS)
    }

    /** Outside the day / night ranges: sleep until the next range starts (nothing wakes the phone before). */
    fun scheduleAfterInactive(ctx: Context) {
        val minuteStart = System.currentTimeMillis() / 60_000L * 60_000L
        scheduleAt(ctx, minuteStart + Schedule.minutesUntilActive(ctx) * 60_000L + LATENCY_MS)
    }

    private fun scheduleAt(ctx: Context, triggerAt: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        try {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
            }
        }
    }

    fun cancel(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx))
        } catch (e: Exception) {
        }
    }
}

/** Receives the pulse alarm and hands it to [MonitorService] (the CPU is kept awake for a moment meanwhile). */
class PulseReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryPercent:pulse-alarm")
                .apply { setReferenceCounted(false) }
                .acquire(5000L)
        } catch (e: Exception) {
        }
        try {
            context.startForegroundService(
                Intent(context, MonitorService::class.java).setAction(PulseScheduler.ACTION)
            )
        } catch (e: Exception) {
        }
    }
}
