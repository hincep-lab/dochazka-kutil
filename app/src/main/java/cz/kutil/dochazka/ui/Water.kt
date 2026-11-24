package cz.kutil.dochazka.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.withFrameMillis
import cz.kutil.dochazka.R
import cz.kutil.dochazka.data.AppPrefs
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// =============================================================================================
// Živá voda. Vše se kreslí v Compose (Canvas) – žádné obrázky navíc, žádné WebView.
// Úspora baterie: jeden společný „takt“ maximálně 30 snímků za sekundu, běží jen když je
// aplikace na obrazovce, a čte se jen ve fázi kreslení (nepřepočítává se rozvržení obrazovky).
// Při vypnutých animacích (nastavení aplikace nebo systémové „Odebrat animace“) takt stojí.
// =============================================================================================

/** Jsou dekorativní animace povolené? Respektuje nastavení aplikace i systémové omezení pohybu. */
@Composable
fun decorAnimationsEnabled(settingsTick: Any? = null): Boolean {
    val ctx = LocalContext.current
    return remember(settingsTick) {
        val system = runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        AppPrefs.decorAnimations(ctx) && system > 0f
    }
}

/** Během scénky s instalatérem běží takt rychleji (plynulý pohyb postavy). */
object WaterClockBoost { @Volatile var until: Float = -1f }

/** Společný čas animací v sekundách (max. [fps] snímků/s, jen když je aplikace vidět). */
/** Jen pro natáčení ukázkového videa v testech: čas animací řídí test. */
object WaterClockDebug {
    @Volatile var manual: androidx.compose.runtime.MutableFloatState? = null
}

@Composable
fun rememberWaterClock(enabled: Boolean, fps: Int = 30): State<Float> {
    WaterClockDebug.manual?.let { return it }
    val t = remember { mutableFloatStateOf(0f) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(enabled, owner) {
        if (!enabled) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val frame = 1000L / fps
            var last = -1L
            var acc = t.floatValue
            while (true) {
                withFrameMillis { ms ->
                    if (last >= 0) acc += min(ms - last, 100L) / 1000f
                    last = ms
                    t.floatValue = acc
                }
                delay(if (acc < WaterClockBoost.until) 1000L / 60 - 8 else frame - 8)
            }
        }
    }
    return t
}

/** Stav vody: 0 = ventil zavřený, klid; 1 = voda proudí. Plynulý náběh i doběh. */
class WaterState(val flow: Float, val valveSpin: Float)

