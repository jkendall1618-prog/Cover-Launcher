package app.cover.launcher

import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Page 5 ("out there"): fuel, the drive to work, music news, and where the day/week/year is at.
// Everything here runs off the main thread; Out holds the latest results for the page to show.

/** Spring Mountain, Qld: where fuel distances and the drive start from (same spot as the weather). */
private const val HOME_LAT = -27.70
private const val HOME_LON = 152.88

private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private fun fetch(url: String, vararg headers: Pair<String, String>): String {
    val b = Request.Builder().url(url)
    headers.forEach { (k, v) -> b.header(k, v) }
    return http.newCall(b.build()).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw HttpError(r.code, r.header("Retry-After")?.toLongOrNull())
        body
    }
}

class HttpError(val code: Int, val retryAfter: Long? = null) : Exception(
    when (code) {
        401, 403 -> "key refused ($code)"
        429 -> "rate limited"
        else -> "error $code"
    },
)

/** Straight-line km between two points. */
private fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/** What page 5 is showing, kept here so it survives the page sliding away and back. */
object Out {
    var fuel by mutableStateOf<Result<FuelInfo>?>(null)
    var drive by mutableStateOf<Result<DriveInfo>?>(null)
    var music by mutableStateOf<Music?>(null)
    var musicBusy by mutableStateOf(false)

    var fuelAt = 0L
    var driveAt = 0L

    const val FUEL_EVERY = 30 * 60_000L
    const val DRIVE_EVERY = 10 * 60_000L
    const val MUSIC_EVERY = 12 * 60 * 60_000L
    /** While some artists' releases are still unchecked, the next small batch goes this soon. */
    const val SCAN_AGAIN = 15 * 60_000L
    /** After a failure with nothing to show, try again this soon. */
    const val MUSIC_RETRY = 30 * 60_000L
}

// ---------- fuel ----------

data class Servo(
    val name: String,
    val lat: Double,
    val lng: Double,
    /** Road distance from home, roughly: straight line × 1.3. */
    val km: Double,
    /** Cents per litre. */
    val price: Double,
    /** Price once the fuel to get there and back is counted. */
    val effective: Double,
    val best: Boolean,
    val cheapest: Boolean,
)

data class FuelInfo(val servos: List<Servo>, val trend: String?)

private class RawPrice(val site: JSONObject, val price: Double, val km: Double)

/**
 * Queensland's fuel price reporting API (free, needs a data-consumer token from fuelpricesqld.com.au).
 * "Recommended" = cheapest once the detour is costed: a 40 L fill, 8 L/100 km, there and back.
 */
object Fuel {
    private const val BASE = "https://fppdirectapi-prod.fuelpricesqld.com.au"
    private const val Q = "countryId=21&geoRegionLevel=3&geoRegionId=1" // the whole state
    private const val RADIUS_KM = 10.0
    private const val ROAD = 1.3
    private const val L_PER_100KM = 8.0
    private const val FILL_L = 40.0
    private const val SHOW = 3

    private fun get(token: String, path: String) =
        fetch("$BASE$path", "Authorization" to "FPDAPI SubscriberToken=$token", "Content-Type" to "application/json")

    private fun arr(j: JSONObject, vararg names: String): JSONArray =
        names.firstNotNullOfOrNull { j.optJSONArray(it) } ?: JSONArray()

    /** The id for regular unleaded (U91), looked up once by name. */
    private fun fuelId(prefs: Prefs, token: String): Int {
        prefs.get(Prefs.FUEL_ID)?.toIntOrNull()?.let { return it }
        val fuels = arr(JSONObject(get(token, "/Subscriber/GetCountryFuelTypes?countryId=21")), "Fuels", "fuels")
        var id = 2
        for (i in 0 until fuels.length()) {
            val f = fuels.getJSONObject(i)
            val name = f.optString("Name", f.optString("name")).trim()
            if (name.equals("Unleaded", ignoreCase = true) || name.contains("91")) {
                id = f.optInt("FuelId", f.optInt("fuelId", id))
                break
            }
        }
        prefs.put(Prefs.FUEL_ID to id.toString())
        return id
    }

