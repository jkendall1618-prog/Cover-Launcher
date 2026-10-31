package app.cover.launcher

import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// Page 6 ("map"): Brisbane and surrounds as a terminal map. Aircraft overhead, current crashes,
// bushfire hotspots and visible satellite passes, around a radar sweep centred on home.

/** Nord, an Omarchy theme: cold blues that suit a map with a bay in it. */
object Nd {
    val bg = Color(0xFF2E3440)
    val water = Color(0xFF262B35)
    val raised = Color(0xFF3B4252)
    val faint = Color(0xFF4C566A)
    val dim = Color(0xFF7B88A1)
    val fg = Color(0xFFD8DEE9)
    val snow = Color(0xFFECEFF4)
    val frost = Color(0xFF88C0D0)
    val frost2 = Color(0xFF81A1C1)
    val red = Color(0xFFBF616A)
    val orange = Color(0xFFD08770)
    val yellow = Color(0xFFEBCB8B)
    val green = Color(0xFFA3BE8C)
    val purple = Color(0xFFB48EAD)
}

/** The area on the map: roughly Redcliffe to Jimboomba, Ipswich to North Stradbroke. */
object Area {
    const val N = -27.10
    const val S = -27.90
    const val W = 152.55
    const val E = 153.50
    const val HOME_LAT = -27.70
    const val HOME_LON = 152.88
    const val CBD_LAT = -27.468
    const val CBD_LON = 153.028

    /** Width over height in km, so the map isn't stretched. */
    val aspect: Float = ((E - W) * 111.32 * cos(Math.toRadians((N + S) / 2)) / ((N - S) * 110.57)).toFloat()

    fun contains(lat: Double, lon: Double) = lat in S..N && lon in W..E

    /** Land or water, from OpenStreetMap coastline data at about 800 m per cell ('#' land, '.' water). */
    fun land(lat: Double, lon: Double): Boolean {
        val r = ((N - lat) / (N - S) * MASK.size).toInt().coerceIn(0, MASK.size - 1)
        val row = MASK[r]
        val c = ((lon - W) / (E - W) * row.length).toInt().coerceIn(0, row.length - 1)
        return row[c] == '#'
    }