@Composable
fun rememberWaterState(working: Boolean, animate: Boolean): WaterState {
    // 1. otočí se ventil, 2. teprve potom se rozběhne / zastaví voda
    val spin = remember { Animatable(0f) }
    LaunchedEffect(working) {
        if (!animate) return@LaunchedEffect
        spin.snapTo(0f)
        spin.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val flow by animateFloatAsState(
        if (working) 1f else 0f,
        if (animate) tween(if (working) 1800 else 2600, delayMillis = if (working) 700 else 300) else tween(0),
        label = "flow",
    )
    return WaterState(flow, if (spin.isRunning) spin.value else 0f)
}

// ---------------------------------------------------------------------------------------------
// Skleněný panel s vodou, do které kape z kohoutku
// ---------------------------------------------------------------------------------------------

enum class GlassTone { WORKING, PENDING, IDLE }

/** Volné místo pod textem panelu, ve kterém se drží vodní hladina. */
val WaterZone: Dp = 46.dp

/** Kde je ústí kohoutku vůči pravému okraji panelu. */
val FaucetNozzleFromEnd: Dp = 114.dp

// Rozměry 3D kohoutku (tools/render_realism.py): obrázek 600×400, ústí v 24,67 % šířky a 68,84 % výšky.
private val FaucetW: Dp = 170.dp
private val FaucetH: Dp = 113.dp
private const val NOZZLE_FY = 0.6884f
private const val WHEEL_FX = 0.5433f
private const val WHEEL_FY = 0.10f
private const val WHEEL_FW = 0.2467f
private const val WHEEL_FH = 0.175f

/** Kdy kapka dopadla na hladinu (fáze v periodě kapání) – zapisuje kreslení, čte zvuk. */
class DripSync { @Volatile var impactPhase: Float = -1f }
private const val DRIP_PERIOD = 3.4f
const val DRIP_PERIOD_S = DRIP_PERIOD

@Composable
fun WaterPanel(
    tone: GlassTone,
    clock: State<Float>,
    water: WaterState,
    /** 0..1 = průběh osmihodinové doby; nad 1 = přesčas. */
    level: Float,
    animate: Boolean,
    modifier: Modifier = Modifier,
    drip: DripSync? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val tint = when (tone) {
        GlassTone.WORKING -> Color(0xFF1C4C86)
        GlassTone.PENDING -> Color(0xFF7A4A12)
        GlassTone.IDLE -> Color(0xFF1A3558)
    }
    val lvl by animateFloatAsState(level.coerceIn(0f, 1.5f), tween(if (animate) 1500 else 0), label = "level")
    val caustics = ImageBitmap.imageResource(R.drawable.water_caustics)
    Box(
        modifier
            .fillMaxWidth()
            // přetékání nad 8 hodin: kreslí se přes okraj panelu i pod něj
            .drawWithContent {
                drawContent()
                if (lvl > 1f && tone == GlassTone.WORKING) drawOverflow(clock.value, animate, ((lvl - 1f) * 4f).coerceIn(0.35f, 1f))
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(14.dp, shape, ambientColor = Color(0xFF4FA8FF), spotColor = Color.Black)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.92f), Color(0xF00B1A31))))
                .drawBehind { drawWater(clock.value, water, lvl, animate, tone, caustics, drip) }
                .border(1.5.dp, Brush.verticalGradient(listOf(Color(0xCCBFE3FF), Color(0x5582B8F0), Color(0x88BFE3FF))), shape),
        ) {
            // skleněný lesk nahoře
            Box(Modifier.fillMaxWidth().height(36.dp).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent))))
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp), content = content)
            }
        }
        // ústí kohoutku sedí 4 dp pod horní hranou panelu
        Faucet(water, Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = -(FaucetH * NOZZLE_FY) + 4.dp))
    }
}

