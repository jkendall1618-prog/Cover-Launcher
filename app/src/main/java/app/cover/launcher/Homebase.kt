package app.cover.launcher

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class Task(
    val id: String,
    val title: String,
    val due: LocalDate?,
    val repeat: String,
    val rotate: Boolean,
    val owner: String,
)

data class Grocery(val id: String, val name: String, val aisle: String)

/**
 * Talks to Homebase's Supabase as the signed-in user, so the same row-level security
 * applies as in the Homebase app. Only the session tokens are stored, never the password.
 */
class Homebase(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    val signedIn get() = prefs.get(Prefs.HB_REFRESH) != null
    val email get() = prefs.get(Prefs.HB_EMAIL)

    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val res = auth("password", body)
        saveSession(res, email.trim())
    }

    fun signOut() = prefs.put(
        Prefs.HB_ACCESS to null, Prefs.HB_REFRESH to null,
        Prefs.HB_EXPIRES to null, Prefs.HB_USER to null,
    )

    fun openTasks(limit: Int = 6): List<Task> {
        if (!signedIn) return emptyList()
        val me = prefs.get(Prefs.HB_USER)
        val arr = JSONArray(
            get("/rest/v1/tasks?select=id,title,due_date,repeat,rotate,owner&done_at=is.null&order=due_date.asc.nullslast&limit=40")
        )
        val all = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Task(
                id = o.getString("id"),
                title = o.optString("title"),
                due = o.optString("due_date").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),
                repeat = o.optString("repeat", "none"),
                rotate = o.optBoolean("rotate", false),
                owner = o.optString("owner", "family"),
            )
        }
        // Yours and the family's; your partner's own tasks stay in Homebase.
        return all.filter { it.owner == "family" || it.owner == me }.take(limit)
    }

    /** Unticked grocery list items, in aisle order like Homebase shows them. */
    fun groceries(): List<Grocery> {
        if (!signedIn) return emptyList()
        val arr = JSONArray(get("/rest/v1/grocery_items?select=id,name,aisle&done=is.false&order=aisle.asc,created_at.asc&limit=100"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Grocery(o.getString("id"), o.optString("name"), o.optString("aisle", "Other"))
        }
    }

    /** Adds to the shared list. Homebase fills in the household; the aisle starts as "Other". */
    fun addGrocery(name: String) {
        send("POST", "/rest/v1/grocery_items", JSONObject().put("name", name.trim()).toString())
    }

    fun tick(g: Grocery) {
        val patch = JSONObject().put("done", true).put("done_at", Instant.now().toString())
        send("PATCH", "/rest/v1/grocery_items?id=eq.${g.id}", patch.toString())
    }

    /** Mirrors Homebase's own "complete": repeating tasks roll forward, one-offs are marked done. */
    fun complete(t: Task) {
        val now = Instant.now().toString()
        val me = prefs.get(Prefs.HB_USER)
        val patch = JSONObject().put("last_done_at", now).put("last_done_by", me ?: JSONObject.NULL)
        if (t.repeat != "none") {
            patch.put("due_date", nextDue(t.due, t.repeat).toString())
            if (t.rotate) {
                val members = JSONArray(get("/rest/v1/members?select=user_id"))
                val ids = (0 until members.length()).map { members.getJSONObject(it).getString("user_id") }
                if (ids.size > 1) {
                    val i = ids.indexOf(t.owner)
                    patch.put("owner", ids[(i + 1) % ids.size])
                }
            }
        } else {
            patch.put("done_at", now)
        }
        send("PATCH", "/rest/v1/tasks?id=eq.${t.id}", patch.toString())
    }

    /** Members and people without logins, for turning "+jac" into Homebase's for_whom keys. */
    fun household(): List<Whom> {
        val out = mutableListOf<Whom>()
        val members = JSONArray(get("/rest/v1/members?select=user_id,display_name"))
        for (i in 0 until members.length()) {
            val o = members.getJSONObject(i)
            out += Whom(o.getString("user_id"), o.optString("display_name"))
        }
        val people = JSONArray(get("/rest/v1/people?select=id,name"))
        for (i in 0 until people.length()) {
            val o = people.getJSONObject(i)
            out += Whom("p:" + o.getString("id"), o.optString("name"))
        }
        return out
    }

    val me: String? get() = prefs.get(Prefs.HB_USER)

    /**
     * Saves an event the way Homebase's own form does: household filled in by the database,
     * all-day events running local midnight to midnight. Returns the new event's id for undo.
     * Reminders come from Homebase's scheduler, which reads this table, so they work as normal.
     */
    fun addEvent(
        title: String,
        date: LocalDate,
        start: LocalTime?,
        minutes: Long,
        allDay: Boolean,
        location: String?,
        forWhom: List<String>,
        remind: String,
    ): String {
        val zone = ZoneId.systemDefault()
        val begins: ZonedDateTime
        val ends: ZonedDateTime
        if (allDay || start == null) {
            begins = date.atStartOfDay(zone)
            ends = date.plusDays(1).atStartOfDay(zone)
        } else {
            begins = date.atTime(start).atZone(zone)
            ends = begins.plusMinutes(minutes.coerceAtLeast(1))
        }
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val body = JSONObject()
            .put("title", title.trim())
            .put("starts_at", begins.format(iso))
            .put("ends_at", ends.format(iso))
            .put("all_day", allDay || start == null)
            .put("for_whom", JSONArray(forWhom.ifEmpty { listOf("family") }))
            .put("remind", remind)
            .put("created_by", me ?: JSONObject.NULL)
        if (!location.isNullOrBlank()) body.put("location", location.trim())
        val text = send("POST", "/rest/v1/events?select=id", body.toString(), prefer = "return=representation")
        return JSONArray(text).getJSONObject(0).getString("id")
    }

    fun deleteEvent(id: String) {
        send("DELETE", "/rest/v1/events?id=eq.$id", null)
    }

    companion object {
        const val URL = "https://jrwjfbrkwhvliuggkdka.supabase.co"
        const val KEY = "sb_publishable_wjVj98zIE9PRXApxC2JinA_NqNA1xnM"

        fun nextDue(due: LocalDate?, repeat: String, today: LocalDate = LocalDate.now()): LocalDate {
            var d = due ?: today
            do {
                d = when (repeat) {
                    "daily" -> d.plusDays(1)
                    "weekly" -> d.plusWeeks(1)
                    "fortnightly" -> d.plusWeeks(2)
                    "monthly" -> d.plusMonths(1)
                    else -> return d
                }
            } while (!d.isAfter(today))
            return d
        }
    }

    // --- plumbing ---

    private fun token(): String {
        val exp = prefs.getLong(Prefs.HB_EXPIRES)
        val access = prefs.get(Prefs.HB_ACCESS)
        if (access != null && System.currentTimeMillis() < exp - 60_000) return access
        val refresh = prefs.get(Prefs.HB_REFRESH) ?: throw IllegalStateException("Not signed in to Homebase")
        val res = auth("refresh_token", JSONObject().put("refresh_token", refresh))
        saveSession(res, prefs.get(Prefs.HB_EMAIL))
        return res.getString("access_token")
    }

    private fun saveSession(res: JSONObject, email: String?) {
        prefs.put(
            Prefs.HB_ACCESS to res.getString("access_token"),
            Prefs.HB_REFRESH to res.getString("refresh_token"),
            Prefs.HB_EXPIRES to System.currentTimeMillis() + res.optLong("expires_in", 3600) * 1000,
            Prefs.HB_USER to res.optJSONObject("user")?.optString("id"),
            Prefs.HB_EMAIL to email,
        )
    }

    private fun auth(grant: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$URL/auth/v1/token?grant_type=$grant")
            .header("apikey", KEY)
            .post(body.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (grant == "refresh_token" && r.code in 400..401) signOut()
                val msg = runCatching { JSONObject(text).optString("error_description").ifBlank { JSONObject(text).optString("msg") } }.getOrNull()
                throw IllegalStateException(msg?.ifBlank { null } ?: "Sign-in failed (${r.code})")
            }
            return JSONObject(text)
        }
    }

    private fun get(path: String): String = send("GET", path, null)

    private fun send(method: String, path: String, body: String?, prefer: String = "return=minimal"): String {
        val req = Request.Builder()
            .url(URL + path)
            .header("apikey", KEY)
            .header("Authorization", "Bearer " + token())
            .header("Prefer", prefer)
            .method(method, body?.toRequestBody(json))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("Homebase ${r.code}")
            return text
        }
    }
}
