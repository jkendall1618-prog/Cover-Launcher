package app.cover.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/** One line in the Signal log. */
data class SigLine(val time: Long, val who: String, val text: String, val mine: Boolean) {
    val key get() = "$time|$who|$text"
}

/** A Signal chat cover has seen a notification from, for the picker in cfg. */
data class SigChat(val id: String, val title: String)

/**
 * Builds the left page from Signal's own notifications, since Signal has no API for other apps.
 * The chat is tracked by its conversation ID, so renaming the group doesn't lose it.
 * The log lives in cover's encrypted storage on the phone (last 300 lines); nothing is sent anywhere.
 */
object SignalFeed {
    const val PKG = "org.thoughtcrime.securesms"
    private const val MAX_LINES = 300

    private const val K_CHAT = "sig_chat"
    private const val K_TITLE = "sig_title"
    private const val K_LOG = "sig_log"

    val lines = mutableStateListOf<SigLine>()
    val title = mutableStateOf<String?>(null)
    val chatId = mutableStateOf<String?>(null)
    val recent = mutableStateListOf<SigChat>()

    /** Live hooks from the current notification. Null once Signal clears it. */
    val reply = mutableStateOf<Notification.Action?>(null)
    private var openLive: PendingIntent? = null

    /** The last "open this chat" link, kept after the notification goes, in case Signal still honours it. */
    private var openSaved: PendingIntent? = null

    private val main = Handler(Looper.getMainLooper())
    private var store: Prefs? = null

    private fun prefs(ctx: Context): Prefs = store ?: Prefs(ctx.applicationContext).also {
        store = it
        load(it)
    }

    fun init(ctx: Context) {
        prefs(ctx)
    }

    private fun load(p: Prefs) {
        val id = p.get(K_CHAT)
        val t = p.get(K_TITLE)
        val saved = runCatching {
            val arr = JSONArray(p.get(K_LOG) ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SigLine(o.getLong("t"), o.optString("w"), o.optString("x"), o.optBoolean("m"))
            }
        }.getOrDefault(emptyList())
        runOnMain {
            chatId.value = id
            title.value = t
            lines.clear()
            lines.addAll(saved)
        }
    }

    private fun save(p: Prefs) {
        val arr = JSONArray()
        lines.takeLast(MAX_LINES).forEach {
            arr.put(JSONObject().put("t", it.time).put("w", it.who).put("x", it.text).put("m", it.mine))
        }
        p.put(K_LOG to arr.toString(), K_TITLE to title.value)
    }

    /** Stable per chat: the conversation shortcut Signal attaches, else its per-chat notification number. */
    private fun idOf(s: StatusBarNotification): String {
        val sc = if (Build.VERSION.SDK_INT >= 26) s.notification?.shortcutId else null
        return when {
            !sc.isNullOrBlank() -> "sc:$sc"
            !s.tag.isNullOrBlank() -> "tag:${s.tag}"
            else -> "id:${s.id}"
        }
    }

    // ---------- called by the notification listener ----------

    fun onPosted(ctx: Context, s: StatusBarNotification) {
        if (s.packageName != PKG) return
        val n = s.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val p = prefs(ctx)
        val id = idOf(s)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val chatTitle = (style?.conversationTitle ?: n.extras?.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim().orEmpty()

        val replyAction = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val incoming = style?.messages.orEmpty().mapNotNull { m ->
            val text = m.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val mine = m.person == null
            SigLine(m.timestamp, if (mine) "me" else m.person?.name?.toString()?.trim().orEmpty().ifEmpty { "?" }, text, mine)
        }.ifEmpty {
            // No messaging style: fall back to the plain title/text pair.
            val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (text.isEmpty()) emptyList() else listOf(SigLine(s.postTime, chatTitle.ifEmpty { "?" }, text, false))
        }

        runOnMain {
            if (chatTitle.isNotEmpty()) {
                val next = (listOf(SigChat(id, chatTitle)) + recent.filter { it.id != id }).take(10)
                recent.clear()
                recent.addAll(next)
            }
            if (id != chatId.value) return@runOnMain

            if (chatTitle.isNotEmpty()) title.value = chatTitle
            reply.value = replyAction
            openLive = n.contentIntent
            if (n.contentIntent != null) openSaved = n.contentIntent

            val have = lines.map { it.key }.toHashSet()
            val fresh = incoming.filter { line ->
                line.key !in have &&
                    // Our own reply sent from cover is already logged; skip Signal echoing it back.
                    !(line.mine && lines.any { it.mine && it.text == line.text && kotlin.math.abs(it.time - line.time) < 5 * 60_000 })
            }
            if (fresh.isNotEmpty()) {
                val merged = (lines + fresh).sortedBy { it.time }.takeLast(MAX_LINES)
                lines.clear()
                lines.addAll(merged)
                save(p)
            } else {
                p.put(K_TITLE to title.value)
            }
        }
    }

    fun onRemoved(s: StatusBarNotification) {
        if (s.packageName != PKG) return
        val id = idOf(s)
        runOnMain {
            if (id == chatId.value) {
                reply.value = null
                openLive = null
            }
        }
    }

    /** At start-up, pick up a notification that's already sitting in the shade. */
    fun seed(ctx: Context, active: Array<StatusBarNotification>?) {
        active.orEmpty().filter { it.packageName == PKG }.forEach { onPosted(ctx, it) }
    }

    // ---------- called by the page and cfg ----------

    fun choose(ctx: Context, chat: SigChat) {
        val p = prefs(ctx)
        runOnMain {
            if (chat.id != chatId.value) {
                lines.clear()
                reply.value = null
                openLive = null
                openSaved = null
            }
            chatId.value = chat.id
            title.value = chat.title
            p.put(K_CHAT to chat.id, K_TITLE to chat.title, K_LOG to "[]")
        }
    }

    fun clearLog(ctx: Context) {
        val p = prefs(ctx)
        runOnMain {
            lines.clear()
            p.put(K_LOG to "[]")
        }
    }

    fun send(act: Activity, text: String): Boolean {
        val a = reply.value ?: return false
        val inputs = a.remoteInputs ?: return false
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        val ok = runCatching { a.actionIntent.send(act, 0, fill) }.isSuccess
        if (ok) {
            lines.add(SigLine(System.currentTimeMillis(), "me", text, true))
            save(prefs(act))
        }
        return ok
    }

    /** Opens the chat in Signal: the live link, then the saved one, then just Signal. */
    fun open(act: Activity): Boolean {
        @Suppress("DEPRECATION")
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(act.windowManager.defaultDisplay.displayId)
        if (Build.VERSION.SDK_INT >= 34) {
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        for (pi in listOfNotNull(openLive, openSaved)) {
            if (runCatching { pi.send(act, 0, null, null, null, null, opts.toBundle()) }.isSuccess) return true
        }
        val launch = act.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        return Actions.startHere(act, launch)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/** Lock-screen switches. Not secret, so plain private storage. */
object SignalLock {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("cover_state", Context.MODE_PRIVATE)
    fun overLock(ctx: Context) = sp(ctx).getBoolean("sig_over_lock", false)
    fun setOverLock(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("sig_over_lock", on).apply()
    fun replyLocked(ctx: Context) = sp(ctx).getBoolean("sig_reply_locked", false)
    fun setReplyLocked(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("sig_reply_locked", on).apply()
}
