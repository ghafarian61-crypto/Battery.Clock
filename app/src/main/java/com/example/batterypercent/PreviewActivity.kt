package com.example.batterypercent

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Settings preview (no charger needed): shows the charging screen at a chosen level, or plays the 100 %
 * animation (optionally from the last seconds before the burst). A touch closes it.
 */
class PreviewActivity : FullScreenActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LEVEL
        if (mode == MODE_ANIM) {
            setBrightness(bright(cfg.bAnim))
            chargeView.updateData(100, SAMPLE_TEMP)
            val skip = intent.getFloatExtra(EXTRA_SKIP, 0f).coerceIn(0f, cfg.animSec - 1f)
            chargeView.startExplosion(skip)
            handler.postDelayed({ finishAndRemoveTask() }, ((cfg.animSec - skip) * 1000f).toLong() + 500L)
        } else {
            setBrightness(bright(cfg.bCharge))
            chargeView.updateData(intent.getIntExtra(EXTRA_LEVEL, cfg.previewLevel).coerceIn(0, 100), SAMPLE_TEMP)
            handler.postDelayed({ finishAndRemoveTask() }, 10_000L)
        }
        chargeView.setOnTouchListener { _, _ ->
            finishAndRemoveTask()
            true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_SKIP = "skip"
        const val MODE_LEVEL = "level"
        const val MODE_ANIM = "anim"
        private const val SAMPLE_TEMP = 31.4f

        fun level(ctx: android.content.Context, level: Int) = Intent(ctx, PreviewActivity::class.java)
            .putExtra(EXTRA_MODE, MODE_LEVEL).putExtra(EXTRA_LEVEL, level)

        fun anim(ctx: android.content.Context, skipSec: Float) = Intent(ctx, PreviewActivity::class.java)
            .putExtra(EXTRA_MODE, MODE_ANIM).putExtra(EXTRA_SKIP, skipSec)
    }
}
