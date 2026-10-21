package com.thiagocosta.sansisland

import android.os.Handler
import android.os.Looper

/**
 * Central state: ongoing items (call, timer, media...) stay until removed, transient items
 * (notifications, charging...) take over the island for a few seconds, one after another.
 * All calls are expected on the main thread; [post] and [remove] hop to it if needed.
 */
object IslandHub {
    fun interface Listener { fun onIslandChanged() }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private val ongoing = LinkedHashMap<String, IslandItem>()
    private val queue = ArrayDeque<IslandItem>()
    private val dismissed = HashSet<String>()
    private val endTransient = Runnable { nextTransient() }

    var currentTransient: IslandItem? = null
        private set

    /** item the user tapped in the secondary bubble, shown first while it lasts */
    private var preferredKey: String? = null

    /** package in the foreground, notifications from it are not shown (you are already looking at it) */
    @Volatile var foregroundPackage: String? = null

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun post(item: IslandItem) {
        if (Looper.myLooper() != main.looper) { main.post { post(item) }; return }
        if (!Prefs.isEnabled(item.kind)) return
        if (item.kind.ongoing) {
            ongoing[item.key] = item
        } else {
            if (currentTransient?.key == item.key) {
                currentTransient = item
                main.removeCallbacks(endTransient)
                main.postDelayed(endTransient, item.durationMs)
            } else {
                queue.removeAll { it.key == item.key }
                queue.addLast(item)
                if (currentTransient == null) nextTransient()
            }
        }
        notifyChanged()
    }

    fun remove(key: String) {
        if (Looper.myLooper() != main.looper) { main.post { remove(key) }; return }
        val removed = ongoing.remove(key) != null
        dismissed.remove(key)
        queue.removeAll { it.key == key }
        if (currentTransient?.key == key) { nextTransient(); return }
        if (removed) notifyChanged()
    }

    /** user swiped the transient item away */
    fun dismissTransient() {
        nextTransient()
    }

    /** user swiped an ongoing item away; it comes back only if it is removed and posted again */
    fun dismissOngoing(key: String) {
        dismissed += key
        if (preferredKey == key) preferredKey = null
        notifyChanged()
    }

    fun prefer(key: String) {
        preferredKey = key
        notifyChanged()
    }

    fun visibleOngoing(): List<IslandItem> {
        val list = ongoing.values.filter { it.key !in dismissed && Prefs.isEnabled(it.kind) }
            .sortedWith(compareByDescending<IslandItem> { it.kind.priority }.thenByDescending { it.updatedAt })
        val pref = list.firstOrNull { it.key == preferredKey } ?: return list
        return listOf(pref) + (list - pref)
    }

    fun primary(): IslandItem? = currentTransient ?: visibleOngoing().firstOrNull()

    fun secondary(): IslandItem? {
        val ongoingList = visibleOngoing()
        return if (currentTransient != null) null else ongoingList.getOrNull(1)
    }

    fun refresh() = notifyChanged()

    private fun nextTransient() {
        main.removeCallbacks(endTransient)
        currentTransient = queue.removeFirstOrNull()
        currentTransient?.let { main.postDelayed(endTransient, it.durationMs.coerceAtLeast(1500)) }
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it.onIslandChanged() }
    }
}