    /** Servos within RADIUS_KM of home: [id, name, lat, lng]. The state-wide list is fetched at most daily. */
    private fun sites(prefs: Prefs, token: String): JSONArray {
        val cached = prefs.get(Prefs.FUEL_SITES)
        if (cached != null && System.currentTimeMillis() - prefs.getLong(Prefs.FUEL_SITES_AT) < 24 * 3_600_000L) {
            return JSONArray(cached)
        }
        val all = arr(JSONObject(get(token, "/Subscriber/GetFullSiteDetails?$Q")), "S", "sites", "Sites")
        val near = JSONArray()
        for (i in 0 until all.length()) {
            val s = all.getJSONObject(i)
            val lat = s.optDouble("Lat", s.optDouble("lat", Double.NaN))
            val lng = s.optDouble("Lng", s.optDouble("lng", Double.NaN))
            if (lat.isNaN() || lng.isNaN()) continue
            if (km(HOME_LAT, HOME_LON, lat, lng) > RADIUS_KM) continue
            near.put(JSONObject().put("id", s.optLong("S", s.optLong("SiteId"))).put("name", s.optString("N")).put("lat", lat).put("lng", lng))
        }
        prefs.put(Prefs.FUEL_SITES to near.toString(), Prefs.FUEL_SITES_AT to System.currentTimeMillis())
        return near
    }

    fun load(prefs: Prefs): FuelInfo {
        val token = prefs.get(Prefs.FUEL_TOKEN) ?: throw IllegalStateException("add a fuel token in cfg")
        val fid = fuelId(prefs, token)
        val near = sites(prefs, token)
        val byId = HashMap<Long, JSONObject>()
        for (i in 0 until near.length()) near.getJSONObject(i).let { byId[it.getLong("id")] = it }

        val prices = arr(JSONObject(get(token, "/Price/GetSitesPrices?$Q")), "SitePrices", "sitePrices")
        val raw = ArrayList<RawPrice>()
        for (i in 0 until prices.length()) {
            val p = prices.getJSONObject(i)
            if (p.optInt("FuelId", -1) != fid) continue
            val site = byId[p.optLong("SiteId", -1)] ?: continue
            val c = p.optDouble("Price", 0.0) / 10.0 // reported in tenths of a cent
            if (c < 50 || c > 400) continue // 9999 and friends mean "not selling"
            raw += RawPrice(site, c, km(HOME_LAT, HOME_LON, site.getDouble("lat"), site.getDouble("lng")) * ROAD)
        }
        if (raw.isEmpty()) return FuelInfo(emptyList(), trend(prefs, null))

        fun eff(r: RawPrice) = r.price * (1 + 2 * r.km * L_PER_100KM / 100 / FILL_L)
        val bestRaw = raw.minBy { eff(it) }
        val cheapRaw = raw.minBy { it.price }
        // Best value first; the outright cheapest always makes the list so the trade-off is visible.
        val shown = raw.sortedBy { eff(it) }.take(SHOW).let { top -> if (top.any { it === cheapRaw }) top else top.dropLast(1) + cheapRaw }
        val servos = shown.map {
            Servo(
                it.site.optString("name"), it.site.getDouble("lat"), it.site.getDouble("lng"),
                it.km, it.price, eff(it), best = it === bestRaw, cheapest = it === cheapRaw,
            )
        }
        val median = raw.map { it.price }.sorted().let { it[it.size / 2] }
        return FuelInfo(servos, trend(prefs, median))
    }

    /** Brisbane prices run in cycles. Keeps a daily median of nearby prices and compares with a few days back. */
    private fun trend(prefs: Prefs, today: Double?): String? {
        val hist = runCatching { JSONObject(prefs.get(Prefs.FUEL_HIST) ?: "{}") }.getOrDefault(JSONObject())
        val now = LocalDate.now()
        if (today != null) hist.put(now.toString(), today)
        val keep = JSONObject()
        hist.keys().forEach { k ->
            val d = runCatching { LocalDate.parse(k) }.getOrNull()
            if (d != null && ChronoUnit.DAYS.between(d, now) <= 10) keep.put(k, hist.getDouble(k))
        }
        prefs.put(Prefs.FUEL_HIST to keep.toString())
        val t = if (keep.has(now.toString())) keep.getDouble(now.toString()) else return null
        val past = (2..5).map { now.minusDays(it.toLong()).toString() }.firstOrNull { keep.has(it) } ?: return "learning"
        val diff = t - keep.getDouble(past)
        return when {
            diff > 3 -> "rising"
            diff < -3 -> "falling"
            else -> "steady"
        }
    }
}

// ---------- drive ----------

data class DriveInfo(val minutes: Int, val usual: Int, val crashes: List<String>, val lat: Double, val lng: Double)

/** TomTom for live drive time (free tier, no card); QLDTraffic for crashes on that route. */
object Drive {
    /** QLDTraffic's published public key; replace it in cfg with your own if it stops working. */
    const val QLDT_PUBLIC = "3e83add325cbb69ac4d8e5bf433d770b"

