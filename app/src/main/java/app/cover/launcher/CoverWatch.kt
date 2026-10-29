package app.cover.launcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/** One window that came to the front: which app, which window class, on which screen. */
data class Seen(val pkg: String, val cls: String, val display: Int) {
    val key get() = "$pkg|$cls|$display"
    val shortCls get() = cls.substringAfterLast('.')

    companion object {
        fun parse(s: String): Seen? {
            val p = s.split('|')
            if (p.size != 3) return null
            return Seen(p[0], p[1], p[2].toIntOrNull() ?: return null)
        }
    }
}

/** Takeover state. Not secret, so it lives in plain private app storage. */
object Takeover {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("cover_state", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("on", on).apply()

    fun target(ctx: Context): Seen? = sp(ctx).getString("target", null)?.let { Seen.parse(it) }
    fun setTarget(ctx: Context, s: Seen?) = sp(ctx).edit().putString("target", s?.key).apply()

    fun recent(ctx: Context): List<Seen> =
        sp(ctx).getString("recent", "").orEmpty().lines().mapNotNull { Seen.parse(it) }

    fun record(ctx: Context, s: Seen) {
        val list = recent(ctx)
        if (list.firstOrNull() == s) return
        val next = (listOf(s) + list.filter { it != s }).take(10)
        sp(ctx).edit().putString("recent", next.joinToString("\n") { it.key }).apply()
    }

    fun serviceOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(ctx, CoverWatch::class.java)
        return enabled.split(':').any {
            it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
        }
    }
}

/**
 * Watches which window comes to the front. When it's the screen you picked as
 * Samsung's cover home, cover is brought back in front on that same display.
 * Only package and window class names are read, never what's on screen.
 */
class CoverWatch : AccessibilityService() {
    private var lastLaunch = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        val cls = e.className?.toString() ?: ""
        val display = if (Build.VERSION.SDK_INT >= 30) e.displayId else Display.DEFAULT_DISPLAY
        val seen = Seen(pkg, cls, display)
        Takeover.record(this, seen)

        if (!Takeover.enabled(this)) return
        val target = Takeover.target(this) ?: return
        if (target.pkg != pkg || target.display != display) return
        // Match the window class too when we know it, so e.g. the notification shade doesn't trigger it.
        if (target.cls.isNotEmpty() && target.cls != cls) return

        val dm = getSystemService(DisplayManager::class.java)
        if (dm?.getDisplay(display)?.state != Display.STATE_ON) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 800) return
        lastLaunch = now

        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
        } catch (t: Throwable) {
            runCatching { startActivity(intent) }
        }
    }

    override fun onInterrupt() {}
}
