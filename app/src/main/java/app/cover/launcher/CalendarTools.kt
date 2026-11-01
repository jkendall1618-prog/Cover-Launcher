package app.cover.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Claude's calendar tool. It reads the Homebase feed when one is saved (fresh, no
 * ICSx⁵ lag) and falls back to the phone's synced calendars, which cover can already
 * see. Every answer says which one it came from.
 */
object CalendarTools {
    private const val MAX_EVENTS = 200
    private const val MAX_DAYS = 90L

    fun run(ctx: Context, prefs: Prefs, name: String, input: JSONObject): String {
        if (name != "get_events") throw IllegalArgumentException("Unknown tool: $name")

        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val from = date(input.optString("from"), zone) ?: now
        var to = date(input.optString("to"), zone, endOfDay = true) ?: from.plus(7, ChronoUnit.DAYS)
        if (!to.isAfter(from)) to = from.plus(1, ChronoUnit.DAYS)
        val capped = from.plus(MAX_DAYS, ChronoUnit.DAYS)
        val truncated = to.isAfter(capped)
        if (truncated) to = capped

        var source = "phone calendar"
        var note: String? = null
        val events: List<CalEvent>

        if (prefs.get(Prefs.HB_ICS) != null) {
            source = "homebase feed"
            events = try {
                Ics.events(CalendarFeed(prefs).text(), from, to, zone)
            } catch (e: Exception) {
                if (!CalendarRepo.hasAccess(ctx)) {
                    throw IllegalStateException(
                        "The Homebase calendar feed couldn't be read and cover doesn't have calendar access. " +
                            "Say the calendar is unavailable rather than guessing.",
                    )
                }
                source = "phone calendar (feed unavailable)"
                note = "The Homebase feed couldn't be read, so this is the phone's last synced copy."
                CalendarRepo.events(ctx, from, to)
            }
        } else if (CalendarRepo.hasAccess(ctx)) {
            note = "No Homebase feed is saved, so this is the phone's synced calendar."
            events = CalendarRepo.events(ctx, from, to)
        } else {
            throw IllegalStateException(
                "No calendar is set up: add the Homebase feed link in cover settings or grant calendar access. " +
                    "Say the calendar is unavailable rather than guessing.",
            )
        }

        val array = JSONArray()
        events.take(MAX_EVENTS).forEach { e ->
            val item = JSONObject()
                .put("start", stamp(e.start, e.allDay, zone))
                .put("end", stamp(e.end, e.allDay, zone))
                .put("allDay", e.allDay)
                .put("title", e.title)
            e.location?.let { item.put("location", it) }
            e.calendar?.let { item.put("calendar", it) }
            array.put(item)
        }

        val result = JSONObject()
            .put("now", stamp(now, false, zone))
            .put("timezone", zone.id)
            .put("source", source)
            .put("fetchedAt", stamp(now, false, zone))
            .put("truncated", truncated || events.size >= MAX_EVENTS)
            .put("events", array)
        note?.let { result.put("note", it) }
        return result.toString()
    }

    private fun date(text: String, zone: ZoneId, endOfDay: Boolean = false): Instant? {
        val value = text.trim()
        if (value.isEmpty()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).atZone(zone).toInstant() }.getOrNull()
            ?: runCatching {
                val day = LocalDate.parse(value)
                (if (endOfDay) day.plusDays(1) else day).atStartOfDay(zone).toInstant()
            }.getOrNull()
    }

    private fun stamp(time: Instant, allDay: Boolean, zone: ZoneId): String =
        if (allDay) time.atZone(zone).toLocalDate().toString()
        else time.truncatedTo(ChronoUnit.SECONDS).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
