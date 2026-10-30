package app.cover.launcher

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Page 2's one-tap actions. Anything that opens another app opens it on the screen cover is on. */
object Actions {
    const val SPOTIFY = "com.spotify.music"
    const val WALLET = "com.google.android.apps.walletnfcrel"
    const val PLAYLIST = "spotify:playlist:27mdqJFkYrSsBZFXdKue8D" // doom and friends

    fun startHere(act: Activity, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            @Suppress("DEPRECATION")
            val id = act.windowManager.defaultDisplay.displayId
            act.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
            true
        } catch (t: Throwable) {
            runCatching { act.startActivity(intent) }.isSuccess
        }
    }

    fun timer(act: Activity, minutes: Int): Boolean = startHere(
        act,
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "cover ${minutes}m")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )

    /** Plays straight through Spotify's media session when it allows that; otherwise opens the playlist. */
    fun playlist(act: Activity): Boolean {
        if (Media.playFromUri(act, SPOTIFY, Uri.parse(PLAYLIST))) return true
        return startHere(act, Intent(Intent.ACTION_VIEW, Uri.parse(PLAYLIST)).setPackage(SPOTIFY))
    }

    fun wallet(act: Activity): Boolean {
        val i = act.packageManager.getLaunchIntentForPackage(WALLET) ?: return false
        return startHere(act, i)
    }

    fun camera(act: Activity): Boolean = startHere(act, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

    fun bluetooth(act: Activity): Boolean = startHere(act, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun wifi(act: Activity): Boolean = startHere(act, Intent(Settings.Panel.ACTION_WIFI))

    // ---------- torch ----------

    fun flashCamera(cm: CameraManager?): String? = cm?.let {
        runCatching {
            it.cameraIdList.firstOrNull { id ->
                it.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    fun setTorch(cm: CameraManager?, id: String?, on: Boolean): Boolean {
        if (cm == null || id == null) return false
        return runCatching { cm.setTorchMode(id, on) }.isSuccess
    }

    // ---------- speed dial ----------

    fun canReadContacts(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canCall(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /**
     * Looks the number up from your contacts at the moment you tap, so cover never keeps a copy.
     * Calls straight away when allowed, otherwise opens the dialer with the number ready.
     */
    fun call(act: Activity, contactUri: String): String? {
        val found: String? = try {
            act.contentResolver.query(
                Uri.parse(contactUri),
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: SecurityException) {
            return "allow contacts in cfg"
        }
        val number = found ?: return "contact gone, pick again in cfg"
        val action = if (canCall(act)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (startHere(act, Intent(action, Uri.fromParts("tel", number, null)))) null else "couldn't start the call"
    }

    // ---------- capture ----------

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss")

    /** Writes one dated note into the folder picked in cfg (your Obsidian inbox). */
    fun capture(ctx: Context, prefs: Prefs, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val tree = prefs.get(Prefs.CAPTURE_DIR)?.let(Uri::parse)
            ?: throw IllegalStateException("pick an inbox folder in cfg")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "capture " + LocalDateTime.now().format(STAMP) + ".md"
        // octet-stream keeps the .md name as written instead of letting Android add its own extension.
        val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", name)
            ?: throw IllegalStateException("couldn't write to the inbox folder")
        val out = ctx.contentResolver.openOutputStream(doc)
            ?: throw IllegalStateException("couldn't write to the inbox folder")
        out.use { it.write((body + "\n").toByteArray()) }
    }

    fun folderName(uri: String?): String? = uri?.let {
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfterLast(':').substringAfterLast('/') }
            .getOrNull()?.ifBlank { "storage root" }
    }
}
