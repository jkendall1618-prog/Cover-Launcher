package app.cover.launcher

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Page 2 ("do"): Kanagawa Dragon, swiped to from page 1. Page 1 lives in MainActivity, unchanged.

private val ROW = 42.dp
private val SQUARE = 44.dp

private data class Dial(val label: String, val uri: String?)

/** A bordered, tappable row cell. Everything on page 2 is built from these. */
@Composable
private fun Cell(
    modifier: Modifier = Modifier,
    border: Color = Kd.border,
    fill: Color = Color.Transparent,
    stroke: Dp = 1.dp,
    pad: Dp = 0.dp,
    arrangement: Arrangement.Horizontal = Arrangement.Center,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    var m = modifier.height(ROW).clip(shape).background(fill).border(stroke, border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(
        m.padding(horizontal = pad),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun DoPage(
    act: MainActivity,
    prefs: Prefs,
    wx: Wx?,
    shop: Int?,
    onHome: () -> Unit,
    onPeople: () -> Unit,
    onShop: () -> Unit,
    onBrief: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // A short status line that borrows the weather slot, e.g. "saved ✓" or "timer 10m ✓".
    var note by remember { mutableStateOf<String?>(null) }
    var noteBad by remember { mutableStateOf(false) }
    fun say(text: String, bad: Boolean = false) {
        note = text
        noteBad = bad
    }
    LaunchedEffect(note) {
        if (note != null) {
            delay(2500)
            note = null
        }
    }

    // ---- capture ----
    var draft by remember { mutableStateOf("") }
    fun save(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Actions.capture(ctx, prefs, text) } }
            r.onSuccess {
                draft = ""
                say("saved ✓")
            }.onFailure { say(it.message ?: "couldn't save", bad = true) }
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { save(it) }
    }
    fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-AU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "capture")
        try {
            mic.launch(i)
        } catch (e: ActivityNotFoundException) {
            say("no voice input", bad = true)
        }
    }

    // ---- torch ----
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    val torchId = remember { Actions.flashCamera(cm) }
    var torch by remember { mutableStateOf(false) }
    DisposableEffect(cm, torchId) {
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId) torch = enabled
            }
        }
        runCatching { cm?.registerTorchCallback(cb, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { cm?.unregisterTorchCallback(cb) } }
    }

    // ---- speed dial (set in cfg) ----
    val dials = remember {
        (0..2).map { i ->
            Dial(prefs.get(Prefs.dialLabel(i)) ?: Prefs.DIAL_DEFAULTS[i], prefs.get(Prefs.dialUri(i)))
        }
    }

    val peopleCount = People.msgs.size
    val notifOk = remember { Media.hasAccess(ctx) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {

        // ===== top: status line + capture =====
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clickable(onClick = onHome).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).border(1.dp, Kd.dim, CircleShape))
                    Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(Kd.amber))
                }
                Spacer(Modifier.width(8.dp))
                T("do", color = Kd.amber)
                Spacer(Modifier.weight(1f))
                val n = note
                when {
                    n != null -> T(n, color = if (noteBad) Kd.red else Kd.sage, size = 12, maxLines = 1)
                    wx != null -> Row {
                        T("${wx.temp}° ", color = Kd.fg, size = 12)
                        T(wx.note, color = Kd.dim, size = 12, maxLines = 1)
                    }
                    else -> T("weather…", color = Kd.faint, size = 12)
                }
            }

            Row(Modifier.fillMaxWidth().height(ROW), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val shape = RoundedCornerShape(4.dp)
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(shape).background(Kd.bgDark)
                        .border(2.dp, Kd.amber, shape).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T("› ", color = Kd.amber)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        textStyle = Body.copy(color = Kd.fg),
                        cursorBrush = SolidColor(Kd.amber),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save(draft) }),
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) T("capture → inbox", color = Kd.faint, maxLines = 1)
                                inner()
                            }
                        },
                    )
                }
                Cell(Modifier.width(SQUARE), border = Kd.amber, fill = Kd.amber, onClick = { listen() }) {
                    T("mic", color = Kd.bg, size = 12)
                }
            }
        }

        // ===== middle: people, music + pay, speed dial =====
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Cell(Modifier.fillMaxWidth(), pad = 10.dp, arrangement = Arrangement.Start, onClick = onPeople) {
                if (!notifOk) {
                    T("people · allow notifications in cfg", color = Kd.dim, size = 12, maxLines = 1)
                } else {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (peopleCount > 0) Kd.sage else Kd.border),
                        contentAlignment = Alignment.Center,
                    ) { T("$peopleCount", color = if (peopleCount > 0) Kd.bg else Kd.dim, size = 12) }
                    Spacer(Modifier.width(8.dp))
                    T("people")
                    T(
                        if (peopleCount > 0) "  · reply from here" else "  · all quiet",
                        Modifier.weight(1f), color = Kd.dim, size = 12, maxLines = 1,
                    )
                    T("›", color = Kd.dim)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Cell(
                    Modifier.weight(1f), border = Kd.sage, fill = Kd.bgDark, pad = 10.dp,
                    arrangement = Arrangement.Start,
                    onClick = { if (Actions.playlist(act)) say("▶ doom and friends") else say("spotify not found", bad = true) },
                ) {
                    T("♪ ", color = Kd.dim, size = 12)
                    T("doom and friends", Modifier.weight(1f), maxLines = 1)
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)).background(Kd.sage),
                        contentAlignment = Alignment.Center,
                    ) { T("▶", color = Kd.bg, size = 12) }
                }
                Cell(
                    Modifier.width(88.dp), border = Kd.amber,
                    onClick = { if (!Actions.wallet(act)) say("wallet not found", bad = true) },
                ) { T("pay", color = Kd.amber) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dials.forEach { d ->
                    Cell(
                        Modifier.weight(1f), pad = 8.dp, arrangement = Arrangement.Start,
                        onClick = {
                            val uri = d.uri
                            if (uri == null) say("set ${d.label} in cfg", bad = true)
                            else Actions.call(act, uri)?.let { say(it, bad = true) }
                        },
                    ) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).border(1.dp, Kd.amber, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { T(d.label.take(1).uppercase(), color = Kd.amber, size = 12) }
                        Spacer(Modifier.width(6.dp))
                        T(d.label, color = if (d.uri != null) Kd.fg else Kd.dim, maxLines = 1)
                    }
                }
            }
        }

        // ===== bottom: timers + shop, toggles + brief =====
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Cell(Modifier.width(SQUARE), border = Color.Transparent) { T("min", color = Kd.faint, size = 11) }
                listOf(5, 10, 25).forEach { m ->
                    Cell(
                        Modifier.width(SQUARE),
                        onClick = { if (Actions.timer(act, m)) say("timer ${m}m ✓") else say("no clock app", bad = true) },
                    ) { T("$m") }
                }
                Cell(
                    Modifier.weight(1f), fill = Kd.bgDark, pad = 10.dp,
                    arrangement = Arrangement.SpaceBetween, onClick = onShop,
                ) {
                    T("shop")
                    T(if (shop == null) "–" else "$shop ›", color = Kd.amber)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Cell(
                    Modifier.width(SQUARE),
                    border = if (torch) Kd.amber else Kd.border,
                    fill = if (torch) Kd.amber else Color.Transparent,
                    onClick = { if (!Actions.setTorch(cm, torchId, !torch)) say("no torch", bad = true) },
                ) { T("torch", color = if (torch) Kd.bg else Kd.dim, size = 11) }
                Cell(
                    Modifier.width(SQUARE), border = Kd.amber,
                    onClick = { if (!Actions.camera(act)) say("no camera app", bad = true) },
                ) { T("cam", color = Kd.amber, size = 11) }
                Cell(Modifier.width(SQUARE), onClick = { Actions.bluetooth(act) }) { T("bt↗", color = Kd.dim, size = 11) }
                Cell(Modifier.width(SQUARE), onClick = { Actions.wifi(act) }) { T("wifi↗", color = Kd.dim, size = 11) }
                Cell(Modifier.weight(1f), border = Kd.sage, fill = Kd.sage, onClick = onBrief) {
                    T("brief me", color = Kd.bg, maxLines = 1)
                }
            }
        }
    }
}

