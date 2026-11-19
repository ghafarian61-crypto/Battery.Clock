package com.example.batterypercent

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * The settings page (built in code, no XML): sliders, switches, number boxes and colour pickers.
 * Every change is saved at once; the widget is redrawn when a slider is released. The charging screen reads
 * the settings the next time it opens (use the preview buttons to see a change immediately).
 */
class SettingsScreen(private val a: Activity, private val onReset: () -> Unit) {

    private val prefs = AppConfig.prefs(a)
    private val density = a.resources.displayMetrics.density
    private val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(16), dp(24), dp(16), dp(48))
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun refreshWidget() {
        try {
            BatteryWidgetProvider.updateAll(a, BatteryInfo.sticky(a), force = true)
        } catch (e: Exception) {
        }
    }

    private fun put(key: String, v: Int) {
        prefs.edit().putInt(key, v).apply()
    }

    private fun cur(key: String): Int = prefs.getInt(key, AppConfig.default(key))

    // ---------------------------------------------------------------- building blocks

    private fun text(s: String, sp: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(a).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
        }

    private fun add(v: View, top: Int = 10) {
        root.addView(
            v,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(top) }
        )
    }

    private fun section(title: String) {
        add(text(title, 18f, Color.rgb(0, 200, 255), true), 28)
    }

    private fun note(s: String) {
        add(text(s, 12f, Color.rgb(170, 170, 170)), 4)
    }

    /** A slider from [min] to [max]; [fmt] turns the number into the text shown next to the label. */
    private fun slider(label: String, key: String, min: Int, max: Int, fmt: (Int) -> String = { it.toString() }) {
        val lo = min
        val hi = max
        val value = cur(key).coerceIn(lo, hi)
        val tv = text("$label:  ${fmt(value)}")
        val sb = SeekBar(a).apply {
            setMax(hi - lo)
            progress = value - lo
            layoutDirection = View.LAYOUT_DIRECTION_LTR      // 0 on the left, like a brightness slider
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = p + lo
                tv.text = "$label:  ${fmt(v)}"
                if (fromUser) put(key, v)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                refreshWidget()
            }
        })
        add(tv, 14)
        add(sb, 2)
    }

    private fun switch(label: String, key: String) {
        val sw = Switch(a).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = cur(key) != 0
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                put(key, if (on) 1 else 0)
                refreshWidget()
            }
        }
        add(sw, 14)
    }

    private fun numberBox(label: String, key: String, min: Int, max: Int): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tv = text(label)
        val et = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cur(key).toString())
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minEms = 3
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                put(key, v.coerceIn(min, max))
            }
        })
        et.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                val v = et.text.toString().toIntOrNull()?.coerceIn(min, max) ?: AppConfig.default(key)
                et.setText(v.toString())
                put(key, v)
                refreshWidget()
            }
        }
        box.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(et, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun colorPicker(label: String, key: String): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = arrayOf("قرمز", "سبز", "آبی", "زرد")
        val sp = Spinner(a)
        sp.adapter = object : ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, names) {
            private fun tint(v: View, pos: Int): View {
                (v as? TextView)?.setTextColor(AppConfig.COLORS[pos])
                return v
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                tint(super.getView(position, convertView, parent), position)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                tint(super.getDropDownView(position, convertView, parent), position)
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sp.setSelection(cur(key).coerceIn(0, names.size - 1))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                put(key, pos)
                refreshWidget()
            }

            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        box.addView(text(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(sp, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun button(label: String, onClick: () -> Unit) {
        add(Button(a).apply {
            text = label
            setOnClickListener { onClick() }
        }, 12)
    }

    // ---------------------------------------------------------------- the page

    fun build(): View {
        val pct = { v: Int -> "$v٪" }

        add(text("تنظیمات", 24f, Color.WHITE, true), 0)
        note("هر تغییر همان لحظه ذخیره می‌شود. صفحهٔ شارژ دفعهٔ بعد که باز شود تغییرها را نشان می‌دهد؛ برای دیدن فوری از دکمه‌های پیش‌نمایش پایین صفحه استفاده کنید. ویجت با رها کردن نوار خودش را دوباره می‌کشد.")

        section("اعداد (صفحهٔ شارژ)")
        slider("اندازهٔ عدد", AppConfig.SCREEN_TEXT, 0, 100)
        slider("ضخامت قلم (۰ = ۱ پیکسل)", AppConfig.SCREEN_STROKE, 0, 100)

        section("اعداد (ویجت)")
        slider("اندازهٔ عدد (۱۰۰ = بزرگ‌ترین اندازهٔ جاگیر)", AppConfig.WIDGET_TEXT, 0, 100)
        slider("ضخامت قلم (۰ = ۱ پیکسل)", AppConfig.WIDGET_STROKE, 0, 100)
        slider("حاشیهٔ مشکی پشت اعداد", AppConfig.WIDGET_OUTLINE, 0, 100)

        section("ظرف")
        slider("ضخامت لبهٔ شیشه (۰ = ۱ پیکسل)", AppConfig.SHELL_STROKE, 0, 100)
        slider("ضخامت خط موج (۰ = ۱ پیکسل)", AppConfig.CREST_STROKE, 0, 100)
        slider("پهنای ظرف (dp)", AppConfig.TUBE_WIDTH, 30, 65)
        switch("موج مایع", AppConfig.WAVE_ON)
        slider("شدت موج", AppConfig.WAVE_AMP, 0, 100)

        section("رنگ و درصد مایع")
        note("رنگ‌های قرمز، سبز و آبی فقط زیرپیکسل خودشان را روشن می‌کنند؛ زرد ترکیب قرمز و سبز است (پیش‌فرض بخش دوم). هر بخش از «شروع» تا «پایان» درصد می‌ماند. بخش بالاترین، موقع ۱۰۰٪ کل شیشه را پر می‌کند.")
        for (n in 1..3) {
            val k = "seg$n"
            add(text("بخش $n", 16f, Color.WHITE, true), 20)
            switch("روشن", k + "_on")
            add(colorPicker("رنگ", k + "_color"), 6)
            add(numberBox("شروع (٪)", k + "_from", 1, 100), 6)
            add(numberBox("پایان (٪)", k + "_to", 1, 100), 6)
        }
        add(text("نوک باتری", 16f, Color.WHITE, true), 20)
        add(colorPicker("رنگ نوک و عدد", AppConfig.CAP_COLOR), 6)
        add(numberBox("از چند درصد (٪)", AppConfig.CAP_FROM, 1, 100), 6)

        section("روشنایی")
        slider("هنگام شارژ عادی", AppConfig.B_CHARGE, 0, 100, pct)
        slider("در مضرب‌های درصد (نور خودکار)", AppConfig.B_BOOST, 0, 100, pct)
        slider("انیمیشن ۱۰۰٪", AppConfig.B_ANIM, 0, 100, pct)
        slider("نمایش با لمس ویجت", AppConfig.B_PEEK, 0, 100, pct)
        note("گوشی پایین‌تر از حداقل روشنایی خودش نمی‌رود.")

        section("زمان و رفتار")
        slider("نور زیاد هر چند درصد", AppConfig.BOOST_EVERY, 1, 50) { "هر $it٪" }
        slider("مدت نور زیاد (ثانیه)", AppConfig.BOOST_SEC, 1, 60)
        slider("مدت نمایش با لمس ویجت (ثانیه)", AppConfig.PEEK_SEC, 1, 10)
        switch("جابه‌جایی ضدسوختگی (burn-in)", AppConfig.SHIFT_ON)
        slider("جابه‌جایی هر چند درصد", AppConfig.SHIFT_EVERY, 1, 50) { "هر $it٪" }
        switch("دابل‌تپ برای رفتن به صفحهٔ اصلی", AppConfig.DOUBLE_TAP)

        section("انیمیشن ۱۰۰٪")
        switch("پخش انیمیشن (خاموش = تصویر ثابت به‌اندازهٔ «مدت نور زیاد»)", AppConfig.ANIM_ON)
        slider("مدت کل انیمیشن (ثانیه)", AppConfig.ANIM_SEC, 15, 300) { "$it ثانیه" }
        slider("تعداد ذرات انفجار", AppConfig.PARTICLES, 0, 200)
        slider("شدت لرزش ظرف", AppConfig.SHAKE, 0, 100)
        slider("تعداد حباب‌های جوشان", AppConfig.BUBBLES, 0, 100)
        note("باد شدن تقریباً کل مدت را می‌گیرد؛ انفجار ۲.۶ ثانیه است و بقیه صفحه سیاه می‌ماند. در پایان برنامه بسته می‌شود.")

        section("دما")
        switch("نمایش دما", AppConfig.SHOW_TEMP)
        switch("فارنهایت به‌جای سانتی‌گراد", AppConfig.TEMP_F)
        note("حد رنگ دما همیشه به سانتی‌گراد است: تا حد اول آبی، تا حد دوم سبز، تا حد سوم نارنجی، بالاتر قرمز.")
        add(numberBox("حد اول (°C)", AppConfig.T1, 0, 100), 6)
        add(numberBox("حد دوم (°C)", AppConfig.T2, 0, 100), 6)
        add(numberBox("حد سوم (°C)", AppConfig.T3, 0, 100), 6)

        section("پیش‌نمایش")
        slider("درصد پیش‌نمایش", AppConfig.PREVIEW_LEVEL, 0, 100, pct)
        button("پیش‌نمایش صفحهٔ شارژ در این درصد") {
            a.startActivity(PreviewActivity.level(a, cur(AppConfig.PREVIEW_LEVEL)))
        }
        button("پیش‌نمایش انیمیشن ۱۰۰٪ از اول") {
            a.startActivity(PreviewActivity.anim(a, 0f))
        }
        button("پیش‌نمایش انیمیشن از ۱۵ ثانیهٔ آخر") {
            a.startActivity(PreviewActivity.anim(a, (AppConfig.load(a).animSec - 15f).coerceAtLeast(0f)))
        }
        note("با یک لمس بسته می‌شود.")

        button("بازگشت همهٔ تنظیمات به پیش‌فرض") {
            AppConfig.reset(a)
            refreshWidget()
            onReset()
        }

        return ScrollView(a).apply { addView(root) }
    }
}
