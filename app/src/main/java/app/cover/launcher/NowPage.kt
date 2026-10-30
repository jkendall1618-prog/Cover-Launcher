package app.cover.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Page 4 ("now"): swipe down from page 1. The playing album's cover redrawn as block glyphs,
// with a visualiser that takes its colours, resting shape and movement from that cover.

private const val COLS = 56

/** Visualiser bars: one under every two columns of the cover, so they stay thick enough to read. */
private const val BARS = COLS / 2
private val GLYPHS = listOf("░", "▒", "▓", "█")

/** The cover cut into a COLS × rows grid, reduced to what page 4 draws. */
class AsciiCover(
    val rows: Int,
    /** Per cell, 0..3, an index into GLYPHS: how bright that patch of the cover is. */
    val level: IntArray,
    /** Per cell, the nearest Tokyo Night colour. */
    val color: Array<Color>,
    /** Per bar, 0..1: the bars' resting height, i.e. the cover's skyline. */
    val base: FloatArray,
    /** Per bar: the cover's strongest colour in the two columns above it. */
    val barColor: Array<Color>,
    /** Dark, flat covers move slowly and smoothly; bright, busy ones fast and jittery. */
    val speed: Float,
    val amp: Float,
    val jitter: Float,
    /** Same album, same movement every time. */
    val seed: Int,
) {
    /** The colour of the tallest resting bar: the one the eye lands on. Used for the ♪ and progress bar. */
    val accent: Color get() = barColor[base.indices.maxByOrNull { base[it] } ?: 0]

    companion object {
        private val HUES = listOf(Tn.red, Tn.orange, Tn.yellow, Tn.green, Tn.cyan, Tn.blue, Tn.purple)
        private val HUE_DEG: List<Float> by lazy {
            HUES.map { c ->
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(c.toArgb(), hsv)
                hsv[0]
            }
        }

        private fun grey(lum: Float) = when {
            lum > 0.6f -> Tn.fg
            lum > 0.28f -> Tn.dim
            else -> Tn.border
        }

        private fun nearestHue(h: Float): Color {
            var best = 0
            var bestD = 999f
            HUE_DEG.forEachIndexed { i, ph ->
                val d = abs(h - ph).let { min(it, 360f - it) }
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            return HUES[best]
        }

        /** Nothing to draw yet: an even, quiet field and a gentle purple pulse. */
        fun blank(rows: Int) = AsciiCover(
            rows = rows,
            level = IntArray(COLS * rows),
            color = Array(COLS * rows) { Mb.border },
            base = FloatArray(BARS) { 0.3f },
            barColor = Array(BARS) { Tn.purple },
            speed = 0.14f,
            amp = 0.14f,
            jitter = 0.04f,
            seed = 7,
        )

        fun from(src: Bitmap, rows: Int, key: String): AsciiCover {
            // Centre square, then average the cover down: 4×4 samples per cell.
            val side = minOf(src.width, src.height)
            val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val sw = COLS * 4
            val sh = rows * 4
            val small = Bitmap.createScaledBitmap(crop, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)

            val n = COLS * rows
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            for (y in 0 until sh) for (x in 0 until sw) {
                val i = (y / 4) * COLS + x / 4
                val p = px[y * sw + x]
                r[i] += ((p shr 16) and 0xFF) / 255f
                g[i] += ((p shr 8) and 0xFF) / 255f
                b[i] += (p and 0xFF) / 255f
            }
            val lum = FloatArray(n)
            for (i in 0 until n) {
                r[i] /= 16f
                g[i] /= 16f
                b[i] /= 16f
                lum[i] = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
            }

            // Stretch brightness so even a very dark cover shows its shapes.
            val lo = lum.min()
            val span = (lum.max() - lo).coerceAtLeast(0.08f)
            val v = FloatArray(n) { ((lum[it] - lo) / span).coerceIn(0f, 1f) }
            val level = IntArray(n) {
                when {
                    v[it] < 0.2f -> 0
                    v[it] < 0.45f -> 1
                    v[it] < 0.72f -> 2
                    else -> 3
                }
            }

            val hsv = FloatArray(3)
            val chroma = BooleanArray(n)
            val color = Array(n) { i ->
                android.graphics.Color.RGBToHSV(
                    (r[i] * 255).toInt(), (g[i] * 255).toInt(), (b[i] * 255).toInt(), hsv,
                )
                if (hsv[1] < 0.22f || lum[i] < 0.08f) {
                    grey(lum[i])
                } else {
                    chroma[i] = true
                    nearestHue(hsv[0])
                }
            }

            // Skyline: the average brightness under each bar (two columns), spread over 0.15..0.75.
            val prof = FloatArray(BARS) { bi ->
                (0 until rows).flatMap { listOf(v[it * COLS + 2 * bi], v[it * COLS + 2 * bi + 1]) }.average().toFloat()
            }
            val pLo = prof.min()
            val pSpan = (prof.max() - pLo).coerceAtLeast(0.05f)
            val base = FloatArray(BARS) { 0.15f + 0.6f * (prof[it] - pLo) / pSpan }

            // Each bar takes the brightest coloured cell above it, else the brightest cell of any kind.
            val barColor = Array(BARS) { bi ->
                val cells = (0 until rows).flatMap { listOf(it * COLS + 2 * bi, it * COLS + 2 * bi + 1) }
                val pick = cells.filter { chroma[it] }.maxByOrNull { v[it] } ?: cells.maxByOrNull { v[it] }!!
                color[pick].let { if (it == Tn.border) Tn.dim else it }
            }

            // Busyness: how much neighbouring cells differ. With overall brightness, sets the motion.
            var diff = 0f
            var pairs = 0
            for (y in 0 until rows) for (x in 0 until COLS) {
                val i = y * COLS + x
                if (x + 1 < COLS) { diff += abs(v[i] - v[i + 1]); pairs++ }
                if (y + 1 < rows) { diff += abs(v[i] - v[i + COLS]); pairs++ }
            }
            val energy = if (pairs == 0) 0f else diff / pairs
            // Finer grids have smaller steps between neighbours, so scale back to the 28-column calibration.
            val t = (0.6f * min(1f, energy * 3f * (COLS / 28f)) + 0.4f * lum.average().toFloat()).coerceIn(0f, 1f)

            return AsciiCover(
                rows = rows,
                level = level,
                color = color,
                base = base,
                barColor = barColor,
                speed = 0.06f + 0.32f * t,
                amp = 0.12f + 0.16f * t,
                jitter = 0.28f * t * t,
                seed = key.hashCode(),
            )
        }
    }
}

/**
 * A deliberate vertical swipe: at least 56dp, and at least twice as far up or down as sideways.
 * It watches without consuming until it's sure, so key taps and sideways page swipes behave as before.
 * Touches that start within [skipTop] of the top or [skipBottom] of the bottom are ignored,
 * leaving One UI's own edge swipes alone.
 * [onSwipe] is captured once, so it should only set remembered state.
 */
fun Modifier.verticalSwipe(
    down: Boolean,
    enabled: Boolean = true,
    skipTop: Dp = 0.dp,
    skipBottom: Dp = 0.dp,
    onSwipe: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(down, skipTop, skipBottom) {
    val trigger = 56.dp.toPx()
    val top = skipTop.toPx()
    val bottom = skipBottom.toPx()
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (first.position.y < top || first.position.y > size.height - bottom) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val c = ev.changes.firstOrNull { it.id == first.id } ?: return@awaitEachGesture
            if (!c.pressed || c.isConsumed) return@awaitEachGesture
            val d = c.positionChange()
            dx += d.x
            dy += d.y
            if (abs(dx) > trigger) return@awaitEachGesture // sideways: the pager's job
            val along = if (down) dy else -dy
            if (along > trigger && along > 2f * abs(dx)) {
                ev.changes.forEach { it.consume() }
                onSwipe()
                // Swallow the rest of this touch so nothing underneath reacts to it.
                while (true) {
                    val rest = awaitPointerEvent(PointerEventPass.Initial)
                    rest.changes.forEach { it.consume() }
                    if (rest.changes.none { it.pressed }) break
                }
                return@awaitEachGesture
            }
        }
    }
}

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
}

