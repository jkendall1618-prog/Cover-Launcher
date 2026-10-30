package app.cover.launcher

import android.content.Intent
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Screen { HOME, CHAT, SETTINGS }

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    /** Screen we were last seen on. Screen 0 is the big inner display; the cover is a separate one. */
    private var lastDisplay = Display.INVALID_DISPLAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If Samsung recreated us during the unfold, remember where we were before.
        lastDisplay = savedInstanceState?.getInt(KEY_DISPLAY, Display.INVALID_DISPLAY) ?: Display.INVALID_DISPLAY
        if (closeIfOnInnerScreen(intent)) return
        setContent { CoverApp(this, resumeTick.intValue) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Icon tapped on purpose (not the takeover): wherever we are now is where you want us.
        if (!intent.getBooleanExtra(EXTRA_TAKEOVER, false)) {
            lastDisplay = Display.INVALID_DISPLAY
            return
        }
        closeIfOnInnerScreen(intent)
    }

    override fun onResume() {
        super.onResume()
        if (closeIfOnInnerScreen(null)) return
        resumeTick.intValue++
    }

    // Unfolding moves us to the inner display; we handle screen-size changes ourselves, so this fires instead of a restart.
    // Only while in front; in the background, onResume makes the call once we know how we got here.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isVisible) closeIfOnInnerScreen(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DISPLAY, currentDisplayId())
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayId(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= 30) display?.displayId else windowManager.defaultDisplay.displayId
    }.getOrNull() ?: Display.INVALID_DISPLAY

    /**
     * Cover belongs on the cover screen. Close (and drop out of recents) when we land on the
     * inner screen because the phone was unfolded, or because the takeover put us there.
     * Opening cover from the main-screen app drawer on purpose still works, so cfg stays reachable.
     */
    private fun closeIfOnInnerScreen(launch: Intent?): Boolean {
        if (isFinishing) return true
        val now = currentDisplayId()
        if (now == Display.INVALID_DISPLAY) return false
        val movedInside = lastDisplay != Display.INVALID_DISPLAY && lastDisplay != Display.DEFAULT_DISPLAY
        val fromTakeover = launch?.getBooleanExtra(EXTRA_TAKEOVER, false) == true
        if (now == Display.DEFAULT_DISPLAY && (movedInside || fromTakeover)) {
            finishAndRemoveTask()
            return true
        }
        lastDisplay = now
        return false
    }

    val isVisible get() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    companion object {
        const val EXTRA_TAKEOVER = "app.cover.launcher.TAKEOVER"
        private const val KEY_DISPLAY = "display"
    }
}

