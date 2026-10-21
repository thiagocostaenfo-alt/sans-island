package com.thiagocosta.sansisland

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log

/** sends a PendingIntent from the island, allowing it to open an activity (we have a visible window) */
fun PendingIntent.sendFromIsland(context: Context) {
    try {
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        send(context, 0, null, null, null, null, options)
    } catch (e: Exception) {
        Log.w("SansIsland", "could not send pending intent", e)
    }
}
