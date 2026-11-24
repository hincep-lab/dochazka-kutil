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
import androidx.compose.runtime.withFrameNanos
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
fun rememberWaterClock(enabled: Boolean, fps: Int = 60): State<Float> {
    WaterClockDebug.manual?.let { return it }
    val t = remember { mutableFloatStateOf(0f) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(enabled, owner, fps) {
        if (!enabled) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // 60: každý snímek na 60Hz displeji, na 120Hz každý druhý; 120: každý snímek displeje
            val minGap = 1_000_000_000L / fps - 2_000_000L
            var last = -1L
            var acc = t.floatValue
            while (true) {
                var gap = 16_000_000L
                withFrameNanos { ns ->
                    if (last < 0) {
                        last = ns
                    } else if (ns - last >= minGap) {
                        gap = ns - last
                        acc += min(gap, 100_000_000L) / 1_000_000_000f
                        last = ns
                        t.floatValue = acc
                    } else {
                        gap = ns - last
                    }
                }
                // pojistka: kdyby snímky chodily nepřirozeně rychle (testy, emulátor)
                if (gap < 4_000_000L) delay(8)
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
val FaucetNozzleFromEnd: Dp = 103.dp

// Kohoutek z balíku grafiky (graphics/valves/vodovodni-kohoutek.png, 740×430), zrcadlově:
// přívod vpravo u okraje obrazovky, ústí podle PRIPOJOVACI_BODY.json v (1 − 0,852; 0,8323).
private val FaucetW: Dp = 150.dp
private val FaucetH: Dp = 87.2.dp
private const val NOZZLE_FY = 0.8323f

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
        // přívod kohoutku (8,75 % šířky obrázku) přesně na pravém okraji obrazovky – kohoutek „vychází ze zdi“
        Faucet(water, Modifier.align(Alignment.TopEnd).offset(x = 12.dp + FaucetW * 0.0875f, y = -(FaucetH * NOZZLE_FY) + 4.dp))
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
    val nx = wv - FaucetNozzleFromEnd.toPx()
    // v práci teče z kohoutku souvislý proud (plní nádobu); doma jen kape
    if (w.flow > 0.12f) {
        drawPourStream(nx, surface + amp * 0.3f, w.flow, phase, h)
        return
    }
    // kapka z kohoutku a kruhy na hladině
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

/** Souvislý proud z kohoutku: průhledný pramen s pohyblivými odlesky, pěna a kruhy u hladiny. */
private fun DrawScope.drawPourStream(nx: Float, hit: Float, flow: Float, t: Float, h: Float) {
    val d = density
    val k = ((flow - 0.12f) / 0.88f).coerceIn(0f, 1f)
    val wTop = (4.5f + 4f * k) * d
    val wBot = (3.4f + 3f * k) * d
    val wob = 0.6f * d * sin(t * 17f)
    val body = Path().apply {
        moveTo(nx - wTop / 2, 0f)
        lineTo(nx + wTop / 2, 0f)
        cubicTo(nx + wTop / 2, hit * 0.4f, nx + wBot / 2 + wob, hit * 0.7f, nx + wBot / 2 + wob, hit)
        lineTo(nx - wBot / 2 + wob, hit)
        cubicTo(nx - wBot / 2 + wob, hit * 0.7f, nx - wTop / 2, hit * 0.4f, nx - wTop / 2, 0f)
        close()
    }
    drawPath(body, Brush.horizontalGradient(listOf(Color(0xAA54B6FF), Color(0xEEEFF8FF), Color(0xAA3E9BEA)), startX = nx - wTop / 2, endX = nx + wTop / 2), alpha = 0.45f + 0.55f * k)
    // tenký lesk uprostřed a putující odlesky dolů
    drawLine(Color.White.copy(alpha = 0.55f * k), Offset(nx - 0.6f * d, 0f), Offset(nx - 0.6f * d + wob, hit), strokeWidth = 0.9f * d)
    for (i in 0 until 6) {
        val q = ((t * 1.9f + i / 6f) % 1f)
        val y = hit * q
        drawLine(Color.White.copy(alpha = 0.7f * k * (1f - q * 0.5f)), Offset(nx + wob * q, y), Offset(nx + wob * q, y + 7f * d), strokeWidth = 1.3f * d, cap = StrokeCap.Round)
    }
    // pěna a víření v místě dopadu
    if (hit < h - 2 * d) {
        drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f * k), Color.Transparent), center = Offset(nx, hit), radius = 16 * d), Offset(nx - 16 * d, hit - 4 * d), Size(32 * d, 8 * d))
        for (ring in 0 until 3) {
            val q = ((t * 1.3f + ring / 3f) % 1f)
            val rw = (8f + 30f * q) * d
            drawOval(Color.White.copy(alpha = (1f - q) * 0.5f * k), Offset(nx - rw, hit - rw * 0.2f), Size(rw * 2, rw * 0.4f), style = Stroke(1.2f * d))
        }
        for (i in 0 until 5) {
            val q = ((t * 2.3f + i * 0.37f) % 1f)
            val dx = (if (i % 2 == 0) 1 else -1) * (4f + 9f * q + i) * d
            drawCircle(Color(0xCCBFE6FF).copy(alpha = (1f - q) * 0.8f * k), 1.3f * d, Offset(nx + dx, hit - 10f * d * q * (1f - q) * 3f))
        }
        // bublinky strhávané proudem pod hladinu
        for (i in 0 until 4) {
            val q = ((t * 0.9f + i * 0.29f) % 1f)
            drawCircle(Color.White.copy(alpha = 0.45f * (1f - q) * k), (1.2f + i % 2) * d, Offset(nx + (i - 1.5f) * 5f * d, hit + 4 * d + 26 * d * q), style = Stroke(0.9f * d))
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
    // při otevírání / zavírání se kohoutek krátce zachvěje (kohoutek je jeden obrázek)
    val shake = if (water.valveSpin > 0f) 2f * sin(water.valveSpin * 40f) else 0f
    Image(
        painterResource(R.drawable.g_kohoutek), contentDescription = null,
        modifier = modifier.size(FaucetW, FaucetH).graphicsLayer { scaleX = -1f; rotationZ = shake },
    )
}

// ---------------------------------------------------------------------------------------------
// Boční potrubí s průhledným úsekem, kterým proudí voda
// ---------------------------------------------------------------------------------------------

@Composable
fun SidePipes(clock: State<Float>, water: WaterState, animate: Boolean, modifier: Modifier = Modifier) {
    val long = ImageBitmap.imageResource(R.drawable.g_trubka_dlouha)
    val glass = ImageBitmap.imageResource(R.drawable.g_trubka_pruhledna)
    val joint = ImageBitmap.imageResource(R.drawable.g_spojka)
    val cast = ImageBitmap.imageResource(R.drawable.g_litina)
    Canvas(modifier.fillMaxSize()) {
        val d = 26.dp.toPx() // vizuální průměr trubek
        val t = if (animate) clock.value else 0f
        /** Vodorovný obrázek trubky otočený svisle; [top]..[top+len] na ose x = [cx]. */
        fun vertical(img: ImageBitmap, cx: Float, top: Float, len: Float, thick: Float = d) {
            if (len <= 0f) return
            rotate(90f, Offset(cx, top + len / 2)) {
                drawImage(
                    img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset((cx - len / 2).toInt(), (top + len / 2 - thick / 2).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(len.toInt(), thick.toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                )
            }
        }
        // levá svislá trubka u okraje, uprostřed průhledný úsek s vodou
        val lx = d * 0.12f
        val glassTop = size.height * 0.30f
        val glassLen = size.height * 0.20f
        val endY = size.height * 0.68f
        vertical(long, lx, -d * 0.4f, glassTop + d * 0.5f)
        vertical(glass, lx, glassTop, glassLen, d * 1.05f)
        drawGlassTube(Rect(lx - d * 0.22f, glassTop + d * 0.35f, lx + d * 0.22f, glassTop + glassLen - d * 0.35f), t, water.flow)
        vertical(long, lx, glassTop + glassLen - d * 0.1f, endY - glassTop - glassLen)
        for (y in listOf(glassTop, glassTop + glassLen)) vertical(joint, lx, y - d * 0.55f, d * 1.1f, d * 1.15f)
        // pravá litinová trubka dole (svod ke vpusti)
        val rx = size.width - d * 0.15f
        val ry = size.height * 0.72f
        vertical(cast, rx, ry, size.height - ry + d)
        vertical(joint, rx, ry - d * 0.2f, d * 1.1f, d * 1.15f)
    }
}

private fun DrawScope.drawGlassTube(r: Rect, t: Float, flow: Float) {
    // voda uvnitř: proudí dolů, při práci jemně podsvícená
    val water = Rect(r.left + r.width * 0.18f, r.top, r.right - r.width * 0.18f, r.bottom)
    clipRect(water.left, water.top, water.right, water.bottom) {
        drawRect(Color(0xFF1E78D8).copy(alpha = 0.10f + 0.25f * flow), water.topLeft, water.size)
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
}

// ---------------------------------------------------------------------------------------------
// Odpadní vpusť s vírem
// ---------------------------------------------------------------------------------------------

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
fun OverflowOverlay(
    clock: State<Float>, overflowing: Boolean, animate: Boolean, panel: PanelBounds, modifier: Modifier = Modifier,
    /** Kde je vpusť (na konci stránky); null = pod okrajem obrazovky. */
    drainAt: PanelBounds? = null,
    /** true = jen pevná vpusť na dně (kreslí se pod obsahem), false = jen stékající voda (nad obsahem). */
    drainOnly: Boolean = false,
) {
    val k by animateFloatAsState(if (overflowing) 1f else 0f, tween(if (animate) 2200 else 0), label = "overflow")
    val grate = ImageBitmap.imageResource(R.drawable.g_odtokova_mrizka)
    var origin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Canvas(
        modifier.onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        val t = if (animate) clock.value else 0f
        val u = density
        val r = panel.rect?.let { Rect(it.left - origin.x, it.top - origin.y, it.right - origin.x, it.bottom - origin.y) }
            ?: Rect(0f, 0f, size.width, size.height * 0.3f)
        // vpusť je na konci stránky: když není vidět, voda stéká pod spodní okraj obrazovky
        val dr = drainAt?.rect
        val drain = if (dr != null) Offset(dr.center.x - origin.x, dr.center.y - origin.y) else Offset(size.width / 2, size.height + 60 * u)
        val startY = r.bottom.coerceIn(0f, size.height * 0.8f)
        val water = Color(0xFF6CC4FF)
        // pramínky: od rohů nádoby dolů a pak do vpusti (jen při přesčasu)
        if (!drainOnly && k > 0.01f) for ((i, x0) in listOf(r.left + 6 * u, r.right - 6 * u).withIndex()) {
            if (i == 2 && k < 0.6f) continue
            val bottom = min(size.height + 40 * u, drain.y - 24 * u) - i * 6 * u
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
        if (!drainOnly) return@Canvas
        // jediná vpusť: pevně na dně obrazovky, sama se nehýbe; vír a louže jen když do ní teče voda
        if (k > 0.01f) {
            drawOval(
                Brush.radialGradient(listOf(Color(0x9954B6FF), Color(0x3340A0FF), Color.Transparent), center = drain, radius = 90 * u * k),
                Offset(drain.x - 90 * u * k, drain.y - 18 * u * k), Size(180 * u * k, 36 * u * k),
            )
            for (j in 0 until 3) {
                val rw = (30f + j * 12f) * u
                rotate(t * (120f - j * 25f) + j * 80f, drain) {
                    drawArc(Color.White.copy(alpha = (0.45f - j * 0.1f) * k), 0f, 100f, false, Offset(drain.x - rw, drain.y - rw * 0.35f), Size(rw * 2, rw * 0.7f), style = Stroke(1.5f * u))
                }
            }
        }
        val gw = 64 * u
        val gh = gw * grate.height / grate.width
        drawImage(
            grate, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(grate.width, grate.height),
            dstOffset = androidx.compose.ui.unit.IntOffset((drain.x - gw / 2).toInt(), (drain.y - gh / 2).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(gw.toInt(), gh.toInt()),
        )
    }
}



/** Vpusť na konci stránky (podlaha přehledu); v přesčasu kolem ní louže a vír. */
@Composable
fun DrainFloor(clock: State<Float>, overflowing: Boolean, animate: Boolean, bounds: PanelBounds, modifier: Modifier = Modifier) {
    val k by animateFloatAsState(if (overflowing) 1f else 0f, tween(if (animate) 2200 else 0), label = "drainWater")
    val grate = ImageBitmap.imageResource(R.drawable.g_odtokova_mrizka)
    Canvas(modifier.fillMaxWidth().height(90.dp).onGloballyPositioned { bounds.rect = it.boundsInRoot() }) {
        val u = density
        val t = if (animate) clock.value else 0f
        val c = Offset(size.width / 2, size.height / 2)
        if (k > 0.01f) {
            drawOval(
                Brush.radialGradient(listOf(Color(0x9954B6FF), Color(0x3340A0FF), Color.Transparent), center = c, radius = 90 * u * k),
                Offset(c.x - 90 * u * k, c.y - 18 * u * k), Size(180 * u * k, 36 * u * k),
            )
            for (j in 0 until 3) {
                val rw = (30f + j * 12f) * u
                rotate(t * (120f - j * 25f) + j * 80f, c) {
                    drawArc(Color.White.copy(alpha = (0.45f - j * 0.1f) * k), 0f, 100f, false, Offset(c.x - rw, c.y - rw * 0.35f), Size(rw * 2, rw * 0.7f), style = Stroke(1.5f * u))
                }
            }
        }
        val gw = 64 * u
        val gh = gw * grate.height / grate.width
        drawImage(
            grate, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(grate.width, grate.height),
            dstOffset = androidx.compose.ui.unit.IntOffset((c.x - gw / 2).toInt(), (c.y - gh / 2).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(gw.toInt(), gh.toInt()),
        )
    }
}