    private fun work(prefs: Prefs, key: String): Pair<Double, Double> {
        val addr = prefs.get(Prefs.WORK_ADDR) ?: throw IllegalStateException("set the work address in cfg")
        prefs.get(Prefs.WORK_LL)?.split("|")?.takeIf { it.size == 3 && it[2] == addr }?.let {
            return it[0].toDouble() to it[1].toDouble()
        }
        val j = JSONObject(fetch("https://api.tomtom.com/search/2/geocode/${Uri.encode(addr)}.json?key=$key&countrySet=AU&limit=1"))
        val pos = j.optJSONArray("results")?.optJSONObject(0)?.optJSONObject("position")
            ?: throw IllegalStateException("couldn't find the work address")
        val ll = pos.getDouble("lat") to pos.getDouble("lon")
        prefs.put(Prefs.WORK_LL to "${ll.first}|${ll.second}|$addr")
        return ll
    }

    fun load(prefs: Prefs): DriveInfo {
        val key = prefs.get(Prefs.TOMTOM_KEY) ?: throw IllegalStateException("add a tomtom key in cfg")
        val (wLat, wLon) = work(prefs, key)
        val url = "https://api.tomtom.com/routing/1/calculateRoute/$HOME_LAT,$HOME_LON:$wLat,$wLon/json" +
            "?key=$key&traffic=true&travelMode=car&routeType=fastest&computeTravelTimeFor=all"
        val route = JSONObject(fetch(url)).getJSONArray("routes").getJSONObject(0)
        val sum = route.getJSONObject("summary")
        val now = sum.getInt("travelTimeInSeconds")
        val free = sum.optInt("noTrafficTravelTimeInSeconds", now - sum.optInt("trafficDelayInSeconds", 0))

        val pts = ArrayList<Pair<Double, Double>>()
        val legs = route.optJSONArray("legs") ?: JSONArray()
        for (i in 0 until legs.length()) {
            val p = legs.getJSONObject(i).optJSONArray("points") ?: continue
            for (k in 0 until p.length()) p.getJSONObject(k).let { pts += it.getDouble("latitude") to it.getDouble("longitude") }
        }
        val crashes = runCatching { crashesNear(prefs, pts) }.getOrDefault(emptyList())
        return DriveInfo((now + 30) / 60, (free + 30) / 60, crashes, wLat, wLon)
    }

    /** Current crashes within ~300 m of the route. */
    private fun crashesNear(prefs: Prefs, route: List<Pair<Double, Double>>): List<String> {
        if (route.isEmpty()) return emptyList()
        // Shared with the map page and cached, so the two don't double up on QLDTraffic.
        val feats = QldEvents.features(prefs)
        val minLat = route.minOf { it.first } - 0.01
        val maxLat = route.maxOf { it.first } + 0.01
        val minLon = route.minOf { it.second } - 0.01
        val maxLon = route.maxOf { it.second } + 0.01
        val out = ArrayList<String>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val p = f.optJSONObject("properties") ?: continue
            if (!p.optString("event_type").equals("Crash", ignoreCase = true)) continue
            val coords = ArrayList<Pair<Double, Double>>()
            collect(f.optJSONObject("geometry"), coords)
            val hit = coords.any { (lat, lon) ->
                lat in minLat..maxLat && lon in minLon..maxLon && route.any { km(lat, lon, it.first, it.second) < 0.3 }
            }
            if (!hit) continue
            val road = p.optJSONObject("road_summary")
            val where = listOfNotNull(
                road?.optString("road_name")?.takeIf { it.isNotBlank() },
                road?.optString("locality")?.takeIf { it.isNotBlank() },
            ).joinToString(", ")
            val impact = p.optJSONObject("impact")?.optString("impact_type")?.takeIf { it.isNotBlank() }
            out += listOfNotNull("crash · $where".takeIf { where.isNotBlank() } ?: "crash on route", impact).joinToString(" · ").lowercase()
        }
        return out.distinct()
    }

    /** GeoJSON coordinates are [lon, lat]; walks points, lines and collections alike. */
    private fun collect(g: JSONObject?, out: MutableList<Pair<Double, Double>>) {
        if (g == null) return
        g.optJSONArray("geometries")?.let { for (i in 0 until it.length()) collect(it.optJSONObject(i), out) }
        val c = g.optJSONArray("coordinates") ?: return
        fun walk(a: JSONArray) {
            if (a.length() >= 2 && a.opt(0) is Number) out += a.getDouble(1) to a.getDouble(0)
            else for (i in 0 until a.length()) a.optJSONArray(i)?.let(::walk)
        }
        walk(c)
    }
}

