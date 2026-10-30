package app.cover.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The Signal page: left of page 1, jade on black, laid out like a terminal session.

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DHM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

private fun stamp(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return if (t.toLocalDate() == LocalDate.now()) t.format(HM) else t.format(DHM).lowercase()
}

@Composable
fun SignalPage(act: MainActivity, locked: Boolean, onHome: () -> Unit, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SignalFeed.init(ctx) }

    val chat = SignalFeed.chatId.value
    val title = SignalFeed.title.value
    val lines = SignalFeed.lines
    val hook = SignalFeed.reply.value
    val replyAllowed = !locked || SignalLock.replyLocked(ctx)

    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusBad by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (status != null) {
            delay(3000)
            status = null
        }
    }

    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (SignalFeed.send(act, t)) {
            draft = ""
            status = "sent ✓"
            statusBad = false
        } else {
            status = "couldn't send, open signal instead"
            statusBad = true
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { draft = it }
    }
    fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-AU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "reply")
        try {
            mic.launch(i)
        } catch (e: ActivityNotFoundException) {
            status = "no voice input"
            statusBad = true
        }
    }

    val shape = RoundedCornerShape(4.dp)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // ---- prompt-style header: ~/signal ❯ chat name ----
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            T("~/signal", color = Jd.jade, size = 12)
            T(" ❯ ", color = Jd.faint, size = 12)
            T((title ?: "no chat").lowercase(), Modifier.weight(1f), color = Jd.fg, size = 12, maxLines = 1)
            if (locked) {
                T("locked", color = Jd.gold, size = 11)
            } else {
                Row(
                    Modifier.clickable(onClick = onHome).padding(vertical = 4.dp, horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(Jd.jade))
                    Box(Modifier.size(6.dp).border(1.dp, Jd.dim, CircleShape))
                    Box(Modifier.size(6.dp).border(1.dp, Jd.dim, CircleShape))
                }
            }
        }

        // ---- the log ----
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(shape).background(Jd.bgDark)
                .border(1.dp, Jd.border, shape).padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            when {
                chat == null -> Column {
                    T("no chat picked yet", color = Jd.dim, size = 12)
                    T("❯ cfg › signal › pick the chat", color = Jd.faint, size = 12)
                }
                lines.isEmpty() -> T("waiting for the next message…", color = Jd.dim, size = 12)
                else -> LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(lines) { l ->
                        Row {
                            T(stamp(l.time) + " ", color = Jd.faint, size = 11)
                            T(
                                (if (l.mine) "me" else l.who.lowercase().take(10)) + " › ",
                                color = if (l.mine) Jd.jade else Jd.gold, size = 12, maxLines = 1,
                            )
                            T(l.text, Modifier.weight(1f), color = Jd.fg, size = 12)
                        }
                    }
                }
            }
        }

        status?.let { T(it, color = if (statusBad) Jd.red else Jd.jade, size = 11, maxLines = 1) }

        // ---- the bottom line: reply if Signal left a live hook, otherwise a way into Signal ----
        if (chat != null) {
            Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    locked && !replyAllowed -> {
                        Row(
                            Modifier.weight(1f).fillMaxHeight().clip(shape).border(1.dp, Jd.border, shape).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { T("read-only while locked", color = Jd.dim, size = 12, maxLines = 1) }
                        Box(
                            Modifier.width(88.dp).fillMaxHeight().clip(shape).border(1.dp, Jd.gold, shape).clickable(onClick = onUnlock),
                            contentAlignment = Alignment.Center,
                        ) { T("unlock", color = Jd.gold) }
                    }
                    hook != null -> {
                        Row(
                            Modifier.weight(1f).fillMaxHeight().clip(shape).border(2.dp, Jd.jade, shape).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T("❯ ", color = Jd.jade)
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.weight(1f),
                                textStyle = Body.copy(color = Jd.fg),
                                cursorBrush = SolidColor(Jd.jade),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onAny = { send(draft) }),
                                decorationBox = { inner ->
                                    Box {
                                        if (draft.isEmpty()) T("reply_", color = Jd.faint)
                                        inner()
                                    }
                                },
                            )
                        }
                        Box(
                            Modifier.width(44.dp).fillMaxHeight().clip(shape).background(Jd.jade)
                                .clickable { if (draft.isBlank()) listen() else send(draft) },
                            contentAlignment = Alignment.Center,
                        ) { T(if (draft.isBlank()) "mic" else "⏎", color = Jd.bg, size = 12) }
                    }
                    else -> {
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(shape).border(1.dp, Jd.jade, shape).clickable {
                                if (!SignalFeed.open(act)) {
                                    status = "signal not found"
                                    statusBad = true
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) { T("open in signal ↗", color = Jd.jade) }
                    }
                }
            }
        }
    }
}

// ---------- cfg ----------

@Composable
fun SignalTile() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SignalFeed.init(ctx) }
    var overLock by remember { mutableStateOf(SignalLock.overLock(ctx)) }
    var replyLocked by remember { mutableStateOf(SignalLock.replyLocked(ctx)) }
    var cleared by remember { mutableStateOf(false) }
    val current = SignalFeed.chatId.value

    Tile(label = "signal") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("chat      ")
            T(SignalFeed.title.value?.lowercase() ?: "not picked", color = if (current != null) Tn.purple else Tn.yellow, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        T("pick from chats that have notified you since this update (renames are fine):", color = Tn.dim, size = 11)
        if (SignalFeed.recent.isEmpty()) T("none yet: wait for a message in that chat, then come back", color = Tn.dim, size = 11)
        SignalFeed.recent.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { SignalFeed.choose(ctx, c) }.padding(vertical = 3.dp)) {
                T(if (c.id == current) "❯ " else "  ", color = Tn.orange, size = 11)
                T(c.title.lowercase(), size = 11, maxLines = 1)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("over lock ")
            T(if (overLock) "on" else "off", Modifier.clickable {
                overLock = !overLock
                SignalLock.setOverLock(ctx, overLock)
            }, color = if (overLock) Tn.green else Tn.dim)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("reply     ")
            T(if (replyLocked) "also while locked" else "only when unlocked", Modifier.clickable {
                replyLocked = !replyLocked
                SignalLock.setReplyLocked(ctx, replyLocked)
            }, color = if (replyLocked) Tn.yellow else Tn.green)
        }
        T(
            "over lock: while the phone's locked, cover shows only this page. anyone holding the phone can read this chat" +
                if (replyLocked) ", and reply as you." else ".",
            color = Tn.dim, size = 11,
        )
        Spacer(Modifier.height(4.dp))
        T(if (cleared) "log cleared" else "clear log", Modifier.clickable {
            SignalFeed.clearLog(ctx)
            cleared = true
        }.padding(vertical = 2.dp), color = Tn.dim, size = 11)
    }
}