private fun DrawScope.drawWater(
    t: Float, w: WaterState, level: Float, animate: Boolean, tone: GlassTone,
    caustics: androidx.compose.ui.graphics.ImageBitmap, drip: DripSync?,
) {
    val wv = size.width
    val h = size.height
    // Panel je nádoba: čím blíž k 8 hodinám, tím je plnější. V 8 h je plná po okraj,
    // pak přetéká (viz drawOverflow). Voda je průsvitná a text má stín, takže zůstává čitelný.
    val rim = 5f * density
    val fillPx = (8f * density) + (h - rim - 8f * density) * min(level, 1f)
    val surface = h - fillPx
    val overtime = (level - 1f).coerceIn(0f, 0.5f) * 2f
    val amp = (1.5f + 2.5f * w.flow) * density
    val phase = if (animate) t else 0f
    val base = if (tone == GlassTone.PENDING) Color(0xFFFFB648) else Color(0xFF39A7FF)
    val glow = 0.22f + 0.18f * overtime

    fun waveY(x: Float, k: Float = 1f): Float =
        surface + amp * k * sin(x / wv * 2f * PI.toFloat() * 1.6f + phase * 1.9f) +
            amp * 0.55f * k * sin(x / wv * 2f * PI.toFloat() * 3.1f - phase * 1.3f + 1.2f)

    // zadní, tmavší vlna
    val back = Path().apply {
        moveTo(0f, h)
        var x = 0f
        while (x <= wv) { lineTo(x, waveY(x + 40f * density, 0.8f) - 4f * density); x += 6f * density }
        lineTo(wv, h); close()
    }
    drawPath(back, Brush.verticalGradient(listOf(base.copy(alpha = glow * 0.6f), Color(0x660A2A55)), startY = surface - amp, endY = h))
    val front = Path().apply {
        moveTo(0f, h)
        var x = 0f
        while (x <= wv) { lineTo(x, waveY(x)); x += 6f * density }
        lineTo(wv, h); close()
    }
    drawPath(front, Brush.verticalGradient(listOf(base.copy(alpha = glow + 0.10f), Color(0x880C3B78)), startY = surface - amp, endY = h))
    // světelné kaustiky ve vodě – dvě vrstvy posouvané proti sobě, jen pod hladinou
    clipPath(front) {
        val tile = 150.dp.toPx()
        for ((layer, dir) in listOf(0 to 1f, 1 to -1f)) {
            val ox = ((phase * 9f * dir * density) % tile + tile) % tile - tile
            val oy = ((phase * (4f + layer * 3f) * density) % tile) - tile
            var y = oy + layer * tile * 0.37f
            while (y < h) {
                var x = ox + layer * tile * 0.5f
                while (x < wv) {
                    drawImage(
                        caustics, srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        srcSize = androidx.compose.ui.unit.IntSize(caustics.width, caustics.height),
                        dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(tile.toInt() + 1, tile.toInt() + 1),
                        alpha = if (tone == GlassTone.PENDING) 0.18f else 0.28f + 0.12f * overtime,
                    )
                    x += tile
                }
                y += tile
            }
        }
    }
    // tmavší hloubka u dna
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66061A3A)), startY = surface + (h - surface) * 0.4f, endY = h), topLeft = Offset(0f, surface), size = Size(wv, h - surface))
    // lesk na hřebeni
    val crest = Path().apply {
        var x = 0f
        moveTo(0f, waveY(0f))
        while (x <= wv) { lineTo(x, waveY(x)); x += 6f * density }
    }
    drawPath(crest, Color.White.copy(alpha = 0.45f + 0.2f * overtime), style = Stroke(width = 1.6f * density))
    // drobné odlesky na hladině
    for (i in 0 until 5) {
        val x = ((i * 0.23f + phase * 0.02f * (1 + i % 2)) % 1f) * wv
        drawLine(Color.White.copy(alpha = 0.35f), Offset(x, waveY(x) + 5f * density), Offset(x + 14f * density, waveY(x + 14f * density) + 5f * density), strokeWidth = 1.2f * density)
    }
    if (!animate) return
    // bublinky stoupající ke hladině
    for (i in 0 until 4) {
        val period = 4.5f + i * 1.3f
        val p = ((phase + i * 1.7f) % period) / period
        if (p > 0.85f) continue
        val bx = wv * (0.12f + 0.21f * i) + 4f * density * sin(phase * 3f + i)
        val by = h - (h - surface) * (p / 0.85f)
        val r = (1.6f + i % 2) * density
        drawCircle(Color.White.copy(alpha = 0.55f), r, Offset(bx, by), style = Stroke(1f * density))
    }
    // kapka z kohoutku a kruhy na hladině – jen když teče voda
    if (w.flow < 0.85f) return
    val nx = wv - FaucetNozzleFromEnd.toPx()
    val p = (phase % DRIP_PERIOD) / DRIP_PERIOD * DRIP_PERIOD
    val form = 1.7f
    val stretch = 0.35f
    val r = 4.6f * density
    val top = 6f * density
    when {
        p < form -> drawDrop(Offset(nx, top + r * (p / form)), r * (0.35f + 0.65f * p / form), 1f)
        p < form + stretch -> {
            val k = (p - form) / stretch
            drawDrop(Offset(nx, top + r + 6f * density * k), r, 1f + 0.6f * k)
        }
        else -> {
            val ft = p - form - stretch
            val y = top + r + 6f * density + 0.5f * 2600f * density * ft * ft / 3f
            val hit = surface + amp * 0.3f
            if (y < hit) {
                drawDrop(Offset(nx, y), r * 0.95f, 1.25f)
            } else {
                val tHit = kotlin.math.sqrt(((hit - top - r - 6f * density) * 3f / (0.5f * 2600f * density)).coerceAtLeast(0f))
                drip?.impactPhase = form + stretch + tHit
                val since = ft - tHit
                for (ring in 0 until 3) {
                    val q = (since - ring * 0.22f) / 1.1f
                    if (q in 0f..1f) {
                        val rw = (8f + 46f * q) * density
                        drawOval(
                            Color.White.copy(alpha = (1f - q) * 0.6f),
                            topLeft = Offset(nx - rw, hit - rw * 0.22f),
                            size = Size(rw * 2, rw * 0.44f),
                            style = Stroke(width = 1.4f * density),
                        )
                    }
                }
                // odstřik
                if (since < 0.35f) {
                    val s = since / 0.35f
                    for (k in -1..1 step 2) {
                        drawCircle(Color(0xCCBFE6FF), 1.6f * density, Offset(nx + k * 10f * density * s, hit - 14f * density * s * (1 - s) * 2f))
                    }
                }
            }
        }
    }
}