@Composable
fun CoverApp(act: MainActivity, tick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val hb = remember { Homebase(prefs) }
    val claude = remember { Claude(prefs) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.HOME) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<Entry>()) }
    var events by remember { mutableStateOf(emptyList<Event>()) }
    var tasks by remember { mutableStateOf(emptyList<Task>()) }
    var taskError by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<NowPlaying?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var battery by remember { mutableIntStateOf(0) }
    val chat = remember { mutableStateListOf<Turn>() }

    fun refresh() {
        scope.launch {
            apps = withContext(Dispatchers.IO) { Apps.load(ctx) }
            events = withContext(Dispatchers.IO) { CalendarRepo.upcoming(ctx) }
            val r = withContext(Dispatchers.IO) { runCatching { hb.openTasks() } }
            tasks = r.getOrDefault(emptyList())
            taskError = r.exceptionOrNull()?.message
        }
    }

    fun open(e: Entry) {
        query = ""
        when (e.builtin) {
            Builtin.ASK -> screen = Screen.CHAT
            Builtin.SETTINGS -> screen = Screen.SETTINGS
            null -> Apps.launch(act, e)
        }
    }

    LaunchedEffect(tick) {
        query = ""
        refresh()
    }

    // Clock, battery and now-playing, only while the launcher is on screen.
    LaunchedEffect(Unit) {
        while (true) {
            if (act.isVisible) {
                now = LocalDateTime.now()
                battery = ctx.getSystemService(BatteryManager::class.java)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
                media = withContext(Dispatchers.IO) { runCatching { Media.now(ctx) }.getOrNull() }
            }
            delay(1500)
        }
    }

    // Auto-launch as soon as the letters narrow it to one thing.
    LaunchedEffect(query) {
        if (query.isNotEmpty()) {
            val m = Apps.match(apps, query)
            if (m.size == 1) {
                delay(180)
                open(m[0])
            }
        }
    }

    BackHandler(enabled = screen != Screen.HOME || query.isNotEmpty()) {
        if (screen != Screen.HOME) screen = Screen.HOME else query = ""
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Tn.bg)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        when (screen) {
            Screen.CHAT -> ChatScreen(claude, chat, onBack = { screen = Screen.HOME })
            Screen.SETTINGS -> SettingsScreen(prefs, hb, claude, tick, onBack = {
                screen = Screen.HOME
                refresh()
            })
            Screen.HOME -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBar(now, battery, onCfg = { screen = Screen.SETTINGS })
                if (query.isEmpty()) {
                    CalTile(events, CalendarRepo.hasAccess(ctx))
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TodoTile(
                            tasks, hb.signedIn, taskError,
                            Modifier.weight(1f).fillMaxHeight(),
                            onDone = { t ->
                                tasks = tasks - t
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { hb.complete(t) } }
                                    r.exceptionOrNull()?.let { taskError = it.message; refresh() }
                                }
                            },
                        )
                        PlayTile(media, Media.hasAccess(ctx), Modifier.weight(1f).fillMaxHeight())
                    }
                    Tile(onClick = { screen = Screen.CHAT }) {
                        Row {
                            T("❯ ", color = Tn.orange)
                            T("ask claude")
                            T("_", color = Tn.dim)
                        }
                    }
                } else {
                    MatchTile(Apps.match(apps, query, 5), onOpen = ::open)
                }
                LauncherTile(
                    query,
                    top = Apps.match(apps, query, 1).firstOrNull(),
                    modifier = Modifier.weight(1f),
                    onKey = { k ->
                        when (k) {
                            "⌫" -> query = query.dropLast(1)
                            "⏎" -> Apps.match(apps, query, 1).firstOrNull()?.let(::open)
                            else -> query += k
                        }
                    },
                    onClear = { query = "" },
                )
            }
        }
    }
}

// ---------- building blocks ----------

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Tn.fg,
    size: Int = 13,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text,
        modifier,
        style = Body.copy(color = color, fontSize = size.sp, lineHeight = (size + 5).sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Tile(
    modifier: Modifier = Modifier,
    label: String? = null,
    accent: Color = Tn.border,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    var m = modifier.fillMaxWidth().clip(shape).border(1.dp, accent, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(horizontal = 9.dp, vertical = 6.dp)) {
        if (label != null) T(label, color = Tn.dim, size = 11)
        content()
    }
}

@Composable
fun StatusBar(now: LocalDateTime, battery: Int, onCfg: () -> Unit) {
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH) }
    val blocks = (battery + 12) / 25
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        T("● cfg", Modifier.clickable(onClick = onCfg).padding(vertical = 2.dp), color = Tn.blue, size = 11)
        Spacer(Modifier.weight(1f))
        T(now.format(fmt).lowercase(), size = 12)
        Spacer(Modifier.weight(1f))
        T("▮".repeat(blocks.coerceIn(0, 4)) + "▯".repeat((4 - blocks).coerceIn(0, 4)) + " $battery%", color = Tn.dim, size = 11)
    }
}

