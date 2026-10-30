package app.cover.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Small encrypted key-value store for the Homebase session and the Anthropic API key. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "cover_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore trouble: fall back to private app storage rather than crash on the cover screen.
        context.getSharedPreferences("cover_plain", Context.MODE_PRIVATE)
    }

    fun get(key: String): String? = sp.getString(key, null)?.takeIf { it.isNotBlank() }
    fun getLong(key: String): Long = sp.getLong(key, 0L)

    /** A speed-dial label as shown on page 2 ("Mom" from build 4 reads as Mum). */
    fun dialName(i: Int): String {
        val saved = get(dialLabel(i))?.trim().orEmpty()
        return when {
            saved.isEmpty() -> DIAL_DEFAULTS[i]
            saved.equals("mom", ignoreCase = true) -> "Mum"
            else -> saved
        }
    }

    fun put(vararg pairs: Pair<String, Any?>) {
        val e = sp.edit()
        for ((k, v) in pairs) when (v) {
            null -> e.remove(k)
            is Long -> e.putLong(k, v)
            else -> e.putString(k, v.toString())
        }
        e.apply()
    }

    companion object {
        const val HB_EMAIL = "hb_email"
        const val HB_ACCESS = "hb_access"
        const val HB_REFRESH = "hb_refresh"
        const val HB_EXPIRES = "hb_expires"
        const val HB_USER = "hb_user"
        const val ANTHROPIC_KEY = "anthropic_key"

        // Page 2
        const val CAPTURE_DIR = "capture_dir"
        fun dialLabel(i: Int) = "dial_label_$i"
        fun dialUri(i: Int) = "dial_uri_$i"
        val DIAL_DEFAULTS = listOf("Jac", "Mum", "Dad")
    }
}
