package com.example.batterypercent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent

/**
 * Charging screen. While the charger is connected it stands in for the switched-off screen
 * ([MonitorService] opens it whenever the screen turns off):
 *  - always on, at the charging brightness from the settings (default: the dimmest the system allows);
 *  - brighter for a few seconds every time the level crosses the boost step (default 10, 20 ... 90);
 *  - at 100%: full brightness and a 60 s animation drawn by [ChargeView] (the tube swells, bursts, then the
 *    screen is black); at the 60 s mark it closes itself (and stays closed until the cable is replugged);
 *  - opened while the battery is already 100%: closes at once;
 *  - closes immediately when the charger is unplugged;
 *  - double tap (or the power button, handled by the service) closes it and shows the home screen.
 */
class ChargeActivity : FullScreenActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLevel = -1
    private var closing = false
    private var seenPlugged = false
    private lateinit var gestures: GestureDetector

    private val dimRunnable = Runnable { setBrightness(bright(cfg.bCharge)) }
    private val closeRunnable = Runnable { finishAndRemoveTask() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onBattery(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChargeState.attach(this)
        setBrightness(bright(cfg.bCharge))

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                goHomeAndClose()
                return true
            }
        })
        chargeView.setOnTouchListener { _, event ->
            if (cfg.doubleTap) gestures.onTouchEvent(event)
            true
        }

        val sticky = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) onBattery(sticky)
    }

    private fun onBattery(i: Intent) {
        if (!BatteryInfo.plugged(i)) {
            // Ignore a stale "unplugged" sticky value right after the plug-in event opened us.
            if (seenPlugged) finishAndRemoveTask()
            return
        }
        seenPlugged = true
        val level = BatteryInfo.level(i)
        if (level < 0) return

        val prev = lastLevel
        lastLevel = level

        if (level >= 100 && prev < 0) {
            // Already full when we were opened: nothing to show.
            ChargeState.fullReached = true
            finishAndRemoveTask()
            return
        }

        chargeView.updateData(level, BatteryInfo.temp(i))

        if (level >= 100) {
            ChargeState.fullReached = true
            if (!closing) {
                closing = true
                handler.removeCallbacks(dimRunnable)
                setBrightness(bright(cfg.bAnim))
                if (cfg.animOn) {
                    chargeView.startExplosion()
                    handler.postDelayed(closeRunnable, cfg.animSec * 1000L)
                } else {
                    // Animation switched off: the still full tube stays for a while, then the screen closes.
                    handler.postDelayed(closeRunnable, cfg.boostSec * 1000L)
                }
            }
        } else if (prev >= 0 && level / cfg.boostEvery > prev / cfg.boostEvery) {
            // Reached 10, 20 ... 90 (the step is a setting): brighter for a while, then back to the charging brightness.
            setBrightness(bright(cfg.bBoost))
            handler.removeCallbacks(dimRunnable)
            handler.postDelayed(dimRunnable, cfg.boostSec * 1000L)
        }
    }

    private fun goHomeAndClose() {
        ChargeState.showing = false
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

    /** Home / recents gesture: the user left the charging screen on purpose. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        ChargeState.showing = false
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: IllegalArgumentException) {
        }
        ChargeState.detach(this)
        super.onDestroy()
    }
}
