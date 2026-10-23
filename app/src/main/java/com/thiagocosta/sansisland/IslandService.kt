package com.thiagocosta.sansisland

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

/**
 * Hosts the island window. An accessibility overlay can be drawn over the status bar and
 * other apps; this service also reads which app is in front and whether the shade is open.
 * Battery, ringer mode and headphone events are watched here as well.
 */
class IslandService : AccessibilityService(), IslandView.Host {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var island: IslandView? = null
    private val params = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
        title = "SansIsland"
    }

    private var shadeOpen = false
    private var landscape = false

    private val hubListener = IslandHub.Listener { island?.render() }
    private val prefsListener: () -> Unit = { main.post { updateGeometry(); island?.render(animate = false) } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        instance = this
        wm = getSystemService(WindowManager::class.java)
        val view = IslandView(this, this)
        island = view
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            island = null
            return
        }
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateGeometry()
        IslandHub.addListener(hubListener)
        Prefs.addChangeListener(prefsListener)
        registerStatusWatchers()
        ScoreWatcher.start(this)
        updateSuppression()
        view.render(animate = false)
    }

    override fun onDestroy() {
        instance = null
        IslandHub.removeListener(hubListener)
        Prefs.removeChangeListener(prefsListener)
        unregisterStatusWatchers()
        ScoreWatcher.stop()
        island?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        island = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg != "com.android.systemui" && pkg != packageName) IslandHub.foregroundPackage = pkg
                checkShade()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> checkShade()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        main.post { updateGeometry(); updateSuppression() }
    }

    // ------------------------------------------------------------ window

    override fun applyWindow(width: Int, height: Int, x: Int, y: Int, touchable: Boolean) {
        val view = island ?: return
        val flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (params.width == width && params.height == height && params.x == x && params.y == y && params.flags == flags) return
        params.width = width
        params.height = height
        params.x = x
        params.y = y
        params.flags = flags
        try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
    }

    /** finds the front camera cutout so the pill sits right around it (Galaxy A56: centered punch hole) */
    private fun updateGeometry() {
        val view = island ?: return
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val cutout = metrics.windowInsets.displayCutout?.boundingRectTop
        val density = resources.displayMetrics.density
        if (cutout != null && !cutout.isEmpty) {
            view.setGeometry(bounds.width(), cutout.centerX(), cutout.centerY(), cutout.width(), cutout.height())
        } else {
            val size = (14 * density).toInt()
            view.setGeometry(bounds.width(), bounds.width() / 2, (20 * density).toInt(), size, size)
        }
    }

    // ------------------------------------------------------------ hiding

    /** the notification shade / quick settings is a big system window at the top: hide while it is open */
    private fun checkShade() {
        val screenH = wm.currentWindowMetrics.bounds.height()
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val open = !locked && try {
            windows.any { w ->
                if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
                val r = android.graphics.Rect()
                w.getBoundsInScreen(r)
                r.top <= 0 && r.height() > screenH * 0.45f
            }
        } catch (_: Exception) { false }
        if (open != shadeOpen) {
            shadeOpen = open
            updateSuppression()
        }
    }

    private fun updateSuppression() {
        island?.setSuppressed(shadeOpen || (landscape && Prefs.hideInLandscape))
    }

    // ------------------------------------------------------------ status events

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_POWER_CONNECTED) return
            // the level is not updated yet at the moment of plugging in
            main.postDelayed({
                val level = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                IslandHub.post(IslandItem(
                    key = "charging", kind = Kind.CHARGING, pkg = null,
                    title = getString(R.string.charging), iconRes = R.drawable.ic_bolt, accent = Accent.GREEN,
                    compactLabel = getString(R.string.charging), compactText = "$level%",
                    durationMs = 3000,
                ))
            }, 400)
        }
    }

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isInitialStickyBroadcast) return
            val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
            val (label, icon, color) = when (mode) {
                AudioManager.RINGER_MODE_SILENT -> Triple(getString(R.string.silent), R.drawable.ic_bell_off, Accent.RED)
                AudioManager.RINGER_MODE_VIBRATE -> Triple(getString(R.string.vibrate), R.drawable.ic_vibrate, Accent.ORANGE)
                else -> Triple(getString(R.string.sound_on), R.drawable.ic_bell, Color.WHITE_ISH)
            }
            IslandHub.post(IslandItem(
                key = "ringer", kind = Kind.RINGER, pkg = null,
                title = label, iconRes = icon, accent = color, compactLabel = label, compactText = " ",
                durationMs = 2000,
            ))
        }
    }

    private var audioCallbackReady = false
    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!audioCallbackReady) { audioCallbackReady = true; return } // first call lists devices already connected
            val device = addedDevices.firstOrNull { it.isSink && it.type in HEADPHONE_TYPES } ?: return
            val name = device.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
                ?: getString(R.string.headphones)
            IslandHub.post(IslandItem(
                key = "audio_device", kind = Kind.AUDIO_DEVICE, pkg = null,
                title = name, text = getString(R.string.connected), iconRes = R.drawable.ic_headphones,
                accent = Accent.DEFAULT, compactLabel = name.take(14), compactText = getString(R.string.connected_short),
                durationMs = 3000,
            ))
        }
    }

    private fun registerStatusWatchers() {
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, ringerReceiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        audioCallbackReady = false
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioCallback, main)
    }

    private fun unregisterStatusWatchers() {
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(ringerReceiver) } catch (_: Exception) { }
        try { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioCallback) } catch (_: Exception) { }
    }

    private object Color { const val WHITE_ISH = 0xFFE5E5EA.toInt() }

    companion object {
        @Volatile var instance: IslandService? = null
            private set

        private val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
