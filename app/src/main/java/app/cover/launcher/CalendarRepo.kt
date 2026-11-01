package app.cover.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

data class Event(val whenLabel: String, val title: String)

/**
 * Events added from cover this session, shown on the calendar tile until ICSx⁵ syncs the
 * real copy down (every 15–30 minutes). Dropped after an hour either way.
 */
object Pending {
    data class Item(val id: String, val date: LocalDate, val start: LocalTime?, val title: String, val addedAt: Long)

    private val items = CopyOnWriteArrayList<Item>()

    fun add(item: Item) {
        items += item
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
    }

    fun current(): List<Item> {
        val cutoff = System.currentTimeMillis() - 60 * 60_000L
        items.removeAll { it.addedAt < cutoff }
        return items.toList()
    }
}

object CalendarRepo {
    fun hasAccess(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Next few events over the coming week from every calendar on the phone (Homebase arrives
     * via ICSx5), plus anything just added from cover that ICSx5 hasn't synced down yet.
     */
    fun upcoming(ctx: Context, limit: Int = 3): List<Event> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // When it starts (for sorting and matching), its label, its title.
        val rows = mutableListOf<Triple<Long, String, String>>()
        if (hasAccess(ctx)) {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now - 86_400_000L)
            ContentUris.appendId(b, now + 7 * 86_400_000L)
            val proj = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            )
            runCatching {
                ctx.contentResolver.query(
                    b.build(), proj, "${CalendarContract.Instances.VISIBLE}=1", null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val title = c.getString(0) ?: "(untitled)"
                        val begin = c.getLong(1)
                        val end = c.getLong(2)
                        val allDay = c.getInt(3) == 1
                        if (end < now) continue
                        if (allDay) {
                            val d = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                            if (d.isBefore(today)) continue
                            rows += Triple(d.atStartOfDay(zone).toInstant().toEpochMilli(), day(d, today) + " all day", title)
                        } else {
                            val t = Instant.ofEpochMilli(begin).atZone(zone)
                            rows += Triple(begin, day(t.toLocalDate(), today) + " " + t.format(HM), title)
                        }
                    }
                }
            }
        }
        for (p in Pending.current()) {
            val at = p.date.atTime(p.start ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
            if (p.start == null && p.date.isBefore(today)) continue
            if (p.start != null && at < now) continue
            // Already synced down: same start, and the feed's title begins with ours ("dinner (Jake)").
            val synced = rows.any { abs(it.first - at) < 60_000L && it.third.startsWith(p.title, ignoreCase = true) }
            if (synced) continue
            val label = day(p.date, today) + " " + (p.start?.format(HM) ?: "all day")
            rows += Triple(at, label, p.title)
        }
        return rows.sortedBy { it.first }.take(limit).map { Event(it.second, it.third) }
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
