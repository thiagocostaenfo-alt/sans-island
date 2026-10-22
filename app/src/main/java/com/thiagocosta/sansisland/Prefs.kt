package com.thiagocosta.sansisland

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var prefs: SharedPreferences
    private val changeListeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("sans_island", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    // keep a strong reference, SharedPreferences only holds weak ones
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        changeListeners.toList().forEach { it() }
    }

    /** the new-Siri glass look replaces whatever look was set before it existed, once */
    fun migrateToGlass() {
        if (prefs.getBoolean("glass_migrated", false)) return
        prefs.edit().putString("style", "glass").putBoolean("glass_migrated", true).apply()
    }

    fun addChangeListener(l: () -> Unit) { changeListeners += l }
    fun removeChangeListener(l: () -> Unit) { changeListeners -= l }

    fun isEnabled(kind: Kind): Boolean =
        !::prefs.isInitialized || kind == Kind.TEST || prefs.getBoolean("kind_${kind.name}", true)

    fun setEnabled(kind: Kind, enabled: Boolean) = prefs.edit().putBoolean("kind_${kind.name}", enabled).apply()

    var offsetXdp: Int
        get() = prefs.getInt("offset_x", 0)
        set(v) = prefs.edit().putInt("offset_x", v).apply()

    var offsetYdp: Int
        get() = prefs.getInt("offset_y", 0)
        set(v) = prefs.edit().putInt("offset_y", v).apply()

    /** pill height adjustment in dp, added to the size taken from the camera cutout */
    var sizeDp: Int
        get() = prefs.getInt("size", 0)
        set(v) = prefs.edit().putInt("size", v).apply()

    var notificationSeconds: Int
        get() = prefs.getInt("notif_seconds", 4)
        set(v) = prefs.edit().putInt("notif_seconds", v).apply()

    var hideInLandscape: Boolean
        get() = prefs.getBoolean("hide_landscape", true)
        set(v) = prefs.edit().putBoolean("hide_landscape", v).apply()

    var skipForegroundApp: Boolean
        get() = prefs.getBoolean("skip_foreground", true)
        set(v) = prefs.edit().putBoolean("skip_foreground", v).apply()

    /** "glass" (new Siri: black with silver glass and the Siri light), "siri" (multicolor glow around the edge),
     *  "accent" (glow in the app color) or "classic" (plain black) */
    var islandStyle: String
        get() = prefs.getString("style", "glass") ?: "glass"
        set(v) = prefs.edit().putString("style", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
}
