package com.example.batterypercent

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Hides the status and navigation bars of a black screen.
 *
 * Setting the flags once in onCreate is not enough: while a screen is opened over the lock screen (the clock and the
 * charging screen are) the system may show the navigation buttons anyway, as white icons on the black picture. So
 * this is called again whenever the window gets focus. Should the system still show the bar, it is black with dark
 * (light-bar style) icons, which cannot be seen on black.
 */
@Suppress("DEPRECATION")
fun Activity.hideSystemBars() {
    val w = window
    w.statusBarColor = Color.BLACK
    w.navigationBarColor = Color.BLACK
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        w.isNavigationBarContrastEnforced = false
        w.isStatusBarContrastEnforced = false
    }
    var flags = View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    w.decorView.systemUiVisibility = flags
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        w.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    }
}
