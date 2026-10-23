package com.thiagocosta.sansisland

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** a team the user follows; id is ESPN's team id */
data class FollowedTeam(val id: String, val name: String)

/**
 * Live scores of the followed teams, from ESPN's public scoreboard (no account or key needed).
 * Checks every 15 minutes, every minute when a game is about to start and every 30 seconds while one is live.
 */
object ScoreWatcher {
    private const val TAG = "SansScores"
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/"
    /** competitions the followed Brazilian teams play (ESPN has no Campeonato Paraense: see notification fallback) */
    private val LEAGUES = listOf(
        "bra.1", "bra.2", "bra.3", "bra.copa_do_brazil", "bra.camp.paulista",
        "conmebol.libertadores", "conmebol.sudamericana", "fifa.cwc",
    )
    private const val KEY_PREFIX = "score:"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var context: Context? = null
    private var running = false
    private val lastScore = HashMap<String, String>()
    private val postSeenAt = HashMap<String, Long>()
    private val shownKeys = HashSet<String>()
    private val tick = Runnable { poll() }
    /** (league, date) of games that are live or about to start: only these are re-checked between full sweeps */
    private var active: Set<Pair<String, String>> = emptySet()
    private var lastSweep = 0L

    /** names of teams that ESPN is covering right now, so notification scores do not duplicate them */
    @Volatile var liveTeamNames: Set<String> = emptySet()
        private set

    fun start(c: Context) {
        context = c.applicationContext
        if (running) return
        running = true
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        clearAll()
    }

    fun refreshNow() {
        if (!running) return
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun clearAll() {
        shownKeys.forEach { IslandHub.remove(it) }
        shownKeys.clear()
        liveTeamNames = emptySet()
    }

    private fun poll() {
        if (!running) return
        val teams = Prefs.followedTeams()
        if (!Prefs.isEnabled(Kind.SCORE) || teams.isEmpty()) {
            clearAll()
            main.postDelayed(tick, 30 * 60_000L)
            return
        }
        val ids = teams.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val sweep = active.isEmpty() || now - lastSweep > 15 * 60_000L
        val targets = if (sweep) sweepTargets() else active.toList()
        if (sweep) lastSweep = now
        io.execute {
            val games = try { fetch(ids, targets) } catch (e: Exception) { Log.w(TAG, "fetch failed", e); null }
            main.post {
                val delay = if (games == null) 5 * 60_000L else apply(games)
                if (running) main.postDelayed(tick, delay)
            }
        }
    }

    // ------------------------------------------------------------ network

    private class Side(val id: String, val abbr: String, val name: String, val color: Int, val alt: Int, val score: String)
    private class Game(
        val id: String, val leagueSlug: String, val date: String, val league: String, val start: Long, val state: String, val detail: String,
        val clockSeconds: Double, val period: Int, val home: Side, val away: Side, val lastEvent: String?, val link: String?,
    )

    /** every competition, today (and yesterday too in the small hours, for games that cross midnight) */
    private fun sweepTargets(): List<Pair<String, String>> {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val now = System.currentTimeMillis()
        val days = mutableListOf(fmt.format(Date(now)))
        if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 3) days += fmt.format(Date(now - 86_400_000L))
        return LEAGUES.flatMap { l -> days.map { l to it } }
    }

