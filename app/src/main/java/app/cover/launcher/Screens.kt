package app.cover.launcher

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- Claude quick chat ----------

@Composable
fun ChatScreen(claude: Claude, chat: SnapshotStateList<Turn>, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        chat += Turn("user", q)
        input = ""
        error = null
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { claude.ask(chat.toList()) } }
            r.onSuccess { chat += Turn("assistant", it) }
                .onFailure {
                    error = it.message
                    chat.removeAt(chat.lastIndex)
                    input = q
                }
            busy = false
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::send)
    }

    fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-AU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "ask claude")
        try {
            mic.launch(i)
        } catch (e: ActivityNotFoundException) {
            error = "No voice input on this phone, type instead"
        }
    }

    // Voice-first: opening a fresh chat starts listening straight away.
    LaunchedEffect(Unit) { if (chat.isEmpty() && claude.hasKey) listen() }
    LaunchedEffect(chat.size, busy) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.size) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("❯ claude", color = Tn.orange)
            Spacer(Modifier.weight(1f))
            T("new", Modifier.clickable { chat.clear(); error = null }.padding(6.dp), color = Tn.dim, size = 12)
            T("esc", Modifier.clickable(onClick = onBack).padding(6.dp), color = Tn.dim, size = 12)
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!claude.hasKey) item { T("add your anthropic api key in cfg first", color = Tn.yellow) }
            items(chat) { t ->
                if (t.role == "user") T("› " + t.text, color = Tn.blue)
                else T(t.text)
            }
            item {
                when {
                    busy -> T("thinking…", color = Tn.dim)
                    error != null -> T(error!!, color = Tn.red)
                    else -> Spacer(Modifier.height(1.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(
                input, { input = it }, "type or tap mic",
                Modifier.weight(1f),
                ime = ImeAction.Send, onIme = { send(input) }, maxLines = 3,
            )
            Btn("mic", Tn.purple) { listen() }
            Btn("⏎", Tn.green) { send(input) }
        }
    }
}

// ---------- settings ----------

@Composable
fun SettingsScreen(prefs: Prefs, hb: Homebase, claude: Claude, tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var perm by remember { mutableIntStateOf(0) }
    val calOk = remember(tick, perm) { CalendarRepo.hasAccess(ctx) }
    val mediaOk = remember(tick, perm) { Media.hasAccess(ctx) }

    var email by remember { mutableStateOf(hb.email ?: "") }
    var password by remember { mutableStateOf("") }
    var hbStatus by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(hb.signedIn) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(claude.hasKey) }

    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm++ }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("❯ cfg", color = Tn.orange)
            Spacer(Modifier.weight(1f))
            T("esc", Modifier.clickable(onClick = onBack).padding(6.dp), color = Tn.dim, size = 12)
        }
        T("easiest to set up once on the main screen.", color = Tn.dim, size = 11)

        Tile(label = "access") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("calendar  ")
                if (calOk) T("on", color = Tn.green)
                else T("grant", Modifier.clickable { calPerm.launch(Manifest.permission.READ_CALENDAR) }, color = Tn.yellow)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("media     ")
                if (mediaOk) T("on", color = Tn.green)
                else T("open settings", Modifier.clickable {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }, color = Tn.yellow)
            }
        }

        Tile(label = "homebase") {
            if (signedIn) {
                T("signed in · ${hb.email ?: ""}", color = Tn.green, maxLines = 1)
                T("sign out", Modifier.clickable { hb.signOut(); signedIn = false }.padding(vertical = 4.dp), color = Tn.dim)
            } else {
                Field(email, { email = it }, "email", Modifier.fillMaxWidth(), keyboard = KeyboardType.Email)
                Spacer(Modifier.height(4.dp))
                Field(password, { password = it }, "password", Modifier.fillMaxWidth(), secret = true, ime = ImeAction.Done)
                Spacer(Modifier.height(4.dp))
                Btn("sign in", Tn.blue) {
                    hbStatus = "signing in…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { hb.signIn(email, password) } }
                        r.onSuccess { password = ""; signedIn = true; hbStatus = null }
                            .onFailure { hbStatus = it.message }
                    }
                }
            }
            hbStatus?.let { T(it, color = Tn.red) }
        }

        Tile(label = "claude") {
            T(if (hasKey) "api key saved" else "no api key yet", color = if (hasKey) Tn.green else Tn.yellow)
            Field(key, { key = it }, if (hasKey) "replace key" else "sk-ant-…", Modifier.fillMaxWidth(), secret = true)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("save", Tn.blue) {
                    if (key.isNotBlank()) {
                        prefs.put(Prefs.ANTHROPIC_KEY to key.trim())
                        key = ""
                        hasKey = true
                    }
                }
                if (hasKey) Btn("remove", Tn.red) { prefs.put(Prefs.ANTHROPIC_KEY to null); hasKey = false }
            }
            T("model: ${Claude.MODEL}", color = Tn.dim, size = 11)
        }

        T("cover v${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}", color = Tn.dim, size = 11)
    }
}

// ---------- small controls ----------

@Composable
fun Btn(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.border(1.dp, color, shape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { T(label, color = color) }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Default,
    onIme: () -> Unit = {},
    maxLines: Int = 1,
) {
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.border(1.dp, Tn.border, shape).padding(horizontal = 8.dp, vertical = 6.dp),
        textStyle = Body,
        cursorBrush = SolidColor(Tn.fg),
        singleLine = maxLines == 1,
        maxLines = maxLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboard,
            imeAction = ime,
        ),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) T(placeholder, color = Tn.dim)
                inner()
            }
        },
    )
}