// ---------- music ----------

data class Release(val artist: String, val title: String, val date: LocalDate, val uri: String, val single: Boolean)
data class Gig(val artist: String, val date: LocalDate, val venue: String, val url: String)
/**
 * [pending]: followed artists whose releases haven't been checked yet, so an empty list isn't "nothing new".
 * [next]: when the page should refresh again. [gigsAt]: when gigs were last fetched (they keep a 12-hour cycle).
 */
data class Music(
    val releases: List<Release>,
    val gigs: List<Gig>,
    val error: String?,
    val at: Long,
    val pending: Int = 0,
    val next: Long = at + Out.MUSIC_EVERY,
    val gigsAt: Long = at,
)

/** Spotify asked for a long wait (or kept refusing); no Spotify calls until [until]. */
class SpotifyPaused(val until: Long) : Exception("rate limited, paused till ${clock(until)}")

private fun clock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

/** Spotify sign-in (PKCE, no secret on the phone) for followed artists and their new releases. */
object Spotify {
    const val REDIRECT = "coverlauncher://spotify/callback"
    /** Follows, top artists, and following everyone in a playlist. Changing this needs a reconnect. */
    private const val SCOPE = "user-follow-read user-follow-modify user-library-modify user-top-read " +
        "playlist-read-private playlist-read-collaborative"

    fun connected(prefs: Prefs) = prefs.get(Prefs.SP_REFRESH) != null

    /** False for connections made before the playlist-follow update: they need one more approval. */
    fun hasScopes(prefs: Prefs) = prefs.get(Prefs.SP_SCOPE)?.let { "playlist-read-private" in it && "user-follow-modify" in it } == true

    fun authUrl(prefs: Prefs): Uri {
        val client = prefs.get(Prefs.SP_CLIENT) ?: throw IllegalStateException("add your spotify client id first")
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = SecureRandom()
        val verifier = (1..64).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        prefs.put(Prefs.SP_VERIFIER to verifier)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", client)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPE)
            .build()
    }

    fun isCallback(uri: Uri?) = uri != null && uri.toString().startsWith(REDIRECT)