    // © OpenStreetMap contributors (ODbL), via geo-maps earth-lands-10m.
    private val MASK = arrayOf(
        "##..###############################################################.....................................#########.......",
        "#################################################################......................................##########.......",
        "#################################################################......................................#########........",
        "################################################################.......................................#########........",
        "###############################################################........................................#########........",
        "###############################################################........................................########.........",
        "#############################################################..........................................########.........",
        "#############################################################..........................................########.........",
        "#############################################################...........................................#######.........",
        "#############################################################...........................................######..........",
        "#############################################################..........................................#######..........",
        "#############################################################.........#................................#######..........",
        "#################################################################....###...............................#######..........",
        "########################################################################................................######..........",
        "########################################################################................................######..........",
        "#######################################################################.................................######..........",
        "########################################################################.................................#####..........",
        "##########################################.#####.######################..................................#####..........",
        "#########################################...####..#####################..................................#####..........",
        "....#################################.............###############.####....................................####..........",
        "....##################################...........###############..........................................####..........",
        "....########################################...##################..........................................###..........",
        "###############################################################............................................###..........",
        "################################################################............................................##..........",
        "####..###########################################################...........................................###.........",
        "###...###########################################################...........................................###.........",
        "......############################################################...........................................##.........",
        ".....#############################################################............................................##........",
        ".....##############################################################.....................#.....................###.......",
        "......##############################################################...................###.................#............",
        ".###...##############################################################...................##.................#............",
        "####....###########################################################..###........#.......................................",
        "####......################################################################......#.......................................",
        "####.......##################################################################..##.......................................",
        "###.........#################################################################.##........................................",
        "#######......###############################################################..#.......#.................................",
        "##########....#############################################################..........##..........................##.....",
        "#############.##############################################################....................................####....",
        "##########################################################################...##..................................####...",
        "##########################################################################..##..................................######..",
        "###########################################################################.##..................................########",
        "##############################################################################..................................########",
        "################################################################.#####.########.................................########",
        "###############################################################..####...#########..............................#########",
        "#################################################################################..............................#########",
        "#############################################################.###################..............................#########",
        "###########################.#################################..##################.............................##########",
        "##########################..#######################################################..........................###########",
        "#########################..#############################..##########################.###......................##########",
        "##########################################################################################..........####...#############",
        "##########################################################################################..........##......############",
        "##########################################################################################..........#........###########",
        "###########################################################################################..................###########",
        "#########################..########...#######################################################................###########",
        "############################################################################################.................###########",
        "####################################################..######################.###############.................##########.",
        "############################################################################.###############.................##########.",
        "############################################################################.################................##########.",
        "################################################################################################.............#########..",
        "################################################################################################.............#########..",
        "#################################################################################################.....#......#########..",
        "################################################################################################......#......########...",
        "###############################################################################################......##......########...",
        "###############################################################################################......###......#######...",
        "################################################################################################...#.###.....########...",
        "################################################################################################...#.#####...#######....",
        "################################################################################################......#.......######....",
        "#################################################################################################.#....##.....#####.....",
        "################################################################################################..##...####...#####.....",
        "################################################################################################..##.#.###.#.######.....",
        "################################################################################################..#..#.###.########.....",
        "#################################################################################################....#..##.########.....",
        "###################################################################################################..#..##.########.....",
        "###################################################################################################.....###########.....",
        "#################################################################################################......############.....",
        "##############################################################################################....####..##.########.....",
        "######################################################################################################.....#######......",
        "#####################################################################################################..####.####.#......",
        "######################################################################################################....#.###..#......",
        "######################################################################################################.#..##.##.........",
        "#####################################################################################################..#.###.....#......",
        "#####################################################################################################...#######.##......",
        "######################################################################################################...###....#.......",
        "######################################################################################################..#...##..#.......",
        "#######################################################################################################.#####...#.......",
        "########################################################################################################...#............",
        "#######################################################################################################..#####.#........",
        "#################################################################################################..####...##...#........",
        "############################################################################################################...#........",
        "###########################################################################################################...##........",
        "###########################################################################################################.####........",
        "#########################################################################################################...####........",
        "#######################################################################################################.###.###.........",
        "######################################################################################################..###.###.........",
        "####################################################################################################..#..##..##.........",
        "###################################################################################################..###.##..##.........",
        "#######################################################################################################.###..##.........",
        "#######################################################################################################.##...##.........",
        "######################################################################################################..###...#.........",
        "######################################################################################################...###..#.........",
    )
}

data class Plane(val lat: Double, val lon: Double, val call: String, val alt: Int?, val kts: Int?, val track: Double?, val type: String)
data class Crash(val lat: Double, val lon: Double, val what: String)
data class Fire(val lat: Double, val lon: Double, val at: Instant?, val conf: Int?)
data class Pass(
    val name: String, val start: Long, val max: Long, val end: Long,
    val startAz: Double, val maxAz: Double, val endAz: Double, val maxEl: Int,
    val from: String, val to: String,
)

/**
 * QLDTraffic's events feed, shared by the map and page 5's drive so they don't each hit it.
 * Kept for 5 minutes; after a "rate limited" it waits as long as asked (15 minutes if not told).
 */
