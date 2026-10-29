package app.cover.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent

enum class Builtin { ASK, SETTINGS }

data class Entry(
    val label: String,
    val pkg: String? = null,
    val cls: String? = null,
    val builtin: Builtin? = null,
) {
    private val words = label.lowercase().split(' ', '-', '.', '_').filter { it.isNotEmpty() }
    val key = label.lowercase()
    val initials = words.joinToString("") { it.take(1) }

    /** Higher is better; null means no match. Single letters only match word starts. */
    fun score(q: String): Int? {
        if (q.isEmpty()) return null
        if (key.startsWith(q)) return 1000 - key.length
        if (initials.startsWith(q) && q.length > 1) return 900 - key.length
        if (words.any { it.startsWith(q) }) return 800 - key.length
        if (q.length < 2) return null
        var i = 0
        var gaps = 0
        var last = -1
        for ((idx, c) in key.withIndex()) {
            if (i < q.length && c == q[i]) {
                if (last >= 0 && idx - last > 1) gaps++
                last = idx
                i++
            }
        }
        return if (i == q.length) 500 - gaps * 20 - key.length else null
    }
}

object Apps {
    fun load(ctx: Context): List<Entry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { Entry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.pkg + "/" + it.cls }
        val builtins = listOf(
            Entry("ask claude", builtin = Builtin.ASK),
            Entry("cover settings", builtin = Builtin.SETTINGS),
        )
        return (builtins + apps).sortedBy { it.key }
    }

    fun match(all: List<Entry>, q: String, limit: Int = 6): List<Entry> =
        all.mapNotNull { e -> e.score(q)?.let { e to it } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    /** Launch on the same screen the launcher is on, so cover-screen launches stay on the cover. */
    fun launch(activity: Activity, e: Entry) {
        val pkg = e.pkg ?: return
        val cls = e.cls ?: return
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            @Suppress("DEPRECATION")
            val displayId = activity.windowManager.defaultDisplay.displayId
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            activity.startActivity(intent, opts.toBundle())
        } catch (t: Throwable) {
            runCatching { activity.startActivity(intent) }
        }
    }
}