    /** Called with the redirect Spotify sends back after you approve. */
    fun finish(prefs: Prefs, uri: Uri) {
        val code = uri.getQueryParameter("code") ?: throw IllegalStateException(uri.getQueryParameter("error") ?: "no code")
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT)
            .add("client_id", prefs.get(Prefs.SP_CLIENT).orEmpty())
            .add("code_verifier", prefs.get(Prefs.SP_VERIFIER).orEmpty())
            .build()
        save(prefs, post(form))
        prefs.put(Prefs.SP_VERIFIER to null)
    }

    fun disconnect(prefs: Prefs) =
        prefs.put(
            Prefs.SP_ACCESS to null, Prefs.SP_REFRESH to null, Prefs.SP_EXPIRES to null, Prefs.SP_ARTISTS to null, Prefs.SP_SCOPE to null,
            Prefs.SP_ARTIST_LIST to null, Prefs.SP_ARTISTS_AT to null, Prefs.SP_SCAN to null, Prefs.SP_PAUSED to null,
        )

    private fun post(form: FormBody): JSONObject {
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(form).build()
        return http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("spotify sign-in failed (${r.code})")
            JSONObject(body)
        }
    }

    private fun save(prefs: Prefs, j: JSONObject) {
        prefs.put(
            Prefs.SP_ACCESS to j.getString("access_token"),
            Prefs.SP_EXPIRES to System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 60_000,
        )
        j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.put(Prefs.SP_REFRESH to it) }
        j.optString("scope").takeIf { it.isNotBlank() }?.let { prefs.put(Prefs.SP_SCOPE to it) }
    }

    private fun token(prefs: Prefs): String {
        val access = prefs.get(Prefs.SP_ACCESS)
        if (access != null && System.currentTimeMillis() < prefs.getLong(Prefs.SP_EXPIRES)) return access
        val refresh = prefs.get(Prefs.SP_REFRESH) ?: throw IllegalStateException("connect spotify in cfg")
        save(
            prefs,
            post(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", prefs.get(Prefs.SP_CLIENT).orEmpty())
                    .build(),
            ),
        )
        return prefs.get(Prefs.SP_ACCESS)!!
    }

    // ----- rate limits -----
    // Spotify answers "too many requests" (429) with a Retry-After in seconds. Short waits are slept off once;
    // a long one is written down and every Spotify call stops until it passes. Retrying early only extends it.

    /** Longest Retry-After worth sleeping through; anything longer pauses Spotify instead. */
    private const val MAX_WAIT_S = 5L
    /** Shortest pause, for when Spotify gives no Retry-After or refuses twice in a row. */
    private const val MIN_PAUSE_S = 120L

    /** When Spotify calls may resume, or 0 if they're not paused. */
    fun pausedUntil(prefs: Prefs): Long = prefs.getLong(Prefs.SP_PAUSED).takeIf { it > System.currentTimeMillis() } ?: 0L

    private fun checkPaused(prefs: Prefs) {
        val until = pausedUntil(prefs)
        if (until > 0) throw SpotifyPaused(until)
    }

    /** Sleeps off a short Retry-After, or records a pause and throws. */
    private fun backOff(prefs: Prefs, retryAfter: Long?, lastTry: Boolean) {
        val s = retryAfter
        if (s == null || s > MAX_WAIT_S || lastTry) {
            val until = System.currentTimeMillis() + maxOf(s ?: 0L, MIN_PAUSE_S) * 1000
            prefs.put(Prefs.SP_PAUSED to until)
            throw SpotifyPaused(until)
        }
        Thread.sleep(s * 1000 + 250)
    }

    private fun api(prefs: Prefs, url: String): JSONObject {
        checkPaused(prefs)
        for (attempt in 1..2) {
            try {
                return JSONObject(fetch(url, "Authorization" to "Bearer ${token(prefs)}"))
            } catch (e: HttpError) {
                if (e.code != 429) throw e
                backOff(prefs, e.retryAfter, lastTry = attempt == 2)
            }
        }
        throw IllegalStateException("unreachable")
    }

    /** A PUT with no body, backing off the same way when Spotify rate-limits. */
    private fun put(prefs: Prefs, url: String) {
        checkPaused(prefs)
        for (attempt in 1..2) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token(prefs)}")
                .put(ByteArray(0).toRequestBody(null))
                .build()
            val retryAfter = http.newCall(req).execute().use { r ->
                if (r.isSuccessful) return
                if (r.code != 429) throw HttpError(r.code)
                r.header("Retry-After")?.toLongOrNull()
            }
            backOff(prefs, retryAfter, lastTry = attempt == 2)
        }
    }

    /** The artist list saved by the last successful lookup, as (id, name). Empty if there isn't one. */
    fun cachedArtists(prefs: Prefs): List<Pair<String, String>> {
        val raw = prefs.get(Prefs.SP_ARTIST_LIST) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONArray(it) }.map { it.getString(0) to it.getString(1) }
        }.getOrDefault(emptyList())
    }

    /**
     * Followed artists plus your most-played artists of the last six months or so, as (id, name).
     * Looked up at most once a day: the list barely changes, and each lookup is up to 8 requests.
     */
    fun artists(prefs: Prefs): List<Pair<String, String>> {
        val cached = cachedArtists(prefs)
        if (cached.isNotEmpty() && System.currentTimeMillis() - prefs.getLong(Prefs.SP_ARTISTS_AT) < 24 * 3_600_000L) return cached
        val top = try {
            val items = api(prefs, "https://api.spotify.com/v1/me/top/artists?time_range=medium_term&limit=50")
                .optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map { items.getJSONObject(it) }.map { it.getString("id") to it.getString("name") }
        } catch (e: SpotifyPaused) {
            throw e
        } catch (e: Exception) {
            emptyList() // older connections can't read top artists until reconnected
        }
        val all = (followed(prefs) + top).distinctBy { it.first }
        prefs.put(
            Prefs.SP_ARTIST_LIST to JSONArray(all.map { JSONArray().put(it.first).put(it.second) }).toString(),
            Prefs.SP_ARTISTS_AT to System.currentTimeMillis(),
            Prefs.SP_ARTISTS to JSONArray(all.map { it.second }).toString(),
        )
        return all
    }

    /** Followed artists as (id, name). Capped at 300 to keep the release scan quick. */
    fun followed(prefs: Prefs): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var url: String? = "https://api.spotify.com/v1/me/following?type=artist&limit=50"
        while (url != null && out.size < 300) {
            val a = api(prefs, url).getJSONObject("artists")
            val items = a.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.getJSONObject(i).let { out += it.getString("id") to it.getString("name") }
            url = a.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return out
    }

    /** Everything needed to show "follow N artists?" before doing it. */
    data class FollowPlan(val all: List<Pair<String, String>>, val fresh: List<Pair<String, String>>)

    fun playlistId(input: String): String? =
        Regex("playlist[/:]([A-Za-z0-9]{10,})").find(input)?.groupValues?.get(1)
            ?: input.trim().takeIf { Regex("[A-Za-z0-9]{22}").matches(it) }

    /** Each track's main artist (not guest features), minus anyone already followed. */
    fun planFollow(prefs: Prefs, playlist: String): FollowPlan {
        val id = playlistId(playlist) ?: throw IllegalStateException("that doesn't look like a playlist link")
        val seen = LinkedHashMap<String, String>()
        var url: String? = "https://api.spotify.com/v1/playlists/$id/items?limit=50"
        while (url != null) {
            val j = try {
                api(prefs, url)
            } catch (e: HttpError) {
                if (e.code == 403 || e.code == 404) throw IllegalStateException("spotify only shares playlists you own or collaborate on")
                throw e
            }
            val items = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val entry = items.getJSONObject(i)
                val t = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue
                val a = t.optJSONArray("artists")?.optJSONObject(0) ?: continue
                val aid = a.optString("id")
                if (aid.isNotBlank()) seen.putIfAbsent(aid, a.optString("name"))
            }
            url = j.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        if (seen.isEmpty()) throw IllegalStateException("no artists found; spotify only shares playlists you own or collaborate on")
        val already = followed(prefs).map { it.first }.toSet()
        val all = seen.map { it.key to it.value }
        return FollowPlan(all, all.filter { it.first !in already })
    }

    /** Follows the given artists, 40 at a time. Returns how many. */
    fun follow(prefs: Prefs, ids: List<String>): Int {
        var done = 0
        for (chunk in ids.chunked(40)) {
            val uris = chunk.joinToString(",") { "spotify:artist:$it" }
            try {
                put(prefs, "https://api.spotify.com/v1/me/library?uris=$uris")
            } catch (e: HttpError) {
                if (e.code == 401) throw IllegalStateException("reconnect spotify in cfg")
                // Spotify's new library endpoint; if it won't take artists for this app, try the older follow call.
                runCatching { put(prefs, "https://api.spotify.com/v1/me/following?type=artist&ids=" + chunk.joinToString(",")) }
                    .onFailure { throw IllegalStateException("spotify refused the follow (${e.code}); followed $done so far") }
            }
            done += chunk.size
            Thread.sleep(200)
        }
        prefs.put(Prefs.SP_ARTISTS_AT to null) // re-read the artist list next refresh so the new follows get scanned
        return done
    }

    // ----- new releases -----
    // Spotify has no "new from artists I follow" call, so it's one request per artist. Hundreds of those in a
    // burst is what trips the rate limit, so artists are checked in small, spaced batches and each result is
    // kept, so a refresh only asks about the artists whose last check is out of date.

    /** Release lookups this far apart: about two a second. */
    private const val GAP_MS = 500L
    /** Artists checked per refresh; the rest wait for the next batch. */
    private const val BATCH = 60
    /** An artist's last check counts as current for this long. */
    private const val STALE_MS = 20 * 3_600_000L
    /** Releases kept per artist, in days, so the 30-day window is always covered. */
    private const val KEEP_DAYS = 45L

    /**
     * [unchecked]: artists never checked yet, so their releases are unknown.
     * [moreDue]: artists still out of date after this batch, so another batch should follow soon.
     * [error]: what stopped this batch early, if anything; [releases] still holds everything known.
     */
    class Scan(val releases: List<Release>, val unchecked: Int, val moreDue: Boolean, val error: Exception?)

    /** Checks the next batch of out-of-date artists and returns every release known from the last [days] days. */
    fun scan(prefs: Prefs, artists: List<Pair<String, String>>, days: Long = 30): Scan {
        val ids = artists.map { it.first }.toSet()
        val old = runCatching { JSONObject(prefs.get(Prefs.SP_SCAN) ?: "{}") }.getOrDefault(JSONObject())
        val state = JSONObject()
        old.keys().forEach { k -> if (k in ids) old.optJSONObject(k)?.let { state.put(k, it) } } // unfollowed artists drop out

        fun lastChecked(id: String) = state.optJSONObject(id)?.optLong("at") ?: 0L
        val start = System.currentTimeMillis()
        val due = artists.filter { start - lastChecked(it.first) > STALE_MS }.sortedBy { lastChecked(it.first) }

        val keepSince = LocalDate.now().minusDays(KEEP_DAYS)
        var error: Exception? = null
        try {
            for ((i, artist) in due.take(BATCH).withIndex()) {
                val (id, name) = artist
                if (i > 0) Thread.sleep(GAP_MS)
                val items = try {
                    api(prefs, "https://api.spotify.com/v1/artists/$id/albums?include_groups=album,single&market=AU&limit=10")
                        .optJSONArray("items") ?: JSONArray()
                } catch (e: HttpError) {
                    if (e.code != 400 && e.code != 404) throw e
                    JSONArray() // artist gone from Spotify: count it as checked, nothing new
                }
                val found = JSONArray()
                for (k in 0 until items.length()) {
                    val a = items.getJSONObject(k)
                    if (a.optString("release_date_precision") != "day") continue
                    val d = runCatching { LocalDate.parse(a.getString("release_date")) }.getOrNull() ?: continue
                    if (d.isBefore(keepSince)) continue
                    found.put(
                        JSONObject().put("t", a.optString("name")).put("d", d.toString())
                            .put("u", a.optString("uri")).put("s", a.optString("album_type") == "single"),
                    )
                }
                state.put(id, JSONObject().put("at", System.currentTimeMillis()).put("n", name).put("r", found))
                if (i % 10 == 9) prefs.put(Prefs.SP_SCAN to state.toString()) // keep progress if the app is closed
            }
        } catch (e: Exception) {
            error = e
        }
        prefs.put(Prefs.SP_SCAN to state.toString())

        val since = LocalDate.now().minusDays(days)
        val latest = LocalDate.now().plusDays(1)
        val out = LinkedHashMap<String, Release>()
        for ((id, name) in artists) {
            val r = state.optJSONObject(id)?.optJSONArray("r") ?: continue
            for (k in 0 until r.length()) {
                val x = r.getJSONObject(k)
                val d = runCatching { LocalDate.parse(x.getString("d")) }.getOrNull() ?: continue
                if (d.isBefore(since) || d.isAfter(latest)) continue
                val uri = x.optString("u")
                out.putIfAbsent(uri, Release(name, x.optString("t"), d, uri, x.optBoolean("s")))
            }
        }
        val now = System.currentTimeMillis()
        return Scan(
            releases = out.values.sortedByDescending { it.date }.take(40),
            unchecked = artists.count { lastChecked(it.first) == 0L },
            moreDue = artists.any { now - lastChecked(it.first) > STALE_MS },
            error = error,
        )
    }
}

