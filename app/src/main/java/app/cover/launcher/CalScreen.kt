package app.cover.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Add to the Homebase calendar from the cover: type a one-liner ("fri 6pm dinner w jac")
 * and watch how it reads before you send it, or flip to the form and tap it together.
 * Saves straight to Homebase as you, so it reaches Jacqui's phone and the reminders as normal.
 */
@Composable
fun CalScreen(hb: Homebase, focus: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var household by remember { mutableStateOf(emptyList<Whom>()) }
    var agenda by remember { mutableStateOf<List<Event>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusBad by remember { mutableStateOf(false) }
    var lastId by remember { mutableStateOf<String?>(null) }
    val focusLine = remember { FocusRequester() }

    // The form's own state, starting on the next whole hour today.
    val firstHour = remember { LocalTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1) }
    var fTitle by remember { mutableStateOf("") }
    var fDay by remember { mutableStateOf(0) }
    var fStart by remember { mutableStateOf(firstHour) }
    var fLen by remember { mutableStateOf(60L) }
    var fAllDay by remember { mutableStateOf(false) }
    var fWho by remember { mutableStateOf(listOf("family")) }
    var fRemind by remember { mutableStateOf("none") }

    fun loadAgenda() {
        scope.launch { agenda = withContext(Dispatchers.IO) { CalendarRepo.upcoming(ctx, 6) } }
    }
    LaunchedEffect(Unit) {
        loadAgenda()
        if (hb.signedIn) {
            household = withContext(Dispatchers.IO) { runCatching { hb.household() }.getOrDefault(emptyList()) }
        }
    }
    LaunchedEffect(form) {
        if (!form && focus && hb.signedIn) runCatching { focusLine.requestFocus() }
    }

    fun save(title: String, date: LocalDate, start: LocalTime?, minutes: Long, allDay: Boolean, place: String?, who: List<String>, remind: String) {
        if (busy) return
        busy = true
        statusBad = false
        status = "adding…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { hb.addEvent(title, date, start, minutes, allDay, place, who, remind) }
            }
            busy = false
            r.onSuccess { id ->
                Pending.add(Pending.Item(id, date, if (allDay) null else start, title.trim(), System.currentTimeMillis()))
                CalendarFeed.invalidate()
                lastId = id
                status = "✓ added  " + CalendarRepo.day(date, LocalDate.now()).trim() + " " +
                    (if (allDay || start == null) "all day" else start.format(Draft.HM)) + "  " + title.trim()
                line = ""
                fTitle = ""
                loadAgenda()
            }.onFailure {
                statusBad = true
                status = it.message ?: "couldn't add"
            }
        }
    }

    fun undo() {
        val id = lastId ?: return
        lastId = null
        statusBad = false
        status = "undoing…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { hb.deleteEvent(id) } }
            r.onSuccess {
                Pending.remove(id)
                CalendarFeed.invalidate()
                status = "undone"
                loadAgenda()
            }.onFailure {
                statusBad = true
                status = it.message ?: "couldn't undo, fix it in homebase"
            }
        }
    }

    val draft = remember(line) { QuickAdd.parse(line, LocalDateTime.now()) }
    val (whoKeys, whoUnknown) = remember(draft, household) { QuickAdd.whom(draft.who, household, hb.me) }

    fun addLine() {
        if (line.isBlank() || draft.problem != null) return
        val date = draft.date ?: return
        save(draft.title, date, draft.start, draft.minutes, draft.allDay, draft.location, whoKeys, draft.remind)
    }

    fun names(keys: List<String>): String =
        if ("family" in keys) "family"
        else keys.joinToString(", ") { k ->
            if (k == hb.me) "me" else household.firstOrNull { it.key == k }?.name?.lowercase() ?: "?"
        }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("cal", color = Tn.blue)
            T("  homebase", color = Tn.dim, size = 12)
            Spacer(Modifier.weight(1f))
            if (hb.signedIn) {
                T(if (form) "line" else "form ⇥", Modifier.clickable { form = !form }.padding(6.dp), color = Tn.blue, size = 12)
            }
            T("esc", Modifier.clickable(onClick = onBack).padding(6.dp), color = Tn.dim, size = 12)
        }

        if (!hb.signedIn) {
            T("sign in to homebase in cfg to add events", color = Tn.dim)
        } else if (!form) {
            // ---------- one-liner ----------
            val shape = RoundedCornerShape(6.dp)
            Row(
                Modifier.fillMaxWidth().border(1.dp, Tn.blue, shape).padding(horizontal = 9.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("❯ ", color = Tn.orange)
                BasicTextField(
                    value = line,
                    onValueChange = { line = it },
                    modifier = Modifier.weight(1f).focusRequester(focusLine),
                    textStyle = Body,
                    cursorBrush = SolidColor(Tn.orange),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onAny = { addLine() }),
                    decorationBox = { inner ->
                        Box {
                            if (line.isEmpty()) T("fri 6pm dinner w jac", color = Tn.dim)
                            inner()
                        }
                    },
                )
            }
            if (line.isNotBlank()) {
                Tile {
                    T(draft.whenText(), color = Tn.blue, maxLines = 1)
                    T(draft.title.ifBlank { "(title)" }, color = if (draft.title.isBlank()) Tn.dim else Tn.fg, maxLines = 1)
                    val meta = buildList {
                        add("for " + names(whoKeys))
                        draft.location?.let { add("@ $it") }
                        if (draft.remind != "none") add("ping ${draft.remind}")
                    }.joinToString(" · ")
                    T(meta, color = Tn.dim, size = 11, maxLines = 1)
                    if (whoUnknown.isNotEmpty()) {
                        T("no " + whoUnknown.joinToString(", ") { "'$it'" } + " in homebase yet", color = Tn.yellow, size = 11, maxLines = 1)
                    }
                    draft.problem?.let { T(it, color = Tn.yellow, size = 11) }
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Btn(if (busy) "adding…" else "↵ add", if (draft.problem == null) Tn.blue else Tn.dim) { addLine() }
                    }
                }
            }
        } else {
            // ---------- form ----------
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowLabel("what")
                    Field(fTitle, { fTitle = it }, "dinner w jac", Modifier.weight(1f), ime = ImeAction.Done)
                }
                val today = LocalDate.now()
                val dayLabels = (0..7).map { i ->
                    when (i) {
                        0 -> "today"
                        1 -> "tmrw"
                        else -> today.plusDays(i.toLong()).format(DOW).lowercase()
                    }
                }
                ChipRows("day", dayLabels.mapIndexed { i, l -> l to (fDay == i) }) { fDay = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowLabel("at")
                    Btn("−", if (fAllDay) Tn.dim else Tn.blue) { if (!fAllDay) fStart = fStart.minusMinutes(15) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        T(
                            if (fAllDay) "all day" else fStart.format(Draft.HM) + " – " + fStart.plusMinutes(fLen).format(Draft.HM),
                            color = if (fAllDay) Tn.dim else Tn.blue,
                        )
                    }
                    Btn("+", if (fAllDay) Tn.dim else Tn.blue) { if (!fAllDay) fStart = fStart.plusMinutes(15) }
                }
                val lens = listOf(30L to "30m", 60L to "1h", 120L to "2h", -1L to "all day")
                ChipRows("for", lens.map { (m, l) -> l to (if (m < 0) fAllDay else !fAllDay && fLen == m) }) { i ->
                    val m = lens[i].first
                    if (m < 0) fAllDay = true else {
                        fAllDay = false
                        fLen = m
                    }
                }
                val people = listOf(Whom("family", "family")) + household
                ChipRows(
                    "who",
                    people.map { p -> (if (p.key == hb.me) "me" else p.name.lowercase()) to (p.key in fWho) },
                ) { i ->
                    val k = people[i].key
                    fWho = if (k == "family") listOf("family") else {
                        val rest = fWho.filter { it != "family" }
                        val next = if (k in rest) rest - k else rest + k
                        next.ifEmpty { listOf("family") }
                    }
                }
                val pings = listOf("none", "30m", "1h", "1d")
                ChipRows("ping", pings.map { it to (fRemind == it) }) { fRemind = pings[it] }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(
                        dayLabels[fDay] + " " + (if (fAllDay) "all day" else fStart.format(Draft.HM)) + " · " + names(fWho),
                        Modifier.weight(1f), color = Tn.dim, size = 11, maxLines = 1,
                    )
                    Btn(if (busy) "adding…" else "↵ add", if (fTitle.isNotBlank()) Tn.blue else Tn.dim) {
                        if (fTitle.isNotBlank()) {
                            save(fTitle, today.plusDays(fDay.toLong()), if (fAllDay) null else fStart, fLen, fAllDay, null, fWho, fRemind)
                        }
                    }
                }
            }
        }

        status?.let { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T(s, Modifier.weight(1f), color = if (statusBad) Tn.red else Tn.green, maxLines = 2)
                if (lastId != null && !busy) {
                    T("undo", Modifier.clickable { undo() }.padding(horizontal = 8.dp, vertical = 4.dp), color = Tn.dim, size = 11)
                }
            }
        }

        if (!form) {
            Tile(label = "next 7 days") {
                val list = agenda
                when {
                    list == null -> T("loading…", color = Tn.dim)
                    list.isEmpty() -> T(if (CalendarRepo.hasAccess(ctx)) "nothing this week" else "grant calendar in cfg", color = Tn.dim)
                    else -> list.forEach { e ->
                        Row {
                            T(e.whenLabel.trimEnd().padEnd(11), color = Tn.blue)
                            T(e.title, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (hb.signedIn) {
                T("+jac +heidi +fam · @place · 2h · 6-8pm · all day · !30m · 6/11", color = Tn.dim, size = 11)
            }
        }
    }
}

private val DOW = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

@Composable
private fun RowLabel(text: String) {
    T(text, Modifier.width(40.dp), color = Tn.dim, size = 11)
}

/** A label then tappable chips, four to a row. */
@Composable
private fun ChipRows(label: String, chips: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row {
        Box(Modifier.width(40.dp).padding(top = 7.dp)) { T(label, color = Tn.dim, size = 11) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            chips.withIndex().chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { (i, chip) ->
                        Chip(chip.first, chip.second, Modifier.weight(1f)) { onPick(i) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val look = if (on) Modifier.background(Tn.blue) else Modifier.border(1.dp, Tn.border, shape)
    Box(
        modifier.height(32.dp).clip(shape).then(look).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        T(label, color = if (on) Tn.bg else Tn.fg, size = 12, maxLines = 1)
    }
}
