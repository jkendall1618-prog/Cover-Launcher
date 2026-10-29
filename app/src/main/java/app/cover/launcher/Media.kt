package app.cover.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** Exists only so Android grants access to media sessions. It ignores notification content. */
class MediaListener : NotificationListenerService()

data class NowPlaying(val title: String, val artist: String, val playing: Boolean)

object Media {
    fun hasAccess(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun controller(ctx: Context): MediaController? = try {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val list = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    } catch (e: SecurityException) {
        null
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
}