@Composable
fun NowPage(active: () -> Boolean, onHome: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val hasAccess = remember { Media.hasAccess(ctx) }
    var track by remember { mutableStateOf<Track?>(null) }
    var cover by remember { mutableStateOf<AsciiCover?>(null) }
    var bars by remember { mutableStateOf(FloatArray(BARS) { 0.05f }) }

    // Cell shape comes from the font itself, so a square cover stays square.
    val tm = rememberTextMeasurer()
    val probe = remember(tm) { tm.measure("█", TextStyle(fontFamily = Mono, fontSize = 10.sp)) }
    val rows = remember(probe) {
        (COLS * probe.size.width.toFloat() / probe.size.height).roundToInt().coerceIn(8, 40)
    }

    // What's playing, twice a second while on screen. The cover is rebuilt only when the track changes;
    // apps sometimes send the art a moment after the title, so it retries briefly.
    LaunchedEffect(rows) {
        var key: String? = null
        var tries = 0
        while (true) {
            if (active()) {
                val t = withContext(Dispatchers.IO) { runCatching { Media.track(ctx) }.getOrNull() }
                track = t
                if (t == null) {
                    key = null
                    cover = null
                } else if (t.key != key) {
                    key = t.key
                    tries = 0
                    cover = null
                }
                if (t != null && cover == null && tries < 12) {
                    tries++
                    cover = withContext(Dispatchers.IO) {
                        runCatching { Media.art(ctx)?.let { AsciiCover.from(it, rows, t.key) } }.getOrNull()
                    }
                }
            }
            delay(500)
        }
    }

    // The bars: swell around the cover's skyline while playing, settle onto a faint copy of it when paused.
    val playing = track?.playing == true
    LaunchedEffect(cover, playing) {
        val c = cover ?: AsciiCover.blank(rows)
        val rnd = Random(c.seed)
        val drift = FloatArray(BARS) { rnd.nextFloat() * 0.6f }
        var ph = 0f
        while (true) {
            if (active()) {
                val prev = bars
                bars = if (playing) {
                    ph += c.speed
                    FloatArray(BARS) { i ->
                        val wave = sin(i * 0.6f + ph + drift[i]) * 0.6f + sin(i * 1.7f - ph * 1.3f) * 0.4f
                        (c.base[i] + c.amp * wave + c.jitter * (rnd.nextFloat() - 0.5f)).coerceIn(0.03f, 1f)
                    }
                } else {
                    FloatArray(BARS) { i -> prev[i] + (c.base[i] * 0.35f - prev[i]) * 0.25f }
                }
            }
            delay(110)
        }
    }

    val t = track
    val accent = cover?.accent ?: Tn.purple

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("~/now-playing $ ", color = Mb.faint, size = 11)
            T((t?.app ?: "media") + " --status", color = Tn.cyan, size = 11, maxLines = 1)
            Spacer(Modifier.weight(1f))
            T("↑ home", Modifier.clickable(onClick = onHome).padding(vertical = 2.dp), color = Mb.dim, size = 11)
        }

        // Cover and visualiser share one column grid, so each bar sits under its own slice of the art.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // In cover rows, which are half as tall since the grid doubled: same physical size as before.
            val visRows = 3.2f
            val gapRows = 1f
            val w10 = probe.size.width.toFloat()
            val h10 = probe.size.height.toFloat()
            val scale = min(
                constraints.maxWidth / (COLS * w10),
                constraints.maxHeight / ((rows + gapRows + visRows) * h10),
            ).coerceAtMost(2.4f)
            val glyphs = remember(tm, scale) {
                val style = TextStyle(fontFamily = Mono, fontSize = (10f * scale).sp)
                GLYPHS.map { tm.measure(it, style) }
            }
            val cw = glyphs[3].size.width.toFloat()
            val ch = glyphs[3].size.height.toFloat()
            val artW = with(density) { (cw * COLS).toDp() }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(artW, with(density) { (ch * rows).toDp() })) {
                    val c = cover?.takeIf { it.rows == rows }
                    for (y in 0 until rows) for (x in 0 until COLS) {
                        val i = y * COLS + x
                        drawText(
                            glyphs[c?.level?.get(i) ?: 0],
                            color = c?.color?.get(i) ?: Mb.border,
                            topLeft = Offset(x * cw, y * ch),
                        )
                    }
                }
                Spacer(Modifier.height(with(density) { (ch * gapRows).toDp() }))
                Canvas(Modifier.size(artW, with(density) { (ch * visRows).toDp() })) {
                    val colors = cover?.barColor
                    val slot = cw * 2f
                    val bw = slot * 0.78f
                    val levels = bars
                    for (x in 0 until BARS) {
                        // Eighth steps, like ▁▂▃▄▅▆▇█.
                        val steps = (levels[x] * 8f).toInt().coerceIn(1, 8)
                        val hh = size.height * steps / 8f
                        drawRect(
                            color = colors?.getOrNull(x) ?: Tn.purple,
                            topLeft = Offset(x * slot + (slot - bw) / 2f, size.height - hh),
                            size = Size(bw, hh),
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            when {
                !hasAccess -> T("allow media in cfg", color = Mb.dim)
                t == null -> {
                    T("nothing playing", color = Mb.dim)
                    T("swipe up for home", color = Mb.faint, size = 11)
                }
                else -> {
                    Row {
                        T("♪ ", color = accent)
                        T(t.title.lowercase(), color = Mb.fg, maxLines = 1)
                    }
                    T(
                        listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — ").lowercase(),
                        color = Mb.dim, size = 11, maxLines = 1,
                    )
                    if (t.durationMs > 0) {
                        val w = 22
                        val f = (t.positionMs.toFloat() / t.durationMs * w).roundToInt().coerceIn(0, w)
                        Row(Modifier.padding(top = 2.dp)) {
                            T("━".repeat(f), color = accent, size = 11)
                            T("─".repeat(w - f), color = Mb.faint, size = 11)
                            T(" " + mmss(t.positionMs), color = Mb.fg, size = 11)
                            T("/" + mmss(t.durationMs), color = Mb.faint, size = 11)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NowKey("[h]", "prev", Modifier.weight(1f)) { Media.prev(ctx) }
            NowKey("[spc]", if (playing) "pause" else "play", Modifier.weight(1f)) { Media.toggle(ctx) }
            NowKey("[l]", "next", Modifier.weight(1f)) { Media.next(ctx) }
        }
    }
}

@Composable
private fun NowKey(key: String, label: String, modifier: Modifier, onTap: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier.fillMaxHeight().clip(shape).border(1.dp, Mb.border, shape).clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Row {
            T(key, color = Tn.blue, size = 11)
            T(" $label", color = Mb.fg, size = 11)
        }
    }
}