/** Ticketmaster's Discovery API (free key). Only finds shows Ticketmaster sells. */
object Gigs {
    fun load(prefs: Prefs, artistNames: Collection<String>): List<Gig> {
        val key = prefs.get(Prefs.TM_KEY) ?: throw IllegalStateException("add a ticketmaster key in cfg")
        val wanted = artistNames.map { it.lowercase().trim() }.toSet()
        if (wanted.isEmpty()) return emptyList()
        val out = ArrayList<Gig>()
        var page = 0
        var pages = 1
        while (page < pages && page < 5) {
            val j = JSONObject(
                fetch(
                    "https://app.ticketmaster.com/discovery/v2/events.json?apikey=$key" +
                        "&countryCode=AU&stateCode=QLD&classificationName=music&size=200&sort=date,asc&page=$page",
                ),
            )
            pages = j.optJSONObject("page")?.optInt("totalPages", 1) ?: 1
            val events = j.optJSONObject("_embedded")?.optJSONArray("events") ?: break
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                val emb = e.optJSONObject("_embedded") ?: continue
                val acts = emb.optJSONArray("attractions") ?: continue
                val hit = (0 until acts.length()).map { acts.getJSONObject(it).optString("name") }
                    .firstOrNull { it.lowercase().trim() in wanted } ?: continue
                val date = try {
                    LocalDate.parse(e.optJSONObject("dates")?.optJSONObject("start")?.optString("localDate"))
                } catch (x: DateTimeParseException) {
                    continue
                } catch (x: NullPointerException) {
                    continue
                }
                val venue = emb.optJSONArray("venues")?.optJSONObject(0)?.optString("name").orEmpty()
                out += Gig(hit, date, venue, e.optString("url"))
            }
            page++
            Thread.sleep(250) // 5 requests a second at most
        }
        return out.distinctBy { it.artist + it.date + it.venue }.sortedBy { it.date }
    }
}