/**
 * Přesčas: voda přetéká přes horní okraj, stéká po bocích nádoby a odkapává spodem.
 * [k] 0..1 = intenzita (roste s délkou přesčasu).
 */
private fun DrawScope.drawOverflow(t: Float, animate: Boolean, k: Float) {
    val w = size.width
    val h = size.height
    val ph = if (animate) t else 0f
    val water = Color(0xFF7CCBFF)
    // pramínky po obou bocích (přes okraj dolů)
    for ((x, dir) in listOf(3f * density to 1f, w - 3f * density to -1f)) {
        val sw = (2.5f + 2.5f * k) * density
        drawLine(Brush.verticalGradient(listOf(water.copy(alpha = 0.85f), water.copy(alpha = 0.35f)), startY = 0f, endY = h), Offset(x, 0f), Offset(x, h + 6f * density), strokeWidth = sw)
        // stékající „vlnky“ v pramínku
        val seg = 18f * density
        var y = (ph * 60f * density) % seg - seg
        while (y < h) {
            drawLine(Color.White.copy(alpha = 0.55f), Offset(x + dir * 0.5f, y), Offset(x + dir * 0.5f, y + seg * 0.3f), strokeWidth = 1.2f * density)
            y += seg
        }
        // přetékající jazyk přes horní hranu
        drawArc(water.copy(alpha = 0.8f), if (dir > 0) 180f else 270f, 90f, false, Offset(x - dir * 0f - 6f * density, -4f * density), Size(12f * density, 12f * density), style = Stroke(sw))
    }
    // odtékání spodem: kapky padají pod panel
    val outs = listOf(0.12f, 0.5f, 0.88f)
    for ((i, fx) in outs.withIndex()) {
        val period = 0.9f + i * 0.17f
        val q = ((ph + i * 0.31f) % period) / period
        val y = h + 2f * density + q * q * 26f * density
        drawDrop(Offset(w * fx, y), (2.6f + 1.2f * k) * density, 1.2f)
    }
    drawLine(water.copy(alpha = 0.7f), Offset(w * 0.1f, h), Offset(w * 0.9f, h), strokeWidth = 2f * density)
}

/** Průhledná kapka s odleskem; [stretch] > 1 = protažená. */
fun DrawScope.drawDrop(c: Offset, r: Float, stretch: Float) {
    val path = Path().apply {
        moveTo(c.x, c.y - r * 1.9f * stretch)
        cubicTo(c.x + r * 0.35f, c.y - r * 1.1f * stretch, c.x + r, c.y - r * 0.4f, c.x + r, c.y + r * 0.15f)
        cubicTo(c.x + r, c.y + r * 0.8f, c.x + r * 0.55f, c.y + r * 1.1f, c.x, c.y + r * 1.1f)
        cubicTo(c.x - r * 0.55f, c.y + r * 1.1f, c.x - r, c.y + r * 0.8f, c.x - r, c.y + r * 0.15f)
        cubicTo(c.x - r, c.y - r * 0.4f, c.x - r * 0.35f, c.y - r * 1.1f * stretch, c.x, c.y - r * 1.9f * stretch)
        close()
    }
    drawPath(path, Brush.radialGradient(listOf(Color(0x99E6F6FF), Color(0x6650B4FF), Color(0xAA1E6FD0)), center = Offset(c.x - r * 0.3f, c.y - r * 0.2f), radius = r * 1.6f))
    drawPath(path, Color.White.copy(alpha = 0.7f), style = Stroke(width = 0.9f * density))
    drawCircle(Color.White.copy(alpha = 0.9f), r * 0.28f, Offset(c.x - r * 0.38f, c.y - r * 0.15f))
}

