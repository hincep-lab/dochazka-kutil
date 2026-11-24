package cz.kutil.dochazka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// =============================================================================================
// Instalatér Kutil – vlastní kreslená postavička (nešika s dobrým srdcem).
// Scénka: praskne trubka, Kutil přiběhne, dostane proud vody do obličeje, třikrát do trubky
// praští klíčem (je to horší), oblepí ji páskou, ukáže palec – a na odchodu uklouzne v louži.
// Vše se kreslí v Compose, žádné obrázky. Čistě pro radost – s docházkou nijak nesouvisí.
// =============================================================================================

internal object T {
    const val RUN_IN = 0.6f
    const val ARRIVE = 3.4f
    const val PEEK = 4.4f
    const val SPLASH = 5.0f
    const val DAZED = 5.6f
    const val CAP_BACK = 6.8f
    const val BONK = 7.0f
    val HITS = floatArrayOf(7.4f, 8.1f, 8.8f)
    const val TAPE = 9.6f
    const val FIXED = 11.4f
    const val THUMB = 11.6f
    const val WALK = 12.8f
    const val SLIP = 13.8f
    const val SIT = 14.4f
    const val UP = 14.9f
    const val END = 17.5f
}

/** Zvukové okamžiky scénky (čas od začátku → zvuk). */
enum class GagSound { BURST_START, BURST_STOP, SPLASH, CLANK, BOING, THUD, PLOP, GURGLE, POP, FLUSH, SNORE, SQUEAK, BURP, CHEER }

/** Scénky se střídají. */
enum class GagScene { PIPE, TOILET }

/** Ucpaný záchod. */
internal object T2 {
    const val RUN_IN = 0.6f
    const val ARRIVE = 3.2f
    const val PEEK = 4.0f
    val PLOPS = floatArrayOf(4.3f, 4.9f, 5.5f, 6.1f, 6.7f)
    const val GEYSER = 7.0f
    const val POP = 9.6f
    const val SIT = 10.4f
    const val UP = 11.0f
    const val FLUSH = 11.3f
    const val THUMB = 12.4f
    const val WALK = 13.4f
    const val SCARE = 14.2f
    const val END = 17.5f
}

internal val TOILET_EVENTS = listOf(
    0.05f to GagSound.GURGLE,
    T2.PLOPS[0] to GagSound.PLOP, T2.PLOPS[1] to GagSound.PLOP, T2.PLOPS[2] to GagSound.PLOP,
    T2.PLOPS[3] to GagSound.PLOP, T2.PLOPS[4] to GagSound.PLOP,
    T2.GEYSER to GagSound.SPLASH,
    T2.POP to GagSound.POP,
    T2.SIT - 0.1f to GagSound.THUD,
    T2.FLUSH to GagSound.FLUSH,
    T2.SCARE to GagSound.GURGLE,
    T2.SCARE + 0.1f to GagSound.BOING,
)

internal fun endOf(scene: GagScene) = if (scene == GagScene.PIPE) T.END else T2.END

internal val GAG_EVENTS = listOf(
    0.05f to GagSound.BURST_START,
    T.SPLASH to GagSound.SPLASH,
    T.HITS[0] to GagSound.CLANK, T.HITS[1] to GagSound.CLANK, T.HITS[2] to GagSound.CLANK,
    T.FIXED to GagSound.BURST_STOP,
    T.SLIP to GagSound.BOING,
    T.SIT - 0.05f to GagSound.THUD,
)

/**
 * Dílna dole na hlavní obrazovce: trubka, vpusť a Kutil. Scénka se spustí po zahájení práce,
 * pak občas sama (jednou za pár minut, jen při práci) a kdykoli po klepnutí.
 */
@Composable
fun PlumberWorkshop(
    clock: State<Float>,
    water: WaterState,
    animate: Boolean,
    working: Boolean,
    onSound: (GagSound) -> Unit,
    modifier: Modifier = Modifier,
    /** Jen pro testovací snímky: pevný okamžik scénky. */
    fixedElapsed: Float? = null,
    fixedScene: GagScene? = null,
) {
    val start = remember { mutableFloatStateOf(-100f) }
    val smoother = remember { PoseSmoother() }
    val sceneIdx = remember { androidx.compose.runtime.mutableIntStateOf((Math.random() * 2).toInt()) }
    val scene = fixedScene ?: GagScene.entries[sceneIdx.intValue % 2]
    val running = { t: Float -> t - start.floatValue in 0f..endOf(scene) }
    // každé další spuštění = jiná scénka
    val begin = { t: Float ->
        sceneIdx.intValue++
        start.floatValue = t
        WaterClockBoost.until = t + T2.END + 1f // během scénky 60 snímků/s
    }
    // automaticky: krátce po začátku práce a pak náhodně jednou za 2,5–5 minut
    LaunchedEffect(working, animate) {
        if (!animate || !working) return@LaunchedEffect
        var next = clock.value + 4f
        snapshotFlow { clock.value }.collect { t ->
            if (t >= next && !running(t)) {
                begin(t)
                next = t + 150f + (Math.random() * 150).toFloat()
            }
        }
    }
    // zvuky ve správných okamžicích
    LaunchedEffect(animate, scene) {
        if (!animate) return@LaunchedEffect
        val events = if (scene == GagScene.PIPE) GAG_EVENTS else TOILET_EVENTS
        var prev = -1f
        snapshotFlow { clock.value - start.floatValue }.collect { e ->
            if (prev >= -1f) for ((at, s) in events) if (prev < at && e >= at) onSound(s)
            prev = e
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(170.dp)
            .semantics { contentDescription = "Instalatér Kutil – klepnutím spustíš scénku" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (animate && !running(clock.value)) begin(clock.value)
            },
    ) {
        val pipes = rememberPipeImages()
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val e = fixedElapsed ?: if (animate) t - start.floatValue else -1f
            drawWorkshop(e, t, water, pipes, scene, smoother)
        }
    }
}

// ---------------------------------------------------------------------------------------------

internal fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k.coerceIn(0f, 1f)
internal fun seg(e: Float, a: Float, b: Float) = ((e - a) / (b - a)).coerceIn(0f, 1f)
internal fun ease(k: Float) = k * k * (3 - 2 * k)

internal fun sprayLevel(e: Float): Float = when {
    e < 0f || e > T.END -> 0f
    e < T.HITS[0] -> min(1f, e / 0.2f) * 0.9f
    e < T.HITS[1] -> 1.2f
    e < T.HITS[2] -> 1.5f
    e < T.TAPE -> 1.8f // čím víc tluče, tím víc to stříká
    e < T.FIXED -> lerp(1.8f, 0f, seg(e, T.TAPE + 0.4f, T.FIXED))
    else -> 0f
}