// ---------- page 2's own screens ----------

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, right: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(title, color = Kd.amber)
        if (right != null) T("  $right", color = Kd.dim, size = 12)
        Spacer(Modifier.weight(1f))
        T("esc", Modifier.clickable(onClick = onBack).padding(6.dp), color = Kd.dim, size = 12)
    }
}

@Composable
private fun PageField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier, onDone: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.border(1.dp, Kd.border, shape).padding(horizontal = 8.dp, vertical = 6.dp),
        textStyle = Body.copy(color = Kd.fg),
        cursorBrush = SolidColor(Kd.amber),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onAny = { onDone() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) T(placeholder, color = Kd.faint)
                inner()
            }
        },
    )
}

@Composable
fun PeopleScreen(act: MainActivity, onBack: () -> Unit) {
    var openKey by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }

    fun send(m: Msg) {
        val text = draft.trim()
        if (text.isEmpty()) return
        if (People.reply(act, m, text)) {
            draft = ""
            openKey = null
            status = "sent to ${m.who.lowercase()} ✓"
            statusOk = true
        } else {
            status = "couldn't send, try open"
            statusOk = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PageHeader("❯ people", onBack)
        if (!Media.hasAccess(act)) T("allow notifications in cfg (media)", color = Kd.dim)
        else if (People.msgs.isEmpty()) T("all quiet", color = Kd.sage)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(People.msgs, key = { it.key }) { m ->
                val isOpen = openKey == m.key
                val shape = RoundedCornerShape(6.dp)
                Column(
                    Modifier.fillMaxWidth().clip(shape)
                        .border(1.dp, if (isOpen) Kd.amber else Kd.border, shape)
                        .clickable {
                            openKey = if (isOpen) null else m.key
                            draft = ""
                        }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    T(m.who.lowercase(), color = Kd.amber, maxLines = 1)
                    if (m.text.isNotEmpty()) T(m.text, color = Kd.fg, maxLines = if (isOpen) 6 else 1)
                    if (isOpen) {
                        Spacer(Modifier.height(6.dp))
                        if (m.reply != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PageField(draft, { draft = it }, "reply", Modifier.weight(1f), onDone = { send(m) })
                                Btn("send", Kd.sage) { send(m) }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Btn("open", Kd.amber) {
                            if (!People.open(act, m)) {
                                status = "couldn't open it"
                                statusOk = false
                            }
                        }
                    }
                }
            }
        }
        status?.let { T(it, color = if (statusOk) Kd.sage else Kd.red, size = 12) }
    }
}