/**
 * Releases and gigs together, kept in cover's storage. Gigs refresh every 12 hours; releases are checked in
 * batches (see [Spotify.scan]), so a refresh may come sooner while artists are still waiting to be checked.
 * A failed refresh never throws away what's already known.
 */
object MusicFeed {
    fun cached(prefs: Prefs): Music? {
        val raw = prefs.get(Prefs.MUSIC_JSON) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val r = j.getJSONArray("r")
            val g = j.getJSONArray("g")
            val at = j.getLong("at")
            Music(
                (0 until r.length()).map { r.getJSONObject(it) }.map {
                    Release(it.getString("a"), it.getString("t"), LocalDate.parse(it.getString("d")), it.getString("u"), it.optBoolean("s"))
                }.filter { !it.date.isBefore(LocalDate.now().minusDays(30)) },
                (0 until g.length()).map { g.getJSONObject(it) }.map {
                    Gig(it.getString("a"), LocalDate.parse(it.getString("d")), it.getString("v"), it.getString("u"))
                }.filter { !it.date.isBefore(LocalDate.now()) },
                j.optString("e").takeIf { it.isNotBlank() },
                at,
                pending = j.optInt("p", 0),
                next = j.optLong("n", at + Out.MUSIC_EVERY),
                gigsAt = j.optLong("ga", at),
            )
        }.getOrNull()
    }

    private fun describe(e: Throwable): String = when (e) {
        is SpotifyPaused -> e.message.orEmpty()
        else -> e.message ?: e.javaClass.simpleName
    }

    /** [force]: the refresh button, which also re-fetches gigs. Spotify's batch size and pauses still apply. */
    fun refresh(prefs: Prefs, force: Boolean = false): Music {
        val prev = cached(prefs)
        val now = System.currentTimeMillis()
        var spotifyError: Throwable? = null
        var releases = prev?.releases ?: emptyList()
        var pending = prev?.pending ?: 0
        var moreDue = false
        var artists: List<Pair<String, String>> = emptyList()

        if (Spotify.connected(prefs)) {
            artists = try {
                Spotify.artists(prefs)
            } catch (e: Exception) {
                spotifyError = e
                Spotify.cachedArtists(prefs)
            }
            if (artists.isNotEmpty()) {
                val scan = Spotify.scan(prefs, artists)
                releases = scan.releases
                pending = scan.unchecked
                moreDue = scan.moreDue
                if (spotifyError == null) spotifyError = scan.error
            }
        } else {
            releases = emptyList()
            pending = 0
        }

        // Gigs: matched against artist names, so a Spotify hiccup falls back to the last known names.
        val names = artists.map { it.second }.ifEmpty {
            runCatching { JSONArray(prefs.get(Prefs.SP_ARTISTS) ?: "[]").let { a -> (0 until a.length()).map { a.getString(it) } } }
                .getOrDefault(emptyList())
        }
        var gigs = prev?.gigs ?: emptyList()
        var gigsAt = prev?.gigsAt ?: 0L
        var gigError: Throwable? = null
        if (names.isNotEmpty() && (force || prev == null || now - gigsAt >= Out.MUSIC_EVERY)) {
            runCatching { Gigs.load(prefs, names) }
                .onSuccess { gigs = it; gigsAt = now }
                .onFailure { gigError = it }
        }

        val errors = listOfNotNull(
            if (!Spotify.connected(prefs)) "connect spotify in cfg" else spotifyError?.let { "spotify: ${describe(it)}" },
            gigError?.let { "gigs: ${describe(it)}" },
        )
        val paused = Spotify.pausedUntil(prefs)
        val next = when {
            paused > 0 -> paused + 60_000L
            errors.isNotEmpty() -> now + Out.MUSIC_RETRY
            moreDue -> now + Out.SCAN_AGAIN
            else -> now + Out.MUSIC_EVERY
        }
        val m = Music(releases, gigs, errors.joinToString(" · ").takeIf { it.isNotBlank() }, now, pending, next, gigsAt)
        val j = JSONObject()
            .put("r", JSONArray(releases.map { JSONObject().put("a", it.artist).put("t", it.title).put("d", it.date.toString()).put("u", it.uri).put("s", it.single) }))
            .put("g", JSONArray(gigs.map { JSONObject().put("a", it.artist).put("d", it.date.toString()).put("v", it.venue).put("u", it.url) }))
            .put("e", m.error ?: "")
            .put("at", m.at)
            .put("p", m.pending)
            .put("n", m.next)
            .put("ga", m.gigsAt)
        prefs.put(Prefs.MUSIC_JSON to j.toString())
        return m
    }
}
