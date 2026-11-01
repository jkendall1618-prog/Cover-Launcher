package app.cover.launcher

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A cover one-liner such as "fri 6pm dinner w jac +jac @the res", read into an event
 * before it is saved. Nothing here touches the network, so the preview updates as you type.
 */
data class Draft(
    val date: LocalDate?,
    val start: LocalTime?,
    val minutes: Long,
    val allDay: Boolean,
    val title: String,
    val location: String?,
    /** "+tags" as typed, lower case, without the plus. */
    val who: List<String>,
    /** Homebase's reminder setting ("none", "at", "10m", "30m", "1h", "am", "1d"), from "!30m". */
    val remind: String = "none",
) {
    val problem: String?
        get() = when {
            title.isBlank() -> "needs a title"
            !allDay && start == null -> "add a time, or 'all day'"
            date == null -> "add a day"
            else -> null
        }

    /** "fri 6 nov  18:00–19:00" or "thu 5 nov  all day". */
    fun whenText(): String {
        val d = date?.format(DAY)?.lowercase() ?: "—"
        val t = when {
            allDay -> "all day"
            start == null -> "--:--"
            else -> start.format(HM) + "–" + start.plusMinutes(minutes).format(HM)
        }
        return "$d  $t"
    }

    companion object {
        val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}

object QuickAdd {
    /** The reminder choices Homebase's scheduler understands. */
    val REMINDS = listOf("none", "at", "10m", "30m", "1h", "am", "1d")

    private val DAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )
    private val TIME = Regex("""^(\d{1,2})(?:[:.]?(\d{2}))?(am|pm|a|p)?$""")
    private val DMY = Regex("""^(\d{1,2})/(\d{1,2})$""")
    private val DUR = Regex("""^(\d+(?:\.\d+)?)(h|hr|hrs|m|min|mins)$""")
    private val SUFFIX = Regex("""(am|pm|a|p)$""")

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): Draft {
        val today = now.toLocalDate()
        val toks = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var date: LocalDate? = null
        var start: LocalTime? = null
        var end: LocalTime? = null
        var minutes: Long? = null
        var allDay = false
        var remind = "none"
        val who = mutableListOf<String>()
        val title = mutableListOf<String>()
        val place = mutableListOf<String>()
        var inPlace = false

        var i = 0
        while (i < toks.size) {
            val raw = toks[i]
            val t = raw.lowercase()
            i++

            if (t.length > 1 && t[0] == '+' && t.drop(1).all { it.isLetter() }) {
                val tag = t.drop(1)
                if (tag !in who) who += tag
                continue
            }
            if (t.length > 1 && t[0] == '!' && t.drop(1) in REMINDS) {
                remind = t.drop(1)
                continue
            }
            if (t[0] == '@') {
                inPlace = true
                if (raw.length > 1) place += raw.drop(1)
                continue
            }
            if (inPlace) {
                place += raw
                continue
            }
            if (date == null) {
                val d = when (t) {
                    // No "tom": "dinner w tom" is a person, not tomorrow.
                    "today", "tonight" -> today
                    "tmrw", "tomorrow", "tmr" -> today.plusDays(1)
                    else -> DAYS[t]?.let { next(today, it) } ?: dayMonth(t, today)
                }
                if (d != null) {
                    date = d
                    continue
                }
            }
            if (!allDay && start == null) {
                if (t == "allday" || (t == "all" && toks.getOrNull(i)?.lowercase() == "day")) {
                    allDay = true
                    if (t == "all") i++
                    continue
                }
                val range = range(t)
                if (range != null) {
                    start = range.first
                    end = range.second
                    continue
                }
                val one = when (t) {
                    "noon", "midday" -> LocalTime.NOON
                    else -> time(t)
                }
                if (one != null) {
                    start = one
                    continue
                }
            }
            if (start != null && end == null && minutes == null) {
                val m = DUR.matchEntire(t)
                if (m != null) {
                    val n = m.groupValues[1].toDouble()
                    minutes = Math.round(if (m.groupValues[2].startsWith("h")) n * 60 else n)
                    continue
                }
            }
            title += raw
        }

        // No day given: today if the time is still ahead, otherwise tomorrow.
        if (date == null) {
            date = when {
                allDay -> today
                start != null -> if (start.isAfter(now.toLocalTime())) today else today.plusDays(1)
                else -> null
            }
        }
        val length = when {
            end != null && start != null -> {
                var m = java.time.Duration.between(start, end).toMinutes()
                if (m <= 0) m += 24 * 60
                m
            }
            minutes != null && minutes > 0 -> minutes
            else -> 60L
        }
        return Draft(
            date = date,
            start = if (allDay) null else start,
            minutes = length,
            allDay = allDay,
            title = title.joinToString(" "),
            location = place.joinToString(" ").ifBlank { null },
            who = who,
            remind = remind,
        )
    }

    /** The next one of that weekday after today: "fri" on a Friday means next week ("today" means today). */
    private fun next(today: LocalDate, day: DayOfWeek): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek != day) d = d.plusDays(1)
        return d
    }

    /** "6/11" is the 6th of November (Australian order), next year if it has passed. */
    private fun dayMonth(t: String, today: LocalDate): LocalDate? {
        val m = DMY.matchEntire(t) ?: return null
        val d = runCatching { LocalDate.of(today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
            ?: return null
        return if (d.isBefore(today)) d.plusYears(1) else d
    }

    /** "6pm", "6:30pm", "6.30pm", "18:00", "1830". A bare "6" is a word, not a time. */
    fun time(t: String): LocalTime? {
        val m = TIME.matchEntire(t) ?: return null
        var h = m.groupValues[1].toInt()
        val hasMin = m.groupValues[2].isNotEmpty()
        val mi = if (hasMin) m.groupValues[2].toInt() else 0
        val ap = m.groupValues[3]
        if (ap.isEmpty() && !hasMin) return null
        if (mi > 59) return null
        if (ap.isNotEmpty()) {
            if (h !in 1..12) return null
            if (ap[0] == 'p' && h < 12) h += 12
            if (ap[0] == 'a' && h == 12) h = 0
        } else if (h > 23) return null
        return LocalTime.of(h, mi)
    }

    /** "6-8pm", "6:30-8pm", "11am-1pm", "18:00-20:00". The first half borrows the second's am/pm. */
    private fun range(t: String): Pair<LocalTime, LocalTime>? {
        val parts = t.split('-')
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        val b = time(parts[1]) ?: return null
        var a = time(parts[0])
        if (a == null) {
            val suffix = SUFFIX.find(parts[1])?.value ?: return null
            a = time(parts[0] + suffix) ?: return null
            // "11-1pm" means 11am to 1pm, not 11pm.
            if (a.isAfter(b)) a = a.minusHours(12)
        }
        return a to b
    }

    /**
     * Turns "+jac", "+heidi", "+me", "+fam" into the keys Homebase stores in for_whom: a
     * member's user id, "p:<id>" for someone without a login, or "family". A tag matches
     * the start of a name ("jac" → Jacqui). Unknown tags come back so the screen can say so.
     */
    fun whom(tags: List<String>, household: List<Whom>, me: String?): Pair<List<String>, List<String>> {
        val keys = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (tag in tags) {
            val key = when (tag) {
                "fam", "family", "all", "everyone" -> "family"
                "me" -> me
                else -> household.firstOrNull { it.name.equals(tag, ignoreCase = true) }?.key
                    ?: household.filter { it.name.startsWith(tag, ignoreCase = true) }.singleOrNull()?.key
            }
            if (key == null) unknown += tag else if (key !in keys) keys += key
        }
        // Family means everyone, so it wins over individual names.
        return (if ("family" in keys || keys.isEmpty()) listOf("family") else keys) to unknown
    }
}

/** Someone an event can be for: a member (user id), a person without a login ("p:<id>"). */
data class Whom(val key: String, val name: String)
