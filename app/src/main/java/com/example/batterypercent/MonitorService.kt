package com.example.batterypercent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/**
 * Always-on foreground service.
 *
 * While the charger is connected the charging screen replaces the switched-off screen:
 *  - the charger is plugged in while the screen is off  -> open it;
 *  - the screen turns off while charging                -> open it (shortly after);
 *  - the screen turns off while it is in front          -> the user pressed the power button:
 *                                                          wake the phone to the home screen instead.
 * It is never opened while the screen is on (the user is using the phone).
 * It also keeps the widget fresh.
 *
 * While NO charger is connected the clock ([ClockActivity]) takes the switched-off screen instead, the same way
 * (opened shortly after the screen turns off, power button = wake to the home screen), but only inside the
 * day / night [Schedule]. The charging screen always has priority: the clock steps aside when the charger
 * is connected.
 */
class MonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "battery_percent"
        private const val NOTIF_ID = 1
        private const val OPEN_DELAY_MS = 400L
        private const val WAKE_DELAY_MS = 200L
        private const val WAKE_HOLD_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The charger is connected. Tracked from the plug events: the sticky battery value can lag behind them. */
    @Volatile private var plugged = false

    private val openClockRunnable = Runnable { showClock() }

    private val openRunnable = Runnable {
        showChargeScreen(BatteryInfo.sticky(this@MonitorService), assumePlugged = false)
    }

    private val wakeRunnable = Runnable {
        wakeScreen()
        goHome()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    plugged = true
                    PulseScheduler.cancel(context)                      // the charging screen takes over
                    handler.removeCallbacks(openClockRunnable)
                    if (ClockState.showing) ClockState.closeScreen()   // the charging screen takes over
                    ChargeState.reset()
                    // The sticky battery intent may still say "unplugged" for a moment, so trust the event.
                    showChargeScreen(BatteryInfo.sticky(context), assumePlugged = true)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    plugged = false
                    handler.removeCallbacks(openRunnable)
                    handler.removeCallbacks(wakeRunnable)
                    ChargeState.reset()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    // Nobody sees the widget while the screen is off; ACTION_SCREEN_ON refreshes it.
                    if (isInteractive()) BatteryWidgetProvider.updateAll(context, intent)
                }
                Intent.ACTION_SCREEN_OFF -> onScreenOff(context)
                Intent.ACTION_SCREEN_ON -> {
                    // A screen-on we did not cause = the user woke the phone: the pulse cycle stops until the
                    // screen goes off again.
                    if (ClockStyle.pulseEnabled(context) && !PulseState.isOurWake()) PulseScheduler.cancel(context)
                    BatteryWidgetProvider.updateAll(context, BatteryInfo.sticky(context))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        plugged = BatteryInfo.plugged(BatteryInfo.sticky(this))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(receiver, filter)
        BatteryWidgetProvider.updateAll(this, BatteryInfo.sticky(this), force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PulseScheduler.ACTION) handler.post { onPulseAlarm() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    private fun isInteractive(): Boolean = getSystemService(PowerManager::class.java).isInteractive

    private fun onScreenOff(context: Context) {
        // The end of a pulse switches the screen off on purpose: that is not the user and needs no reaction.
        if (PulseState.consumeExpectedOff()) return
        handler.removeCallbacks(openRunnable)
        handler.removeCallbacks(wakeRunnable)
        handler.removeCallbacks(openClockRunnable)
        if (!(plugged || BatteryInfo.plugged(BatteryInfo.sticky(context)))) {
            ChargeState.reset()
            onScreenOffClock()
            return
        }
        if (ChargeState.showing) {
            // The charging screen was in front, so this is the power button: act like "wake the phone".
            ChargeState.closeScreen()
            handler.postDelayed(wakeRunnable, WAKE_DELAY_MS)
        } else {
            // The screen turned off by timeout / power button while using the phone: put our screen in its place.
            handler.postDelayed(openRunnable, OPEN_DELAY_MS)
        }
    }

    /** Screen went off with no charger: the clock is the stand-in screen. */
    private fun onScreenOffClock() {
        if (ClockState.showing) {
            if (ClockState.expired) {
                // Outside the schedule the clock no longer holds the screen: it went off by itself.
                // Close it quietly; nothing is woken, the screen stays off.
                ClockState.closeScreen()
            } else {
                // The clock was in front and holds the screen on, so this is the power button.
                ClockState.closeScreen()
                handler.postDelayed(wakeRunnable, WAKE_DELAY_MS)
            }
        } else {
            handler.postDelayed(openClockRunnable, OPEN_DELAY_MS)
        }
    }

    private fun showClock() {
        if (isInteractive()) return                      // screen is on: the user is using the phone
        if (plugged) return                              // charging: the charging screen is used instead
        if (!Schedule.isActive(this)) return             // outside the day / night ranges: stay off
        if (ClockState.showing) return
        if (!Settings.canDrawOverlays(this)) return
        if (ClockStyle.pulseActive(this)) {
            startPulse()
            return
        }
        try {
            startActivity(
                Intent(this, ClockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    /** The alarm of pulse mode: wake the screen for a few seconds, unless the situation has changed. */
    private fun onPulseAlarm() {
        if (!ClockStyle.pulseActive(this)) return                         // mode switched off / no permission: the chain ends
        if (plugged || BatteryInfo.plugged(BatteryInfo.sticky(this))) return   // the charging screen is used instead
        if (isInteractive()) return                                       // someone uses the phone: restart at next screen-off
        if (!Schedule.isActive(this)) {                                   // outside the day / night ranges: sleep until the next one
            PulseScheduler.scheduleAfterInactive(this)
            return
        }
        if (ClockState.showing || !Settings.canDrawOverlays(this)) {
            PulseScheduler.scheduleNext(this)
            return
        }
        startPulse()
    }

    /**
     * One pulse: open the clock in pulse mode (it turns the screen on through its window flags, shows the time for a
     * few seconds and switches the screen off again) and book the next alarm. The CPU is kept awake only for the start.
     */
    @Suppress("DEPRECATION")
    private fun startPulse() {
        PulseState.markOurWake()
        try {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryPercent:pulse-start")
                .apply { setReferenceCounted(false) }
                .acquire(10_000L)
        } catch (e: Exception) {
        }
        try {
            startActivity(
                Intent(this, ClockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ClockActivity.EXTRA_PULSE, true)
            )
        } catch (e: Exception) {
        }
        PulseScheduler.scheduleNext(this)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "BatteryPercent:wake"
            ).acquire(WAKE_HOLD_MS)
        } catch (e: Exception) {
        }
    }

    private fun goHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    private fun showChargeScreen(i: Intent?, assumePlugged: Boolean) {
        if (i == null) return
        if (isInteractive()) return                      // screen is on: the user is using the phone
        if (!assumePlugged && !BatteryInfo.plugged(i)) {
            ChargeState.reset()
            return
        }
        val level = BatteryInfo.level(i)
        if (level >= 100) {
            ChargeState.fullReached = true
            return
        }
        if (level < 0 || ChargeState.fullReached || ChargeState.showing) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            startActivity(
                Intent(this, ChargeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_title))
            .setOngoing(true)
            .build()
    }
}
