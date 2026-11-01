package app.cover.launcher

import biweekly.Biweekly
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone

/** One thing on a calendar, whether it came from the feed or the phone. */
data class CalEvent(
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val title: String,
    val location: String? = null,
    val calendar: String? = null,
)

/**
 * Reads an iCalendar (ICS) feed, expanding recurring events into the window asked for.
 * Biweekly handles TZID/VTIMEZONE, RRULE, COUNT/UNTIL and EXDATE; all-day events keep
 * their date with no time.
 */
object Ics {
    fun events(text: String, from: Instant, to: Instant, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> {
        val ical = Biweekly.parse(text).first()
        val timeZone = TimeZone.getTimeZone(zone)
        val out = ArrayList<CalEvent>()

        for (event in ical.events) {
            if (event.status?.value?.equals("CANCELLED", ignoreCase = true) == true) continue
            val startProp = event.dateStart ?: continue
            val startDate = startProp.value ?: continue
            val allDay = !startDate.hasTime()
            val endDate = event.dateEnd?.value
            val duration = when {
                endDate != null -> (endDate.time - startDate.time).coerceAtLeast(0L)
                allDay -> 86_400_000L
                else -> 3_600_000L
            }
            val title = event.summary?.value?.trim()?.ifBlank { "(untitled)" } ?: "(untitled)"
            val location = event.location?.value?.trim()?.takeIf { it.isNotEmpty() }
            val excluded = event.exceptionDates.flatMap { it.values }.map { it.time }.toHashSet()

            val rule = event.recurrenceRule?.value
            if (rule == null) {
                add(out, startDate, duration, allDay, title, location, from, to)
            } else {
                val dates = rule.getDateIterator(startDate, timeZone)
                dates.advanceTo(Date.from(from.minusMillis(duration)))
                var guard = 0
                while (dates.hasNext() && guard++ < 2_000) {
                    val date = dates.next() ?: break
                    if (date.toInstant().isAfter(to)) break
                    if (excluded.contains(date.time)) continue
                    add(out, date, duration, allDay, title, location, from, to)
                }
            }
        }
        return out.sortedBy { it.start }
    }

    private fun add(
        out: MutableList<CalEvent>,
        start: Date,
        duration: Long,
        allDay: Boolean,
        title: String,
        location: String?,
        from: Instant,
        to: Instant,
    ) {
        val begins = start.toInstant()
        val ends = begins.plusMillis(duration)
        if (begins < to && ends > from) out += CalEvent(begins, ends, allDay, title, location)
    }
}
