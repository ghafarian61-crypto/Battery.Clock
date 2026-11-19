package com.example.batterypercent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Second settings tab: the clock that stands in for the switched-off screen (type, colours, brightness,
 * hourly flash and the day / night schedule). Same code as the former standalone Clock app, now hosted by
 * [MainActivity] next to [SettingsScreen].
 */
class ClockSettings(private val a: Activity, private val onReset: () -> Unit) {

    private fun dp(v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    private fun fmt(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun text(res: Int, size: Float, color: Int): TextView {
        val t = TextView(a)
        t.setText(res)
        t.textSize = size
        t.setTextColor(color)
        return t
    }

    private lateinit var preview: ClockView

    private fun buildUi(): View {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(24))

        // ---- live preview (shows the current time, real battery values and every change at once)
        preview = ClockView(a)
        preview.respectCutout = false
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))
        refreshPreview()

        // ---- type
        addChoice(
            root, R.string.style_title,
            intArrayOf(R.string.style_analog, R.string.style_digital),
            ClockStyle.mode(a)
        ) { ClockStyle.setMode(a, it); refreshPreview() }
        addChoice(
            root, R.string.hour_title,
            intArrayOf(R.string.hour_24, R.string.hour_12),
            if (ClockStyle.hour12(a)) 1 else 0
        ) { ClockStyle.setHour12(a, it == 1); refreshPreview() }

        // ---- clock digits / numbers
        addHeader(root, R.string.sec_clock)
        addColorChoice(root, R.string.clock_color, "clock_color")
        addSeek(root, R.string.clock_level, ClockStyle.LEVEL_MIN, 100, "clock_level", 100, "%")
        addSeek(root, R.string.clock_size, ClockStyle.SIZE_MIN, 100, "clock_size", 100, "%")
        addSeek(root, R.string.line_width, ClockStyle.LINE_MIN, ClockStyle.LINE_MAX, "line", 1, " px")

        // ---- analog hands
        addHeader(root, R.string.sec_hands)
        addColorChoice(root, R.string.hands_color, "hands_color")
        addSeek(root, R.string.hands_level, ClockStyle.LEVEL_MIN, 100, "hands_level", 100, "%")

        // ---- battery percentage and temperature (digital clock, in place of the colon dots)
        addHeader(root, R.string.sec_info)
        addSwitch(root, R.string.show_charge, "show_charge", true)
        addSwitch(root, R.string.show_temp, "show_temp", true)
        addSwitch(root, R.string.temp_decimal, "temp_decimal", true)
        addColorChoice(root, R.string.info_color, "info_color")
        addSeek(root, R.string.info_level, ClockStyle.LEVEL_MIN, 100, "info_level", 100, "%")
        addSeek(root, R.string.info_size, ClockStyle.INFO_SIZE_MIN, ClockStyle.INFO_SIZE_MAX, "info_size", 60, "%")

        // ---- colour of the percentage by charge level
        addHeader(root, R.string.sec_charge_colors)
        addSwitch(root, R.string.by_level, "charge_by_level", true)
        addSeek(root, R.string.limit_1, 0, 99, "charge_t1", 30, "%")
        addSeek(root, R.string.limit_2, 0, 99, "charge_t2", 60, "%")
        addPalette(root, R.string.band_1, "charge_c1", ClockStyle.P_RED)
        addPalette(root, R.string.band_2, "charge_c2", ClockStyle.P_YELLOW)
        addPalette(root, R.string.band_3_charge, "charge_c3", ClockStyle.P_GREEN)
        addPalette(root, R.string.band_full, "charge_full", ClockStyle.P_BLUE)

        // ---- colour of the temperature by degrees
        addHeader(root, R.string.sec_temp_colors)
        addSwitch(root, R.string.by_level, "temp_by_level", true)
        addSeek(root, R.string.limit_1, 0, 99, "temp_t1", 30, "°")
        addSeek(root, R.string.limit_2, 0, 99, "temp_t2", 35, "°")
        addPalette(root, R.string.band_1, "temp_c1", ClockStyle.P_BLUE)
        addPalette(root, R.string.band_2, "temp_c2", ClockStyle.P_GREEN)
        addPalette(root, R.string.band_3_temp, "temp_c3", ClockStyle.P_RED)

        // ---- hourly flash
        addHeader(root, R.string.sec_flash)
        addSwitch(root, R.string.flash_on, "flash_on", true)
        addSeek(root, R.string.flash_sec, 1, ClockStyle.FLASH_MAX_SEC, "flash_sec", 10, " s")

        // ---- hourly shift (burn-in protection)
        addHeader(root, R.string.sec_shift)
        addSwitch(root, R.string.shift_on, "shift_on", true)
        addSeek(root, R.string.shift_range, ClockStyle.SHIFT_MIN, 100, "shift_range", 100, "%")
        addSeek(root, R.string.top_pad, 0, ClockStyle.TOP_PAD_MAX, "top_pad", 0, " dp")
        val shiftNote = text(R.string.shift_note, 13f, Color.GRAY)
        shiftNote.setPadding(0, dp(8), 0, 0)
        root.addView(shiftNote)