@Composable
fun ShopScreen(hb: Homebase, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Grocery>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { hb.groceries() } }
            list = r.getOrDefault(emptyList())
            error = r.exceptionOrNull()?.message
        }
    }
    LaunchedEffect(Unit) { load() }

    val shown = list
    val err = error
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PageHeader("❯ shop", onBack, right = shown?.size?.toString())
        when {
            !hb.signedIn -> T("sign in to homebase in cfg", color = Kd.dim)
            err != null -> T(err, color = Kd.red)
            shown == null -> T("loading…", color = Kd.dim)
            shown.isEmpty() -> T("list's empty", color = Kd.sage)
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                shown.groupBy { it.aisle.ifBlank { "Other" } }.forEach { (aisle, gs) ->
                    item(key = "aisle:$aisle") {
                        T(aisle.lowercase(), Modifier.padding(top = 6.dp), color = Kd.faint, size = 11)
                    }
                    items(gs, key = { it.id }) { g ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                list = shown - g
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { hb.tick(g) } }
                                    r.exceptionOrNull()?.let {
                                        error = it.message
                                        load()
                                    }
                                }
                            }.padding(vertical = 6.dp),
                        ) {
                            T("[ ] ", color = Kd.amber)
                            T(g.name.lowercase(), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BriefScreen(text: String?, busy: Boolean, error: String?, onBack: () -> Unit, onAgain: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageHeader("❯ brief", onBack)
        when {
            busy -> T("thinking…", color = Kd.dim)
            error != null -> T(error, color = Kd.red)
            text != null -> T(text, color = Kd.fg)
        }
        Spacer(Modifier.weight(1f))
        if (!busy) Btn("again", Kd.sage, onAgain)
    }
}

// ---------- page 2 setup, shown inside cfg ----------

@Composable
fun Page2Tile(prefs: Prefs) {
    val ctx = LocalContext.current
    var perm by remember { mutableIntStateOf(0) }
    val contactsOk = remember(perm) { Actions.canReadContacts(ctx) }
    val callOk = remember(perm) { Actions.canCall(ctx) }
    var inbox by remember { mutableStateOf(prefs.get(Prefs.CAPTURE_DIR)) }
    val labels = remember { (0..2).map { prefs.get(Prefs.dialLabel(it)) ?: "" }.toMutableStateList() }
    val picked = remember { (0..2).map { prefs.get(Prefs.dialUri(it)) }.toMutableStateList() }
    var slot by remember { mutableIntStateOf(-1) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perm++ }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            prefs.put(Prefs.CAPTURE_DIR to uri.toString())
            inbox = uri.toString()
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (uri != null && slot in 0..2) {
            prefs.put(Prefs.dialUri(slot) to uri.toString())
            picked[slot] = uri.toString()
        }
    }
    val askPerms = { perms.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) }

    Tile(label = "page 2") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("contacts  ")
            if (contactsOk) T("on", color = Tn.green) else T("grant", Modifier.clickable { askPerms() }, color = Tn.yellow)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("calls     ")
            if (callOk) T("on", color = Tn.green) else T("grant", Modifier.clickable { askPerms() }, color = Tn.yellow)
        }
        if (!callOk) T("without calls, speed dial opens the dialer instead", color = Tn.dim, size = 11)
        T("people uses the media access above", color = Tn.dim, size = 11)

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("inbox     ")
            T(
                Actions.folderName(inbox) ?: "pick",
                Modifier.clickable { folder.launch(null) },
                color = if (inbox != null) Tn.green else Tn.yellow,
                maxLines = 1,
            )
        }
        T("capture saves a note per entry into this folder: pick your vault's inbox", color = Tn.dim, size = 11)

        Spacer(Modifier.height(6.dp))
        T("speed dial · label, then pick who it calls", color = Tn.dim, size = 11)
        (0..2).forEach { i ->
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Field(
                    labels[i],
                    { v ->
                        labels[i] = v
                        prefs.put(Prefs.dialLabel(i) to v.trim().ifBlank { null })
                    },
                    Prefs.DIAL_DEFAULTS[i],
                    Modifier.weight(1f),
                )
                val done = picked[i] != null
                Btn(if (done) "✓ re-pick" else "pick", if (done) Tn.green else Tn.yellow) {
                    slot = i
                    pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }
            }
        }
    }
}