// ---------------------------------------------------------------------------------------------
// Chromovaný kohoutek s ventilem (skládá se z předrenderovaných kovových dílů)
// ---------------------------------------------------------------------------------------------

@Composable
fun Faucet(water: WaterState, modifier: Modifier = Modifier) {
    val spin = water.valveSpin
    // při otevření / zavření se kolo otočí o 1,5 otáčky; 6 předrenderovaných fází na 90°
    val frame = if (spin > 0f) (((spin * 540f) / 15f).toInt() % 6) else 0
    val wheel = when (frame) {
        1 -> R.drawable.faucet_wheel_1
        2 -> R.drawable.faucet_wheel_2
        3 -> R.drawable.faucet_wheel_3
        4 -> R.drawable.faucet_wheel_4
        5 -> R.drawable.faucet_wheel_5
        else -> R.drawable.faucet_wheel_0
    }
    Box(modifier.size(FaucetW, FaucetH)) {
        Image(painterResource(R.drawable.faucet_body), contentDescription = null, modifier = Modifier.fillMaxSize())
        Image(
            painterResource(wheel), contentDescription = null,
            modifier = Modifier
                .offset(x = FaucetW * WHEEL_FX, y = FaucetH * WHEEL_FY)
                .size(FaucetW * WHEEL_FW, FaucetH * WHEEL_FH),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Boční potrubí s průhledným úsekem, kterým proudí voda
// ---------------------------------------------------------------------------------------------

@Composable
fun SidePipes(clock: State<Float>, water: WaterState, animate: Boolean, modifier: Modifier = Modifier) {
    val pipes = rememberPipeImages()
    Canvas(modifier.fillMaxSize()) {
        val d = 20.dp.toPx()
        val k = d / 64f
        val s = pipes.tl.width * k
        val t = if (animate) clock.value else 0f
        fun img(i: androidx.compose.ui.graphics.ImageBitmap, x: Float, y: Float, w: Float, h: Float) = drawImage(
            i, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(i.width, i.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()), dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        )
        // levá svislá trubka u okraje (napůl za okrajem obrazovky)
        val lx = -d * 0.45f
        val glassTop = size.height * 0.30f
        val glassLen = size.height * 0.20f
        img(pipes.v, lx, 0f, d, glassTop)
        img(pipes.v, lx, glassTop + glassLen, d, size.height * 0.68f - glassTop - glassLen)
        drawGlassTube(Rect(lx, glassTop, lx + d, glassTop + glassLen), t, water.flow)
        for (y in listOf(glassTop - 6.dp.toPx(), glassTop + glassLen - 3.dp.toPx())) {
            img(pipes.clampV, lx + d / 2 - pipes.clampV.width * k / 2, y, pipes.clampV.width * k, pipes.clampV.height * k)
        }
        // pravá trubka dole s měděnou spojkou (ke vpusti)
        val rx = size.width - d * 0.55f
        val ry = size.height * 0.74f
        img(pipes.v, rx, ry, d, size.height - ry)
        img(pipes.copper, rx - d * 0.06f, ry + d * 1.2f, d * 1.12f, d * 1.5f)
    }
}

private fun DrawScope.drawGlassTube(r: Rect, t: Float, flow: Float) {
    // sklo
    drawRect(Brush.horizontalGradient(listOf(Color(0x5582C8FF), Color(0x22FFFFFF), Color(0x55195FAF)), startX = r.left, endX = r.right), r.topLeft, r.size)
    // voda uvnitř: proudí dolů, při práci jemně podsvícená
    val water = Rect(r.left + r.width * 0.18f, r.top, r.right - r.width * 0.18f, r.bottom)
    clipRect(water.left, water.top, water.right, water.bottom) {
        drawRect(Color(0xFF1E78D8).copy(alpha = 0.35f + 0.35f * flow), water.topLeft, water.size)
        val band = 26.dp.toPx()
        val off = (t * 30.dp.toPx() * flow) % band
        var y = water.top - band + off
        while (y < water.bottom) {
            drawRect(Color.White.copy(alpha = 0.10f * flow), Offset(water.left, y), Size(water.width, band * 0.35f))
            y += band
        }
        if (flow > 0.3f) {
            for (i in 0 until 2) {
                val q = ((t * 0.35f + i * 0.5f) % 1f)
                drawCircle(Color.White.copy(alpha = 0.7f * flow), 1.8f * density, Offset(water.center.x + (i - 0.5f) * 3f * density, water.top + q * water.height), style = Stroke(1f * density))
            }
        }
    }
    // modré podsvícení aktivního úseku
    if (flow > 0f) drawRect(Color(0xFF4FB4FF).copy(alpha = 0.18f * flow), Offset(r.left - 4.dp.toPx(), r.top), Size(r.width + 8.dp.toPx(), r.height))
    drawLine(Color.White.copy(alpha = 0.6f), Offset(r.left + r.width * 0.28f, r.top), Offset(r.left + r.width * 0.28f, r.bottom), strokeWidth = 1.5f * density)
}

// ---------------------------------------------------------------------------------------------
// Odpadní vpusť s vírem
// ---------------------------------------------------------------------------------------------

@Composable
fun AnimatedDrain(clock: State<Float>, water: WaterState, animate: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2 + 4.dp.toPx())
            val t = if (animate) clock.value else 0f
            val flat = 0.5f // stejná perspektiva jako vyrenderovaná vpusť
            // mokrá podlaha a mělká voda kolem vpusti
            drawOval(
                Brush.radialGradient(listOf(Color(0x7046A8FF), Color(0x302A6FC0), Color(0x00143A6E)), center = c, radius = size.width * 0.38f),
                Offset(c.x - size.width * 0.42f, c.y - size.width * 0.42f * flat), Size(size.width * 0.84f, size.width * 0.84f * flat),
            )
            // vír: proudnice stáčející se k vpusti (rychlejší, když teče voda)
            val speed = 18f + 40f * water.flow
            for (i in 0 until 5) {
                val rw = (92f + i * 14f) * density
                rotate(t * speed * (1f - i * 0.1f) + i * 63f, c) {
                    drawArc(
                        Color(0xFFCFEAFF).copy(alpha = 0.30f - i * 0.04f),
                        startAngle = 0f, sweepAngle = 70f + i * 8f, useCenter = false,
                        topLeft = Offset(c.x - rw, c.y - rw * flat), size = Size(rw * 2, rw * 2 * flat),
                        style = Stroke(width = (1.8f - i * 0.2f) * density),
                    )
                }
            }
            if (animate) {
                for (i in 0 until 4) {
                    val q = ((t * 0.25f + i * 0.25f) % 1f)
                    val ang = t * 1.2f + i * 1.6f
                    val rr = (110f - 40f * q) * density
                    drawCircle(Color.White.copy(alpha = 0.65f * (1 - q)), (1.4f + i % 2) * density, Offset(c.x + rr * cos(ang), c.y + rr * flat * sin(ang)), style = Stroke(1f * density))
                }
            }
        }
        Image(painterResource(R.drawable.drain_persp), null, Modifier.size(150.dp, 100.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Pulzující indikátor stavu (GPS)
// ---------------------------------------------------------------------------------------------

@Composable
fun PulseIndicator(color: Color, clock: State<Float>, animate: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.drawBehind {
            if (!animate) return@drawBehind
            val q = (clock.value % 1.8f) / 1.8f
            drawCircle(color.copy(alpha = 0.45f * (1 - q)), radius = size.minDimension / 2 * (0.7f + 0.8f * q), center = center)
        },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// Skleněná karta hlavní obrazovky
// ---------------------------------------------------------------------------------------------

@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 12.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xE0234A7C), Color(0xE8122A4C))))
            .border(1.2.dp, Brush.verticalGradient(listOf(Color(0xCCBFE3FF), Color(0x4482B8F0))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent))))
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.padding(padding), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp), content = content)
        }
    }
}


