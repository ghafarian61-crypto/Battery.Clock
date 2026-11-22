package com.example.batterypercent

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** Common base: immersive black full-screen window that shows a [ChargeView] and controls window brightness. */
abstract class FullScreenActivity : Activity() {

    protected lateinit var chargeView: ChargeView

    /** User settings, read when the screen opens. */
    protected lateinit var cfg: AppConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Let the picture extend under the camera hole so the blue cap can sit right below it.
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        hideSystemBars()
        cfg = AppConfig.load(this)
        chargeView = ChargeView(this, cfg)
        setContentView(chargeView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    protected fun setBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    companion object {
        /** Window brightness for a 0..100 setting (0 is treated as the dimmest value the system accepts). */
        fun bright(v: Int): Float = AppConfig.brightness(v)
    }
}