object QldEvents {
    private var body: String? = null
    private var at = 0L
    private var blockedUntil = 0L
    private const val KEEP = 5 * 60_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun features(prefs: Prefs): JSONArray {
        val now = System.currentTimeMillis()
        val fresh = body?.takeIf { now - at < KEEP }
        val text = fresh ?: run {
            if (now < blockedUntil) {
                // Keep showing the last good copy while we wait, if there is one.
                body ?: throw IllegalStateException("rate limited, retry ${hhmm(blockedUntil)}")
            } else {
                val key = prefs.get(Prefs.QLDT_KEY) ?: Drive.QLDT_PUBLIC
                http.newCall(Request.Builder().url("https://api.qldtraffic.qld.gov.au/v2/events?apikey=$key").build()).execute().use { r ->
                    if (r.code == 429) {
                        val wait = r.header("Retry-After")?.toLongOrNull()?.times(1000) ?: (15 * 60_000L)
                        blockedUntil = now + wait.coerceIn(60_000L, 60 * 60_000L)
                        body ?: throw IllegalStateException("rate limited, retry ${hhmm(blockedUntil)}")
                    } else {
                        if (!r.isSuccessful) throw HttpError(r.code)
                        r.body?.string().orEmpty().also { body = it; at = now }
                    }
                }
            }
        }
        return JSONObject(text).optJSONArray("features") ?: JSONArray()
    }
}

/** Keys this page needs, kept with the others in Prefs. */
object MapKeys {
    const val N2YO = "n2yo_key"
    const val FIRMS = "firms_key"
}

/** Latest results for the map, kept here so they survive swiping away and back. */
object Sky {
    var planes by mutableStateOf<List<Plane>>(emptyList())
    var crashes by mutableStateOf<List<Crash>>(emptyList())
    var fires by mutableStateOf<List<Fire>>(emptyList())
    var passes by mutableStateOf<List<Pass>>(emptyList())
    var errors by mutableStateOf<Map<String, String>>(emptyMap())

    var planesAt = 0L
    var crashesAt = 0L
    var firesAt = 0L
    var passesAt = 0L

    private const val PLANES_EVERY = 15_000L
    private const val CRASHES_EVERY = 5 * 60_000L
    private const val FIRES_EVERY = 20 * 60_000L
    private const val PASSES_EVERY = 6 * 60 * 60_000L

