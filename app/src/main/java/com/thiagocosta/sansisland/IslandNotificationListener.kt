package com.thiagocosta.sansisland

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads notifications and media sessions and turns them into island items:
 * calls, timers/stopwatches, screen recording, live progress (deliveries, downloads, navigation),
 * music, and regular notifications that pop up for a few seconds.
 */
class IslandNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val controllerCallbacks = HashMap<MediaController, MediaController.Callback>()
    private val lastAlertWhen = HashMap<String, Long>()
    private val removePausedMedia = Runnable { IslandHub.remove(MEDIA_KEY) }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        trackControllers(list ?: emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }

    override fun onListenerConnected() {
        connected = true
        try {
            activeNotifications?.forEach { handle(it, initial = true) }
        } catch (e: Exception) { Log.w(TAG, "reading active notifications failed", e) }
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            sessionManager = msm
            val component = ComponentName(this, IslandNotificationListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            trackControllers(msm.getActiveSessions(component))
        } catch (e: Exception) { Log.w(TAG, "media sessions not available", e) }
    }

    override fun onListenerDisconnected() {
        connected = false
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controllerCallbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllerCallbacks.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn, initial = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandHub.remove(sbn.key)
        lastAlertWhen.remove(sbn.key)
    }

    // ---------------------------------------------------------------- notifications

    private fun handle(sbn: StatusBarNotification, initial: Boolean) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = n.extras
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // handled by media sessions

        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim().orEmpty()
        val text = notificationText(n)
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val showChrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val chronoBase = if (showChrono) SystemClock.elapsedRealtime() + (n.`when` - System.currentTimeMillis()) else null
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progressValue = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val progress = if (progressMax > 0) (progressValue.toFloat() / progressMax).coerceIn(0f, 1f) else null
        val callType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else 0
        val promoted = Build.VERSION.SDK_INT >= 36 && (n.flags and Notification.FLAG_PROMOTED_ONGOING) != 0
        val pkg = sbn.packageName
        val accent = Accent.forPackage(this, pkg)
        val icon = largeIcon(n) ?: appIcon(pkg)
        val open = openAction(sbn)
        val lowerText = "$title $text".lowercase()

        val isCall = n.category == Notification.CATEGORY_CALL || callType != 0
        val isTimer = showChrono && (countDown || n.category == Notification.CATEGORY_ALARM
                || n.category == Notification.CATEGORY_STOPWATCH || "clock" in pkg || "alarm" in pkg)
        val isRecording = ongoing && ("screenrecorder" in pkg || "smartcapture" in pkg || "recorder" in pkg
                || "gravando" in lowerText || "gravação de tela" in lowerText || "recording" in lowerText)
        val isLive = ongoing && (progress != null || indeterminate || promoted || n.category == Notification.CATEGORY_NAVIGATION
                || n.category == Notification.CATEGORY_PROGRESS)

        when {
            isCall -> {
                val incoming = callType == 1 || (n.fullScreenIntent != null && !showChrono && callType != 2)
                IslandHub.post(IslandItem(
                    key = sbn.key, kind = Kind.CALL, pkg = pkg,
                    title = title.ifEmpty { getString(R.string.call) },
                    text = if (incoming) getString(R.string.incoming_call) else text,
                    icon = icon, iconIsPicture = icon != null && largeIcon(n) != null, iconRes = R.drawable.ic_phone,
                    accent = Accent.GREEN,
                    compactText = if (incoming) getString(R.string.incoming_short) else null,
                    chronoBase = if (incoming) null else chronoBase,
                    actions = notificationActions(sbn, call = true),
                    open = open, incomingCall = incoming,
                ))
            }
            isTimer -> IslandHub.post(IslandItem(
                key = sbn.key, kind = Kind.TIMER, pkg = pkg,
                title = title.ifEmpty { getString(R.string.timer) }, text = text,
                iconRes = R.drawable.ic_timer, accent = Accent.ORANGE,
                chronoBase = chronoBase, countDown = countDown,
                actions = notificationActions(sbn, call = false), open = open,
            ))
            isRecording -> IslandHub.post(IslandItem(
                key = sbn.key, kind = Kind.RECORDING, pkg = pkg,
                title = title.ifEmpty { getString(R.string.recording) }, text = text,
                iconRes = R.drawable.ic_record, accent = Accent.RED,
                chronoBase = chronoBase, compactText = if (chronoBase == null) "REC" else null,
                actions = notificationActions(sbn, call = false), open = open,
            ))
            isLive -> IslandHub.post(IslandItem(
                key = sbn.key, kind = Kind.LIVE, pkg = pkg,
                title = title.ifEmpty { appLabel(pkg) }, text = text,
                icon = icon, iconRes = if (n.category == Notification.CATEGORY_NAVIGATION) R.drawable.ic_navigation else R.drawable.ic_bell,
                accent = accent,
                compactText = progress?.let { "${(it * 100).toInt()}%" },
                chronoBase = chronoBase, countDown = countDown,
                progress = progress, indeterminate = indeterminate,
                actions = notificationActions(sbn, call = false), open = open,
            ))
            ongoing -> Unit // silent ongoing notifications (VPN, USB...) are not island material
            else -> {
                if (initial) return
                if (title.isEmpty() && text.isEmpty()) return
                // updates of a notification already shown, without a new alert, are skipped
                val previous = lastAlertWhen[sbn.key]
                if (previous != null && ((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0 || previous == n.`when`)) return
                if (!alerts(sbn)) return
                lastAlertWhen[sbn.key] = n.`when`
                if (Prefs.skipForegroundApp && IslandHub.foregroundPackage == pkg) return
                IslandHub.post(IslandItem(
                    key = sbn.key, kind = Kind.NOTIFICATION, pkg = pkg,
                    title = title.ifEmpty { appLabel(pkg) }, text = text,
                    icon = icon, iconIsPicture = largeIcon(n) != null, accent = accent,
                    actions = notificationActions(sbn, call = false), open = open,
                    durationMs = Prefs.notificationSeconds * 1000L,
                ))
            }
        }
    }

    private fun notificationText(n: Notification): String {
        val extras = n.extras
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            val last = messages.last() as? android.os.Bundle
            val txt = last?.getCharSequence("text")
            if (!txt.isNullOrBlank()) return txt.toString().trim()
        }
        return (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()
    }

    /** only notifications that would make sound or pop up on their own become island alerts */
    private fun alerts(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        if (!currentRanking.getRanking(sbn.key, ranking)) return true
        if (ranking.isAmbient) return false
        return ranking.importance >= android.app.NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun largeIcon(n: Notification): Drawable? = try {
        n.getLargeIcon()?.loadDrawable(this)
    } catch (_: Exception) { null }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) { null }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun openAction(sbn: StatusBarNotification): (() -> Unit)? {
        val intent = sbn.notification.contentIntent ?: return null
        val autoCancel = (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL) != 0
        val key = sbn.key
        return {
            intent.sendFromIsland(this)
            if (autoCancel) try { cancelNotification(key) } catch (_: Exception) { }
        }
    }

    private fun notificationActions(sbn: StatusBarNotification, call: Boolean): List<IslandAction> {
        val actions = sbn.notification.actions ?: return emptyList()
        return actions.filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(3)
            .map { action ->
                val label = action.title.toString()
                val lower = label.lowercase()
                val tone = when {
                    !call -> Tone.NEUTRAL
                    listOf("recus", "rejeit", "encerr", "deslig", "decline", "hang", "end", "ignor").any { it in lower } -> Tone.NEGATIVE
                    listOf("atend", "answer", "aceit", "accept").any { it in lower } -> Tone.POSITIVE
                    else -> Tone.NEUTRAL
                }
                IslandAction(label, tone = tone) { action.actionIntent.sendFromIsland(this) }
            }
    }

    // ---------------------------------------------------------------- media

    private fun trackControllers(list: List<MediaController>) {
        controllerCallbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllerCallbacks.clear()
        list.forEach { controller ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMedia()
                override fun onSessionDestroyed() = refreshMedia()
            }
            controller.registerCallback(cb, main)
            controllerCallbacks[controller] = cb
        }
        refreshMedia()
    }

    private fun refreshMedia() {
        val controllers = controllerCallbacks.keys.toList()
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val paused = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED && it.metadata != null }
        val controller = playing ?: paused
        if (controller == null || controller.metadata == null) {
            main.removeCallbacks(removePausedMedia)
            IslandHub.remove(MEDIA_KEY)
            return
        }
        val metadata = controller.metadata!!
        val state = controller.playbackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val pkg = controller.packageName
        val accent = Accent.fromBitmap(art) ?: Accent.forPackage(this, pkg)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: appLabel(pkg)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val controls = controller.transportControls
        val info = MediaInfo(
            controller = controller,
            playing = isPlaying,
            positionMs = state?.position ?: 0L,
            positionAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )
        val icon: Drawable? = art?.let { BitmapDrawable(resources, scaledArt(it)) } ?: appIcon(pkg)
        IslandHub.post(IslandItem(
            key = MEDIA_KEY, kind = Kind.MEDIA, pkg = pkg,
            title = title, text = artist,
            icon = icon, iconIsPicture = art != null, iconRes = R.drawable.ic_music,
            accent = accent, media = info,
            actions = listOf(
                IslandAction("prev", R.drawable.ic_skip_previous) { controls.skipToPrevious() },
                if (isPlaying) IslandAction("pause", R.drawable.ic_pause) { controls.pause() }
                else IslandAction("play", R.drawable.ic_play) { controls.play() },
                IslandAction("next", R.drawable.ic_skip_next) { controls.skipToNext() },
            ),
            open = controller.sessionActivity?.let { pi -> { pi.sendFromIsland(this) } },
        ))
        main.removeCallbacks(removePausedMedia)
        if (!isPlaying) main.postDelayed(removePausedMedia, PAUSED_MEDIA_TIMEOUT)
    }

    private fun scaledArt(bitmap: Bitmap): Bitmap {
        val max = 256
        if (bitmap.width <= max && bitmap.height <= max) return bitmap
        val scale = max.toFloat() / maxOf(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    companion object {
        private const val TAG = "SansIslandListener"
        private const val MEDIA_KEY = "media"
        private const val PAUSED_MEDIA_TIMEOUT = 30_000L
        @Volatile var connected = false
            private set
    }
}
