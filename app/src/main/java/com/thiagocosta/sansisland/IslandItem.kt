package com.thiagocosta.sansisland

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.media.session.MediaController

/** what the island can show; priority decides which item wins the pill when several are active */
enum class Kind(val priority: Int, val ongoing: Boolean) {
    CALL(100, true),
    TIMER(80, true),
    RECORDING(75, true),
    MEDIA(60, true),
    LIVE(50, true),
    NOTIFICATION(90, false),
    CHARGING(85, false),
    AUDIO_DEVICE(85, false),
    RINGER(85, false),
    TEST(95, false),
}

enum class Tone { NEUTRAL, POSITIVE, NEGATIVE }

class IslandAction(
    val label: String,
    val iconRes: Int = 0,
    val tone: Tone = Tone.NEUTRAL,
    val run: () -> Unit,
)

class MediaInfo(
    val controller: MediaController,
    val playing: Boolean,
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() when [positionMs] was valid */
    val positionAt: Long,
    val speed: Float,
    val durationMs: Long,
)

class IslandItem(
    val key: String,
    val kind: Kind,
    val pkg: String?,
    val title: String,
    val text: String? = null,
    val icon: Drawable? = null,
    /** icon is album art / contact photo, shown rounded and untinted */
    val iconIsPicture: Boolean = false,
    /** small icon resource used when [icon] is null, tinted with [accent] */
    val iconRes: Int = R.drawable.ic_bell,
    val accent: Int,
    /** short label on the left of the camera in compact mode, e.g. "Carregando" */
    val compactLabel: String? = null,
    /** short text on the right of the camera in compact mode, e.g. "45%" */
    val compactText: String? = null,
    /** chronometer base in SystemClock.elapsedRealtime() time base */
    val chronoBase: Long? = null,
    val countDown: Boolean = false,
    val progress: Float? = null,
    val indeterminate: Boolean = false,
    val actions: List<IslandAction> = emptyList(),
    val open: (() -> Unit)? = null,
    /** how long a transient item stays, 0 for ongoing items */
    val durationMs: Long = 0,
    val media: MediaInfo? = null,
    val incomingCall: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