    /** Satellites worth looking up for: the ISS, China's station and Hubble. */
    private val SATS = listOf(25544 to "iss", 48274 to "tiangong", 20580 to "hubble")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", "cover-launcher").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpError(r.code, r.header("Retry-After")?.toLongOrNull())
            body
        }

    private fun fail(feed: String, e: Throwable) {
        Log.w("cover-map", "$feed failed", e)
        errors = errors + (feed to (e.message ?: e.javaClass.simpleName).lowercase().take(40))
    }

    private fun ok(feed: String) {
        if (feed in errors) errors = errors - feed
    }

    /** Call often; each feed only refetches when it's due. Runs on the IO dispatcher. */
    fun tick(prefs: Prefs) {
        val now = System.currentTimeMillis()
        if (now - planesAt > PLANES_EVERY) {
            planesAt = now
            runCatching { loadPlanes() }.onSuccess { planes = it; ok("air") }.onFailure { fail("air", it) }
        }
        if (now - crashesAt > CRASHES_EVERY) {
            crashesAt = now
            runCatching { loadCrashes(prefs) }.onSuccess { crashes = it; ok("crash") }.onFailure { fail("crash", it) }
        }
        if (now - firesAt > FIRES_EVERY) {
            firesAt = now
            runCatching { loadFires(prefs) }.onSuccess { fires = it; ok("fire") }.onFailure { fail("fire", it) }
        }
        if (now - passesAt > PASSES_EVERY) {
            val key = prefs.get(MapKeys.N2YO)
            if (key == null) {
                if (errors["sat"] == null) errors = errors + ("sat" to "add n2yo key in cfg")
            } else {
                passesAt = now
                runCatching { loadPasses(key) }.onSuccess { passes = it; ok("sat") }.onFailure { fail("sat", it) }
            }
        }
    }

    /** Forget fetch times so everything reloads (after a key is saved). */
    fun reset() {
        planesAt = 0L; crashesAt = 0L; firesAt = 0L; passesAt = 0L
    }

    // ----- aircraft: free ADS-B aggregators, no key. Same readsb format, so try each in turn. -----

    private val AIR = listOf("https://api.adsb.lol", "https://api.airplanes.live", "https://api.adsb.one")

    private fun loadPlanes(): List<Plane> {
        // 35 nm from the middle of the map reaches its corners.
        val path = "/v2/point/-27.50/153.025/35"
        var last: Throwable? = null
        for (host in AIR) {
            val r = runCatching { parsePlanes(JSONObject(get(host + path))) }
            if (r.isSuccess) return r.getOrThrow()
            last = r.exceptionOrNull()
        }
        throw last ?: IllegalStateException("no aircraft source")
    }

    private fun parsePlanes(j: JSONObject): List<Plane> {
        val ac = j.optJSONArray("ac") ?: j.optJSONArray("aircraft") ?: return emptyList()
        val out = ArrayList<Plane>()
        for (i in 0 until ac.length()) {
            val a = ac.optJSONObject(i) ?: continue
            if (!a.has("lat") || !a.has("lon")) continue
            val lat = a.getDouble("lat")
            val lon = a.getDouble("lon")
            if (!Area.contains(lat, lon)) continue
            // "ground" for planes on the tarmac: leave those off.
            val alt = (a.opt("alt_baro") as? Number)?.toInt() ?: continue
            out += Plane(
                lat, lon,
                call = a.optString("flight").trim().ifBlank { a.optString("r").trim() }.ifBlank { a.optString("hex") },
                alt = alt,
                kts = (a.opt("gs") as? Number)?.toInt(),
                track = (a.opt("track") as? Number)?.toDouble(),
                type = a.optString("t").trim(),
            )
        }
        return out
    }

    // ----- crashes: QLDTraffic, the same feed page 5 uses for the drive. -----

    private fun loadCrashes(prefs: Prefs): List<Crash> {
        val feats = QldEvents.features(prefs)
        val out = ArrayList<Crash>()
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            if (!p.optString("event_type").equals("Crash", ignoreCase = true)) continue
            val pt = firstPoint(f.optJSONObject("geometry")) ?: continue
            if (!Area.contains(pt.first, pt.second)) continue
            val road = p.optJSONObject("road_summary")
            val what = listOfNotNull(
                road?.optString("road_name")?.takeIf { it.isNotBlank() },
                road?.optString("locality")?.takeIf { it.isNotBlank() },
                p.optJSONObject("impact")?.optString("impact_type")?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").lowercase().ifBlank { "crash" }
            out += Crash(pt.first, pt.second, what)
        }
        return out
    }

    /** First [lat, lon] in a GeoJSON geometry of any shape (GeoJSON stores [lon, lat]). */
    private fun firstPoint(g: JSONObject?): Pair<Double, Double>? {
        if (g == null) return null
        g.optJSONArray("geometries")?.let { for (i in 0 until it.length()) firstPoint(it.optJSONObject(i))?.let { p -> return p } }
        var a: JSONArray = g.optJSONArray("coordinates") ?: return null
        while (a.length() > 0 && a.opt(0) is JSONArray) a = a.getJSONArray(0)
        return if (a.length() >= 2) a.getDouble(1) to a.getDouble(0) else null
    }

    // ----- bushfire hotspots: Geoscience Australia's DEA Hotspots (last 72 hours), no key; NASA FIRMS if you add one. -----

    private fun loadFires(prefs: Prefs): List<Fire> {
        val dea = runCatching { deaFires() }
        if (dea.isSuccess) return dea.getOrThrow()
        val key = prefs.get(MapKeys.FIRMS) ?: throw dea.exceptionOrNull()!!
        return firmsFires(key)
    }

    private fun deaFires(): List<Fire> {
        // WFS 1.0.0 takes the box as lon,lat.
        val url = "https://hotspots.dea.ga.gov.au/geoserver/wfs?service=WFS&version=1.0.0&request=GetFeature" +
            "&typeName=public:hotspots_three_days&outputFormat=application/json" +
            "&bbox=${Area.W},${Area.S},${Area.E},${Area.N}"
        val feats = JSONObject(get(url)).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Fire>()
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val pt = firstPoint(f.optJSONObject("geometry")) ?: continue
            if (!Area.contains(pt.first, pt.second)) continue
            val p = f.optJSONObject("properties")
            val at = prop(p, "datetime")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val conf = prop(p, "confidence")?.toDoubleOrNull()?.roundToInt()?.takeIf { it >= 0 }
            out += Fire(pt.first, pt.second, at, conf)
        }
        return out
    }

    /** Property lookup that ignores case ("Datetime", "datetime"). */
    private fun prop(p: JSONObject?, name: String): String? {
        if (p == null) return null
        val k = p.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return p.optString(k).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun firmsFires(key: String): List<Fire> {
        val csv = get("https://firms.modaps.eosdis.nasa.gov/api/area/csv/$key/VIIRS_NOAA20_NRT/${Area.W},${Area.S},${Area.E},${Area.N}/2")
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val head = lines[0].split(",")
        val iLat = head.indexOf("latitude")
        val iLon = head.indexOf("longitude")
        val iDate = head.indexOf("acq_date")
        val iTime = head.indexOf("acq_time")
        if (iLat < 0 || iLon < 0) throw IllegalStateException("firms: " + lines[0].take(30))
        return lines.drop(1).mapNotNull { l ->
            val c = l.split(",")
            val lat = c.getOrNull(iLat)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = c.getOrNull(iLon)?.toDoubleOrNull() ?: return@mapNotNull null
            val at = runCatching {
                val t = c[iTime].padStart(4, '0')
                Instant.parse("${c[iDate]}T${t.take(2)}:${t.takeLast(2)}:00Z")
            }.getOrNull()
            Fire(lat, lon, at, null)
        }
    }

    // ----- satellite passes: N2YO visible passes over home, next two days. -----

    private fun loadPasses(key: String): List<Pass> {
        val out = ArrayList<Pass>()
        for ((id, name) in SATS) {
            val j = JSONObject(get("https://api.n2yo.com/rest/v1/satellite/visualpasses/$id/${Area.HOME_LAT}/${Area.HOME_LON}/100/2/120/&apiKey=$key"))
            j.optString("error").takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
            val arr = j.optJSONArray("passes") ?: continue
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                out += Pass(
                    name,
                    start = p.getLong("startUTC") * 1000, max = p.getLong("maxUTC") * 1000, end = p.getLong("endUTC") * 1000,
                    startAz = p.optDouble("startAz"), maxAz = p.optDouble("maxAz"), endAz = p.optDouble("endAz"),
                    maxEl = p.optDouble("maxEl").roundToInt(),
                    from = p.optString("startAzCompass").lowercase(), to = p.optString("endAzCompass").lowercase(),
                )
            }
        }
        return out.sortedBy { it.start }
    }
}

