package app.cover.launcher

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Temperature now, plus when rain is next likely in the coming 12 hours. */
data class Wx(val temp: Int, val note: String)

/** Open-Meteo: free, no key, no account. Fixed to Spring Mountain, Qld, so no location permission. */
object Weather {
    private const val LAT = -27.70
    private const val LON = 152.88
    private const val RAIN_CHANCE = 50

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val HOUR = DateTimeFormatter.ofPattern("ha", Locale.ENGLISH)

    fun now(): Wx {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
            "&current=temperature_2m&hourly=precipitation_probability&forecast_days=2&timezone=auto"
        val text = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("weather ${r.code}")
            r.body?.string().orEmpty()
        }
        val j = JSONObject(text)
        val temp = j.getJSONObject("current").getDouble("temperature_2m").roundToInt()
        val hourly = j.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val chance = hourly.getJSONArray("precipitation_probability")

        val now = LocalDateTime.now()
        val thisHour = now.withMinute(0).withSecond(0).withNano(0)
        var note = "dry next 12h"
        for (i in 0 until times.length()) {
            val t = LocalDateTime.parse(times.getString(i))
            if (t.isBefore(thisHour)) continue
            if (t.isAfter(now.plusHours(12))) break
            if (chance.optInt(i, 0) >= RAIN_CHANCE) {
                note = if (t == thisHour) "rain likely now" else "rain from " + t.format(HOUR).lowercase()
                break
            }
        }
        return Wx(temp, note)
    }
}
