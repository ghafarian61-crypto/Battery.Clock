package com.example.batterypercent

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.Calendar

/**
 * The clock screen. [MonitorService] opens it whenever the screen turns off, so it stands in for the
 * switched-off screen:
 *  - shows over the lock screen, turns the screen on and KEEPS it on (window flag + a screen wake lock,
 *    because some phones, e.g. the Galaxy A34, ignore the flag alone and time the screen out);
 *  - brightness follows the day / night [Schedule]; outside both ranges it draws black only, lets the screen
 *    switch off by itself and never reopens;
 *  - redraws once a minute (ACTION_TIME_TICK), nothing else runs;
 *  - on the hour (minute 0) it is shown at full brightness for 10 seconds, then back to the scheduled value;
 *  - one tap closes it and shows the home / lock screen; so does the power button (handled by the service).
 */
class ClockActivity : Activity() {

    private lateinit var clockView: ClockView

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var flashing = false

    /** Pulse mode: shown for a few seconds, then the screen is switched off again (see [Pulse.kt]). */
    private var pulse = false

    // Window changes cause a visible surface update, so they are made only when the value really changes.
    private var lastBrightness = -1f
    private var screenHeld = false

    private val endFlash = Runnable {
        flashing = false
        applySchedule()
    }

    private val endPulse = Runnable { finishPulse() }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applySchedule()
            // ACTION_TIME_TICK fires at the start of every minute: minute 0 = a new hour has just begun.
            if (intent.action == Intent.ACTION_TIME_TICK &&
                !pulse &&
                !ClockState.expired &&
                ClockStyle.flashEnabled(this@ClockActivity) &&
                Calendar.getInstance().get(Calendar.MINUTE) == 0
            ) {
                flash()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pulse = intent?.getBooleanExtra(EXTRA_PULSE, false) == true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        requestLowRefresh()

        clockView = ClockView(this)
        setContentView(clockView)
        ClockState.attach(this)

        // One short tap (finger lifted without moving or holding) closes the clock at once, so the lock screen
        // and the fingerprint spot show up. A long press or a swipe does nothing.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                goHomeAndClose()
                return true
            }
        })
        clockView.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            true
        }

        registerReceiver(
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)          // every minute
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        )
        applySchedule()
        if (pulse) {
            if (ClockState.expired) finishPulse() else handler.postDelayed(endPulse, pulseMillis())
        }
    }

    /** True in the first minute of an hour while the hourly flash is switched on. */
    private fun isHourFlash(): Boolean =
        ClockStyle.flashEnabled(this) && Calendar.getInstance().get(Calendar.MINUTE) == 0

    /** How long a pulse lasts; the hourly flash makes the one at minute 0 at least as long as the flash time. */
    private fun pulseMillis(): Long {
        var sec = ClockStyle.pulseSeconds(this)
        if (isHourFlash()) sec = maxOf(sec, ClockStyle.flashSeconds(this))
        return sec * 1000L
    }

    /** End of a pulse: let go of the screen, switch it off (like the power button) and close the clock. */
    private fun finishPulse() {
        handler.removeCallbacks(endPulse)
        holdScreen(false)
        // Announce the screen-off first: the service must not mistake it for the user and open the clock again.
        PulseState.markExpectedOff(this, true)
        if (!ScreenLock.lock(this)) PulseState.markExpectedOff(this, false)   // not possible: the system timeout does it
        ClockState.showing = false
        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        applySchedule()
    }

    /** Shows the time and applies the brightness of the current range; outside every range: black and off. */
    private fun applySchedule() {
        clockView.configure(ClockStyle.load(this))
        val c = Calendar.getInstance()
        clockView.setTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        readBattery()

        val brightness = Schedule.brightnessNow(this)
        if (brightness == null) {
            expire()
            return
        }
        if (ClockState.expired) {
            ClockState.expired = false
            clockView.blank = false
        }
        holdScreen(true)                                     // also renews the wake lock every minute
        if (!flashing) setBrightness(if (pulse && isHourFlash()) FULL_BRIGHTNESS else brightness)
    }

    /** Battery percentage and temperature from the sticky battery broadcast (read once a minute, no receiver kept). */
    private fun readBattery() {
        try {
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val temp = if (b.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            } else {
                Int.MIN_VALUE
            }
            clockView.setBattery(percent, temp)
        } catch (e: Exception) {
        }
    }

    /** Outside the schedule: stop drawing, stop keeping the screen on; the normal timeout switches it off. */
    private fun expire() {
        if (ClockState.expired) return
        ClockState.expired = true
        flashing = false
        handler.removeCallbacks(endFlash)
        clockView.blank = true
        holdScreen(false)
        setBrightness(DIM_BRIGHTNESS)
    }

    /** Full brightness for the flash time chosen in the settings, then back to the scheduled brightness. */
    private fun flash() {
        flashing = true
        setBrightness(FULL_BRIGHTNESS)
        handler.removeCallbacks(endFlash)
        handler.postDelayed(endFlash, ClockStyle.flashSeconds(this) * 1000L)
    }

    /**
     * Keeps the screen on: the window flag plus a screen-bright wake lock. The lock has a timeout and is renewed
     * every minute, so even if this screen dies unexpectedly it cannot keep the screen on for long.
     */
    @Suppress("DEPRECATION")
    private fun holdScreen(on: Boolean) {
        try {
            if (on) {
                if (!screenHeld) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    screenHeld = true
                }
                if (wakeLock == null) {
                    wakeLock = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "ClockAOD:clock")
                        .apply { setReferenceCounted(false) }
                }
                // A pulse needs the lock only for its own few seconds, so a crash cannot keep the screen on.
                wakeLock?.acquire(if (pulse) pulseMillis() + 5000L else WAKE_LOCK_MS)
            } else {
                if (screenHeld) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    screenHeld = false
                }
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        } catch (e: Exception) {
        }
    }

    /**
     * Asks the system for the lowest refresh rate the screen offers: the picture changes once a minute, so there is
     * no reason to refresh it 60 / 120 times a second. It is only a request; the phone decides (LTPO panels such as the
     * S24 may go down to 1 Hz, other panels usually stop at 60 Hz).
     */
    @Suppress("DEPRECATION")
    private fun requestLowRefresh() {
        try {
            val lp = window.attributes
            lp.preferredRefreshRate = LOW_REFRESH_HZ
            val display = windowManager.defaultDisplay
            val current = display.mode
            var lowest = current
            for (m in display.supportedModes) {
                if (m.physicalWidth == current.physicalWidth &&
                    m.physicalHeight == current.physicalHeight &&
                    m.refreshRate < lowest.refreshRate
                ) {
                    lowest = m
                }
            }
            lp.preferredDisplayModeId = lowest.modeId
            window.attributes = lp
        } catch (e: Exception) {
        }
    }

    private fun setBrightness(value: Float) {
        if (value == lastBrightness) return
        lastBrightness = value
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private fun goHomeAndClose() {
        ClockState.showing = false
        if (pulse) {
            // The user wants the phone: stop the pulse cycle (it restarts when the screen goes off again).
            handler.removeCallbacks(endPulse)
            PulseState.expectOffUntil = 0L
            PulseScheduler.cancel(this)
        }
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
        finishAndRemoveTask()
    }

    /** Home / recents gesture: the user left the clock on purpose. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        ClockState.showing = false
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        holdScreen(false)
        try {
            unregisterReceiver(timeReceiver)
        } catch (e: IllegalArgumentException) {
        }
        ClockState.detach(this)
        super.onDestroy()
    }

    companion object {
        /** Intent extra: this run of the clock is one pulse of pulse mode. */
        const val EXTRA_PULSE = "pulse"

        /** Refresh rate (Hz) requested for the clock: the lowest the system will give. */
        const val LOW_REFRESH_HZ = 1f

        /** Window brightness outside the schedule: 0.0f = the lowest value the system allows. */
        const val DIM_BRIGHTNESS = 0.0f

        /** Window brightness during the hourly flash (1.0 = the maximum). */
        const val FULL_BRIGHTNESS = 1.0f

        /** Wake lock timeout; it is renewed every minute while the clock is shown. */
        private const val WAKE_LOCK_MS = 5 * 60_000L
    }
}
