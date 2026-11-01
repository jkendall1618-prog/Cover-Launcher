package app.cover.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Headlines for the music page, plus which feeds didn't answer last time and when it was fetched. */
data class News(val items: List<Headline>, val dead: List<String>, val at: Long, val next: Long)

/**
 * Doom metal news for the music page: a few sites' RSS feeds, newest first, with anything that
 * names an artist you follow on Spotify starred and pinned to the top. Refreshes every three
 * hours; a failed feed never wipes what was already showing.
 */
object DoomNews {
    /** Name and feed, editable in cfg (one per line, "name | url" or just the url). */
    val DEFAULTS = listOf(
        "obelisk" to "https://theobelisk.net/obelisk/feed/",
        "doomed & stoned" to "https://doomedandstoned.com/rss",
        "decibel" to "https://www.decibelmagazine.com/tag/doom-metal/feed/",
        "metal-rules" to "https://www.metal-rules.com/category/news/doom-metal/feed/",
        "doom charts" to "https://doomcharts.com/feed/",
    )

    const val FEEDS_KEY = "news_feeds"
    private const val CACHE_KEY = "news_json"
    private const val EVERY = 3 * 60 * 60_000L
    private const val RETRY = 30 * 60_000L
    private const val KEEP_DAYS = 14L
    private const val LIMIT = 15

    var news by mutableStateOf<News?>(null)
    var busy by mutableStateOf(false)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun due(now: Long = System.currentTimeMillis()): Boolean = news.let { it == null || now >= it.next }

    fun feeds(prefs: Prefs): List<Pair<String, String>> {
        val custom = prefs.get(FEEDS_KEY)?.lines()?.mapNotNull { parseLine(it) }.orEmpty()
        return custom.ifEmpty { DEFAULTS }
    }

    /** The cfg box's text: one feed per line. */
    fun feedsText(prefs: Prefs): String = feeds(prefs).joinToString("\n") { "${it.first} | ${it.second}" }

    private fun parseLine(line: String): Pair<String, String>? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val name = t.substringBefore('|', "").trim()
        val url = t.substringAfter('|').trim()
        if (!url.startsWith("http")) return null
        val label = name.ifEmpty { runCatching { URI(url).host.removePrefix("www.") }.getOrNull() ?: url }
        return label.lowercase() to url
    }

    fun cached(prefs: Prefs): News? {
        val raw = prefs.get(CACHE_KEY) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val a = j.getJSONArray("i")
            val d = j.getJSONArray("d")
            val at = j.getLong("at")
            News(
                (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Headline(it.getString("t"), it.getString("l"), Instant.ofEpochMilli(it.getLong("a")), it.getString("s"), it.optBoolean("x"))
                },
                (0 until d.length()).map { d.getString(it) },
                at,
                j.optLong("n", at + EVERY),
            )
        }.getOrNull()
    }

    /** Fetches every feed, merges with what was kept, saves and returns the result. Runs off the main thread. */
    fun refresh(prefs: Prefs): News {
        val prev = cached(prefs)
        val now = System.currentTimeMillis()
        val artists = Spotify.cachedArtists(prefs).map { it.second }
        val fresh = mutableListOf<Headline>()
        val dead = mutableListOf<String>()
        for ((name, url) in feeds(prefs)) {
            runCatching { Rss.parse(fetch(url), name) }
                .onSuccess { if (it.isEmpty()) dead += name else fresh += it }
                .onFailure { dead += name }
        }
        // Keep last time's headlines from feeds that didn't answer, so one slow site doesn't empty the list.
        val kept = prev?.items?.filter { it.source in dead }.orEmpty()
        val since = Instant.ofEpochMilli(now).minus(KEEP_DAYS, ChronoUnit.DAYS)
        val items = Rss.merge(fresh + kept, artists, since, LIMIT)
        val allDead = fresh.isEmpty()
        val n = News(
            items = if (allDead && prev != null) prev.items else items,
            dead = dead,
            at = now,
            next = now + if (allDead) RETRY else EVERY,
        )
        val j = JSONObject()
            .put("i", JSONArray(n.items.map {
                JSONObject().put("t", it.title).put("l", it.link).put("a", it.at.toEpochMilli()).put("s", it.source).put("x", it.star)
            }))
            .put("d", JSONArray(n.dead))
            .put("at", n.at)
            .put("n", n.next)
        prefs.put(CACHE_KEY to j.toString())
        return n
    }

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            // Some blogs turn away requests that don't look like a browser or feed reader.
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) cover-launcher feed reader")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.5")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("${r.code}")
            return r.body?.string().orEmpty()
        }
    }
}
