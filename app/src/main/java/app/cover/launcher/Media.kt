package app.cover.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Grants access to media sessions (page 1's play tile, page 4's now-playing), feeds page 2's "people" row (memory only)
 * and the Signal page's log (the one chat you picked, kept in cover's encrypted storage). Nothing is sent anywhere.
 */
class MediaListener : NotificationListenerService() {
    private fun resync() = People.sync(runCatching { activeNotifications }.getOrNull())

    override fun onListenerConnected() {
        resync()
        SignalFeed.seed(this, runCatching { activeNotifications }.getOrNull())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        resync()
        sbn?.let { SignalFeed.onPosted(this, it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        resync()
        sbn?.let { SignalFeed.onRemoved(it) }
    }

    override fun onListenerDisconnected() = People.sync(null)
}

data class NowPlaying(val title: String, val artist: String, val playing: Boolean)

/** Page 4's view of a track. [key] changes when the track does, so the cover is only redrawn then. */
data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val app: String,
    val positionMs: Long,
    val durationMs: Long,
    val key: String,
)

object Media {
    fun hasAccess(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun sessions(ctx: Context): List<MediaController> = try {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun controller(ctx: Context): MediaController? {
        val list = sessions(ctx)
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }

    fun now(ctx: Context): NowPlaying? {
        val c = controller(ctx) ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        return NowPlaying(title, artist, c.playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    /** Page 4: the full picture of what's playing, including where in the track we are. */
    fun track(ctx: Context): Track? {
        val c = controller(ctx) ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val ps = c.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        var pos = ps?.position ?: 0L
        if (playing && ps != null && ps.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        }
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        if (dur > 0) pos = pos.coerceIn(0L, dur)
        val app = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName).lowercase().substringBefore(' ')
        return Track(title, artist, album, playing, app, pos.coerceAtLeast(0L), dur, "$title|$artist|$album")
    }

    /** The cover art the playing app hands to the system, as a plain software bitmap. Null if it gave none. */
    fun art(ctx: Context): Bitmap? {
        val md = controller(ctx)?.metadata ?: return null
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: listOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
            ).firstNotNullOfOrNull { key ->
                // Only local content:// art; nothing is fetched from the internet here.
                md.getString(key)?.takeIf { it.startsWith("content://") }?.let { s ->
                    runCatching {
                        ctx.contentResolver.openInputStream(Uri.parse(s))?.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
            }
            ?: return null
        return if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
    }

    fun prev(ctx: Context) = controller(ctx)?.transportControls?.skipToPrevious()
    fun next(ctx: Context) = controller(ctx)?.transportControls?.skipToNext()
    fun toggle(ctx: Context) {
        val c = controller(ctx) ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    /** Asks an app's own media session to play a URI without opening the app. False if it can't. */
    fun playFromUri(ctx: Context, pkg: String, uri: Uri): Boolean {
        val c = sessions(ctx).firstOrNull { it.packageName == pkg } ?: return false
        val actions = c.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_PLAY_FROM_URI == 0L) return false
        return runCatching { c.transportControls.playFromUri(uri, null) }.isSuccess
    }
}
