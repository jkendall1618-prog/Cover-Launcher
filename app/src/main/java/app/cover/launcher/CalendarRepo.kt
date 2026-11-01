package app.cover.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Event(val whenLabel: String, val title: String)

object CalendarRepo {
    fun hasAccess(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Next few events over the coming week from every calendar on the phone (Homebase arrives via ICSx5). */
    fun upcoming(ctx: Context, limit: Int = 3): List<Event> {
        if (!hasAccess(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - 86_400_000L)
        ContentUris.appendId(b, now + 7 * 86_400_000L)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<Event>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        runCatching {
            ctx.contentResolver.query(
                b.build(), proj, "${CalendarContract.Instances.VISIBLE}=1", null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val title = c.getString(0) ?: "(untitled)"
                    val begin = c.getLong(1)
                    val end = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    if (end < now) continue
                    val label = if (allDay) {
                        val d = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                        if (d.isBefore(today)) continue
                        day(d, today) + " all day"
                    } else {
                        val t = Instant.ofEpochMilli(begin).atZone(zone)
                        day(t.toLocalDate(), today) + " " + t.format(HM)
                    }
                    out += Event(label, title)
                }
            }
        }
        return out
    }

    /** Instances overlapping [from, to), with enough detail for Claude's calendar tool. */
    fun events(ctx: Context, from: Instant, to: Instant, limit: Int = 200): List<CalEvent> {
        if (!hasAccess(ctx)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uri, from.toEpochMilli())
        ContentUris.appendId(uri, to.toEpochMilli())
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<CalEvent>()
        runCatching {
            ctx.contentResolver.query(
                uri.build(), projection, "${CalendarContract.Instances.VISIBLE}=1", null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val allDay = c.getInt(3) == 1
                    out += CalEvent(
                        start = if (allDay) allDayStart(c.getLong(1)) else Instant.ofEpochMilli(c.getLong(1)),
                        end = if (allDay) allDayStart(c.getLong(2)) else Instant.ofEpochMilli(c.getLong(2)),
                        allDay = allDay,
                        title = c.getString(0) ?: "(untitled)",
                        location = c.getString(4)?.takeIf { it.isNotBlank() },
                        calendar = c.getString(5)?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        return out
    }

    /** Android stores an all-day event at UTC midnight; its date is what matters, not that instant. */
    private fun allDayStart(ms: Long): Instant =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DOW = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

    fun day(d: LocalDate, today: LocalDate): String = when {
        d == today -> "today"
        d == today.plusDays(1) -> "tmrw"
        d.isBefore(today) -> "now"
        else -> d.format(DOW).lowercase()
    }.padEnd(5)
}
