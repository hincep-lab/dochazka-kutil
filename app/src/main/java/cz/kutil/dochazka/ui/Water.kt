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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

/** Společný čas animací v sekundách (max. [fps] snímků/s, jen když je aplikace vidět). */
@Composable
fun rememberWaterClock(enabled: Boolean, fps: Int = 30): State<Float> {
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
                delay(frame - 8)
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
val FaucetNozzleFromEnd: Dp = 58.dp
private const val DRIP_PERIOD = 3.4f

@Composable
fun WaterPanel(
    tone: GlassTone,
    clock: State<Float>,
    water: WaterState,
    /** 0..1 = průběh osmihodinové doby; nad 1 = přesčas. */
    level: Float,
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val tint = when (tone) {
        GlassTone.WORKING -> Color(0xFF1C4C86)
        GlassTone.PENDING -> Color(0xFF7A4A12)
        GlassTone.IDLE -> Color(0xFF1A3558)
    }
    val lvl by animateFloatAsState(level.coerceIn(0f, 1.5f), tween(if (animate) 1500 else 0), label = "level")
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(14.dp, shape, ambientColor = Color(0xFF4FA8FF), spotColor = Color.Black)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.92f), Color(0xF00B1A31))))
                .drawBehind { drawWater(clock.value, water, lvl, animate, tone) }
                .border(1.5.dp, Brush.verticalGradient(listOf(Color(0xCCBFE3FF), Color(0x5582B8F0), Color(0x88BFE3FF))), shape),
        ) {
            // skleněný lesk nahoře
            Box(Modifier.fillMaxWidth().height(36.dp).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent))))
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = WaterZone), content = content)
            }
        }
        Faucet(water, Modifier.align(Alignment.TopEnd).offset(x = 12.dp + 2.dp, y = (-48).dp))
    }
}

private fun DrawScope.drawWater(t: Float, w: WaterState, level: Float, animate: Boolean, tone: GlassTone) {
    val wv = size.width
    val h = size.height
    // Hladina se drží v pásu pod textem (obsah panelu má dole volné místo WaterZone):
    // prázdná 10 dp, plná osmihodinová směna 40 dp. Přesčas = jasnější podsvícení.
    val fillPx = (10f + 30f * min(level, 1f)) * density
    val surface = h - fillPx
    val overtime = (level - 1f).coerceIn(0f, 0.5f) * 2f
    val amp = (1.5f + 2.5f * w.flow) * density
    val phase = if (animate) t else 0f
    val base = if (tone == GlassTone.PENDING) Color(0xFFFFB648) else Color(0xFF39A7FF)
    val glow = 0.30f + 0.25f * overtime

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
    drawPath(front, Brush.verticalGradient(listOf(base.copy(alpha = glow + 0.12f), Color(0xAA0C3B78)), startY = surface - amp, endY = h))
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
    val pipes = rememberPipeImages()
    val valve = painterResource(R.drawable.pipe_valve)
    Box(modifier.size(150.dp, 54.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val d = 16.dp.toPx()
            val k = d / 64f
            val s = pipes.tl.width * k
            // osa ústí: FaucetNozzleFromEnd od pravého okraje panelu (+ 14 dp přesah k okraji obrazovky)
            val nx = size.width - (FaucetNozzleFromEnd + 14.dp).toPx()
            val x0 = nx - d / 2
            val y0 = 8.dp.toPx()
            fun img(i: androidx.compose.ui.graphics.ImageBitmap, x: Float, y: Float, w: Float, h: Float) = drawImage(
                i, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(i.width, i.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()), dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            )
            img(pipes.h, x0 + s - 1, y0, size.width - x0 - s + 2, d)
            img(pipes.tl, x0, y0, s, s)
            img(pipes.v, x0, y0 + s - 1, d, size.height - y0 - s - 4.dp.toPx())
            // ústí (širší kroužek)
            val cw = pipes.clampV.width * k
            val ch = pipes.clampV.height * k
            img(pipes.clampV, nx - cw / 2, size.height - ch - 2.dp.toPx(), cw, ch)
            // objímka u stěny
            img(pipes.clampH, size.width - pipes.clampH.width * k * 1.6f, y0 + d / 2 - pipes.clampH.height * k / 2, pipes.clampH.width * k, pipes.clampH.height * k)
        }
        // ruční kolo ventilu na hlavě kohoutku – při otevření/zavření se „otočí“
        val spin = water.valveSpin
        val turn = if (spin > 0f) 0.35f + 0.65f * abs(cos(spin * 2f * PI.toFloat())) else 1f
        Image(
            valve, contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 150.dp - FaucetNozzleFromEnd - 14.dp + 12.dp, y = (-22).dp)
                .size(58.dp, 46.dp)
                .graphicsLayer { scaleX = turn },
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
    Box(modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val t = if (animate) clock.value else 0f
            // mělká voda kolem vpusti
            drawOval(Brush.radialGradient(listOf(Color(0x6642A5FF), Color(0x00143A6E)), center = c, radius = size.width * 0.32f), Offset(c.x - size.width * 0.32f, c.y - size.height * 0.42f), Size(size.width * 0.64f, size.height * 0.84f))
            // vír – spirálové oblouky, rychlejší, když teče voda
            val speed = 25f + 55f * water.flow
            for (i in 0 until 4) {
                val rw = (58f + i * 13f) * density
                rotate(t * speed * (1f - i * 0.12f) + i * 70f, c) {
                    drawArc(
                        Color.White.copy(alpha = 0.32f - i * 0.05f),
                        startAngle = 0f, sweepAngle = 110f, useCenter = false,
                        topLeft = Offset(c.x - rw, c.y - rw * 0.38f), size = Size(rw * 2, rw * 0.76f),
                        style = Stroke(width = 1.6f * density),
                    )
                }
            }
            // bublinky
            if (animate) {
                for (i in 0 until 3) {
                    val q = ((t * 0.3f + i * 0.33f) % 1f)
                    val ang = (t * 1.4f + i * 2.1f)
                    val rr = (64f - 30f * q) * density
                    drawCircle(Color.White.copy(alpha = 0.6f * (1 - q)), 2f * density, Offset(c.x + rr * cos(ang), c.y + rr * 0.38f * sin(ang)), style = Stroke(1f * density))
                }
            }
        }
        Image(painterResource(R.drawable.drain_grate), null, Modifier.size(96.dp, 96.dp).graphicsLayer { scaleY = 0.62f })
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

