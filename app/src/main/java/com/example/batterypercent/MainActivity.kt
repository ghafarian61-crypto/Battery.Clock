package com.example.batterypercent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Launcher: starts the service and asks for every missing permission in ONE run,
 * one after the other, without the app closing in between:
 * notifications -> battery optimisation -> display over other apps.
 * When nothing is missing any more it shows the settings with two tabs:
 * tab 1 = battery / charging screen ([SettingsScreen]), tab 2 = clock ([ClockSettings]).
 */
class MainActivity : Activity() {

    private companion object {
        const val RC_NOTIF = 1
        const val RC_BATTERY = 2
        const val RC_OVERLAY = 3
        const val STEPS = 3
    }

    private var step = 0
    private var settingsShown = false
    private var tab = 0
    private lateinit var tabBattery: Button
    private lateinit var tabClock: Button
    private lateinit var content: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startForegroundService(Intent(this, MonitorService::class.java))
        } catch (e: Exception) {
        }

        if (needsNotifications() || needsBatteryExemption() || needsOverlay()) {
            Toast.makeText(this, R.string.msg_permissions_intro, Toast.LENGTH_LONG).show()
        }
        next()
    }

    private fun needsNotifications() = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun needsBatteryExemption() =
        !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun needsOverlay() = !Settings.canDrawOverlays(this)

    /** Runs the next permission request that is still missing; every request returns here. */
    private fun next() {
        val pkg = Uri.parse("package:$packageName")
        while (step < STEPS) {
            when (step++) {
                0 -> if (needsNotifications()) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIF)
                    return
                }
                1 -> if (needsBatteryExemption()) {
                    startActivityForResult(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), RC_BATTERY
                    )
                    return
                }
                2 -> if (needsOverlay()) {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), RC_OVERLAY
                    )
                    return
                }
            }
        }
        if (settingsShown) return
        settingsShown = true
        if (needsOverlay()) Toast.makeText(this, R.string.msg_overlay_missing, Toast.LENGTH_LONG).show()
        showSettings()
    }

    private fun showSettings() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), 0)
        }
        tabBattery = Button(this).apply { setText(R.string.tab_battery); setOnClickListener { selectTab(0) } }
        tabClock = Button(this).apply { setText(R.string.tab_clock); setOnClickListener { selectTab(1) } }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        bar.addView(tabBattery, lp())
        bar.addView(tabClock, lp())
        content = FrameLayout(this)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        selectTab(tab)
    }

    /** Shows the page of tab [t] (0 = battery, 1 = clock) and marks the selected tab button. */
    private fun selectTab(t: Int) {
        tab = t
        tabBattery.alpha = if (t == 0) 1f else 0.45f
        tabClock.alpha = if (t == 1) 1f else 0.45f
        val page: View = if (t == 0) SettingsScreen(this) { showSettings() }.build()
        else ClockSettings(this) { showSettings() }.build()
        content.removeAllViews()
        content.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        next()
    }

    @Deprecated("Framework Activity result API is fine here (no AndroidX).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        next()
    }
}
