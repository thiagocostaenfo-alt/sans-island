package com.thiagocosta.sansisland

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import kotlin.math.roundToInt

/** setup screen: permissions, position around the camera, which events show up */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var accessibilityStatus: TextView
    private lateinit var notificationStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(56), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) }
        setContentView(scroll)

        root.addView(text(getString(R.string.app_name), 30f, Color.WHITE, bold = true))
        root.addView(text(getString(R.string.tagline), 14f, GRAY).apply { setPadding(0, dp(4), 0, dp(20)) })

        // permissions
        section(getString(R.string.section_permissions))
        accessibilityStatus = permissionCard(
            getString(R.string.perm_accessibility), getString(R.string.perm_accessibility_desc)
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        notificationStatus = permissionCard(
            getString(R.string.perm_notifications), getString(R.string.perm_notifications_desc)
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        card {
            addView(text(getString(R.string.restricted_title), 15f, Color.WHITE, bold = true))
            addView(text(getString(R.string.restricted_desc), 13f, GRAY).apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(button(getString(R.string.open_app_info)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        root.addView(button(getString(R.string.test_island)) { postTest() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
        })

        // look
        section(getString(R.string.section_style))
        card {
            val options = listOf(
                "siri" to R.string.style_siri,
                "accent" to R.string.style_accent,
                "classic" to R.string.style_classic,
            )
            val group = RadioGroup(this@MainActivity)
            options.forEachIndexed { i, (value, label) ->
                group.addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = getString(label)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    buttonTintList = ColorStateList.valueOf(Accent.DEFAULT)
                    setPadding(dp(8), dp(10), 0, dp(10))
                    isChecked = Prefs.islandStyle == value
                    setOnCheckedChangeListener { _, checked -> if (checked) { Prefs.islandStyle = value; postTest() } }
                })
            }
            addView(group)
        }

        // position
        section(getString(R.string.section_position))
        card {
            addView(text(getString(R.string.position_hint), 13f, GRAY).apply { setPadding(0, 0, 0, dp(8)) })
            slider(this, getString(R.string.offset_horizontal), -60, 60, Prefs.offsetXdp, "dp") { Prefs.offsetXdp = it }
            slider(this, getString(R.string.offset_vertical), -30, 30, Prefs.offsetYdp, "dp") { Prefs.offsetYdp = it }
            slider(this, getString(R.string.size), -8, 12, Prefs.sizeDp, "dp") { Prefs.sizeDp = it }
        }

        // events
        section(getString(R.string.section_events))
        card {
            listOf(
                Kind.CALL to R.string.kind_call,
                Kind.MEDIA to R.string.kind_media,
                Kind.TIMER to R.string.kind_timer,
                Kind.NOTIFICATION to R.string.kind_notification,
                Kind.LIVE to R.string.kind_live,
                Kind.RECORDING to R.string.kind_recording,
                Kind.CHARGING to R.string.kind_charging,
                Kind.AUDIO_DEVICE to R.string.kind_audio,
                Kind.RINGER to R.string.kind_ringer,
            ).forEach { (kind, label) ->
                switch(this, getString(label), Prefs.isEnabled(kind)) { Prefs.setEnabled(kind, it); IslandHub.refresh() }
            }
        }

        // behavior
        section(getString(R.string.section_behavior))
        card {
            slider(this, getString(R.string.notification_duration), 2, 10, Prefs.notificationSeconds, "s") { Prefs.notificationSeconds = it }
            switch(this, getString(R.string.skip_foreground), Prefs.skipForegroundApp) { Prefs.skipForegroundApp = it }
            switch(this, getString(R.string.hide_landscape), Prefs.hideInLandscape) { Prefs.hideInLandscape = it }
            switch(this, getString(R.string.haptics), Prefs.haptics) { Prefs.haptics = it }
        }

        section(getString(R.string.section_gestures))
        card { addView(text(getString(R.string.gestures_help), 13f, GRAY)) }
    }

    override fun onResume() {
        super.onResume()
        setStatus(accessibilityStatus, isAccessibilityOn())
        setStatus(notificationStatus, NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName))
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, IslandService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun postTest() {
        if (!isAccessibilityOn()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        IslandHub.post(IslandItem(
            key = "test", kind = Kind.TEST, pkg = packageName,
            title = getString(R.string.test_title), text = getString(R.string.test_text),
            iconRes = R.drawable.ic_bell, accent = Accent.DEFAULT, durationMs = 4000,
            actions = listOf(IslandAction(getString(R.string.ok)) { }),
        ))
    }

    // ------------------------------------------------------------ ui helpers

    private fun setStatus(view: TextView, on: Boolean) {
        view.text = getString(if (on) R.string.status_on else R.string.status_off)
        view.setTextColor(if (on) Accent.GREEN else Accent.ORANGE)
    }

    private fun section(title: String) {
        root.addView(text(title.uppercase(), 12f, GRAY, bold = true).apply { setPadding(dp(4), dp(22), 0, dp(8)) })
    }

    private fun card(content: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(CARD) }
        }
        c.content()
        root.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun permissionCard(title: String, desc: String, onClick: () -> Unit): TextView {
        lateinit var status: TextView
        card {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(title, 16f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            status = text("", 13f, GRAY, bold = true)
            row.addView(status)
            addView(row)
            addView(text(desc, 13f, GRAY).apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(button(getString(R.string.open_settings), onClick))
        }
        return status
    }

    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) {
        val title = text("", 14f, Color.WHITE)
        fun label(v: Int) { title.text = "$label: $v $unit" }
        label(value)
        parent.addView(title.apply { setPadding(0, dp(8), 0, 0) })
        parent.addView(SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(Accent.DEFAULT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    label(progress + min)
                    onChange(progress + min)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        })
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(parent: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        parent.addView(Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Accent.GREEN, 0xFF3A3A3C.toInt())
            )
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Accent.DEFAULT) }
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val GRAY = 0xFF8E8E93.toInt()
        private const val CARD = 0xFF1C1C1E.toInt()
    }
}