// ---------------------------------------------------------------------------------------------
// Přetékání přes celou obrazovku: z nádoby stékají pramínky až dolů do vpusti
// ---------------------------------------------------------------------------------------------

/** Kde je na obrazovce panel s nádobou (v souřadnicích kořene). */
class PanelBounds { var rect by androidx.compose.runtime.mutableStateOf<Rect?>(null) }

@Composable
fun OverflowOverlay(clock: State<Float>, overflowing: Boolean, animate: Boolean, panel: PanelBounds, modifier: Modifier = Modifier) {
    val k by animateFloatAsState(if (overflowing) 1f else 0f, tween(if (animate) 2200 else 0), label = "overflow")
    if (k <= 0.01f) return
    val grate = ImageBitmap.imageResource(R.drawable.drain_persp)
    var origin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Canvas(
        modifier.onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        val t = if (animate) clock.value else 0f
        val u = density
        val r = panel.rect?.let { Rect(it.left - origin.x, it.top - origin.y, it.right - origin.x, it.bottom - origin.y) }
            ?: Rect(0f, 0f, size.width, size.height * 0.3f)
        val drain = Offset(size.width / 2, size.height - 22 * u)
        val startY = r.bottom.coerceIn(0f, size.height * 0.8f)
        val water = Color(0xFF6CC4FF)
        // pramínky: od rohů nádoby dolů a pak do vpusti
        for ((i, x0) in listOf(r.left + 6 * u, r.right - 6 * u).withIndex()) {
            if (i == 2 && k < 0.6f) continue
            val bottom = size.height - 46 * u - i * 6 * u
            val path = Path().apply {
                moveTo(x0, startY)
                // lehké vlnění pramínku
                var y = startY
                while (y < bottom) {
                    y += 24 * u
                    lineTo(x0 + 2.2f * u * sin(y / (31 * u) + t * 2.5f + i), min(y, bottom))
                }
                quadraticTo(x0, drain.y - 4 * u, (x0 + drain.x) / 2, drain.y - 2 * u)
                quadraticTo(drain.x + (x0 - drain.x) * 0.15f, drain.y, drain.x, drain.y)
            }
            val wdt = (if (i == 2) 3.5f else 5f) * u * k
            drawPath(path, water.copy(alpha = 0.40f * k), style = Stroke(wdt * 1.8f, cap = StrokeCap.Round))
            drawPath(path, water.copy(alpha = 0.75f * k), style = Stroke(wdt, cap = StrokeCap.Round))
            // proudění: bílé odlesky běží po pramínku dolů
            drawPath(
                path, Color.White.copy(alpha = 0.75f * k),
                style = Stroke(
                    1.4f * u, cap = StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10 * u, 26 * u), -(t * 90f * u) % (36 * u)),
                ),
            )
            // kapky, které odskakují
            val q = ((t * 0.8f + i * 0.37f) % 1f)
            drawDrop(Offset(x0 + (if (i == 1) -1 else 1) * 6 * u * q, startY + (bottom - startY) * q), 2f * u * k, 1.2f)
        }
        // louže a vír u vpusti dole
        drawOval(
            Brush.radialGradient(listOf(Color(0x9954B6FF), Color(0x3340A0FF), Color.Transparent), center = drain, radius = 90 * u * k),
            Offset(drain.x - 90 * u * k, drain.y - 18 * u * k), Size(180 * u * k, 36 * u * k),
        )
        for (j in 0 until 4) {
            val rw = (26f + j * 12f) * u
            rotate(t * (160f - j * 25f) + j * 80f, drain) {
                drawArc(Color.White.copy(alpha = (0.55f - j * 0.1f) * k), 0f, 100f, false, Offset(drain.x - rw, drain.y - rw * 0.35f), Size(rw * 2, rw * 0.7f), style = Stroke(1.6f * u))
            }
        }
        val gw = 70 * u
        val gh = gw * grate.height / grate.width
        drawImage(
            grate, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(grate.width, grate.height),
            dstOffset = androidx.compose.ui.unit.IntOffset((drain.x - gw / 2).toInt(), (drain.y - gh / 2).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(gw.toInt(), gh.toInt()), alpha = k,
        )
    }
}