    private fun fetch(teamIds: Set<String>, targets: List<Pair<String, String>>): List<Game> {
        val games = ArrayList<Game>()
        for ((league, date) in targets) {
            val json = try { get("$BASE$league/scoreboard?dates=$date") } catch (e: Exception) { continue } ?: continue
            val root = JSONObject(json)
            val leagueName = root.optJSONArray("leagues")?.optJSONObject(0)?.optString("name").orEmpty()
            val events = root.optJSONArray("events") ?: continue
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val competitors = comp.optJSONArray("competitors") ?: continue
                var home: Side? = null
                var away: Side? = null
                for (j in 0 until competitors.length()) {
                    val c = competitors.getJSONObject(j)
                    val t = c.optJSONObject("team") ?: continue
                    val side = Side(
                        t.optString("id"), t.optString("abbreviation").ifEmpty { t.optString("shortDisplayName").take(3).uppercase() },
                        t.optString("shortDisplayName").ifEmpty { t.optString("displayName") },
                        parseColor(t.optString("color"), 0xFF3A3A3C.toInt()), parseColor(t.optString("alternateColor"), Color.WHITE),
                        c.optString("score", "0"),
                    )
                    if (c.optString("homeAway") == "home") home = side else away = side
                }
                if (home == null || away == null) continue
                if (home.id !in teamIds && away.id !in teamIds) continue
                val status = e.optJSONObject("status") ?: comp.optJSONObject("status") ?: continue
                val type = status.optJSONObject("type")
                games += Game(
                    id = e.optString("id"),
                    leagueSlug = league, date = date,
                    league = leagueName,
                    start = parseIso(e.optString("date")),
                    state = type?.optString("state").orEmpty(),
                    detail = type?.optString("shortDetail").orEmpty(),
                    clockSeconds = status.optDouble("clock", 0.0),
                    period = status.optInt("period", 0),
                    home = home, away = away,
                    lastEvent = lastScoringPlay(comp, home, away),
                    link = e.optJSONArray("links")?.optJSONObject(0)?.optString("href"),
                )
            }
        }
        return games
    }

    private fun lastScoringPlay(comp: JSONObject, home: Side, away: Side): String? {
        val details = comp.optJSONArray("details") ?: return null
        for (i in details.length() - 1 downTo 0) {
            val d = details.getJSONObject(i)
            val text = d.optJSONObject("type")?.optString("text").orEmpty()
            val goal = d.optBoolean("scoringPlay")
            val red = text.contains("Red Card", true)
            if (!goal && !red) continue
            val who = d.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName").orEmpty()
            val teamId = d.optJSONObject("team")?.optString("id")
            val abbr = if (teamId == home.id) home.abbr else if (teamId == away.id) away.abbr else ""
            val minute = d.optJSONObject("clock")?.optString("displayValue").orEmpty()
            val what = when {
                red -> "Expulsão"
                text.contains("Own Goal", true) -> "Gol contra"
                text.contains("Penalty", true) -> "Gol de pênalti"
                else -> "Gol"
            }
            return listOf("$what${if (who.isNotEmpty()) " de $who" else ""}", if (abbr.isNotEmpty()) "($abbr)" else "", minute)
                .filter { it.isNotEmpty() }.joinToString(" ")
        }
        return null
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "SansIsland/1.6 (Android)")
        return try {
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun parseColor(hex: String, fallback: Int) = try {
        if (hex.length == 6) Color.parseColor("#$hex") else fallback
    } catch (_: Exception) { fallback }

    private fun parseIso(s: String): Long = try {
        val f = SimpleDateFormat(if (s.length > 17) "yyyy-MM-dd'T'HH:mm:ss'Z'" else "yyyy-MM-dd'T'HH:mm'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        f.parse(s)?.time ?: 0L
    } catch (_: Exception) { 0L }

    // ------------------------------------------------------------ island

    /** posts or removes the island items; returns the delay until the next check */
    private fun apply(games: List<Game>): Long {
        val now = System.currentTimeMillis()
        val keep = HashSet<String>()
        val nextActive = HashSet<Pair<String, String>>()
        val live = HashSet<String>()
        var delay = 15 * 60_000L
        for (g in games) {
            val key = KEY_PREFIX + g.id
            val minutesToStart = (g.start - now) / 60_000L
            val show = when (g.state) {
                "in" -> true
                "pre" -> minutesToStart in -30..15
                "post" -> {
                    val seen = postSeenAt.getOrPut(g.id) { now }
                    now - seen < 15 * 60_000L && now - g.start < 4 * 3_600_000L
                }
                else -> false
            }
            when {
                g.state == "in" -> { delay = minOf(delay, 30_000L); nextActive += g.leagueSlug to g.date }
                g.state == "pre" && minutesToStart in -30..60 -> { delay = minOf(delay, 60_000L); nextActive += g.leagueSlug to g.date }
                g.state == "post" && show -> nextActive += g.leagueSlug to g.date // keep the final score until it is removed
            }
            if (!show) continue
            keep += key
            if (g.state == "in") { live += g.home.name; live += g.away.name }
            val scoreText = g.home.score + "-" + g.away.score
            val previous = lastScore.put(g.id, scoreText)
            val changed = previous != null && previous != scoreText
            val status = when (g.state) {
                "pre" -> "Hoje " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(g.start))
                "post" -> "Encerrado"
                else -> when {
                    g.detail.equals("HT", true) -> "Intervalo"
                    g.detail.isNotEmpty() -> g.detail
                    else -> "${(g.clockSeconds / 60).toInt()}'"
                }
            }
            val fraction = when (g.state) {
                "post" -> 1f
                "in" -> (g.clockSeconds / 5400.0).toFloat().coerceIn(0f, 1f)
                else -> null
            }
            val info = ScoreInfo(
                home = TeamSide(g.home.abbr, g.home.name, g.home.color, g.home.alt, g.home.score),
                away = TeamSide(g.away.abbr, g.away.name, g.away.color, g.away.alt, g.away.score),
                status = status, live = g.state == "in", minuteFraction = fraction,
                lastEvent = g.lastEvent, scoreChanged = changed, league = g.league,
            )
            val link = g.link
            IslandHub.post(IslandItem(
                key = key, kind = Kind.SCORE, pkg = null,
                title = "${g.home.name} ${g.home.score} x ${g.away.score} ${g.away.name}",
                text = g.lastEvent ?: g.league,
                accent = if (g.state == "in") Accent.GREEN else 0xFF9A9AA2.toInt(),
                score = info,
                open = link?.let { url -> { openUrl(url) } },
            ))
        }
        (shownKeys - keep).forEach { IslandHub.remove(it) }
        shownKeys.clear(); shownKeys += keep
        liveTeamNames = live
        active = nextActive
        return delay
    }

    private fun openUrl(url: String) {
        val c = context ?: return
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    /** team search for the settings screen; runs the callback on the main thread */
    fun search(query: String, callback: (List<FollowedTeam>) -> Unit) {
        io.execute {
            val result = try {
                val json = get("https://site.web.api.espn.com/apis/common/v3/search?query=" + Uri.encode(query) + "&limit=20&type=team")
                val items = JSONObject(json ?: "{}").optJSONArray("items")
                val list = ArrayList<FollowedTeam>()
                if (items != null) for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    if (it.optString("type") != "team" || !it.optString("uid").startsWith("s:600")) continue // soccer only
                    val name = it.optString("displayName")
                    if (name.contains("U17") || name.contains("U20") || name.contains("S20")) continue
                    list += FollowedTeam(it.optString("id"), name)
                }
                list.distinctBy { it.id }
            } catch (e: Exception) { emptyList() }
            main.post { callback(result) }
        }
    }

    // ------------------------------------------------------------ notification fallback

    private val SCORE_RE = Regex("""^\s*(.+?)\s+(\d{1,2})\s*[x×–-]\s*(\d{1,2})\s+(.+?)\s*$""")
    private val MINUTE_RE = Regex("""(\d{1,3})(\+\d{1,2})?\s*['’′]""")
    private val SPORTS_APPS = setOf(
        "com.globo.globoesporte", "br.com.globo.ge", "com.sofascore.results", "de.motain.iliga",
        "com.mobilefootie.wc2010", "com.espn.score_center", "com.google.android.googlequicksearchbox", "com.onefootball.android",
    )

    /**
     * Scores from sports apps' notifications (e.g. Campeonato Paraense, which ESPN does not cover).
     * Returns true when the notification was turned into a score.
     */
    fun fromNotification(key: String, pkg: String, title: String, text: String, ongoing: Boolean, open: (() -> Unit)?): Boolean {
        if (pkg !in SPORTS_APPS || !Prefs.isEnabled(Kind.SCORE)) return false
        val teams = Prefs.followedTeams()
        val line = listOf(title, text).firstOrNull { SCORE_RE.matches(it) } ?: return false
        val m = SCORE_RE.matchEntire(line) ?: return false
        val (homeName, homeScore, awayScore, awayName) = m.destructured
        val followed = teams.any { t -> homeName.contains(t.name, true) || awayName.contains(t.name, true) }
        if (!followed) return false
        if (liveTeamNames.any { homeName.contains(it, true) || awayName.contains(it, true) }) return true // ESPN has it
        val other = if (SCORE_RE.matches(title)) text else title
        val minute = MINUTE_RE.find(other)?.value?.replace(" ", "")
        val ended = other.contains("encerr", true) || other.contains("final", true) || other.contains("FT")
        val itemKey = "notifscore:$key"
        val previous = lastScore.put(itemKey, "$homeScore-$awayScore")
        IslandHub.post(IslandItem(
            key = itemKey, kind = Kind.SCORE, pkg = pkg,
            title = line, text = other.ifBlank { null },
            accent = Accent.GREEN,
            score = ScoreInfo(
                home = TeamSide(abbr(homeName), homeName.trim(), 0xFF3A3A3C.toInt(), Color.WHITE, homeScore),
                away = TeamSide(abbr(awayName), awayName.trim(), 0xFF3A3A3C.toInt(), Color.WHITE, awayScore),
                status = when { ended -> "Encerrado"; minute != null -> minute; else -> "Ao vivo" },
                live = !ended, minuteFraction = minute?.takeWhile { it.isDigit() }?.toFloatOrNull()?.let { (it / 90f).coerceIn(0f, 1f) },
                lastEvent = null, scoreChanged = previous != null && previous != "$homeScore-$awayScore", league = "",
            ),
            open = open,
        ))
        return true
    }

    fun removeNotificationScore(key: String) {
        IslandHub.remove("notifscore:$key")
        lastScore.remove("notifscore:$key")
    }

    private fun abbr(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.length > 2 }
        return (if (words.size >= 2) words.take(3).joinToString("") { it.take(1) } else name.trim().take(3)).uppercase()
    }
}