        // ---- day / night schedule
        addHeader(root, R.string.sec_schedule)
        addRow(root, Schedule.DAY, R.string.day)
        addRow(root, Schedule.NIGHT, R.string.night)

        val reset = Button(a)
        reset.setText(R.string.reset_defaults)
        reset.setOnClickListener {
            ClockStyle.reset(a)
            onReset()
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(32)
        root.addView(reset, lp)

        val scroll = ScrollView(a)
        scroll.setBackgroundColor(Color.BLACK)
        scroll.addView(root)
        return scroll
    }

    /** Re-reads every look setting into the preview (also the real time and battery). */
    private fun refreshPreview() {
        val cfg = ClockStyle.load(a)
        preview.configure(cfg)
        val c = java.util.Calendar.getInstance()
        preview.setTime(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        try {
            val b = a.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                preview.setBattery(
                    if (level >= 0 && scale > 0) level * 100 / scale else -1,
                    b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 250)
                )
            }
        } catch (e: Exception) {
        }
        val lp = preview.layoutParams
        val wanted = dp(if (cfg.digital) 240 else 420)
        if (lp != null && lp.height != wanted) {
            lp.height = wanted
            preview.layoutParams = lp
        }
    }

    private fun addHeader(root: LinearLayout, res: Int) {
        val t = text(res, 20f, Color.rgb(255, 170, 0))
        t.setPadding(0, dp(36), 0, dp(0))
        root.addView(t)
    }

    /** Auto / red / green / blue, stored under [key]. */
    private fun addColorChoice(root: LinearLayout, titleRes: Int, key: String) {
        val title = text(titleRes, 16f, Color.WHITE)
        title.setPadding(0, dp(16), 0, dp(2))
        root.addView(title)

        val labels = intArrayOf(R.string.c_auto, R.string.c_red, R.string.c_green, R.string.c_blue)
        val tints = intArrayOf(Color.WHITE, Color.rgb(255, 90, 90), Color.rgb(90, 255, 90), Color.rgb(110, 150, 255))
        val group = RadioGroup(a)
        group.orientation = RadioGroup.HORIZONTAL
        val ids = IntArray(labels.size)
        for (k in labels.indices) {
            val rb = RadioButton(a)
            rb.setText(labels[k])
            rb.setTextColor(tints[k])
            rb.textSize = 15f
            rb.id = View.generateViewId()
            ids[k] = rb.id
            group.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.check(ids[ClockStyle.getInt(a, key, ClockStyle.C_AUTO).coerceIn(0, ids.size - 1)])
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = ids.indexOf(checkedId)
            if (index >= 0) {
                ClockStyle.setInt(a, key, index)
                refreshPreview()
            }
        }
        root.addView(group)
    }

    /** Red / yellow / green / blue (value = ClockStyle.P_*), stored under [key]. */
    private fun addPalette(root: LinearLayout, titleRes: Int, key: String, default: Int) {
        val title = text(titleRes, 16f, Color.WHITE)
        title.setPadding(0, dp(16), 0, dp(2))
        root.addView(title)

        val labels = intArrayOf(R.string.c_red, R.string.c_yellow, R.string.c_green, R.string.c_blue)
        val tints = intArrayOf(Color.rgb(255, 90, 90), Color.rgb(255, 230, 60), Color.rgb(90, 255, 90), Color.rgb(110, 150, 255))
        val group = RadioGroup(a)
        group.orientation = RadioGroup.HORIZONTAL
        val ids = IntArray(labels.size)
        for (k in labels.indices) {
            val rb = RadioButton(a)
            rb.setText(labels[k])
            rb.setTextColor(tints[k])
            rb.textSize = 15f
            rb.id = View.generateViewId()
            ids[k] = rb.id
            group.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.check(ids[ClockStyle.getInt(a, key, default).coerceIn(0, ids.size - 1)])
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = ids.indexOf(checkedId)
            if (index >= 0) {
                ClockStyle.setInt(a, key, index)
                refreshPreview()
            }
        }
        root.addView(group)
    }

    /** A bar from [min] to [max] with its value; saved under [key] while the finger moves. */
    private fun addSeek(root: LinearLayout, titleRes: Int, min: Int, max: Int, key: String, default: Int, suffix: String) {
        val title = text(titleRes, 16f, Color.WHITE)
        title.setPadding(0, dp(16), 0, dp(2))
        root.addView(title)

        val current = ClockStyle.getInt(a, key, default).coerceIn(min, max)
        val value = TextView(a)
        value.textSize = 16f
        value.setTextColor(Color.WHITE)
        value.gravity = Gravity.END
        value.minWidth = dp(64)
        value.text = "$current$suffix"

        val seek = SeekBar(a)
        seek.max = max - min
        seek.progress = current - min
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = "${progress + min}$suffix"
                if (fromUser) {
                    ClockStyle.setInt(a, key, progress + min)
                    refreshPreview()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        root.addView(row)
    }

    private fun addSwitch(root: LinearLayout, titleRes: Int, key: String, default: Boolean) {
        val cb = CheckBox(a)
        cb.setText(titleRes)
        cb.setTextColor(Color.WHITE)
        cb.textSize = 16f
        cb.isChecked = ClockStyle.getBool(a, key, default)
        cb.setOnCheckedChangeListener { _, checked ->
            ClockStyle.setBool(a, key, checked)
            refreshPreview()
        }
        cb.setPadding(0, dp(8), 0, dp(0))
        root.addView(cb)
    }

    /** A title and a row of radio buttons; [onPick] gets the index of the chosen one (saved at once). */
    private fun addChoice(
        root: LinearLayout,
        titleRes: Int,
        labelRes: IntArray,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val title = text(titleRes, 18f, Color.WHITE)
        title.setPadding(0, dp(24), 0, dp(4))
        root.addView(title)

        val group = RadioGroup(a)
        group.orientation = RadioGroup.HORIZONTAL
        val ids = IntArray(labelRes.size)
        for (k in labelRes.indices) {
            val rb = RadioButton(a)
            rb.setText(labelRes[k])
            rb.setTextColor(Color.WHITE)
            rb.textSize = 16f
            rb.id = View.generateViewId()
            ids[k] = rb.id
            group.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.check(ids[selected.coerceIn(0, ids.size - 1)])
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = ids.indexOf(checkedId)
            if (index >= 0) onPick(index)
        }
        root.addView(group)
    }

    /** One range: [title], [start] to [end] (tap to type), and the brightness bar with its percentage. */
    private fun addRow(root: LinearLayout, i: Int, titleRes: Int) {
        val title = text(titleRes, 18f, Color.WHITE)
        title.setPadding(0, dp(28), 0, dp(8))
        root.addView(title)

        val times = LinearLayout(a)
        times.orientation = LinearLayout.HORIZONTAL
        times.gravity = Gravity.CENTER_VERTICAL

        val startBtn = Button(a)
        startBtn.text = fmt(Schedule.start(a, i))
        startBtn.setOnClickListener {
            askTime(Schedule.start(a, i)) { m ->
                Schedule.setStart(a, i, m)
                startBtn.text = fmt(m)
            }
        }

        val endBtn = Button(a)
        endBtn.text = fmt(Schedule.end(a, i))
        endBtn.setOnClickListener {
            askTime(Schedule.end(a, i)) { m ->
                Schedule.setEnd(a, i, m)
                endBtn.text = fmt(m)
            }
        }

        val toLabel = text(R.string.to_word, 16f, Color.LTGRAY)
        toLabel.gravity = Gravity.CENTER
        toLabel.setPadding(dp(12), 0, dp(12), 0)

        times.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(toLabel)
        times.addView(endBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(times)

        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(0, dp(8), 0, 0)

        val current = Schedule.percent(a, i)
        val percent = TextView(a)
        percent.textSize = 18f
        percent.setTextColor(Color.WHITE)
        percent.gravity = Gravity.END
        percent.minWidth = dp(64)
        percent.text = "$current%"

        val seek = SeekBar(a)
        seek.max = 100
        seek.progress = current
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                percent.text = "$progress%"
                if (fromUser) Schedule.setPercent(a, i, progress)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        bar.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(percent)
        root.addView(bar)
    }

    /** Persian / Arabic digits -> 0-9 (a Persian keyboard may type them). */
    private fun latin(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun field(value: String): EditText {
        val e = EditText(a)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.filters = arrayOf(InputFilter.LengthFilter(2))
        e.gravity = Gravity.CENTER
        e.textSize = 26f
        e.setText(value)
        e.setSelectAllOnFocus(true)
        return e
    }

    /** Asks for a time by typing: hour field and minute field. Keeps the dialog open while the input is wrong. */
    private fun askTime(currentMinutes: Int, onPicked: (Int) -> Unit) {
        val hour = field((currentMinutes / 60).toString())
        val minute = field(String.format(Locale.US, "%02d", currentMinutes % 60))

        val colon = TextView(a)
        colon.text = ":"
        colon.textSize = 26f
        colon.setPadding(dp(8), 0, dp(8), 0)

        val box = LinearLayout(a)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(24), dp(16), dp(24), 0)
        box.addView(hour, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(colon)
        box.addView(minute, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(a)
            .setTitle(R.string.time_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        hour.requestFocus()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val h = latin(hour.text.toString()).toIntOrNull()
            val m = if (minute.text.isBlank()) 0 else latin(minute.text.toString()).toIntOrNull()
            if (h == null || h !in 0..23) {
                hour.error = a.getString(R.string.err_hour)
                return@setOnClickListener
            }
            if (m == null || m !in 0..59) {
                minute.error = a.getString(R.string.err_minute)
                return@setOnClickListener
            }
            onPicked(h * 60 + m)
            dialog.dismiss()
        }
    }

    fun build(): View = buildUi()
}