@Composable
fun CalTile(events: List<Event>, hasAccess: Boolean) {
    Tile(label = "cal", accent = Tn.blue) {
        when {
            !hasAccess -> T("grant calendar in cfg", color = Tn.dim)
            events.isEmpty() -> T("nothing this week", color = Tn.dim)
            else -> events.forEach { e ->
                Row {
                    T(e.whenLabel.trimEnd().padEnd(11), color = Tn.blue)
                    T(e.title, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun TodoTile(
    tasks: List<Task>,
    signedIn: Boolean,
    error: String?,
    modifier: Modifier,
    onDone: (Task) -> Unit,
) {
    val today = LocalDate.now()
    Tile(modifier, label = "todo") {
        when {
            !signedIn -> T("sign in to homebase in cfg", color = Tn.dim)
            error != null && tasks.isEmpty() -> T(error, color = Tn.red, maxLines = 2)
            tasks.isEmpty() -> T("all clear", color = Tn.green)
            else -> tasks.take(3).forEach { t ->
                val mark = when {
                    t.due == null -> Tn.green
                    t.due.isBefore(today) -> Tn.red
                    t.due == today -> Tn.yellow
                    else -> Tn.green
                }
                Row(Modifier.fillMaxWidth().clickable { onDone(t) }.padding(vertical = 1.dp)) {
                    T("[ ] ", color = mark)
                    T(t.title.lowercase(), maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun PlayTile(np: NowPlaying?, hasAccess: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    Tile(modifier, label = "play") {
        when {
            !hasAccess -> T("allow media in cfg", color = Tn.dim)
            np == null -> T("nothing playing", color = Tn.dim)
            else -> {
                T(np.title.lowercase(), maxLines = 1)
                if (np.artist.isNotBlank()) T(np.artist.lowercase(), color = Tn.dim, size = 11, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("⏮", Modifier.clickable { Media.prev(ctx) }.padding(horizontal = 6.dp, vertical = 2.dp), color = Tn.purple, size = 16)
            T(if (np?.playing == true) "⏸" else "▶", Modifier.clickable { Media.toggle(ctx) }.padding(horizontal = 6.dp, vertical = 2.dp), color = Tn.purple, size = 16)
            T("⏭", Modifier.clickable { Media.next(ctx) }.padding(horizontal = 6.dp, vertical = 2.dp), color = Tn.purple, size = 16)
        }
    }
}

@Composable
fun MatchTile(matches: List<Entry>, onOpen: (Entry) -> Unit) {
    Tile(label = "match", accent = Tn.purple) {
        if (matches.isEmpty()) T("no match", color = Tn.dim)
        matches.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(vertical = 3.dp)) {
                T(if (i == 0) "❯ " else "  ", color = Tn.orange)
                T(e.label.lowercase(), color = if (i == 0) Tn.fg else Tn.dim, maxLines = 1)
            }
        }
    }
}

private val KEYS = ('a'..'z').map { it.toString() } + listOf("⌫", "⏎")

@Composable
fun LauncherTile(
    query: String,
    top: Entry?,
    modifier: Modifier,
    onKey: (String) -> Unit,
    onClear: () -> Unit,
) {
    Tile(modifier) {
        Row {
            T("launch ", color = Tn.dim, size = 11)
            T("❯ ", color = Tn.orange, size = 11)
            T(query + "▌", size = 11)
            if (top != null && query.isNotEmpty()) T("  → " + top.label.lowercase(), color = Tn.dim, size = 11, maxLines = 1)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KEYS.chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { k ->
                        Key(
                            k,
                            Modifier.weight(1f).fillMaxHeight(),
                            onTap = { onKey(k) },
                            onLong = { if (k == "⌫") onClear() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Key(label: String, modifier: Modifier, onTap: () -> Unit, onLong: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(4.dp)
    val accent = when (label) {
        "⌫" -> Tn.red
        "⏎" -> Tn.green
        else -> Tn.fg
    }
    Box(
        modifier
            .clip(shape)
            .border(1.dp, Tn.border, shape)
            .pointerInput(label) {
                detectTapGestures(
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        tap()
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        long()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        T(label, color = accent, size = 14)
    }
}