/** Azimuth of a pass at [t], blending start → highest → end the short way round. */
private fun Pass.azAt(t: Long): Double {
    fun mix(a: Double, b: Double, f: Double): Double {
        var d = (b - a) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return (a + d * f + 360) % 360
    }
    return if (t <= max) mix(startAz, maxAz, ((t - start).toDouble() / (max - start).coerceAtLeast(1))) else
        mix(maxAz, endAz, ((t - max).toDouble() / (end - max).coerceAtLeast(1)))
}

private val ARROWS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

private sealed interface Mark {
    data class Air(val p: Plane) : Mark
    data class Hit(val c: Crash) : Mark
    data class Burn(val f: Fire) : Mark
    data class Sat(val p: Pass) : Mark
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HHMM)

private fun ago(i: Instant?): String {
    if (i == null) return ""
    val m = (System.currentTimeMillis() - i.toEpochMilli()) / 60_000
    return if (m < 60) "${m}m ago" else "${m / 60}h ago"
}

private fun describe(m: Mark): String = when (m) {
    is Mark.Air -> listOfNotNull(
        m.p.call.lowercase(),
        m.p.type.lowercase().takeIf { it.isNotBlank() },
        m.p.alt?.let { "%,d ft".format(it) },
        m.p.kts?.let { "$it kt" },
    ).joinToString(" · ")
    is Mark.Hit -> "crash · " + m.c.what
    is Mark.Burn -> listOfNotNull("hotspot", ago(m.f.at).takeIf { it.isNotBlank() }, m.f.conf?.let { "$it% conf" }).joinToString(" · ")
    is Mark.Sat -> "${m.p.name} overhead · ${m.p.from}→${m.p.to} · max ${m.p.maxEl}° · till ${hhmm(m.p.end)}"
}

