package app.cover.launcher

import android.content.Context
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Idle screensaver for page 1: a block-letter clock over terminal effects, in Tokyo Night.
 * Tap to wake, long-press for the next effect, pick the phone up and it gets out of the way.
 */

/** How long page 1 sits untouched before the screensaver starts. */
const val IDLE_AFTER_MS = 15_000L

private enum class Fx { RAIN, DECRYPT, STARS }

/** Rotate effects this often. */
private const val FX_EVERY_MS = 3 * 60_000L

/** Off the charger, hold the screen on for at most this long, then let the cover time out as normal. */
private const val AWAKE_CAP_MS = 10 * 60_000L

/** Off the charger, don't hold the screen on below this. */
private const val MIN_BATTERY = 20

private const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾅﾆﾇﾈﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾗﾘﾜ0123456789\$#@%&*<>{}[]=+"

private val DIGITS = mapOf(
    '0' to listOf("███", "█ █", "█ █", "█ █", "███"),
    '1' to listOf(" █ ", "██ ", " █ ", " █ ", "███"),
    '2' to listOf("███", "  █", "███", "█  ", "███"),
    '3' to listOf("███", "  █", "███", "  █", "███"),
    '4' to listOf("█ █", "█ █", "███", "  █", "  █"),
    '5' to listOf("███", "█  ", "███", "  █", "███"),
    '6' to listOf("███", "█  ", "███", "█ █", "███"),
    '7' to listOf("███", "  █", "  █", "  █", "  █"),
    '8' to listOf("███", "█ █", "███", "█ █", "███"),
    '9' to listOf("███", "█ █", "███", "  █", "███"),
    ':' to listOf("   ", " █ ", "   ", " █ ", "   "),
)

private fun blockClock(hhmm: String): String =
    (0 until 5).joinToString("\n") { r -> hhmm.map { DIGITS[it]?.get(r) ?: "   " }.joinToString(" ") }

@Composable
fun IdleScreen(
    act: MainActivity,
    events: List<Event>,
    tasks: List<Task>,
    media: NowPlaying?,
    battery: Int,
    onWake: () -> Unit,
) {
    val ctx = LocalContext.current
    val wake by rememberUpdatedState(onWake)
    BackHandler { wake() }

    var fx by remember { mutableStateOf(Fx.RAIN) }
    var frame by remember { mutableIntStateOf(0) }
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    var charging by remember { mutableStateOf(isCharging(ctx)) }
    var flatAndStill by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }

    // ~15 fps is plenty for text effects and kind to the battery.
    LaunchedEffect(Unit) {
        while (true) {
            frame++
            delay(66)
        }
    }
    LaunchedEffect(Unit) {
        var fxAt = System.currentTimeMillis()
        while (true) {
            clock = LocalDateTime.now()
            charging = isCharging(ctx)
            if (System.currentTimeMillis() - fxAt > FX_EVERY_MS) {
                fx = Fx.entries[(fx.ordinal + 1) % Fx.entries.size]
                fxAt = System.currentTimeMillis()
            }
            delay(1000)
        }
    }

    // Accelerometer: lying flat and still keeps it awake; a pick-up wakes the launcher.
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var last: FloatArray? = null
        var stillSince = System.currentTimeMillis()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                val prev = last
                last = v.clone()
                if (prev == null) return
                val dx = v[0] - prev[0]
                val dy = v[1] - prev[1]
                val dz = v[2] - prev[2]
                val jolt = sqrt(dx * dx + dy * dy + dz * dz)
                val now = System.currentTimeMillis()
                if (jolt > 1.8f) {
                    stillSince = now
                    // Ignore the first moment so the tap that ended typing doesn't count as a pick-up.
                    if (now - startedAt > 1500) wake()
                }
                // Gravity along the screen's axis means it's lying on a surface (which side the sensor sees varies by fold).
                flatAndStill = abs(v[2]) > 8.5f && now - stillSince > 2000
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (acc != null) sm?.registerListener(listener, acc, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm?.unregisterListener(listener) }
    }

    // Hold the screen on only when it makes sense, and never forever off the charger.
    val elapsed = clock.let { System.currentTimeMillis() - startedAt }
    val holdOn = charging || (flatAndStill && battery > MIN_BATTERY && elapsed < AWAKE_CAP_MS)
    DisposableEffect(holdOn) {
        val w = act.window
        if (holdOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_regular) }.getOrNull()
        }
    }
    val rain = remember { Rain() }
    val stars = remember { Stars() }
    val decrypt = remember { Decrypt() }

    // Real lines for the decrypt effect, so it doubles as a status read.
    val lines = remember(events, tasks, media, battery, charging) {
        buildList {
            events.take(3).forEach { add(it.whenLabel.trim().lowercase() + " " + it.title.lowercase()) }
            add(if (tasks.isEmpty()) "todo all clear" else "todo ${tasks.size} open")
            media?.let { add((if (it.playing) "playing " else "paused ") + it.title.lowercase()) }
            add("battery $battery%" + if (charging) " charging" else "")
            if (size < 6) add("no alerts")
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Tn.bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { wake() },
                    onLongPress = { fx = Fx.entries[(fx.ordinal + 1) % Fx.entries.size] },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            frame // redraw every tick
            when (fx) {
                Fx.RAIN -> rain.draw(this, paint)
                Fx.STARS -> stars.draw(this, paint)
                Fx.DECRYPT -> decrypt.draw(this, paint, lines)
            }
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val shape = RoundedCornerShape(6.dp)
            BasicText(
                blockClock(clock.format(DateTimeFormatter.ofPattern("HH:mm"))),
                Modifier
                    .background(Tn.bg.copy(alpha = 0.85f), shape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = Body.copy(color = Tn.blue, fontSize = 18.sp, lineHeight = 18.sp),
            )
            BasicText(
                clock.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).lowercase(),
                Modifier
                    .background(Tn.bg.copy(alpha = 0.85f), shape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = Body.copy(color = Tn.green, fontSize = 13.sp),
            )
        }
        BasicText(
            "jake@flip ~ idle · " + fx.name.lowercase() + if (charging) " · ⚡" else "",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            style = Body.copy(color = Tn.dim, fontSize = 11.sp),
        )
    }
}

