package app.cover.launcher

import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
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
        val key = prefs.get(Prefs.QLDT_KEY) ?: QLDT_PUBLIC
        val feats = JSONObject(fetch("https://api.qldtraffic.qld.gov.au/v2/events?apikey=$key")).optJSONArray("features") ?: return emptyList()
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
data class Music(val releases: List<Release>, val gigs: List<Gig>, val error: String?, val at: Long)

/** Spotify sign-in (PKCE, no secret on the phone) for followed artists and their new releases. */
object Spotify {
    const val REDIRECT = "coverlauncher://spotify/callback"
    private const val SCOPE = "user-follow-read"

    fun connected(prefs: Prefs) = prefs.get(Prefs.SP_REFRESH) != null

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
        prefs.put(Prefs.SP_ACCESS to null, Prefs.SP_REFRESH to null, Prefs.SP_EXPIRES to null, Prefs.SP_ARTISTS to null)

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

    private fun api(prefs: Prefs, url: String): JSONObject {
        repeat(3) {
            try {
                return JSONObject(fetch(url, "Authorization" to "Bearer ${token(prefs)}"))
            } catch (e: HttpError) {
                if (e.code != 429) throw e
                Thread.sleep(((e.retryAfter ?: 2) * 1000).coerceAtMost(8000))
            }
        }
        throw IllegalStateException("spotify is rate limiting, try later")
    }

    /** Followed artists as (id, name). Capped at 300 to keep the release scan quick. */
    fun artists(prefs: Prefs): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var url: String? = "https://api.spotify.com/v1/me/following?type=artist&limit=50"
        while (url != null && out.size < 300) {
            val a = api(prefs, url).getJSONObject("artists")
            val items = a.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.getJSONObject(i).let { out += it.getString("id") to it.getString("name") }
            url = a.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        prefs.put(Prefs.SP_ARTISTS to JSONArray(out.map { it.second }).toString())
        return out
    }

    /** Albums and singles from followed artists released in the last [days] days, newest first. */
    fun releases(prefs: Prefs, artists: List<Pair<String, String>>, days: Long = 30): List<Release> {
        val since = LocalDate.now().minusDays(days)
        val out = LinkedHashMap<String, Release>()
        for ((id, name) in artists) {
            val items = api(prefs, "https://api.spotify.com/v1/artists/$id/albums?include_groups=album,single&market=AU&limit=10")
                .optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val a = items.getJSONObject(i)
                if (a.optString("release_date_precision") != "day") continue
                val d = runCatching { LocalDate.parse(a.getString("release_date")) }.getOrNull() ?: continue
                if (d.isBefore(since) || d.isAfter(LocalDate.now().plusDays(1))) continue
                val uri = a.optString("uri")
                out.putIfAbsent(uri, Release(name, a.optString("name"), d, uri, a.optString("album_type") == "single"))
            }
            Thread.sleep(60)
        }
        return out.values.sortedByDescending { it.date }.take(40)
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

/** Releases and gigs together, cached for 12 hours in cover's storage. */
object MusicFeed {
    fun cached(prefs: Prefs): Music? = runCatching {
        val j = JSONObject(prefs.get(Prefs.MUSIC_JSON) ?: return null)
        val r = j.getJSONArray("r")
        val g = j.getJSONArray("g")
        Music(
            (0 until r.length()).map { r.getJSONObject(it) }.map {
                Release(it.getString("a"), it.getString("t"), LocalDate.parse(it.getString("d")), it.getString("u"), it.optBoolean("s"))
            },
            (0 until g.length()).map { g.getJSONObject(it) }.map {
                Gig(it.getString("a"), LocalDate.parse(it.getString("d")), it.getString("v"), it.getString("u"))
            }.filter { !it.date.isBefore(LocalDate.now()) },
            j.optString("e").takeIf { it.isNotBlank() },
            j.getLong("at"),
        )
    }.getOrNull()

    fun refresh(prefs: Prefs): Music {
        val errors = ArrayList<String>()
        var releases = emptyList<Release>()
        var names: List<String> = emptyList()
        if (Spotify.connected(prefs)) {
            runCatching {
                val artists = Spotify.artists(prefs)
                names = artists.map { it.second }
                releases = Spotify.releases(prefs, artists)
            }.onFailure { errors += "spotify: ${it.message}" }
        } else {
            errors += "connect spotify in cfg"
        }
        val gigs = if (names.isEmpty()) emptyList() else runCatching { Gigs.load(prefs, names) }
            .onFailure { errors += "gigs: ${it.message}" }.getOrDefault(emptyList())
        val m = Music(releases, gigs, errors.joinToString(" · ").takeIf { it.isNotBlank() }, System.currentTimeMillis())
        val j = JSONObject()
            .put("r", JSONArray(releases.map { JSONObject().put("a", it.artist).put("t", it.title).put("d", it.date.toString()).put("u", it.uri).put("s", it.single) }))
            .put("g", JSONArray(gigs.map { JSONObject().put("a", it.artist).put("d", it.date.toString()).put("v", it.venue).put("u", it.url) }))
            .put("e", m.error ?: "")
            .put("at", m.at)
        prefs.put(Prefs.MUSIC_JSON to j.toString())
        return m
    }
}
