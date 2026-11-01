package app.cover.launcher

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the Homebase calendar feed (the same link ICSx⁵ subscribes to), saved in
 * settings. Keeps the last good copy for two minutes so a chat that asks twice in a
 * row doesn't download the whole feed twice.
 */
class CalendarFeed(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun text(): String {
        val url = prefs.get(Prefs.HB_ICS)?.trim().orEmpty()
            .replaceFirst("webcal://", "https://")
        if (url.isEmpty()) throw IllegalStateException("No Homebase calendar feed is saved in cover settings")

        val now = System.currentTimeMillis()
        cache?.takeIf { it.first == url && now - it.second < 120_000 }?.let { return it.third }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/calendar")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("The Homebase calendar feed answered ${response.code}")
            if (!body.contains("BEGIN:VCALENDAR")) throw IllegalStateException("That link isn't an iCalendar feed")
            cache = Triple(url, now, body)
            return body
        }
    }

    companion object {
        /** URL, when it was fetched, and the feed itself: one copy across the app. */
        @Volatile
        private var cache: Triple<String, Long, String>? = null

        /** Drop the cached copy, e.g. after adding an event, so Claude sees it straight away. */
        fun invalidate() {
            cache = null
        }
    }
}