internal fun DrawScope.drawWorkshop(e: Float, t: Float, water: WaterState, pipes: PipeImages, scene: GagScene, smoother: PoseSmoother) {
    val w = size.width
    val h = size.height
    val u = 1.dp.toPx()
    val floorY = h - 14 * u
    val pipeY = 26 * u
    val d = 20 * u
    val crackX = w * 0.50f
    val drainX = w * 0.80f

    // podlaha s mokrým leskem
    drawRect(Brush.verticalGradient(listOf(Color(0x00122A4A), Color(0x88102440), Color(0xCC0A1A30)), startY = floorY - 30 * u, endY = h), Offset(0f, floorY - 30 * u), Size(w, h - floorY + 30 * u))
    drawLine(Color(0x5582B8F0), Offset(0f, floorY), Offset(w, floorY), strokeWidth = 1 * u)

    // vodorovná trubka podél stěny
    val k = d / 64f
    fun img(i: androidx.compose.ui.graphics.ImageBitmap, x: Float, y: Float, ww: Float, hh: Float) = drawImage(
        i, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(i.width, i.height),
        dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()), dstSize = androidx.compose.ui.unit.IntSize(ww.toInt(), hh.toInt()),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
    )
    img(pipes.h, -2f, pipeY, w + 4f, d)
    for (cx in listOf(w * 0.12f, w * 0.88f)) img(pipes.clampH, cx, pipeY + d / 2 - pipes.clampH.height * k / 2, pipes.clampH.width * k, pipes.clampH.height * k)

    if (scene == GagScene.TOILET) {
        // vpusť a záchod místo praskliny
        drawOval(Color(0xFF0A111C), Offset(drainX + 10 * u - 22 * u, floorY - 3 * u), Size(44 * u, 9 * u))
        drawOval(Color(0xFFB7C3D0), Offset(drainX + 10 * u - 24 * u, floorY - 4 * u), Size(48 * u, 11 * u), style = Stroke(2.5f * u))
        drawToiletScene(e, t, floorY, pipeY, w, u, smoother)
        return
    }
    val spray = sprayLevel(e)
    val cracked = e in 0f..T.END
    if (cracked) {
        // prasklina
        val p = Path().apply {
            moveTo(crackX - 7 * u, pipeY + d * 0.55f); lineTo(crackX - 2 * u, pipeY + d * 0.35f)
            lineTo(crackX + 1 * u, pipeY + d * 0.7f); lineTo(crackX + 6 * u, pipeY + d * 0.45f)
        }
        drawPath(p, Color(0xFF1A2230), style = Stroke(2 * u, cap = StrokeCap.Round))
    }
    // lepicí páska
    if (e >= T.TAPE && e <= T.END) {
        val k2 = ease(seg(e, T.TAPE + 0.3f, T.FIXED))
        val tw = 22 * u * k2
        if (tw > 1) {
            drawRect(Color(0xFF9AA0A8), Offset(crackX - tw / 2, pipeY - 1 * u), Size(tw, d + 2 * u))
            for (i in 0 until 4) {
                val xx = crackX - tw / 2 + tw * (i + 0.5f) / 4
                drawLine(Color(0xFF6E747C), Offset(xx, pipeY), Offset(xx - 3 * u, pipeY + d), strokeWidth = 1 * u)
            }
            drawLine(Color.White.copy(alpha = 0.5f), Offset(crackX - tw / 2, pipeY + 4 * u), Offset(crackX + tw / 2, pipeY + 4 * u), strokeWidth = 1.5f * u)
        }
    }

    // louže pod prasklinou, která stéká k vpusti
    val puddle = when {
        e < 0f || e > T.END -> 0f
        e < T.FIXED -> min(1f, e / 4f)
        else -> 1f - seg(e, T.FIXED + 2f, T.END) * 0.6f
    }
    if (puddle > 0f) {
        val pw = (40 + 70 * puddle) * u
        drawOval(Brush.radialGradient(listOf(Color(0x8854B6FF), Color(0x2240A0FF), Color.Transparent), center = Offset(crackX + 20 * u, floorY + 2 * u), radius = pw), Offset(crackX + 20 * u - pw, floorY - 3 * u), Size(pw * 2, 10 * u))
        drawLine(Color(0x9954B6FF), Offset(crackX + 20 * u, floorY + 1 * u), Offset(lerp(crackX, drainX, puddle), floorY + 1 * u), strokeWidth = 3 * u * puddle, cap = StrokeCap.Round)
    }

    // vpusť v podlaze
    drawOval(Color(0xFF0A111C), Offset(drainX - 22 * u, floorY - 3 * u), Size(44 * u, 9 * u))
    drawOval(Color(0xFFB7C3D0), Offset(drainX - 24 * u, floorY - 4 * u), Size(48 * u, 11 * u), style = Stroke(2.5f * u))
    for (i in -2..2) drawLine(Color(0xFF55606C), Offset(drainX + i * 7 * u, floorY - 2 * u), Offset(drainX + i * 7 * u, floorY + 5 * u), strokeWidth = 1.5f * u)

    // proud vody z trubky
    if (spray > 0f) drawSpray(Offset(crackX, pipeY + d * 0.6f), spray, t, floorY, u)

    // Kutil
    if (e in 0f..T.END) {
        drawPlumber(smoother.smooth(poseAt(e, t, crackX, w, u), t, e), floorY, u)
    } else {
        // mimo scénku: jen nenápadná cedulka
        drawCircle(Color(0x33FFFFFF), 2 * u, Offset(w * 0.06f, floorY - 6 * u))
    }
}

internal fun DrawScope.drawSpray(o: Offset, s: Float, t: Float, floorY: Float, u: Float) {
    // soustředěný proud šikmo doleva dolů (tam, kde stojí Kutil) + pár odletujících kapek
    val g = 900f * u
    val jets = listOf(-1f to 1f, -0.82f to 0.75f, -1.18f to 0.55f)
    for ((k, a) in jets) {
        val vx = (95f + 55f * s) * u * k
        val vy = (-15f + 10f * k) * u
        val path = Path()
        var started = false
        var tt = 0f
        var last = o
        while (tt < 0.7f) {
            val x = o.x + vx * tt
            val y = o.y + vy * tt + 0.5f * g * tt * tt
            if (y > floorY) break
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            last = Offset(x, y)
            tt += 0.02f
        }
        drawPath(path, Color(0x6690D4FF).copy(alpha = 0.45f * a), style = Stroke((3f + 3f * s) * u * a, cap = StrokeCap.Round))
        drawPath(path, Color.White.copy(alpha = 0.55f * a), style = Stroke(1.2f * u, cap = StrokeCap.Round))
        // kapky letící po proudu
        for (j in 0 until 5) {
            val q = ((t * 2.2f + j / 5f + k) % 1f) * 0.7f
            val x = o.x + vx * q + (j % 2 - 0.5f) * 3 * u
            val y = o.y + vy * q + 0.5f * g * q * q
            if (y < floorY) drawDrop(Offset(x, y), (1.6f + s) * u, 1f)
        }
        // cákání na podlaze
        if (last.y > floorY - 4 * u) {
            for (j in 0 until 3) {
                val q = ((t * 3f + j / 3f) % 1f)
                drawCircle(Color(0xAA9ED8FF), 1.3f * u, Offset(last.x + (j - 1) * 6 * u * q, floorY - 10 * u * q * (1 - q) * 2))
            }
        }
    }
    // pár kapek odletujících na druhou stranu
    for (j in 0 until (3 * s).toInt() + 1) {
        val q = ((t * 1.7f + j * 0.29f) % 1f) * 0.5f
        val x = o.x + 60f * u * q
        val y = o.y - 25f * u * q + 0.5f * g * q * q
        if (y < floorY) drawCircle(Color(0xCC8FD3FF), 1.5f * u, Offset(x, y))
    }
    drawCircle(Brush.radialGradient(listOf(Color(0x5596D6FF), Color.Transparent), center = o, radius = 18 * u * s), 18 * u * s, o)
}

