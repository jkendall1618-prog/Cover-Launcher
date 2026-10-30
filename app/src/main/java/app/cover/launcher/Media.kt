package app.cover.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Grants access to media sessions (page 1's play tile) and feeds page 2's "people" row.
 * Message notifications are held in memory only while cover runs: never saved, never sent anywhere.
 */
class MediaListener : NotificationListenerService() {
    private fun resync() = People.sync(runCatching { activeNotifications }.getOrNull())

    override fun onListenerConnected() = resync()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = resync()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = resync()
    override fun onListenerDisconnected() = People.sync(null)
}

data class NowPlaying(val title: String, val artist: String, val playing: Boolean)

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