private fun isCharging(ctx: Context): Boolean =
    ctx.getSystemService(BatteryManager::class.java)?.isCharging == true

private fun DrawScope.text(paint: Paint, s: String, x: Float, y: Float, color: Color, sizePx: Float) {
    paint.color = color.toArgb()
    paint.textSize = sizePx
    drawIntoCanvas { it.nativeCanvas.drawText(s, x, y, paint) }
}

/** Matrix rain: columns of glyphs with fading tails. */
private class Rain {
    private var cols = 0
    private var rows = 0
    private var heads = FloatArray(0)
    private var speeds = FloatArray(0)
    private var lens = IntArray(0)
    private var grid = CharArray(0)
    private val trail = listOf(Tn.blue, Tn.purple, Tn.cyan)

    fun draw(d: DrawScope, paint: Paint) = with(d) {
        val px = 12.dp.toPx()
        val cw = px * 0.9f
        val c = (size.width / cw).toInt().coerceAtLeast(1)
        val r = (size.height / px).toInt().coerceAtLeast(1)
        if (c != cols || r != rows) {
            cols = c
            rows = r
            heads = FloatArray(c) { -Random.nextFloat() * r }
            speeds = FloatArray(c) { 0.25f + Random.nextFloat() * 0.45f }
            lens = IntArray(c) { 6 + Random.nextInt(12) }
            grid = CharArray(c * r) { GLYPHS.random() }
        }
        repeat(c * r / 40) { grid[Random.nextInt(grid.size)] = GLYPHS.random() }
        for (i in 0 until cols) {
            heads[i] += speeds[i]
            if (heads[i] - lens[i] > rows) {
                heads[i] = -Random.nextFloat() * 8f
                speeds[i] = 0.25f + Random.nextFloat() * 0.45f
            }
            val head = heads[i].toInt()
            for (k in 0 until lens[i]) {
                val row = head - k
                if (row < 0 || row >= rows) continue
                val col = if (k == 0) Tn.fg else trail[i % trail.size].copy(alpha = 1f - k.toFloat() / lens[i])
                text(paint, grid[row * cols + i].toString(), i * cw, (row + 1) * px, col, px)
            }
        }
    }
}

/** Starfield: points flying out from the middle. */
private class Stars {
    private val n = 80
    private val x = FloatArray(n) { Random.nextFloat() * 2 - 1 }
    private val y = FloatArray(n) { Random.nextFloat() * 2 - 1 }
    private val z = FloatArray(n) { Random.nextFloat() }

    fun draw(d: DrawScope, paint: Paint) = with(d) {
        val cx = size.width / 2
        val cy = size.height / 2
        val spread = size.minDimension / 5
        for (i in 0 until n) {
            z[i] -= 0.012f
            if (z[i] <= 0.02f) {
                x[i] = Random.nextFloat() * 2 - 1
                y[i] = Random.nextFloat() * 2 - 1
                z[i] = 1f
            }
            val sx = cx + x[i] / z[i] * spread
            val sy = cy + y[i] / z[i] * spread
            if (sx < 0 || sy < 0 || sx > size.width || sy > size.height) continue
            val near = 1f - z[i]
            val glyph = if (near > 0.75f) "*" else if (near > 0.4f) "+" else "·"
            text(paint, glyph, sx, sy, if (near > 0.75f) Tn.fg else Tn.blue.copy(alpha = 0.4f + near * 0.6f), (8 + near * 10).dp.toPx())
        }
    }
}

/** Decrypt: real status lines resolve out of noise, hold, then scramble again. */
private class Decrypt {
    private var ticks = IntArray(0)

    fun draw(d: DrawScope, paint: Paint, lines: List<String>) = with(d) {
        val px = 11.dp.toPx()
        val gap = px * 1.6f
        val width = (size.width / (px * 0.62f)).toInt() - 4
        val rows = (size.height / gap).toInt()
        if (ticks.size != rows) ticks = IntArray(rows) { Random.nextInt(120) }
        for (r in 0 until rows) {
            ticks[r]++
            if (ticks[r] > 240) ticks[r] = 0
            val word = lines[r % lines.size].take(width)
            val shown = (ticks[r] / 3).coerceAtMost(word.length)
            val sb = StringBuilder("> ")
            for (k in 0 until width) {
                sb.append(
                    when {
                        k < shown -> word.getOrElse(k) { ' ' }
                        k < shown + 6 && shown < word.length -> GLYPHS.random()
                        else -> ' '
                    }
                )
            }
            val done = shown >= word.length
            text(paint, sb.toString(), 10.dp.toPx(), (r + 1) * gap, if (done) Tn.dim else Tn.cyan, px)
        }
    }
}
