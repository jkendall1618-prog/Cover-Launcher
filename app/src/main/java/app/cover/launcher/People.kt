package app.cover.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateListOf

/** One conversation with an unread message: who, the latest line, and how to reply or open it. */
data class Msg(
    val key: String,
    val pkg: String,
    val who: String,
    val text: String,
    val time: Long,
    val reply: Notification.Action?,
    val open: PendingIntent?,
)

/** Message notifications from people. In memory only; cleared when cover stops. */
object People {
    val msgs = mutableStateListOf<Msg>()
    private val main = Handler(Looper.getMainLooper())

    fun sync(active: Array<StatusBarNotification>?) {
        val next = active.orEmpty()
            .mapNotNull(::toMsg)
            .sortedByDescending { it.time }
            .distinctBy { it.pkg + "|" + it.who }
        main.post {
            msgs.clear()
            msgs.addAll(next)
        }
    }

    private fun toMsg(s: StatusBarNotification): Msg? {
        val n = s.notification ?: return null
        if (s.packageName == BuildConfigPkg) return null
        if (s.isOngoing) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val ex = n.extras ?: return null
        val template = ex.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val isMessage = n.category == Notification.CATEGORY_MESSAGE ||
            template.endsWith("MessagingStyle") ||
            ex.containsKey(Notification.EXTRA_MESSAGES)
        if (!isMessage) return null
        val who = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        if (who.isEmpty()) return null
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        return Msg(s.key, s.packageName, who, text, s.postTime, reply, n.contentIntent)
    }

    /** Sends a reply through the messaging app's own reply action, like replying from the shade. */
    fun reply(act: Activity, m: Msg, text: String): Boolean {
        val a = m.reply ?: return false
        val inputs = a.remoteInputs ?: return false
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return runCatching { a.actionIntent.send(act, 0, fill) }.isSuccess
    }

    /** Opens the conversation in its app, on the screen cover is on. */
    fun open(act: Activity, m: Msg): Boolean {
        val pi = m.open ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(act.windowManager.defaultDisplay.displayId)
            if (Build.VERSION.SDK_INT >= 34) {
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            pi.send(act, 0, null, null, null, null, opts.toBundle())
        }.isSuccess
    }

    private const val BuildConfigPkg = "app.cover.launcher"
}