@Composable
fun MapPage(prefs: Prefs, active: () -> Boolean) {
    val ctx = LocalContext.current
    var frame by remember { mutableIntStateOf(0) }
    var sec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var selected by remember { mutableStateOf<Mark?>(null) }
    val hits = remember { ArrayList<Pair<Offset, Mark>>() }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_regular) }.getOrNull()
        }
    }
    val base = remember { arrayOfNulls<Bitmap>(1) }

    // Feeds: only while this page is the one on screen.
    LaunchedEffect(Unit) {
        loadWork(prefs)
        while (true) {
            if (active()) withContext(Dispatchers.IO) { Sky.tick(prefs) }
            delay(3000)
        }
    }
    // Sweep animation at ~12 fps, only while visible.
    LaunchedEffect(Unit) {
        while (true) {
            if (active()) frame++
            val s = System.currentTimeMillis() / 1000
            if (s != sec) sec = s
            delay(if (active()) 80 else 1000)
        }
    }

    val now = sec * 1000
    val live = Sky.passes.firstOrNull { now in it.start..it.end }
    val next = Sky.passes.firstOrNull { it.start > now }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("❯ ", color = Nd.frost)
            T("map", color = Nd.fg)
            Spacer(Modifier.weight(1f))
            T("✈${Sky.planes.size} ", color = Nd.frost, size = 11)
            T("✕${Sky.crashes.size} ", color = Nd.red, size = 11)
            T("▲${Sky.fires.size}", color = Nd.orange, size = 11)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures { at ->
                            val near = hits.minByOrNull { (p, _) -> hypot(p.x - at.x, p.y - at.y) }
                            selected = near?.takeIf { (p, _) -> hypot(p.x - at.x, p.y - at.y) < 28.dp.toPx() }?.second
                        }
                    },
            ) {
                frame
                drawMap(paint, base, hits, live, selected)
            }
        }
        // Info: what you tapped, else what's coming up overhead, else feed trouble.
        val info = selected?.let(::describe)
            ?: live?.let { describe(Mark.Sat(it)) }
            ?: next?.let { "${it.name} ${hhmm(it.start)} · ${it.from}→${it.to} · max ${it.maxEl}°" }
            ?: "tap a mark for details"
        T(info, Modifier.fillMaxWidth().padding(horizontal = 4.dp).clickable { selected = null }, color = if (selected != null) Nd.snow else Nd.dim, size = 12, maxLines = 2)
        val trouble = Sky.errors.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
        T(
            if (trouble.isNotBlank()) trouble else "@ home  w work  ◆ sat   © osm",
            Modifier.padding(horizontal = 4.dp),
            color = if (trouble.isNotBlank()) Nd.red else Nd.faint,
            size = 10,
            maxLines = 1,
        )
    }
}

private fun DrawScope.glyph(paint: Paint, s: String, x: Float, y: Float, color: Color, sizeDp: Float) {
    paint.color = color.toArgb()
    paint.textSize = sizeDp.dp.toPx()
    paint.textAlign = Paint.Align.CENTER
    drawIntoCanvas { it.nativeCanvas.drawText(s, x, y + paint.textSize * 0.35f, paint) }
}

