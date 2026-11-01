package app.cover.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// Page 5 ("out there"): swipe up from page 1. Gruvbox, four quarters: fuel, drive, music, progress.

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)

private fun ago(d: LocalDate): String = when (val n = ChronoUnit.DAYS.between(d, LocalDate.now())) {
    0L -> "today"
    1L -> "yesterday"
    else -> "$n days ago"
}

private fun agoShort(d: LocalDate): String = ChronoUnit.DAYS.between(d, LocalDate.now()).let { if (it <= 0) "now" else "${it}d" }

/** Turn-by-turn straight away, no route preview. On the cover screen when Maps is allowed there. */
fun navigate(act: Activity, lat: Double, lng: Double) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d")).setPackage("com.google.android.apps.maps")
    if (!Actions.startHere(act, i)) Toast.makeText(act, "google maps not found", Toast.LENGTH_SHORT).show()
}

private fun openUrl(act: Activity, url: String) {
    if (url.isBlank() || !Actions.startHere(act, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
        Toast.makeText(act, "couldn't open that", Toast.LENGTH_SHORT).show()
    }
}

/** Plays through Spotify's session if it allows, otherwise opens the release in Spotify. */
private fun play(act: Activity, uri: String) {
    val u = Uri.parse(uri)
    if (Media.playFromUri(act, Actions.SPOTIFY, u)) return
    if (!Actions.startHere(act, Intent(Intent.ACTION_VIEW, u).setPackage(Actions.SPOTIFY))) {
        Toast.makeText(act, "spotify not found", Toast.LENGTH_SHORT).show()
    }
}

/** Opens the album's own page in Spotify (no auto-play). Falls back to the web link if Spotify won't take the URI. */
private fun openAlbum(act: Activity, uri: String) {
    if (uri.isBlank()) {
        Toast.makeText(act, "no link for that one", Toast.LENGTH_SHORT).show(); return
    }
    if (Actions.startHere(act, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(Actions.SPOTIFY))) return
    // spotify:album:ID -> https://open.spotify.com/album/ID
    val web = uri.removePrefix("spotify:").replace(':', '/').let { "https://open.spotify.com/$it" }
    openUrl(act, web)
}

/** Fetches releases and gigs in the background; survives the page closing. MusicFeed decides when the next one is due. */
fun refreshMusic(act: MainActivity, prefs: Prefs, force: Boolean = false) {
    if (Out.musicBusy) return
    Out.musicBusy = true
    act.lifecycleScope.launch {
        try {
            Out.music = withContext(Dispatchers.IO) { MusicFeed.refresh(prefs, force) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Something unexpected: keep what's showing and wait before trying again, rather than every 20 s.
            val t = System.currentTimeMillis()
            Out.music = (Out.music ?: Music(emptyList(), emptyList(), null, t))
                .copy(error = "music: ${e.message ?: "refresh failed"}", next = t + Out.MUSIC_RETRY)
        } finally {
            Out.musicBusy = false
        }
    }
}

/** Doom news in the background, like [refreshMusic]. [force]: the refresh button. */
fun refreshNews(act: MainActivity, prefs: Prefs, force: Boolean = false) {
    if (DoomNews.busy || (!force && !DoomNews.due())) return
    DoomNews.busy = true
    act.lifecycleScope.launch {
        try {
            DoomNews.news = withContext(Dispatchers.IO) { DoomNews.refresh(prefs) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            DoomNews.busy = false
        }
    }
}

/** "now", "5h", "3d" since a headline went up. */
private fun agoAt(t: Instant): String {
    val mins = ChronoUnit.MINUTES.between(t, Instant.now())
    return when {
        mins < 60 -> "now"
        mins < 24 * 60 -> "${mins / 60}h"
        else -> "${mins / (24 * 60)}d"
    }
}

/** True when there's no music yet or the last refresh says another is due. */
private fun musicDue(t: Long): Boolean = Out.music.let { it == null || t >= it.next }

private data class Bar(val label: String, val fraction: Double, val color: Color)

private fun progress(now: LocalDateTime): List<Bar> {
    val day = now.toLocalTime().toSecondOfDay() / 86_400.0
    val week = (now.dayOfWeek.value - 1 + day) / 7.0
    val month = (now.dayOfMonth - 1 + day) / now.toLocalDate().lengthOfMonth()
    val year = (now.dayOfYear - 1 + day) / now.toLocalDate().lengthOfYear()
    return listOf(
        Bar("day", day, Gb.blue),
        Bar("week", week, Gb.aqua),
        Bar("month", month, Gb.yellow),
        Bar("year", year, Gb.orange),
    )
}

@Composable
private fun Quad(
    label: String,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    var m = modifier.fillMaxHeight().clip(shape).border(1.dp, Gb.bg1, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(horizontal = 8.dp, vertical = 6.dp)) {
        T(label, color = Gb.gray, size = 11, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
fun OutPage(
    act: MainActivity,
    prefs: Prefs,
    now: LocalDateTime,
    active: () -> Boolean,
    onHome: () -> Unit,
    onMusic: () -> Unit,
) {
    // Fuel every 30 min, the drive every 10, music twice a day; failures retry after a couple of minutes.
    LaunchedEffect(Unit) {
        if (Out.music == null) Out.music = withContext(Dispatchers.IO) { MusicFeed.cached(prefs) }
        while (true) {
            if (active()) {
                val t = System.currentTimeMillis()
                if (t - Out.fuelAt > Out.FUEL_EVERY) {
                    Out.fuelAt = t
                    val r = withContext(Dispatchers.IO) { runCatching { Fuel.load(prefs) } }
                    if (r.isFailure) Out.fuelAt = t - Out.FUEL_EVERY + 120_000L
                    if (r.isSuccess || Out.fuel?.isSuccess != true) Out.fuel = r
                }
                if (t - Out.driveAt > Out.DRIVE_EVERY) {
                    Out.driveAt = t
                    val r = withContext(Dispatchers.IO) { runCatching { Drive.load(prefs) } }
                    if (r.isFailure) Out.driveAt = t - Out.DRIVE_EVERY + 120_000L
                    if (r.isSuccess || Out.drive?.isSuccess != true) Out.drive = r
                }
                if (musicDue(t)) refreshMusic(act, prefs)
            }
            delay(20_000)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("~/out-there", color = Gb.gray, size = 11)
            Spacer(Modifier.weight(1f))
            T(now.format(HM), color = Gb.fg, size = 12)
            Spacer(Modifier.weight(1f))
            T("home ↓", Modifier.clickable(onClick = onHome).padding(vertical = 2.dp), color = Gb.gray, size = 11)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FuelQuad(act, Modifier.weight(1f))
            DriveQuad(act, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MusicQuad(act, Modifier.weight(1f), onMusic)
            ProgressQuad(now, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FuelQuad(act: Activity, modifier: Modifier) {
    val r = Out.fuel
    val info = r?.getOrNull()
    val best = info?.servos?.firstOrNull { it.best }
    Quad("fuel · u91", modifier, onClick = best?.let { b -> { navigate(act, b.lat, b.lng) } }) {
        when {
            r == null -> T("…", color = Gb.gray)
            info == null -> T(r.exceptionOrNull()?.message ?: "fuel unavailable", color = Gb.red, size = 11, maxLines = 3)
            info.servos.isEmpty() -> T("no u91 prices nearby", color = Gb.gray, size = 11)
            else -> info.servos.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { navigate(act, s.lat, s.lng) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(if (s.best) "★ " else "  ", color = Gb.green, size = 12)
                    T(f1(s.price), color = if (s.best) Gb.green else Gb.fg, size = 12)
                    if (s.cheapest && !s.best) T(" $", color = Gb.gray, size = 12)
                    Spacer(Modifier.weight(1f))
                    T(f1(s.km) + "km", color = Gb.gray, size = 11)
                }
            }
        }
        info?.trend?.let { tr ->
            Spacer(Modifier.weight(1f))
            Row {
                T("cycle ", color = Gb.gray, size = 11)
                when (tr) {
                    "rising" -> T("rising ↑", color = Gb.orange, size = 11)
                    "falling" -> T("falling ↓", color = Gb.green, size = 11)
                    "steady" -> T("steady", color = Gb.fg, size = 11)
                    else -> T("learning…", color = Gb.gray, size = 11)
                }
            }
        }
    }
}

@Composable
private fun DriveQuad(act: Activity, modifier: Modifier) {
    val r = Out.drive
    val d = r?.getOrNull()
    Quad("drive · work", modifier, onClick = d?.let { x -> { navigate(act, x.lat, x.lng) } }) {
        when {
            r == null -> T("…", color = Gb.gray)
            d == null -> T(r.exceptionOrNull()?.message ?: "drive unavailable", color = Gb.red, size = 11, maxLines = 3)
            else -> {
                val delta = d.minutes - d.usual
                Row(verticalAlignment = Alignment.Bottom) {
                    T("${d.minutes}", color = Gb.yellow, size = 20)
                    T(" min", color = Gb.fg, size = 12)
                    if (delta >= 1) T(" +$delta", color = if (delta >= 5) Gb.red else Gb.orange, size = 12)
                }
                T("usual ${d.usual}", color = Gb.gray, size = 11)
                if (d.crashes.isEmpty()) {
                    T("no crashes on route", color = Gb.gray, size = 11)
                } else {
                    d.crashes.take(2).forEach { T("▲ $it", color = Gb.red, size = 11, maxLines = 2) }
                }
            }
        }
    }
}

@Composable
private fun MusicQuad(act: Activity, modifier: Modifier, onMusic: () -> Unit) {
    val m = Out.music
    Quad("music ↗", modifier, onClick = onMusic) {
        if (m == null) {
            T(if (Out.musicBusy) "fetching…" else "…", color = Gb.gray, size = 11)
            return@Quad
        }
        val g = m.gigs.firstOrNull()
        if (g != null) {
            T("♪ gig · " + g.date.format(DAY).lowercase(), color = Gb.purple, size = 11, maxLines = 1)
            T(g.artist.lowercase(), color = Gb.fg, size = 12, maxLines = 1)
            T(g.venue.lowercase(), color = Gb.gray, size = 11, maxLines = 1)
        } else {
            T("no gigs listed", color = Gb.gray, size = 11)
        }
        Spacer(Modifier.height(4.dp))
        val rel = m.releases.firstOrNull()
        if (rel != null) {
            // Tapping the release goes straight to the album; the rest of the quarter still opens the music page.
            Column(Modifier.fillMaxWidth().clickable { openAlbum(act, rel.uri) }) {
                T("◉ new · " + ago(rel.date) + " ↗", color = Gb.aqua, size = 11, maxLines = 1)
                T("${rel.artist} — ${rel.title}".lowercase(), color = Gb.fg, size = 12, maxLines = 2)
            }
        } else if (m.pending > 0) {
            T("checking releases… ${m.pending} to go", color = Gb.gray, size = 11, maxLines = 1)
        } else {
            T("nothing new this month", color = Gb.gray, size = 11)
        }
        if (m.error != null && m.gigs.isEmpty() && m.releases.isEmpty()) {
            T(m.error, color = Gb.red, size = 11, maxLines = 2)
        }
    }
}

@Composable
private fun ProgressQuad(now: LocalDateTime, modifier: Modifier) {
    Quad("progress", modifier) {
        progress(now).forEach { b ->
            val k = (b.fraction * 8).roundToInt().coerceIn(0, 8)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                T(b.label.padEnd(6), color = Gb.gray, size = 11)
                T("█".repeat(k), color = b.color, size = 11)
                T("░".repeat(8 - k), color = Gb.bg2, size = 11)
                Spacer(Modifier.weight(1f))
                T("${(b.fraction * 100).toInt()}%", color = Gb.fg, size = 11)
            }
        }
    }
}

// ---------- music, full page ----------

@Composable
fun MusicScreen(act: MainActivity, prefs: Prefs, onBack: () -> Unit) {
    val m = Out.music
    LaunchedEffect(Unit) {
        if (Out.music == null) Out.music = withContext(Dispatchers.IO) { MusicFeed.cached(prefs) }
        if (DoomNews.news == null) DoomNews.news = withContext(Dispatchers.IO) { DoomNews.cached(prefs) }
        // Page 5's loop isn't running while this page is open, so keep the release batches going here.
        while (true) {
            if (act.isVisible && musicDue(System.currentTimeMillis())) refreshMusic(act, prefs)
            if (act.isVisible) refreshNews(act, prefs)
            delay(20_000)
        }
    }
    val news = DoomNews.news
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("❯ music", color = Gb.purple)
            Spacer(Modifier.weight(1f))
            if (Out.musicBusy || DoomNews.busy) {
                T("fetching…", Modifier.padding(6.dp), color = Gb.gray, size = 12)
            } else {
                T(
                    "refresh",
                    Modifier.clickable {
                        refreshMusic(act, prefs, force = true)
                        refreshNews(act, prefs, force = true)
                    }.padding(6.dp),
                    color = Gb.aqua, size = 12,
                )
            }
            T("esc", Modifier.clickable(onClick = onBack).padding(6.dp), color = Gb.gray, size = 12)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { T("gigs · qld", color = Gb.gray, size = 11) }
            if (m == null || m.gigs.isEmpty()) {
                item { T("none listed on ticketmaster", color = Gb.gray, size = 12) }
            } else {
                items(m.gigs) { g ->
                    Column(Modifier.fillMaxWidth().clickable { openUrl(act, g.url) }.padding(vertical = 3.dp)) {
                        Row {
                            T(g.date.format(DAY).lowercase().padEnd(11), color = Gb.yellow, size = 12)
                            T(g.artist.lowercase(), color = Gb.fg, size = 12, maxLines = 1)
                        }
                        T(g.venue.lowercase() + "  tickets ↗", color = Gb.gray, size = 11, maxLines = 1)
                    }
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    T("new · last 30 days", color = Gb.gray, size = 11)
                }
            }
            if (m == null || m.releases.isEmpty()) {
                // Only say "nothing new" once every artist has actually been checked.
                val msg = when {
                    m == null -> "not checked yet"
                    m.pending > 0 -> "still checking · ${m.pending} artists to go"
                    else -> "nothing new from artists you follow"
                }
                item { T(msg, color = Gb.gray, size = 12) }
            } else {
                items(m.releases) { r ->
                    Row(Modifier.fillMaxWidth().clickable { openAlbum(act, r.uri) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(agoShort(r.date).padEnd(5), color = Gb.aqua, size = 12)
                        Column(Modifier.weight(1f)) {
                            T(r.title.lowercase(), color = Gb.fg, size = 12, maxLines = 1)
                            T(r.artist.lowercase() + if (r.single) " · single" else "", color = Gb.gray, size = 11, maxLines = 1)
                        }
                        // ▶ keeps the old behaviour: play it without leaving cover.
                        T("▶", Modifier.clickable { play(act, r.uri) }.padding(horizontal = 8.dp, vertical = 4.dp), color = Gb.green, size = 12)
                    }
                }
                if (m.pending > 0) item { T("${m.pending} artists still to check", color = Gb.gray, size = 11) }
            }

            // ----- doom news -----
            item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    T("news · doom", color = Gb.gray, size = 11)
                }
            }
            if (news == null || news.items.isEmpty()) {
                item {
                    T(
                        when {
                            DoomNews.busy -> "reading feeds…"
                            news == null -> "not fetched yet"
                            else -> "nothing in the last two weeks"
                        },
                        color = Gb.gray, size = 12,
                    )
                }
            } else {
                items(news.items, key = { "n:" + it.link }) { h ->
                    Row(Modifier.fillMaxWidth().clickable { openUrl(act, h.link) }.padding(vertical = 3.dp)) {
                        T(agoAt(h.at).padEnd(5), color = Gb.aqua, size = 12)
                        Column(Modifier.weight(1f)) {
                            T(
                                (if (h.star) "★ " else "") + h.title.lowercase(),
                                color = if (h.star) Gb.yellow else Gb.fg, size = 12, maxLines = 2,
                            )
                            T(h.source + "  ↗", color = Gb.gray, size = 11, maxLines = 1)
                        }
                    }
                }
            }
            if (!news?.dead.isNullOrEmpty()) {
                item { T("no answer: " + news!!.dead.joinToString(", "), color = Gb.gray, size = 11, maxLines = 2) }
            }

            item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    m?.error?.let { T(it, color = Gb.red, size = 11) }
                    val at = m?.at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().format(HM) }
                    T("gigs: ticketmaster listings only" + (at?.let { " · updated $it" } ?: ""), color = Gb.gray, size = 11)
                }
            }
        }
    }
}

// ---------- setup, shown inside cfg ----------

@Composable
private fun KeyRow(prefs: Prefs, key: String, label: String, placeholder: String, secret: Boolean = true, onSaved: () -> Unit = {}) {
    var saved by remember { mutableStateOf(prefs.get(key)) }
    var v by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(label.padEnd(11), size = 12)
        when {
            saved == null -> T("not set", color = Tn.dim, size = 12)
            secret -> T("saved", color = Tn.green, size = 12)
            else -> T(saved!!, color = Tn.green, size = 12, maxLines = 1)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(v, { v = it }, placeholder, Modifier.weight(1f), secret = secret)
        Btn("save", Tn.blue) {
            if (v.isNotBlank()) {
                prefs.put(key to v.trim())
                saved = v.trim()
                v = ""
                onSaved()
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
fun OutTile(prefs: Prefs, tick: Int) {
    val ctx = LocalContext.current
    // Re-checked on resume, so it flips to connected when Spotify sends you back.
    var connected by remember(tick) { mutableStateOf(Spotify.connected(prefs)) }
    var status by remember { mutableStateOf<String?>(null) }
    Tile(label = "page 5 · out there") {
        T("each service gives a free key; see the readme for where.", color = Tn.dim, size = 11)
        Spacer(Modifier.height(4.dp))
        KeyRow(prefs, Prefs.FUEL_TOKEN, "fuel", "subscriber token") { Out.fuelAt = 0L }
        KeyRow(prefs, Prefs.TOMTOM_KEY, "tomtom", "api key") { Out.driveAt = 0L }
        KeyRow(prefs, Prefs.WORK_ADDR, "work", "street, suburb", secret = false) { Out.driveAt = 0L }
        KeyRow(prefs, Prefs.QLDT_KEY, "qldtraffic", "optional, own key") { Out.driveAt = 0L }
        KeyRow(prefs, Prefs.TM_KEY, "ticketmstr", "consumer key")
        KeyRow(prefs, Prefs.SP_CLIENT, "spotify id", "client id", secret = false)
        NewsFeedsRow(prefs)
        T("spotify redirect uri: ${Spotify.REDIRECT}", color = Tn.dim, size = 11)
        Spacer(Modifier.height(4.dp))
        val signIn = {
            status = runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Spotify.authUrl(prefs)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            }.getOrElse { it.message }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (connected) {
                T("spotify connected", color = Tn.green, size = 12)
                Btn("disconnect", Tn.red) {
                    Spotify.disconnect(prefs)
                    connected = false
                }
            } else {
                Btn("connect spotify", Tn.green, signIn)
            }
        }
        status?.let { T(it, color = Tn.red, size = 11) }
        if (connected) {
            if (Spotify.hasScopes(prefs)) {
                FollowTool(prefs)
            } else {
                Spacer(Modifier.height(4.dp))
                T("reconnect once to allow top artists and following a playlist's artists.", color = Tn.yellow, size = 11)
                Spacer(Modifier.height(4.dp))
                Btn("reconnect spotify", Tn.yellow, signIn)
            }
        }
    }
}

/** The music page's news feeds, one per line ("name | url"). Clearing the box goes back to the defaults. */
@Composable
private fun NewsFeedsRow(prefs: Prefs) {
    var text by remember { mutableStateOf(DoomNews.feedsText(prefs)) }
    var msg by remember { mutableStateOf<String?>(null) }
    T("news feeds · one per line, name | url", color = Tn.dim, size = 11)
    Field(text, { text = it; msg = null }, "name | https://…/feed/", Modifier.fillMaxWidth(), maxLines = 8)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn("save", Tn.blue) {
            prefs.put(DoomNews.FEEDS_KEY to text.trim().ifEmpty { null })
            text = DoomNews.feedsText(prefs)
            DoomNews.news = DoomNews.news?.copy(next = 0L)
            msg = "saved · ${DoomNews.feeds(prefs).size} feeds, fetched next time music opens"
        }
        Btn("defaults", Tn.dim) {
            prefs.put(DoomNews.FEEDS_KEY to null)
            text = DoomNews.feedsText(prefs)
            DoomNews.news = DoomNews.news?.copy(next = 0L)
            msg = "back to the default feeds"
        }
    }
    msg?.let { T(it, color = Tn.green, size = 11) }
    Spacer(Modifier.height(4.dp))
}

/** Follow every main artist in a playlist: scan, show the count, follow on a second tap. */
@Composable
private fun FollowTool(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(Actions.PLAYLIST) }
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Spotify.FollowPlan?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }

    Spacer(Modifier.height(6.dp))
    T("follow every artist in a playlist (yours or collaborative)", color = Tn.dim, size = 11)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(link, { link = it; plan = null }, "playlist link", Modifier.weight(1f))
        Btn(if (busy) "…" else "scan", Tn.blue) {
            if (busy) return@Btn
            busy = true
            plan = null
            bad = false
            msg = "reading playlist…"
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Spotify.planFollow(prefs, link) } }
                busy = false
                r.onSuccess {
                    plan = it
                    msg = "${it.all.size} artists · ${it.fresh.size} not followed yet"
                }.onFailure {
                    bad = true
                    msg = it.message ?: "couldn't read that playlist"
                }
            }
        }
    }
    msg?.let { T(it, color = if (bad) Tn.red else Tn.fg, size = 11) }
    plan?.takeIf { it.fresh.isNotEmpty() }?.let { p ->
        T(
            p.fresh.take(6).joinToString(", ") { it.second.lowercase() } + if (p.fresh.size > 6) ", …" else "",
            color = Tn.dim, size = 11, maxLines = 2,
        )
        Spacer(Modifier.height(4.dp))
        Btn(if (busy) "following…" else "follow ${p.fresh.size}", Tn.green) {
            if (busy) return@Btn
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Spotify.follow(prefs, p.fresh.map { it.first }) } }
                busy = false
                r.onSuccess { n ->
                    plan = null
                    bad = false
                    msg = "followed $n · music page is refreshing"
                    (ctx as? MainActivity)?.let { refreshMusic(it, prefs) }
                }.onFailure {
                    bad = true
                    msg = it.message ?: "spotify refused the follow"
                }
            }
        }
    }
}
