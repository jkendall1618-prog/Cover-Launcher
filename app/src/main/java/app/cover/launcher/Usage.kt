package app.cover.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** Today so far: how many times the phone was unlocked, and how long a screen was on. */
data class Today(val unlocks: Int, val screenMs: Long) {
    val screenLabel: String
        get() {
            val m = screenMs / 60_000
            return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
        }
}

/** Reads Android's own usage events. Needs "usage access", granted once in settings. Nothing leaves the phone. */
object Usage {
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun today(ctx: Context): Today? {
        if (!hasAccess(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(start, end) ?: return null
        val e = UsageEvents.Event()
        var unlocks = 0
        var total = 0L
        var onSince: Long? = null
        var sawScreenEvent = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlocks++
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (onSince == null) onSince = e.timeStamp
                    sawScreenEvent = true
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Screen already on at midnight: count from midnight.
                    val from = onSince ?: if (!sawScreenEvent) start else null
                    if (from != null) total += e.timeStamp - from
                    onSince = null
                    sawScreenEvent = true
                }
            }
        }
        onSince?.let { total += end - it }
        return Today(unlocks, total.coerceAtLeast(0))
    }
}