// ---------------------------------------------------------------------------------------------
// Scénka 2: ucpaný záchod a zvon
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.drawToiletScene(e: Float, t: Float, floorY: Float, pipeY: Float, w: Float, u: Float, smoother: PoseSmoother) {
    val tx = w * 0.60f
    val rimY = floorY - 40 * u
    val bowlC = Offset(tx - 10 * u, rimY)
    // hladina v míse: bublá, při pumpování se houpe, po spláchnutí se točí
    val gurgle = e in 0f..T2.GEYSER || e in T2.SCARE..(T2.SCARE + 0.8f)
    val slosh = if (e in T2.PEEK..T2.GEYSER) 4 * u * sin(e * 13f) else 0f
    drawToilet(tx, floorY, u, lidUp = true)
    drawOval(Color(0xCC4FA8E8), Offset(bowlC.x - 15 * u, rimY - 3 * u + slosh * 0.3f), Size(30 * u, 7 * u))
    if (gurgle && e >= 0f) {
        for (i in 0 until 4) {
            val q = ((t * 1.8f + i * 0.27f) % 1f)
            drawCircle(Color.White.copy(alpha = 0.8f * (1 - q)), (2f + 2f * q) * u, Offset(bowlC.x - 9 * u + i * 6 * u, rimY - 2 * u - 10 * u * q), style = Stroke(1.2f * u))
        }
    }
    // spláchnutí – vír v míse
    if (e in T2.FLUSH..(T2.FLUSH + 1.8f)) {
        val k = seg(e, T2.FLUSH, T2.FLUSH + 1.8f)
        for (i in 0 until 3) rotate(t * 500f + i * 120f, bowlC) {
            drawArc(Color.White.copy(alpha = 0.7f * (1 - k)), 0f, 120f, false, Offset(bowlC.x - 12 * u, bowlC.y - 3 * u), Size(24 * u, 6 * u), style = Stroke(1.5f * u))
        }
    }
    // gejzír
    if (e in T2.GEYSER..(T2.GEYSER + 1.6f)) {
        val k = seg(e, T2.GEYSER, T2.GEYSER + 1.6f)
        val height = (rimY - pipeY - 10 * u) * sin(min(1f, k * 2.2f) * PI.toFloat() / 2) * (1 - max0(k - 0.6f) * 2.5f)
        if (height > 0) {
            val top = rimY - height
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xEEBFE6FF), Color(0xAA4FA8E8)), startY = top, endY = rimY),
                Offset(bowlC.x - 7 * u, top), Size(14 * u, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7 * u),
            )
            drawLine(Color.White.copy(alpha = 0.8f), Offset(bowlC.x - 3 * u, top + 4 * u), Offset(bowlC.x - 3 * u, rimY), strokeWidth = 1.5f * u)
            for (i in 0 until 10) {
                val q = ((t * 1.3f + i * 0.1f) % 1f)
                val dx = (i - 4.5f) * 5 * u * q
                drawDrop(Offset(bowlC.x + dx, top + 30 * u * q * q), 2f * u, 1f)
            }
        }
    }
    if (e in 0f..T2.END) drawPlumber(smoother.smooth(poseToilet(e, t, tx, w, u), t, e), floorY, u)
}

internal fun max0(v: Float) = if (v < 0f) 0f else v