private fun DrawScope.drawMap(
    paint: Paint,
    base: Array<Bitmap?>,
    hits: ArrayList<Pair<Offset, Mark>>,
    live: Pass?,
    selected: Mark?,
) {
    // Fit the area into the space without stretching it.
    val w: Float
    val h: Float
    if (size.width / size.height > Area.aspect) { h = size.height; w = h * Area.aspect } else { w = size.width; h = w / Area.aspect }
    val ox = (size.width - w) / 2
    val oy = (size.height - h) / 2
    fun x(lon: Double) = ox + ((lon - Area.W) / (Area.E - Area.W)).toFloat() * w
    fun y(lat: Double) = oy + ((Area.N - lat) / (Area.N - Area.S)).toFloat() * h
    clipRect(ox, oy, ox + w, oy + h) {

    // The coastline layer never changes, so draw it once per size and reuse it.
    val bw = size.width.toInt().coerceAtLeast(1)
    val bh = size.height.toInt().coerceAtLeast(1)
    val cached = base[0]?.takeIf { it.width == bw && it.height == bh } ?: run {
        base[0]?.recycle()
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val fill = Paint().apply { color = Nd.water.toArgb() }
        val cell = 7.dp.toPx()
        val cols = (w / cell).toInt().coerceAtLeast(1)
        val rows = (h / cell).toInt().coerceAtLeast(1)
        val cw = w / cols
        val ch = h / rows
        fun landAt(col: Int, row: Int): Boolean {
            if (col < 0 || row < 0 || col >= cols || row >= rows) return true
            return Area.land(Area.N - (row + 0.5) / rows * (Area.N - Area.S), Area.W + (col + 0.5) / cols * (Area.E - Area.W))
        }
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 9.dp.toPx()
        for (r in 0 until rows) for (col in 0 until cols) {
            val cx = ox + (col + 0.5f) * cw
            val cy = oy + (r + 0.5f) * ch
            if (!landAt(col, r)) {
                c.drawRect(ox + col * cw, oy + r * ch, ox + (col + 1) * cw, oy + (r + 1) * ch, fill)
                continue
            }
            val coast = !landAt(col - 1, r) || !landAt(col + 1, r) || !landAt(col, r - 1) || !landAt(col, r + 1)
            paint.color = (if (coast) Nd.frost2 else Nd.faint).toArgb()
            c.drawText(if (coast) "•" else "·", cx, cy + paint.textSize * 0.35f, paint)
        }
        // Range rings around home: 10, 25 and 50 km.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.dp.toPx()
            color = Nd.raised.toArgb()
            pathEffect = DashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f)
        }
        val kmPx = w / ((Area.E - Area.W) * 111.32 * cos(Math.toRadians((Area.N + Area.S) / 2))).toFloat()
        listOf(10f, 25f, 50f).forEach { c.drawCircle(x(Area.HOME_LON), y(Area.HOME_LAT), it * kmPx, ring) }
        paint.color = Nd.dim.toArgb()
        c.drawText("bne", x(Area.CBD_LON), y(Area.CBD_LAT) + 4.dp.toPx(), paint)
        base[0] = bmp
        bmp
    }
    drawIntoCanvas { it.nativeCanvas.drawBitmap(cached, 0f, 0f, null) }

    val hx = x(Area.HOME_LON)
    val hy = y(Area.HOME_LAT)

    // Radar sweep from home: one turn every 6 seconds, with a fading tail.
    val sweep = (System.currentTimeMillis() % 6000L) / 6000.0 * 360.0
    val reach = hypot(size.width, size.height)
    for (k in 0 until 14) {
        val a = Math.toRadians(sweep - k * 2.5)
        drawLine(
            Nd.frost.copy(alpha = 0.22f * (1f - k / 14f)),
            Offset(hx, hy),
            Offset(hx + (sin(a) * reach).toFloat(), hy - (cos(a) * reach).toFloat()),
            strokeWidth = 1.5.dp.toPx(),
        )
    }

    hits.clear()

    // A satellite going over right now: a dashed bearing from home, with the name at the end.
    if (live != null) {
        val a = Math.toRadians(live.azAt(System.currentTimeMillis()))
        val len = minOf(w, h) * 0.45f
        val ex = hx + (sin(a) * len).toFloat()
        val ey = hy - (cos(a) * len).toFloat()
        val steps = 12
        for (s in 0 until steps step 2) {
            drawLine(
                Nd.purple,
                Offset(hx + (ex - hx) * s / steps, hy + (ey - hy) * s / steps),
                Offset(hx + (ex - hx) * (s + 1) / steps, hy + (ey - hy) * (s + 1) / steps),
                strokeWidth = 1.dp.toPx(),
            )
        }
        glyph(paint, "◆ ${live.name}", ex, ey, Nd.purple, 11f)
        hits += Offset(ex, ey) to Mark.Sat(live)
    }

    workLL?.let { glyph(paint, "w", x(it.second), y(it.first), Nd.green, 12f) }
    glyph(paint, "@", hx, hy, Nd.green, 13f)

    Sky.fires.forEach { f ->
        val p = Offset(x(f.lon), y(f.lat))
        glyph(paint, "▲", p.x, p.y, Nd.orange, 11f)
        hits += p to Mark.Burn(f)
    }
    Sky.crashes.forEach { c ->
        val p = Offset(x(c.lon), y(c.lat))
        glyph(paint, "✕", p.x, p.y, Nd.red, 12f)
        hits += p to Mark.Hit(c)
    }
    Sky.planes.forEach { pl ->
        val p = Offset(x(pl.lon), y(pl.lat))
        val arrow = pl.track?.let { ARROWS[(((it + 22.5) % 360) / 45).toInt().coerceIn(0, 7)] } ?: "•"
        // Low and climbing out or coming in: warmer. Cruising: cooler.
        val col = when {
            (pl.alt ?: 0) < 5000 -> Nd.yellow
            (pl.alt ?: 0) < 20000 -> Nd.frost
            else -> Nd.frost2
        }
        glyph(paint, arrow, p.x, p.y, col, 13f)
        hits += p to Mark.Air(pl)
    }

    // Ring and label the one you tapped.
    selected?.let { s ->
        hits.firstOrNull { it.second == s }?.first?.let { p ->
            drawCircle(Nd.snow, 10.dp.toPx(), p, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            if (s is Mark.Air) glyph(paint, s.p.call.lowercase(), p.x, p.y - 15.dp.toPx(), Nd.snow, 10f)
        }
    }
    }
}

