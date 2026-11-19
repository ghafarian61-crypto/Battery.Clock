package com.example.batterypercent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.widget.RemoteViews

class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context, BatteryInfo.sticky(context), force = true)
        try {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        } catch (e: Exception) {
        }
    }

    /** The widget was resized: draw the digits again for the new size. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateAll(context, BatteryInfo.sticky(context), force = true)
    }

    companion object {
        private val SLASH_COLOR = Color.rgb(0, 200, 255)

        // Last values pushed to the widget: unchanged battery broadcasts are skipped.
        @Volatile private var lastLevel = Int.MIN_VALUE
        @Volatile private var lastTemp = Int.MIN_VALUE

        /**
         * Draws "level/temp" with the pencil digits, centred in a [wPx] x [hPx] bitmap. The size slider scales the
         * largest size that fits; the thickness slider sets the pen width; a soft dark halo keeps the digits
         * readable on coloured wallpapers.
         */
        private fun render(cfg: AppConfig, wPx: Int, hPx: Int, level: Int, tempTenths: Int): Bitmap {
            val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val levelText = if (level >= 0) level.toString() else "--"
            val temp = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else Float.NaN
            val tempText = if (!cfg.showTemp || temp.isNaN()) null else cfg.tempText(temp)
            val slash = "/"

            // Total width of the line for digits of height h (a little margin on every side).
            fun lineWidth(t: ThinText): Float {
                var wd = t.width(levelText)
                if (tempText != null) wd += t.gap + t.width(slash) + t.gap + t.width(tempText)
                return wd + t.stroke
            }

            fun strokeFor(h: Float): Float = 1f + cfg.widgetStrokeV / 100f * (0.25f * h - 1f).coerceAtLeast(0f)

            val maxW = wPx * 0.94f
            var h = minOf(hPx * 0.62f, wPx * 0.5f)
            var thin = ThinText(h, strokeFor(h))
            var tries = 0
            while (lineWidth(thin) > maxW && tries < 40) {
                h *= 0.94f
                thin = ThinText(h, strokeFor(h))
                tries++
            }
            // Size slider: 100 = the biggest that fits, 0 = a third of it.
            val k = 0.33f + 0.67f * cfg.widgetTextV / 100f
            h *= k
            thin = ThinText(h, strokeFor(h))

            val total = lineWidth(thin)
            var x = (wPx - total) / 2f
            val bottom = (hPx + thin.h) / 2f
            val halo = 1f + thin.stroke * 0.5f
            val haloAlpha = (255 * cfg.widgetOutlineV / 100f).toInt()

            thin.draw(canvas, levelText, x, bottom, cfg.levelColor(level), halo, haloAlpha)
            if (tempText != null) {
                x += thin.width(levelText) + thin.gap
                thin.draw(canvas, slash, x, bottom, SLASH_COLOR, halo, haloAlpha)
                x += thin.width(slash) + thin.gap
                thin.draw(canvas, tempText, x, bottom, cfg.tempColor(temp), halo, haloAlpha)
            }
            return bmp
        }

        /** Widget size in physical pixels for the current orientation. */
        private fun sizePx(context: Context, manager: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = manager.getAppWidgetOptions(id)
            val land = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            var wDp = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            var hDp = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (wDp <= 0) wDp = 70
            if (hDp <= 0) hDp = 70
            val d = context.resources.displayMetrics.density
            return Pair(Math.round(wDp * d).coerceAtLeast(16), Math.round(hDp * d).coerceAtLeast(16))
        }

        fun updateAll(context: Context, battery: Intent?, force: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val level = BatteryInfo.level(battery)
            val tempTenths =
                battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (!force && level == lastLevel && tempTenths == lastTemp) return
            lastLevel = level
            lastTemp = tempTenths

            // Tapping the widget shows the current level + temperature for a few seconds, charger or not.
            val tap = PendingIntent.getActivity(
                context,
                0,
                Intent(context, PeekActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cfg = AppConfig.load(context)
            for (id in ids) {
                val (wPx, hPx) = sizePx(context, manager, id)
                val views = RemoteViews(context.packageName, R.layout.widget_battery)
                views.setImageViewBitmap(R.id.widget_image, render(cfg, wPx, hPx, level, tempTenths))
                views.setOnClickPendingIntent(R.id.widget_root, tap)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