internal fun DrawScope.drawToilet(tx: Float, floorY: Float, u: Float, lidUp: Boolean) {
    val white = Brush.horizontalGradient(listOf(Color(0xFFDCE6F0), Color.White, Color(0xFFB8C6D4)))
    // nádržka
    drawRoundRect(white, Offset(tx + 4 * u, floorY - 76 * u), Size(26 * u, 36 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4 * u))
    drawRoundRect(Color(0xFFB8C6D4), Offset(tx + 2 * u, floorY - 78 * u), Size(30 * u, 5 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
    // páčka splachování
    drawLine(Color(0xFF9AA6B4), Offset(tx + 6 * u, floorY - 70 * u), Offset(tx - 1 * u, floorY - 68 * u), strokeWidth = 2.5f * u, cap = StrokeCap.Round)
    // zvednuté víko opřené o nádržku
    if (lidUp) drawRoundRect(white, Offset(tx - 2 * u, floorY - 74 * u), Size(6 * u, 34 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * u))
    // mísa a noha
    val bowl = Path().apply {
        moveTo(tx - 30 * u, floorY - 42 * u)
        lineTo(tx + 8 * u, floorY - 42 * u)
        cubicTo(tx + 8 * u, floorY - 26 * u, tx + 2 * u, floorY - 20 * u, tx - 2 * u, floorY - 18 * u)
        lineTo(tx + 2 * u, floorY)
        lineTo(tx - 20 * u, floorY)
        lineTo(tx - 16 * u, floorY - 18 * u)
        cubicTo(tx - 26 * u, floorY - 22 * u, tx - 30 * u, floorY - 30 * u, tx - 30 * u, floorY - 42 * u)
        close()
    }
    drawPath(bowl, white)
    drawPath(bowl, Color(0xFF8A9AAD), style = Stroke(1.2f * u))
    // sedátko
    drawRoundRect(Color(0xFFEFF3F7), Offset(tx - 32 * u, floorY - 45 * u), Size(42 * u, 4 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
}

internal fun poseToilet(e: Float, t: Float, tx: Float, w: Float, u: Float): Pose {
    val standX = tx - 58 * u
    var x = standX
    var run = 0f
    var armR = 10f
    var armL = -10f
    var lean = 0f
    var tilt = 0f
    var sit = 0f
    var capLift = 0f
    var capSpin = 0f
    var dazed = 0f
    var grin = 0f
    var wink = false
    var thumb = false
    var closed = false
    var plunger = true
    var onFace = false
    var plungeDy = 0f
    when {
        e < T2.ARRIVE -> {
            x = lerp(-50 * u, standX, seg(e, T2.RUN_IN, T2.ARRIVE))
            run = if (e < T2.RUN_IN) 0f else 1f
            armR = 35f + 25f * sin(t * 18f); armL = -30f * sin(t * 18f)
        }
        e < T2.PEEK -> { lean = ease(seg(e, T2.ARRIVE, T2.PEEK)) * 0.5f; armR = 40f; grin = -0.5f }
        e < T2.GEYSER -> { // pumpuje zvonem
            x = standX + 14 * u
            val ph = ((e - T2.PEEK) % 0.6f) / 0.6f
            armR = 38f + 14f * sin(ph * 2 * PI.toFloat())
            armL = 30f + 14f * sin(ph * 2 * PI.toFloat())
            lean = 0.45f
            plungeDy = 4 * u * sin(ph * 2 * PI.toFloat())
            grin = -0.6f
        }
        e < T2.POP -> { // gejzír ho odhodí, zvon se mu přisaje na obličej
            val k = seg(e, T2.GEYSER, T2.GEYSER + 0.5f)
            x = standX + 8 * u - 24 * u * ease(k) + 3 * u * sin(t * 9f)
            tilt = -12f * sin(k * PI.toFloat()) + 6f * sin(t * 7f)
            onFace = true
            plunger = false
            armR = 75f + 15f * sin(t * 11f); armL = 75f + 15f * sin(t * 11f + 1f)
            capLift = 50 * u * sin(seg(e, T2.GEYSER, T2.GEYSER + 1.6f) * PI.toFloat())
            capSpin = 300f * seg(e, T2.GEYSER, T2.GEYSER + 1.6f)
        }
        e < T2.SIT -> { // plop! zvon povolí a Kutil si sedne na zadek
            val k = seg(e, T2.POP, T2.SIT)
            x = standX - 16 * u - 12 * u * k
            sit = ease(k); tilt = -40f * ease(k)
            armR = 140f; armL = 60f
            closed = true
        }
        e < T2.UP -> { x = standX - 28 * u; sit = 1f; tilt = lerp(-40f, 0f, seg(e, T2.SIT, T2.UP)); dazed = 1f }
        e < T2.THUMB -> { // vstane a spláchne
            val k = seg(e, T2.UP, T2.FLUSH)
            sit = 1f - ease(k)
            x = lerp(standX - 28 * u, standX + 20 * u, ease(seg(e, T2.UP + 0.1f, T2.FLUSH)))
            run = if (e < T2.FLUSH) 0.6f else 0f
            armR = if (e >= T2.FLUSH - 0.2f) 135f - 30f * seg(e, T2.FLUSH, T2.FLUSH + 0.3f) else 20f
        }
        e < T2.WALK -> { thumb = true; grin = 1f; wink = true; armR = 150f; x = standX + 20 * u; plunger = false }
        e < T2.SCARE -> {
            x = lerp(standX + 20 * u, standX + 70 * u, seg(e, T2.WALK, T2.SCARE))
            run = 0.5f; grin = 0.6f
            armR = 25f + 15f * sin(t * 9f); armL = -20f * sin(t * 9f)
        }
        e < T2.SCARE + 0.9f -> { // blurp! leknutí – vyskočí
            val k = seg(e, T2.SCARE, T2.SCARE + 0.9f)
            x = standX + 70 * u + 10 * u * k
            tilt = 8f * sin(k * 20f)
            capLift = 22 * u * sin(k * PI.toFloat())
            armR = 160f; armL = -160f
            closed = false
            grin = -0.8f
        }
        else -> {
            x = lerp(standX + 80 * u, w + 70 * u, ease(seg(e, T2.SCARE + 0.9f, T2.END)))
            run = 1f; grin = -0.3f
            armR = 35f + 30f * sin(t * 20f); armL = -30f * sin(t * 20f)
        }
    }
    return Pose(
        x, 1f, run, t * 14f, armR, armL, lean, tilt, sit, capLift, capSpin, e >= T2.GEYSER, dazed, grin, wink, thumb, false, closed,
        plunger = plunger, plungerOnFace = onFace, plungeDy = plungeDy,
    )
}

// ---------------------------------------------------------------------------------------------
// Póza postavičky v čase scénky
// ---------------------------------------------------------------------------------------------

internal class Pose(
    val x: Float,
    val facing: Float, // 1 = doprava, -1 = doleva
    val run: Float, // 0..1 intenzita běhu
    val stride: Float, // fáze kroku
    val armR: Float, // úhel pravé ruky (stupně, 0 = dolů, 90 = dopředu)
    val armL: Float,
    val lean: Float, // předklon 0..1
    val tilt: Float, // otočení celé postavy (pád)
    val sit: Float, // posazení na zadek 0..1
    val capLift: Float, // jak vysoko je čepice nad hlavou
    val capSpin: Float,
    val wet: Boolean,
    val dazed: Float,
    val grin: Float,
    val wink: Boolean,
    val thumb: Boolean,
    val tape: Boolean,
    val eyesClosed: Boolean,
    val plunger: Boolean = false,
    val plungerOnFace: Boolean = false,
    val plungeDy: Float = 0f,
    /** Zvednutí nad podlahu v px (štafle, ležení). */
    val lift: Float = 0f,
    /** Co drží v ruce navíc: 0 nic, 1 štafle, 2 kachnička. */
    val carry: Int = 0,
    val zzz: Boolean = false,
    val noTool: Boolean = false,
)

internal fun poseAt(e: Float, t: Float, crackX: Float, w: Float, u: Float): Pose {
    val standX = crackX - 30 * u
    var x = standX
    var facing = 1f
    var run = 0f
    var armR = 10f
    var armL = -10f
    var lean = 0f
    var tilt = 0f
    var sit = 0f
    var capLift = 0f
    var capSpin = 0f
    var dazed = 0f
    var grin = 0f
    var wink = false
    var thumb = false
    var tape = false
    var closed = false
    val wet = e >= T.SPLASH
    when {
        e < T.ARRIVE -> {
            val k = seg(e, T.RUN_IN, T.ARRIVE)
            x = lerp(-50 * u, standX, k)
            run = if (e < T.RUN_IN) 0f else 1f
            armR = 30f * sin(t * 18f); armL = -30f * sin(t * 18f)
        }
        e < T.PEEK -> { // drbe se na hlavě
            armR = 160f + 12f * sin(t * 20f)
        }
        e < T.SPLASH -> { // nakloní se k prasklině
            lean = ease(seg(e, T.PEEK, T.SPLASH))
            x = standX + 10 * u * lean
        }
        e < T.DAZED -> { // proud vody do obličeje → odletí dozadu, čepice letí
            val k = seg(e, T.SPLASH, T.DAZED)
            x = standX + 10 * u - 26 * u * ease(k)
            tilt = -14f * sin(k * PI.toFloat())
            armR = 120f; armL = -120f
            closed = true
            capLift = 60 * u * sin(k * PI.toFloat() * 0.5f)
            capSpin = 360f * k
        }
        e < T.BONK -> { // omámený, čepice padá zpět
            x = standX - 16 * u
            dazed = 1f
            val k = seg(e, T.DAZED, T.CAP_BACK)
            capLift = lerp(60 * u, 0f, ease(k)) + if (k < 1f) 8 * u * sin(k * 12f) else 0f
            capSpin = 360f + 200f * k
            if (e > T.CAP_BACK) { dazed = 0.6f; armR = 20f + 10f * sin(t * 4f) }
        }
        e < T.TAPE -> { // tluče klíčem
            x = lerp(standX - 16 * u, standX, seg(e, T.BONK, T.BONK + 0.3f))
            val phase = ((e - T.BONK) % 0.7f) / 0.7f
            armR = if (phase < 0.55f) lerp(60f, 175f, phase / 0.55f) else lerp(175f, 70f, (phase - 0.55f) / 0.45f)
            grin = -0.5f
            lean = 0.2f
        }
        e < T.THUMB -> { // lepí páskou, předkloněný
            lean = 0.75f
            tape = true
            armR = 110f + 25f * sin(t * 14f)
            armL = 95f + 25f * cos(t * 14f)
        }
        e < T.WALK -> { // palec nahoru, mrknutí
            thumb = true
            grin = 1f
            wink = true
            armR = 150f
        }
        e < T.SLIP -> { // odchází spokojeně
            x = lerp(standX, standX + 70 * u, seg(e, T.WALK, T.SLIP))
            run = 0.5f
            grin = 0.6f
            armR = 20f * sin(t * 9f); armL = -20f * sin(t * 9f)
        }
        e < T.SIT -> { // uklouzne v louži – nohy nahoru
            val k = seg(e, T.SLIP, T.SIT)
            x = standX + 70 * u + 14 * u * k
            tilt = -70f * ease(k)
            sit = ease(k)
            armR = 150f; armL = -150f
            capLift = 18 * u * sin(k * PI.toFloat())
            closed = true
        }
        e < T.UP -> {
            x = standX + 84 * u
            sit = 1f
            tilt = lerp(-70f, -10f, seg(e, T.SIT, T.UP))
            dazed = 0.8f
        }
        else -> { // vstane a odejde
            val k = seg(e, T.UP, T.END)
            sit = 1f - ease(seg(e, T.UP, T.UP + 0.4f))
            x = lerp(standX + 84 * u, w + 60 * u, ease(seg(e, T.UP + 0.4f, T.END)))
            run = if (k > 0.15f) 1f else 0f
            grin = 0.3f
            armR = 30f * sin(t * 18f); armL = -30f * sin(t * 18f)
        }
    }
    return Pose(x, facing, run, t * 14f, armR, armL, lean, tilt, sit, capLift, capSpin, wet, dazed, grin, wink, thumb, tape, closed)
}

// ---------------------------------------------------------------------------------------------
// Kreslení postavičky (buclatý instalatér v lacláčích)
// ---------------------------------------------------------------------------------------------

// Paleta – jednotný kreslený styl: plné barvy, měkké stínování, tmavá obrysová linka.
internal val Outline = Color(0xFF241A14)
internal val Skin = Color(0xFFF4C29A)
internal val SkinShade = Color(0xFFD99670)
internal val Overalls = Color(0xFF3473D1)
internal val OverallsShade = Color(0xFF1F4C93)
internal val Shirt = Color(0xFF8FAE98)
internal val ShirtShade = Color(0xFF5F7F69)
internal val Cap = Color(0xFFF2922A)
internal val CapShade = Color(0xFFBF6410)
internal val Boot = Color(0xFF6B4428)
internal val BootShade = Color(0xFF3E2716)
internal val Hair = Color(0xFF3A2418)
internal val Ink = Color(0xFF1B1410)

/** Plynulý přechod mezi pózami: každá hodnota se k cílové blíží tlumeně (žádné skoky). */
internal class PoseSmoother {
    private var cur: FloatArray? = null
    private var lastT = 0f
    private var lastE = -10f
    internal fun smooth(target: Pose, t: Float, e: Float): Pose {
        val v = floatArrayOf(target.x, target.armR, target.armL, target.lean, target.tilt, target.sit, target.capLift, target.capSpin, target.run, target.plungeDy, target.dazed, target.grin, target.lift)
        val c = cur
        if (c == null || e < lastE || e - lastE > 0.5f) {
            cur = v; lastT = t; lastE = e
            return target
        }
        val dt = (t - lastT).coerceIn(0f, 0.1f)
        lastT = t; lastE = e
        val kFast = 1f - kotlin.math.exp(-dt * 22f)
        val kSlow = 1f - kotlin.math.exp(-dt * 11f)
        for (i in v.indices) {
            val k = if (i == 0 || i == 6 || i == 7 || i == 9 || i == 12) kFast else kSlow
            c[i] += (v[i] - c[i]) * k
        }
        return Pose(
            c[0], target.facing, c[8], target.stride, c[1], c[2], c[3], c[4], c[5], c[6], c[7], target.wet, c[10], c[11],
            target.wink, target.thumb, target.tape, target.eyesClosed, target.plunger, target.plungerOnFace, c[9],
            c[12], target.carry, target.zzz, target.noTool,
        )
    }
}

internal fun DrawScope.outlined(path: Path, fill: Brush, u: Float, w: Float = 1.6f) {
    drawPath(path, fill)
    drawPath(path, Outline, style = Stroke(w * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** Končetina jako zaoblený „rukáv“ s obrysem (dva úseky přes koleno / loket). */
internal fun DrawScope.limb(a: Offset, b: Offset, c: Offset, width: Float, fill: Color, shade: Color, u: Float) {
    val path = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y) }
    drawPath(path, Outline, style = Stroke(width + 3.2f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(path, fill, style = Stroke(width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    // stín na spodní straně
    val off = Offset(0f, width * 0.22f)
    val sh = Path().apply { moveTo(a.x + off.x, a.y + off.y); lineTo(b.x + off.x, b.y + off.y); lineTo(c.x + off.x, c.y + off.y) }
    drawPath(sh, shade.copy(alpha = 0.55f), style = Stroke(width * 0.45f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

internal fun polar(o: Offset, len: Float, deg: Float): Offset {
    val r = Math.toRadians(deg.toDouble())
    // 0° = dolů, kladné = dopředu (doprava)
    return Offset(o.x + (len * sin(r)).toFloat(), o.y + (len * cos(r)).toFloat())
}

internal fun DrawScope.drawPlumber(p: Pose, floorY: Float, u: Float) {
    val t = p.stride / 14f
    val runCycle = p.stride * 0.95f
    val bob = abs(sin(runCycle)) * 3.2f * u * p.run
    val breathe = 1f + 0.025f * sin(t * 2.2f)
    val jiggle = sin(runCycle * 2f - 0.6f) * 2.2f * u * p.run + sin(t * 2.2f) * 0.4f * u
    // měkký stín na podlaze (zůstává na zemi i když je Kutil na štaflích)
    val shK = (1f - (p.lift / (90 * u)).coerceIn(0f, 0.7f))
    drawOval(Brush.radialGradient(listOf(Color(0x66000000), Color.Transparent), center = Offset(p.x, floorY), radius = 26 * u * shK), Offset(p.x - 26 * u * shK, floorY - 4 * u), Size(52 * u * shK, 8 * u))
    translate(p.x, floorY - bob + p.sit * 12 * u - p.lift) {
        // štafle nesené přes rameno – za tělem
        if (p.carry == 1) drawCarriedLadder(p.facing, u)
        scale(p.facing, 1f, pivot = Offset.Zero) {
            rotate(p.tilt + 7f * p.run, pivot = Offset(0f, -12 * u)) {
                // ---------- nohy (stehno + lýtko, koleno se při běhu ohýbá)
                for (side in listOf(-1, 1)) {
                    val ph = runCycle + if (side > 0) 0f else PI.toFloat()
                    val thigh = if (p.sit > 0f) lerp(sin(ph) * 38f * p.run, 82f, p.sit) else sin(ph) * 38f * p.run
                    val knee = if (p.sit > 0f) lerp(max0(-sin(ph + 0.9f)) * 70f * p.run, -10f, p.sit) else max0(-sin(ph + 0.9f)) * 70f * p.run + 4f
                    val hip = Offset(side * 6f * u, -22 * u)
                    val k = polar(hip, 11 * u, thigh)
                    val ankle = polar(k, 10 * u, thigh - knee)
                    val back = side < 0
                    val col = if (back) OverallsShade else Overalls
                    limb(hip, k, ankle, 10 * u, col, OverallsShade, u)
                    // bota
                    rotate(-(thigh - knee) * 0.6f + if (p.sit > 0f) -60f * p.sit else 0f, pivot = ankle) {
                        val boot = Path().apply {
                            moveTo(ankle.x - 6 * u, ankle.y - 2 * u)
                            cubicTo(ankle.x - 7 * u, ankle.y + 4 * u, ankle.x - 6 * u, ankle.y + 6 * u, ankle.x - 3 * u, ankle.y + 6 * u)
                            lineTo(ankle.x + 9 * u, ankle.y + 6 * u)
                            cubicTo(ankle.x + 13 * u, ankle.y + 6 * u, ankle.x + 13 * u, ankle.y + 1 * u, ankle.x + 8 * u, ankle.y)
                            cubicTo(ankle.x + 4 * u, ankle.y - 1 * u, ankle.x + 3 * u, ankle.y - 4 * u, ankle.x + 2 * u, ankle.y - 4 * u)
                            close()
                        }
                        outlined(boot, Brush.verticalGradient(listOf(if (back) BootShade else Boot, BootShade), startY = ankle.y - 4 * u, endY = ankle.y + 6 * u), u)
                        drawLine(Color(0xFF231509), Offset(ankle.x - 4 * u, ankle.y + 5.4f * u), Offset(ankle.x + 10 * u, ankle.y + 5.4f * u), strokeWidth = 1.6f * u)
                    }
                }
                rotate(p.lean * 30f, pivot = Offset(0f, -22 * u)) {
                    // ---------- zadní ruka (za tělem)
                    drawArmLimb(Offset(-6 * u, -54 * u), p.armL, u, back = true, p = p)
                    // ---------- tělo: hruškovitý trup s bříškem dopředu
                    val bx = 32 * u * breathe + jiggle // pořádné bříško
                    val torso = Path().apply {
                        moveTo(-9 * u, -60 * u)
                        cubicTo(2 * u, -64 * u, 12 * u, -61 * u, 14 * u, -55 * u)
                        cubicTo(bx, -48 * u, bx + 2 * u, -30 * u, 14 * u, -21 * u)
                        cubicTo(6 * u, -15 * u, -12 * u, -15 * u, -19 * u, -22 * u)
                        cubicTo(-25 * u, -30 * u, -22 * u, -50 * u, -9 * u, -60 * u)
                        close()
                    }
                    outlined(torso, Brush.radialGradient(listOf(Shirt, ShirtShade), center = Offset(2 * u, -54 * u), radius = 26 * u), u)
                    // lacláče – spodní část trupu + náprsenka
                    val pants = Path().apply {
                        moveTo(-21 * u, -40 * u)
                        cubicTo(-6 * u, -43 * u, 8 * u, -44 * u, bx - 2 * u, -42 * u)
                        cubicTo(bx + 2 * u, -32 * u, 20 * u, -22 * u, 14 * u, -21 * u)
                        cubicTo(6 * u, -15 * u, -12 * u, -15 * u, -19 * u, -22 * u)
                        cubicTo(-23 * u, -27 * u, -23 * u, -35 * u, -21 * u, -40 * u)
                        close()
                    }
                    outlined(pants, Brush.radialGradient(listOf(Color(0xFF5B92E8), Overalls, OverallsShade), center = Offset(8 * u, -38 * u), radius = 28 * u), u)
                    val bib = Path().apply {
                        moveTo(1 * u, -43 * u); lineTo(3 * u, -56 * u); lineTo(15 * u, -55 * u)
                        cubicTo(18 * u, -50 * u, 19 * u, -46 * u, 18 * u, -43 * u); close()
                    }
                    outlined(bib, Brush.verticalGradient(listOf(Overalls, OverallsShade)), u, 1.3f)
                    // šle a mosazné knoflíky
                    drawLine(Outline, Offset(4 * u, -55 * u), Offset(-6 * u, -61 * u), strokeWidth = 4.2f * u, cap = StrokeCap.Round)
                    drawLine(OverallsShade, Offset(4 * u, -55 * u), Offset(-6 * u, -61 * u), strokeWidth = 2.6f * u, cap = StrokeCap.Round)
                    for (bxx in listOf(5f, 14f)) {
                        drawCircle(Outline, 2.1f * u, Offset(bxx * u, -53 * u))
                        drawCircle(Color(0xFFF2C14E), 1.5f * u, Offset(bxx * u, -53 * u))
                    }
                    // náprsní kapsa s tužkou, šev
                    drawLine(Color(0xFFE8C040), Offset(13 * u, -50 * u), Offset(15 * u, -55 * u), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
                    drawRoundRect(OverallsShade, Offset(7 * u, -50 * u), Size(9 * u, 6 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * u))
                    drawArc(Color(0x55FFFFFF), 200f, 90f, false, Offset(-2 * u, -42 * u), Size(22 * u, 16 * u), style = Stroke(2.2f * u, cap = StrokeCap.Round))
                    // při předklonu vykoukne instalatérská klasika
                    if (p.lean > 0.5f) {
                        val crack = Path().apply {
                            moveTo(-17 * u, -36 * u); cubicTo(-19 * u, -40 * u, -14 * u, -42 * u, -12 * u, -40 * u); close()
                        }
                        outlined(crack, Brush.linearGradient(listOf(Skin, SkinShade)), u, 1.2f)
                    }
                    // ---------- hlava
                    drawPlumberHead(Offset(4 * u, -74 * u), p, u, t)
                    // ---------- přední ruka
                    drawArmLimb(Offset(8 * u, -55 * u), p.armR, u, back = false, p = p)
                    if (p.plungerOnFace) {
                        val f = Offset(22 * u, -73 * u)
                        drawLine(Outline, Offset(f.x + 4 * u, f.y), Offset(f.x + 34 * u, f.y - 4 * u), strokeWidth = 5.6f * u, cap = StrokeCap.Round)
                        drawLine(Color(0xFFC08A52), Offset(f.x + 4 * u, f.y), Offset(f.x + 34 * u, f.y - 4 * u), strokeWidth = 3.4f * u, cap = StrokeCap.Round)
                        val cup = Path().apply {
                            moveTo(f.x - 2 * u, f.y - 11 * u)
                            cubicTo(f.x + 10 * u, f.y - 9 * u, f.x + 10 * u, f.y + 9 * u, f.x - 2 * u, f.y + 11 * u)
                            close()
                        }
                        outlined(cup, Brush.horizontalGradient(listOf(Color(0xFF8E1B1B), Color(0xFFD63A2F)), startX = f.x - 2 * u, endX = f.x + 8 * u), u)
                    }
                }
            }
        }
        // létající čepice
        if (p.capLift > 0.5f) {
            rotate(p.capSpin, pivot = Offset(4 * u, -92 * u - p.capLift)) { drawPlumberCap(Offset(4 * u, -90 * u - p.capLift), u) }
        }
        if (p.carry == 2) drawDuck(Offset(16 * u * p.facing, -46 * u), 0.9f * u, p.facing)
        if (p.zzz) {
            for (i in 0 until 3) {
                val q = ((t * 0.45f + i / 3f) % 1f)
                drawZ(Offset(30 * u + 14 * u * q + i * 2 * u, -60 * u - 40 * u * q), (4f + 4f * q) * u, Color.White.copy(alpha = 0.85f * (1f - q)))
            }
        }
        if (p.dazed > 0.05f) {
            for (i in 0 until 3) {
                val a = t * 5f + i * 2.1f
                val c = Offset(4 * u + 17 * u * cos(a), -98 * u + 5 * u * sin(a))
                drawStar(c, 4.5f * u, Color(0xFFFFD54A).copy(alpha = p.dazed.coerceIn(0f, 1f)))
            }
        }
    }
}

internal fun DrawScope.drawArmLimb(sh: Offset, angle: Float, u: Float, back: Boolean, p: Pose) {
    // loket se přirozeně ohýbá víc, když je ruka nahoře
    val elbowBend = 25f + 35f * (abs(angle) / 180f)
    val el = polar(sh, 10 * u, angle)
    val wr = polar(el, 10 * u, angle + elbowBend * (if (angle >= 0) 1 else -1))
    val sleeve = if (back) ShirtShade else Shirt
    limb(sh, el, el, 8.5f * u, sleeve, ShirtShade, u)
    limb(el, wr, wr, 6.5f * u, if (back) SkinShade else Skin, SkinShade, u)
    val dir = Math.toDegrees(kotlin.math.atan2((wr.x - el.x).toDouble(), (wr.y - el.y).toDouble())).toFloat()
    // palčák (dlaň s palcem)
    rotate(-dir, pivot = wr) {
        val hand = Path().apply {
            moveTo(wr.x - 3.5f * u, wr.y)
            cubicTo(wr.x - 5 * u, wr.y + 6 * u, wr.x + 5 * u, wr.y + 7 * u, wr.x + 4 * u, wr.y)
            close()
        }
        outlined(hand, Brush.verticalGradient(listOf(if (back) SkinShade else Skin, SkinShade)), u, 1.3f)
        if (!back && p.thumb) {
            val th = Path().apply {
                moveTo(wr.x + 2 * u, wr.y + 1 * u)
                cubicTo(wr.x + 2 * u, wr.y - 7 * u, wr.x + 6 * u, wr.y - 7 * u, wr.x + 5 * u, wr.y + 1 * u)
                close()
            }
            outlined(th, Brush.verticalGradient(listOf(Skin, SkinShade)), u, 1.3f)
        }
        if (!back) {
            val tip = Offset(wr.x, wr.y + 4 * u)
            val wrench = !p.thumb && !p.tape && !p.plunger && !p.plungerOnFace && !p.noTool
            if (wrench) {
                drawLine(Outline, tip, Offset(tip.x, tip.y + 17 * u), strokeWidth = 5f * u, cap = StrokeCap.Round)
                drawLine(Color(0xFFD63A2F), tip, Offset(tip.x, tip.y + 8 * u), strokeWidth = 3.4f * u, cap = StrokeCap.Round)
                drawLine(Color(0xFF9AA6B2), Offset(tip.x, tip.y + 8 * u), Offset(tip.x, tip.y + 16 * u), strokeWidth = 3.4f * u, cap = StrokeCap.Round)
                val jaw = Path().apply {
                    moveTo(tip.x - 2 * u, tip.y + 15 * u)
                    cubicTo(tip.x - 7 * u, tip.y + 17 * u, tip.x - 6 * u, tip.y + 24 * u, tip.x, tip.y + 23 * u)
                    lineTo(tip.x - 1 * u, tip.y + 20 * u)
                    cubicTo(tip.x - 3 * u, tip.y + 20 * u, tip.x - 3 * u, tip.y + 17 * u, tip.x + 1 * u, tip.y + 17 * u)
                    close()
                }
                outlined(jaw, Brush.linearGradient(listOf(Color(0xFFDCE4EC), Color(0xFF7D8894))), u, 1.2f)
            }
            if (p.plunger) {
                val end = Offset(tip.x, tip.y + 28 * u + p.plungeDy)
                drawLine(Outline, Offset(tip.x, tip.y - 8 * u), end, strokeWidth = 5f * u, cap = StrokeCap.Round)
                drawLine(Color(0xFFC08A52), Offset(tip.x, tip.y - 8 * u), end, strokeWidth = 3.2f * u, cap = StrokeCap.Round)
                val cup = Path().apply {
                    moveTo(end.x - 10 * u, end.y + 9 * u)
                    cubicTo(end.x - 9 * u, end.y - 1 * u, end.x + 9 * u, end.y - 1 * u, end.x + 10 * u, end.y + 9 * u)
                    close()
                }
                outlined(cup, Brush.verticalGradient(listOf(Color(0xFFD63A2F), Color(0xFF8E1B1B)), startY = end.y, endY = end.y + 9 * u), u)
            }
            if (p.carry == 3) {
                // korbel piva (horní okraj s pěnou je u ruky – při zvednutí k puse se nakloní)
                val mug = androidx.compose.ui.geometry.Rect(tip.x - 6 * u, tip.y - 4 * u, tip.x + 6 * u, tip.y + 12 * u)
                drawRoundRect(Outline, Offset(mug.left - 1 * u, mug.top - 1 * u), Size(mug.width + 2 * u, mug.height + 2 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * u))
                drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFF2B21B), Color(0xFFC77D0A)), startX = mug.left, endX = mug.right), mug.topLeft, mug.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
                drawRoundRect(Color.White, Offset(mug.left - 0.5f * u, mug.top - 1.5f * u), Size(mug.width + 1 * u, 4 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
                drawLine(Color.White.copy(alpha = 0.6f), Offset(mug.left + 2.5f * u, mug.top + 4 * u), Offset(mug.left + 2.5f * u, mug.bottom - 2 * u), strokeWidth = 1.2f * u)
                drawArc(Outline, -90f, 180f, false, Offset(mug.right - 3 * u, mug.top + 2 * u), Size(8 * u, 9 * u), style = Stroke(2.6f * u))
                drawArc(Color(0xFFE8E2D0), -90f, 180f, false, Offset(mug.right - 3 * u, mug.top + 2 * u), Size(8 * u, 9 * u), style = Stroke(1.4f * u))
            }
            if (p.tape) {
                drawCircle(Outline, 6.4f * u, Offset(tip.x, tip.y + 3 * u), style = Stroke(4.6f * u))
                drawCircle(Color(0xFFA9AFB7), 6.4f * u, Offset(tip.x, tip.y + 3 * u), style = Stroke(3f * u))
            }
        }
    }
}

internal fun DrawScope.drawPlumberHead(c: Offset, p: Pose, u: Float, t: Float) {
    // krk / podbradek, ucho, hlava s tvářemi
    val head = Path().apply {
        moveTo(c.x - 12 * u, c.y - 4 * u)
        cubicTo(c.x - 13 * u, c.y - 16 * u, c.x + 10 * u, c.y - 19 * u, c.x + 13 * u, c.y - 6 * u)
        cubicTo(c.x + 16 * u, c.y + 2 * u, c.x + 15 * u, c.y + 10 * u, c.x + 8 * u, c.y + 13 * u)
        cubicTo(c.x + 2 * u, c.y + 16 * u, c.x - 8 * u, c.y + 15 * u, c.x - 11 * u, c.y + 8 * u)
        cubicTo(c.x - 13 * u, c.y + 4 * u, c.x - 12 * u, c.y, c.x - 12 * u, c.y - 4 * u)
        close()
    }
    outlined(head, Brush.radialGradient(listOf(Color(0xFFFAD3B0), Skin, SkinShade), center = Offset(c.x + 3 * u, c.y - 4 * u), radius = 18 * u), u)
    // strniště na bradě (jemný stín místo teček)
    val jaw = Path().apply {
        moveTo(c.x - 9 * u, c.y + 6 * u)
        cubicTo(c.x - 4 * u, c.y + 15 * u, c.x + 8 * u, c.y + 14 * u, c.x + 12 * u, c.y + 6 * u)
        cubicTo(c.x + 6 * u, c.y + 9 * u, c.x - 3 * u, c.y + 9 * u, c.x - 9 * u, c.y + 6 * u)
        close()
    }
    drawPath(jaw, Color(0x33402A1C))
    // ucho
    val ear = Path().apply {
        moveTo(c.x - 9 * u, c.y - 3 * u)
        cubicTo(c.x - 16 * u, c.y - 6 * u, c.x - 16 * u, c.y + 5 * u, c.x - 9 * u, c.y + 4 * u)
        close()
    }
    outlined(ear, Brush.radialGradient(listOf(Skin, SkinShade), center = Offset(c.x - 12 * u, c.y), radius = 6 * u), u, 1.3f)
    drawArc(SkinShade, 120f, 140f, false, Offset(c.x - 14 * u, c.y - 2 * u), Size(5 * u, 5 * u), style = Stroke(1 * u))
    // vlasy na spáncích
    drawPath(Path().apply {
        moveTo(c.x - 11 * u, c.y - 6 * u); cubicTo(c.x - 13 * u, c.y - 10 * u, c.x - 8 * u, c.y - 12 * u, c.x - 5 * u, c.y - 10 * u); close()
    }, Hair)
    // tvář
    drawOval(Brush.radialGradient(listOf(Color(0x88FF7A6B), Color.Transparent), center = Offset(c.x + 7 * u, c.y + 3 * u), radius = 6 * u), Offset(c.x + 1 * u, c.y - 3 * u), Size(12 * u, 12 * u))
    // oči (mrkání každé ~3,5 s)
    val blink = ((t % 3.5f) > 3.38f) || p.eyesClosed || p.dazed > 0.5f
    val eyeL = Offset(c.x + 4 * u, c.y - 4 * u)
    val eyeR = Offset(c.x + 11 * u, c.y - 4.5f * u)
    if (blink) {
        if (p.dazed > 0.5f) {
            for (eo in listOf(eyeL, eyeR)) {
                drawLine(Ink, Offset(eo.x - 2.4f * u, eo.y - 2.4f * u), Offset(eo.x + 2.4f * u, eo.y + 2.4f * u), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
                drawLine(Ink, Offset(eo.x - 2.4f * u, eo.y + 2.4f * u), Offset(eo.x + 2.4f * u, eo.y - 2.4f * u), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
            }
        } else {
            for (eo in listOf(eyeL, eyeR)) drawArc(Ink, 20f, 140f, false, Offset(eo.x - 3 * u, eo.y - 2 * u), Size(6 * u, 4 * u), style = Stroke(1.5f * u, cap = StrokeCap.Round))
        }
    } else {
        for ((i, eo) in listOf(eyeL, eyeR).withIndex()) {
            if (i == 1 && p.wink) {
                drawArc(Ink, 200f, 140f, false, Offset(eo.x - 3 * u, eo.y - 1 * u), Size(6 * u, 4 * u), style = Stroke(1.5f * u, cap = StrokeCap.Round))
                continue
            }
            drawOval(Color.White, Offset(eo.x - 2.8f * u, eo.y - 3.6f * u), Size(5.6f * u, 7.2f * u))
            drawOval(Outline, Offset(eo.x - 2.8f * u, eo.y - 3.6f * u), Size(5.6f * u, 7.2f * u), style = Stroke(1.1f * u))
            val look = if (p.grin < -0.4f) Offset(0.6f * u, 1.2f * u) else Offset(1.1f * u, 0f)
            drawOval(Color(0xFF3B2A1E), Offset(eo.x - 1.6f * u + look.x, eo.y - 1.9f * u + look.y), Size(3.2f * u, 4 * u))
            drawCircle(Color.White, 0.8f * u, Offset(eo.x - 0.4f * u + look.x, eo.y - 1 * u + look.y))
        }
    }
    // husté obočí
    val browLift = if (p.grin < -0.4f) -1.5f * u else if (p.grin > 0.5f) -1f * u else 0f
    for ((i, eo) in listOf(eyeL, eyeR).withIndex()) {
        val br = Path().apply {
            moveTo(eo.x - 3.5f * u, eo.y - 5 * u + browLift + if (i == 0 && p.grin < -0.4f) 1.5f * u else 0f)
            quadraticTo(eo.x, eo.y - 7.5f * u + browLift, eo.x + 3.5f * u, eo.y - 5.5f * u + browLift)
        }
        drawPath(br, Hair, style = Stroke(2.2f * u, cap = StrokeCap.Round))
    }
    // velký baňatý nos
    val nose = Path().apply {
        moveTo(c.x + 12 * u, c.y - 3 * u)
        cubicTo(c.x + 22 * u, c.y - 5 * u, c.x + 23 * u, c.y + 5 * u, c.x + 15 * u, c.y + 5 * u)
        cubicTo(c.x + 11 * u, c.y + 5 * u, c.x + 9 * u, c.y + 1 * u, c.x + 12 * u, c.y - 3 * u)
        close()
    }
    outlined(nose, Brush.radialGradient(listOf(Color(0xFFF07A6A), Color(0xFFCF4E40)), center = Offset(c.x + 16 * u, c.y - 1 * u), radius = 7 * u), u)
    drawOval(Color.White.copy(alpha = 0.6f), Offset(c.x + 15 * u, c.y - 2 * u), Size(3 * u, 2 * u))
    // ústa
    when {
        p.grin > 0.2f -> {
            val m = Path().apply {
                moveTo(c.x + 3 * u, c.y + 8 * u)
                cubicTo(c.x + 6 * u, c.y + 14 * u, c.x + 13 * u, c.y + 13 * u, c.x + 15 * u, c.y + 8 * u)
                close()
            }
            outlined(m, Brush.verticalGradient(listOf(Color(0xFF7A2A22), Color(0xFF4A1410))), u, 1.2f)
            drawRect(Color.White, Offset(c.x + 7 * u, c.y + 8 * u), Size(5 * u, 1.8f * u))
        }
        p.grin < -0.2f -> drawArc(Ink, 200f, 140f, false, Offset(c.x + 5 * u, c.y + 9 * u), Size(8 * u, 5 * u), style = Stroke(1.5f * u, cap = StrokeCap.Round))
        else -> drawOval(Color(0xFF4A1410), Offset(c.x + 7 * u, c.y + 9 * u), Size(4 * u, 3 * u))
    }
    // zatočený knír
    val wob = sin(t * 3f) * 0.4f * u
    val mus = Path().apply {
        moveTo(c.x + 11 * u, c.y + 5 * u)
        cubicTo(c.x + 7 * u, c.y + 4 * u, c.x + 3 * u, c.y + 6 * u, c.x + 1 * u, c.y + 9 * u + wob)
        cubicTo(c.x - 1 * u, c.y + 11 * u, c.x - 3 * u, c.y + 9 * u, c.x - 1 * u, c.y + 7 * u)
        cubicTo(c.x - 4 * u, c.y + 10 * u, c.x + 4 * u, c.y + 11 * u, c.x + 11 * u, c.y + 8 * u)
        cubicTo(c.x + 18 * u, c.y + 11 * u, c.x + 24 * u, c.y + 10 * u, c.x + 21 * u, c.y + 7 * u)
        cubicTo(c.x + 23 * u, c.y + 9 * u, c.x + 21 * u, c.y + 11 * u, c.x + 19 * u, c.y + 9 * u + wob)
        cubicTo(c.x + 17 * u, c.y + 6 * u, c.x + 14 * u, c.y + 4 * u, c.x + 11 * u, c.y + 5 * u)
        close()
    }
    outlined(mus, Brush.verticalGradient(listOf(Color(0xFF55372A), Hair), startY = c.y + 4 * u, endY = c.y + 11 * u), u, 1.2f)
    // čepice nebo rozcuchané vlasy
    if (p.capLift <= 0.5f) drawPlumberCap(Offset(c.x + 1 * u, c.y - 13 * u), u)
    else {
        val hair = Path().apply {
            moveTo(c.x - 11 * u, c.y - 8 * u)
            for (i in 0..6) {
                val x = c.x - 9 * u + i * 3.5f * u
                lineTo(x, c.y - 20 * u - (i % 2) * 3 * u)
                lineTo(x + 1.7f * u, c.y - 14 * u)
            }
            lineTo(c.x + 12 * u, c.y - 8 * u); close()
        }
        outlined(hair, Brush.verticalGradient(listOf(Color(0xFF55372A), Hair)), u, 1.2f)
    }
    // mokrý obličej – kapky stékají
    if (p.wet) {
        for (i in 0 until 3) {
            val y = c.y - 8 * u + ((t * 0.7f + i * 0.33f) % 1f) * 20 * u
            drawDrop(Offset(c.x - 6 * u + i * 8 * u, y), 1.5f * u, 1.3f)
        }
    }
}

internal fun DrawScope.drawPlumberCap(c: Offset, u: Float) {
    val dome = Path().apply {
        moveTo(c.x - 13 * u, c.y + 3 * u)
        cubicTo(c.x - 14 * u, c.y - 10 * u, c.x + 12 * u, c.y - 12 * u, c.x + 13 * u, c.y + 2 * u)
        close()
    }
    outlined(dome, Brush.radialGradient(listOf(Color(0xFFFFB45C), Cap, CapShade), center = Offset(c.x - 3 * u, c.y - 5 * u), radius = 16 * u), u)
    drawLine(CapShade, Offset(c.x - 1 * u, c.y - 8 * u), Offset(c.x + 1 * u, c.y + 2 * u), strokeWidth = 1 * u)
    drawCircle(Outline, 1.8f * u, Offset(c.x - 1 * u, c.y - 8.5f * u))
    drawCircle(Cap, 1.1f * u, Offset(c.x - 1 * u, c.y - 8.5f * u))
    // kšilt
    val brim = Path().apply {
        moveTo(c.x + 8 * u, c.y + 1 * u)
        cubicTo(c.x + 16 * u, c.y - 1 * u, c.x + 23 * u, c.y + 1 * u, c.x + 24 * u, c.y + 4 * u)
        cubicTo(c.x + 18 * u, c.y + 6 * u, c.x + 12 * u, c.y + 5 * u, c.x + 8 * u, c.y + 4 * u)
        close()
    }
    outlined(brim, Brush.verticalGradient(listOf(Cap, CapShade)), u)
    // logo K
    drawCircle(Color.White, 3.6f * u, Offset(c.x - 4 * u, c.y - 3 * u))
    drawCircle(Outline, 3.6f * u, Offset(c.x - 4 * u, c.y - 3 * u), style = Stroke(0.9f * u))
    drawLine(CapShade, Offset(c.x - 5.3f * u, c.y - 5.2f * u), Offset(c.x - 5.3f * u, c.y - 0.8f * u), strokeWidth = 1.1f * u)
    drawLine(CapShade, Offset(c.x - 5.3f * u, c.y - 3 * u), Offset(c.x - 2.4f * u, c.y - 5.2f * u), strokeWidth = 1.1f * u)
    drawLine(CapShade, Offset(c.x - 5.3f * u, c.y - 3 * u), Offset(c.x - 2.4f * u, c.y - 0.8f * u), strokeWidth = 1.1f * u)
    drawArc(Color(0x66FFFFFF), 200f, 70f, false, Offset(c.x - 10 * u, c.y - 9 * u), Size(16 * u, 12 * u), style = Stroke(1.5f * u, cap = StrokeCap.Round))
}

internal fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val a = -PI / 2 + i * PI / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + (rr * cos(a)).toFloat()
        val y = c.y + (rr * sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