/** Work, from page 5's saved address (no pin if it isn't set). */
private var workLL: Pair<Double, Double>? = null

fun loadWork(prefs: Prefs) {
    workLL = prefs.get(Prefs.WORK_LL)?.split("|")?.takeIf { it.size >= 2 }?.let {
        runCatching { it[0].toDouble() to it[1].toDouble() }.getOrNull()
    }
}

// ---------- cfg ----------

@Composable
fun MapTile(prefs: Prefs) {
    Tile(label = "map page") {
        T("aircraft, crashes and hotspots need no key.", color = Tn.dim, size = 11)
        Spacer(Modifier.height(4.dp))
        MapKeyRow(prefs, MapKeys.N2YO, "n2yo", "api key (sat passes)")
        MapKeyRow(prefs, MapKeys.FIRMS, "nasa firms", "map key (optional)")
    }
}

@Composable
private fun MapKeyRow(prefs: Prefs, key: String, label: String, placeholder: String) {
    var saved by remember { mutableStateOf(prefs.get(key) != null) }
    var v by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(label.padEnd(11), size = 12)
        T(if (saved) "saved" else "not set", color = if (saved) Tn.green else Tn.dim, size = 12)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(v, { v = it }, placeholder, Modifier.weight(1f), secret = true)
        Btn("save", Tn.blue) {
            if (v.isNotBlank()) {
                prefs.put(key to v.trim())
                v = ""
                saved = true
                Sky.reset()
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
