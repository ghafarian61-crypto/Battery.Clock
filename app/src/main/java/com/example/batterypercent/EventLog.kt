package com.example.batterypercent

import android.app.KeyguardManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** True while the lock screen is up (the phone is locked). False if unknown. */
fun keyguardLocked(ctx: Context): Boolean = try {
    ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
} catch (e: Exception) {
    false
}

/**
 * Tiny event log (last [MAX] lines) shared by the service and the clock screen, shown in the settings screen.
 * It exists only to see what the phone does around screen-off / lock, nothing else is recorded.
 */
object EventLog {
    private const val PREFS = "clock_log"
    private const val KEY = "lines"
    private const val MAX = 60

    @Synchronized
    fun add(ctx: Context, message: String) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY, "") ?: ""
            val lines = ArrayList<String>()
            if (old.isNotEmpty()) lines.addAll(old.split("\n"))
            lines.add(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + message)
            while (lines.size > MAX) lines.removeAt(0)
            prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (e: Exception) {
        }
    }

    /** Newest line first. */
    @Synchronized
    fun read(ctx: Context): String {
        val old = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (old.isEmpty()) return ""
        return old.split("\n").reversed().joinToString("\n")
    }

    @Synchronized
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
