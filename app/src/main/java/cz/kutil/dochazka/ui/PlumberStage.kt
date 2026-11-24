package cz.kutil.dochazka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// =============================================================================================
// Kutilovo divadlo: spodní část přehledu, po které Kutil sám chodí a střídá scénky.
// Nic se neovládá klepnutím – vrstva doteky propouští dál k tlačítkům pod ní.
// =============================================================================================

enum class Act(val length: Float) {
    PIPE(17.5f), TOILET(17.5f), LADDER(18.5f), SINK(17.5f), TUB(19f), WANDER(10f),
    VALVE(17f), TAPE(17f), BUCKET(16.5f), FLOOD(16.5f), COFFEE(18.5f), SPIRAL(16f),
    /** Jednou po skončení směny, pak televize. */
    END_SHIFT(12f),
    /** Mimo pracovní dobu: křeslo, televize a pivo (smyčka). */
    TV(24f),
    /** Od 20:00 do 6:00: spí v křesle (smyčka). */
    SLEEP(16f),
}

private val SCENES = listOf(
    Act.PIPE, Act.TOILET, Act.LADDER, Act.SINK, Act.TUB,
    Act.VALVE, Act.TAPE, Act.BUCKET, Act.FLOOD, Act.COFFEE, Act.SPIRAL,
)

private fun eventsOf(a: Act): List<Pair<Float, GagSound>> = when (a) {
    Act.PIPE -> listOf(
        0.05f to GagSound.BURST_START, T.SPLASH to GagSound.SPLASH,
        T.HITS[0] to GagSound.CLANK, T.HITS[1] to GagSound.CLANK, T.HITS[2] to GagSound.CLANK,
        T.FIXED to GagSound.BURST_STOP, T.SLIP to GagSound.BOING, T.SIT - 0.05f to GagSound.THUD,
    )
    Act.TOILET -> listOf(
        0.05f to GagSound.GURGLE, 4.3f to GagSound.PLOP, 4.9f to GagSound.PLOP, 5.5f to GagSound.PLOP,
        6.1f to GagSound.PLOP, 6.7f to GagSound.PLOP, 7.0f to GagSound.SPLASH, 9.6f to GagSound.POP,
        10.3f to GagSound.THUD, 11.3f to GagSound.FLUSH, 14.2f to GagSound.GURGLE, 14.3f to GagSound.BOING,
    )
    Act.LADDER -> listOf(
        7.4f to GagSound.CLANK, 8.0f to GagSound.CLANK, 8.6f to GagSound.CLANK,
        10.5f to GagSound.CREAK, 11.0f to GagSound.METAL_FALL, 12.8f to GagSound.BOING, 13.15f to GagSound.THUD,
    )
    Act.SINK -> listOf(
        5.6f to GagSound.CLANK, 6.4f to GagSound.CLANK, 7.3f to GagSound.CLANK, 8.6f to GagSound.CLANK,
        10.0f to GagSound.SPLASH, 10.35f to GagSound.CLANK, 10.4f to GagSound.BOING,
    )
    Act.TUB -> listOf(
        8.6f to GagSound.SNORE, 10.6f to GagSound.SNORE, 12.2f to GagSound.SQUEAK, 12.25f to GagSound.BOING,
        14.4f to GagSound.SQUEAK,
    )
    Act.VALVE -> listOf(
        3.3f to GagSound.CREAK, 4.8f to GagSound.CREAK, 6.0f to GagSound.POP, 6.02f to GagSound.BURST_START, 6.8f to GagSound.TOOL_DROP,
        8.3f to GagSound.PLOP, 8.62f to GagSound.BURST_STOP, 9.8f to GagSound.BURST_START, 11.4f to GagSound.BURST_STOP,
        11.8f to GagSound.CLANK, 12.3f to GagSound.CLANK, 12.8f to GagSound.CLANK, 13.4f to GagSound.SPLASH,
    )
    Act.TAPE -> listOf(
        3.2f to GagSound.TAPE, 4.5f to GagSound.TAPE, 5.8f to GagSound.TAPE, 6.9f to GagSound.TAPE, 9.0f to GagSound.CREAK,
        10.4f to GagSound.POP, 10.42f to GagSound.SPLASH_BIG, 10.45f to GagSound.BURST_START, 10.9f to GagSound.THUD,
        12.1f to GagSound.BURST_STOP, 14.6f to GagSound.TOOL_DROP,
    )
    Act.BUCKET -> listOf(
        3.5f to GagSound.CLANK, 4.2f to GagSound.CLANK, 4.9f to GagSound.CLANK, 5.2f to GagSound.BURST_START,
        9.0f to GagSound.BUCKET, 9.05f to GagSound.SPLASH_BIG, 9.25f to GagSound.BURST_STOP, 11.0f to GagSound.THUD, 13.7f to GagSound.BUCKET,
    )
    Act.FLOOD -> listOf(
        0.2f to GagSound.BURST_START, 0.5f to GagSound.SPLASH, 2.6f to GagSound.SQUEAK, 6.1f to GagSound.SQUEAK, 9.9f to GagSound.BURST_STOP,
        10.3f to GagSound.GURGLE, 12.1f to GagSound.GURGLE, 13.3f to GagSound.THUD, 13.6f to GagSound.SQUEAK,
    )
    Act.COFFEE -> listOf(
        3.9f to GagSound.STIR, 6.1f to GagSound.SIGH, 8.6f to GagSound.PLOP, 9.4f to GagSound.POP, 9.42f to GagSound.BURST_START,
        9.6f to GagSound.BOING, 11.5f to GagSound.BURST_STOP, 12.2f to GagSound.PLOP, 15.3f to GagSound.SPLASH, 15.9f to GagSound.BURP,
    )
    Act.SPIRAL -> listOf(
        3.6f to GagSound.DRILL, 6.2f to GagSound.DRILL, 6.4f to GagSound.BOING, 8.8f to GagSound.DRILL, 10.6f to GagSound.CLANK,
        11.6f to GagSound.METAL_FALL, 12.0f to GagSound.SIGH,
    )
    Act.END_SHIFT -> listOf(
        0.4f to GagSound.CLANK, 1.0f to GagSound.CLANK, 1.6f to GagSound.CLANK, 2.6f to GagSound.SIGH, 4.3f to GagSound.KEYS,
        6.0f to GagSound.BURST_START, 11.9f to GagSound.BURST_STOP,
    )
    Act.WANDER -> emptyList()
    Act.TV -> listOf(9.2f to GagSound.BURP, 18.0f to GagSound.CHEER)
    Act.SLEEP -> listOf(2.0f to GagSound.SNORE, 9.5f to GagSound.SNORE)
}

/** Jen pro natáčení ukázkového videa v testech: pevné pořadí scének bez procházek. */
object PlumberDebug {
    @Volatile var forcedOrder: List<Act>? = null
    /** Scénář pro natáčení: čas → (scénka, čas od jejího začátku). */
    @Volatile var script: ((Float) -> Pair<Act, Float>?)? = null
}

/** Výška jeviště (spodní část přehledu). */
val StageHeight = 190.dp

@Composable
fun PlumberStage(
    clock: State<Float>,
    water: WaterState,
    animate: Boolean,
    onSound: (GagSound) -> Unit,
    modifier: Modifier = Modifier,
    /** Kutil pracuje jen během směny. */
    working: Boolean = true,
    /** 20:00–6:00 = spí. */
    night: Boolean = false,
    fixedAct: Act? = null,
    fixedElapsed: Float? = null,
) {
    val act = remember { mutableIntStateOf(Act.WANDER.ordinal) }
    val start = remember { mutableFloatStateOf(-1000f) }
    val variant = remember { mutableIntStateOf(0) }
    val smoother = remember { PoseSmoother() }
    // byl Kutil v této obrazovce v práci? Po skončení směny se nejdřív sbalí (scénka END_SHIFT)
    val wasWorking = remember { booleanArrayOf(working) }
    // režisér: scénky se střídají samy, mezi nimi se Kutil občas jen projde
    LaunchedEffect(animate, fixedAct, working, night) {
        if (!animate || fixedAct != null) return@LaunchedEffect
        if (!working && PlumberDebug.forcedOrder == null) {
            if (wasWorking[0]) {
                // právě skončila směna: „padla!“, pak teprve televize
                wasWorking[0] = false
                act.intValue = Act.END_SHIFT.ordinal
                start.floatValue = clock.value
                val until = clock.value + Act.END_SHIFT.length + 1f
                snapshotFlow { clock.value }.first { it >= until }
            }
            // po práci: televize a pivo, v noci spánek – běží ve smyčce
            act.intValue = (if (night) Act.SLEEP else Act.TV).ordinal
            start.floatValue = clock.value
            return@LaunchedEffect
        }
        wasWorking[0] = true
        val forced = PlumberDebug.forcedOrder?.toMutableList()
        val order = forced ?: SCENES.shuffled().toMutableList()
        var nextAt = clock.value + 2.5f
        var sinceScene = 0
        snapshotFlow { clock.value }.collect { t ->
            if (t < nextAt) return@collect
            if (forced != null && order.isEmpty()) return@collect
            val next = if (forced == null && sinceScene == 0 && Math.random() < 0.55) {
                Act.WANDER
            } else {
                if (order.isEmpty()) order += SCENES.shuffled()
                order.removeAt(0)
            }
            sinceScene = if (next == Act.WANDER) 1 else 0
            act.intValue = next.ordinal
            variant.intValue = (Math.random() * 3).toInt()
            start.floatValue = t
            nextAt = t + next.length + if (forced != null) 1f else 3f + (Math.random() * 6).toFloat()
        }
    }
    // zvuky ve správných okamžicích
    LaunchedEffect(animate, act.intValue, start.floatValue) {
        if (!animate || fixedAct != null) return@LaunchedEffect
        val events = eventsOf(Act.entries[act.intValue])
        onSound(GagSound.BURST_STOP) // stříkání z předchozí scénky nikdy nepřetrvá
        var prev = -1f
        val a = Act.entries[act.intValue]
        val loop = a == Act.TV || a == Act.SLEEP
        snapshotFlow { (clock.value - start.floatValue).let { if (loop && it >= 0f) it % a.length else it } }.collect { e ->
            if (loop && e < prev) prev = -0.01f
            if (prev >= 0f || e < 0.2f) for ((at, s) in events) if (prev < at && e >= at) onSound(s)
            prev = e
        }
    }
    val pipes = rememberPipeImages()
    Canvas(modifier) {
        val t = clock.value
        val scripted = PlumberDebug.script?.invoke(t)
        if (PlumberDebug.script != null && scripted == null) return@Canvas
        val a = scripted?.first ?: fixedAct ?: Act.entries[act.intValue]
        var e = scripted?.second ?: fixedElapsed ?: if (animate) t - start.floatValue else -1f
        val looping = a == Act.TV || a == Act.SLEEP
        if (looping && e >= 0f) e %= a.length
        if (!animate && looping) e = 1f
        if (e < 0f || e > a.length) return@Canvas
        val u = 1.dp.toPx()
        val floorY = size.height - 10 * u
        // rekvizity se jemně objeví a zmizí
        val fade = if (looping) 1f else min(1f, min(e / 0.6f, (a.length - e) / 0.6f)).coerceIn(0f, 1f)
        when (a) {
            Act.PIPE -> actPipe(e, t, floorY, u, fade, pipes, smoother)
            Act.TOILET -> drawToiletScene2(e, t, floorY, u, smoother)
            Act.LADDER -> actLadder(e, t, floorY, u, fade, pipes, smoother)
            Act.SINK -> actSink(e, t, floorY, u, fade, smoother)
            Act.TUB -> actTub(e, t, floorY, u, fade, smoother)
            Act.TV -> actLounge(e, t, floorY, u, sleep = false, sm = smoother)
            Act.SLEEP -> actLounge(e, t, floorY, u, sleep = true, sm = smoother)
            Act.VALVE -> actValve(e, t, floorY, u, fade, pipes, smoother)
            Act.TAPE -> actTape(e, t, floorY, u, fade, pipes, smoother)
            Act.BUCKET -> actBucket(e, t, floorY, u, fade, pipes, smoother)
            Act.FLOOD -> actFlood(e, t, floorY, u, fade, pipes, smoother)
            Act.COFFEE -> actCoffee(e, t, floorY, u, fade, pipes, smoother)
            Act.SPIRAL -> actSpiral(e, t, floorY, u, fade, smoother)
            Act.END_SHIFT -> actEndShift(e, t, floorY, u, fade, pipes, smoother)
            Act.WANDER -> drawPlumber(smoother.smooth(poseWander(e, t, size.width, u, variant.intValue), t, e), floorY, u)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// pomocné
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.withAlpha(a: Float, block: DrawScope.() -> Unit) {
    if (a >= 0.99f) { block(); return }
    if (a <= 0.01f) return
    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = a }) }
    block()
    drawIntoCanvas { it.restore() }
}

internal fun sg(e: Float, a: Float, b: Float) = ((e - a) / (b - a)).coerceIn(0f, 1f)
internal fun ez(k: Float) = k * k * (3 - 2 * k)
internal fun lp(a: Float, b: Float, k: Float) = a + (b - a) * k.coerceIn(0f, 1f)

internal fun P(
    x: Float, t: Float, facing: Float = 1f, run: Float = 0f, armR: Float = 10f, armL: Float = -10f, lean: Float = 0f,
    tilt: Float = 0f, sit: Float = 0f, capLift: Float = 0f, capSpin: Float = 0f, wet: Boolean = false, dazed: Float = 0f,
    grin: Float = 0f, wink: Boolean = false, thumb: Boolean = false, tape: Boolean = false, closed: Boolean = false,
    lift: Float = 0f, carry: Int = 0, zzz: Boolean = false, wrench: Boolean = true, bucket: Boolean = false,
) = Pose(
    x, facing, run, t * 14f, armR, armL, lean, tilt, sit, capLift, capSpin, wet, dazed, grin, wink, thumb,
    tape = tape, eyesClosed = closed, lift = lift, carry = carry, zzz = zzz, noTool = !wrench || carry > 0 || tape,
    headgear = if (bucket) 1 else 0,
)

/** Chůze z bodu do bodu s houpáním rukou. */
internal fun walkArms(t: Float, fast: Boolean = false): Pair<Float, Float> {
    val k = if (fast) 18f else 11f
    return (28f * sin(t * k)) to (-28f * sin(t * k))
}

// ---------------------------------------------------------------------------------------------
// Procházka mezi scénkami
// ---------------------------------------------------------------------------------------------

private fun poseWander(e: Float, t: Float, w: Float, u: Float, v: Int): Pose {
    val fromLeft = v % 2 == 0
    val mid = w * (0.42f + 0.08f * v)
    val x0 = if (fromLeft) -60 * u else w + 60 * u
    val x1 = if (fromLeft) w + 60 * u else -60 * u
    val f = if (fromLeft) 1f else -1f
    return when {
        e < 3.5f -> { val (r, l) = walkArms(t); P(lp(x0, mid, ez(sg(e, 0f, 3.5f))), t, f, run = 0.55f, armR = r, armL = l, grin = 0.4f) }
        e < 6.5f -> when (v) {
            0 -> P(mid, t, f, armR = 170f, armL = -170f, grin = -0.3f, closed = sg(e, 4f, 5.5f) in 0.1f..0.9f) // protahování a zívání
            1 -> P(mid, t, f, armR = 55f + 12f * sin(t * 9f), armL = 40f, grin = 1f) // plácá se po bříšku
            else -> P(mid, t, f, armR = 150f + 10f * sin(t * 6f), grin = 0.2f, wrench = false) // drbe se na hlavě
        }
        else -> { val (r, l) = walkArms(t); P(lp(mid, x1, ez(sg(e, 6.5f, 10f))), t, f, run = 0.55f, armR = r, armL = l, grin = 0.5f) }
    }
}

// ---------------------------------------------------------------------------------------------
// 1) Prasklá trubka (pose z Plumber.kt)
// ---------------------------------------------------------------------------------------------

private fun DrawScope.actPipe(e: Float, t: Float, floorY: Float, u: Float, fade: Float, pipes: PipeImages, sm: PoseSmoother) {
    val w = size.width
    val d = 20 * u
    val pipeY = floorY - 108 * u
    val crackX = w * 0.5f
    withAlpha(fade) {
        val k = d / 64f
        drawImage(pipes.h, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(pipes.h.width, pipes.h.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(0, pipeY.toInt()), dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), d.toInt()))
        for (cx in listOf(w * 0.12f, w * 0.88f)) {
            drawImage(pipes.clampH, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(pipes.clampH.width, pipes.clampH.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(cx.toInt(), (pipeY + d / 2 - pipes.clampH.height * k / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize((pipes.clampH.width * k).toInt(), (pipes.clampH.height * k).toInt()))
        }
        val crack = Path().apply {
            moveTo(crackX - 7 * u, pipeY + d * 0.55f); lineTo(crackX - 2 * u, pipeY + d * 0.35f)
            lineTo(crackX + 1 * u, pipeY + d * 0.7f); lineTo(crackX + 6 * u, pipeY + d * 0.45f)
        }
        drawPath(crack, Color(0xFF1A2230), style = Stroke(2 * u, cap = StrokeCap.Round))
        if (e >= T.TAPE) {
            val tw = 22 * u * ez(sg(e, T.TAPE + 0.3f, T.FIXED))
            drawRect(Color(0xFF9AA0A8), Offset(crackX - tw / 2, pipeY - 1 * u), Size(tw, d + 2 * u))
        }
        // louže
        val puddle = if (e < T.FIXED) min(1f, e / 4f) else 1f - sg(e, T.FIXED + 2f, T.END) * 0.7f
        val pw = (40 + 70 * puddle) * u
        drawOval(Brush.radialGradient(listOf(Color(0x8854B6FF), Color(0x2240A0FF), Color.Transparent), center = Offset(crackX + 20 * u, floorY + 2 * u), radius = pw),
            Offset(crackX + 20 * u - pw, floorY - 3 * u), Size(pw * 2, 10 * u))
    }
    val s = sprayLevel(e)
    if (s > 0f) drawSpray(Offset(crackX, pipeY + d * 0.6f), s, t, floorY, u)
    drawPlumber(sm.smooth(poseAt(e, t, crackX, w, u), t, e), floorY, u)
}

// ---------------------------------------------------------------------------------------------
// 2) Ucpaný záchod (pose z Plumber.kt)
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawToiletScene2(e: Float, t: Float, floorY: Float, u: Float, sm: PoseSmoother) {
    // původní scénka kreslí záchod i postavu; gejzír dosáhne do výšky 150 dp
    drawToiletScene(e, t, floorY, floorY - 150 * u, size.width, u, sm)
}

// ---------------------------------------------------------------------------------------------
// 3) Štafle a ventil
// ---------------------------------------------------------------------------------------------

private fun DrawScope.actLadder(e: Float, t: Float, floorY: Float, u: Float, fade: Float, pipes: PipeImages, sm: PoseSmoother) {
    val w = size.width
    val px = w * 0.80f
    val valveY = floorY - 140 * u
    val lx = px - 46 * u
    val d = 20 * u
    val fixed = e > 9f
    withAlpha(fade) {
        // svislá trubka se ventilem
        drawImage(pipes.v, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(pipes.v.width, pipes.v.height),
            dstOffset = androidx.compose.ui.unit.IntOffset((px - d / 2).toInt(), 0), dstSize = androidx.compose.ui.unit.IntSize(d.toInt(), floorY.toInt()))
        drawOval(Color(0xFF241A14), Offset(px - 19 * u, valveY - 6 * u), Size(38 * u, 12 * u), style = Stroke(5.5f * u))
        drawOval(Color(0xFFD63A2F), Offset(px - 19 * u, valveY - 6 * u), Size(38 * u, 12 * u), style = Stroke(3.5f * u))
        drawLine(Color(0xFFD63A2F), Offset(px - 17 * u, valveY), Offset(px + 17 * u, valveY), strokeWidth = 2.5f * u)
        if (!fixed) {
            for (i in 0 until 3) {
                val q = ((t * 1.4f + i / 3f) % 1f)
                drawDrop(Offset(px + 4 * u, valveY + 8 * u + (floorY - valveY - 8 * u) * q * q), 2.2f * u, 1.2f)
            }
        }
        // štafle
        val ladderUp = e in 3.2f..10.6f || e in 13.6f..14.6f
        if (ladderUp) {
            val wob = if (e in 9.4f..10.6f) 9f * sin(e * 18f) * sg(e, 9.4f, 10.4f) else 1.2f * sin(t * 3f)
            val fall = if (e in 10.4f..10.6f) 0f else 0f
            rotate(wob + fall, Offset(lx, floorY)) { drawLadder(lx, floorY, u) }
        } else if (e in 10.6f..13.6f) {
            // spadlé štafle leží
            rotate(80f * ez(sg(e, 10.6f, 11.0f)), Offset(lx + 22 * u, floorY)) { drawLadder(lx, floorY, u) }
        }
    }
    drawPlumber(sm.smooth(poseLadder(e, t, lx, px, valveY, floorY, w, u), t, e), floorY, u)
}

private fun DrawScope.drawLadder(cx: Float, floorY: Float, u: Float) {
    val top = floorY - 78 * u
    val wood = Color(0xFFB9C4CF)
    val dark = Color(0xFF241A14)
    for ((a, b) in listOf(Offset(cx - 18 * u, floorY) to Offset(cx - 4 * u, top), Offset(cx + 18 * u, floorY) to Offset(cx + 4 * u, top))) {
        drawLine(dark, a, b, strokeWidth = 6 * u, cap = StrokeCap.Round)
        drawLine(wood, a, b, strokeWidth = 4 * u, cap = StrokeCap.Round)
    }
    for (i in 1..3) {
        val y = floorY - 20 * u * i
        val half = 18 * u - 14 * u * (20 * i / 78f)
        drawLine(dark, Offset(cx - half, y), Offset(cx + half, y), strokeWidth = 5 * u, cap = StrokeCap.Round)
        drawLine(Color(0xFFE2E8EE), Offset(cx - half, y), Offset(cx + half, y), strokeWidth = 3 * u, cap = StrokeCap.Round)
    }
    drawRoundRect(dark, Offset(cx - 9 * u, top - 4 * u), Size(18 * u, 7 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
    drawRoundRect(Color(0xFFF2922A), Offset(cx - 8 * u, top - 3 * u), Size(16 * u, 5 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
}

internal fun DrawScope.drawCarriedLadder(facing: Float, u: Float) {
    rotate(-12f * facing, Offset(-4 * u * facing, -60 * u)) {
        val c = Offset(-4 * u * facing, -60 * u)
        drawLine(Color(0xFF241A14), Offset(c.x - 34 * u, c.y - 5 * u), Offset(c.x + 34 * u, c.y - 5 * u), strokeWidth = 5 * u, cap = StrokeCap.Round)
        drawLine(Color(0xFFB9C4CF), Offset(c.x - 34 * u, c.y - 5 * u), Offset(c.x + 34 * u, c.y - 5 * u), strokeWidth = 3 * u, cap = StrokeCap.Round)
        drawLine(Color(0xFF241A14), Offset(c.x - 34 * u, c.y + 5 * u), Offset(c.x + 34 * u, c.y + 5 * u), strokeWidth = 5 * u, cap = StrokeCap.Round)
        drawLine(Color(0xFFB9C4CF), Offset(c.x - 34 * u, c.y + 5 * u), Offset(c.x + 34 * u, c.y + 5 * u), strokeWidth = 3 * u, cap = StrokeCap.Round)
        for (i in -2..2) drawLine(Color(0xFFE2E8EE), Offset(c.x + i * 13 * u, c.y - 5 * u), Offset(c.x + i * 13 * u, c.y + 5 * u), strokeWidth = 2.5f * u)
    }
}

private fun poseLadder(e: Float, t: Float, lx: Float, px: Float, valveY: Float, floorY: Float, w: Float, u: Float): Pose {
    val standX = lx - 4 * u
    val step = 20 * u
    return when {
        e < 3.2f -> { val (r, l) = walkArms(t); P(lp(-70 * u, standX - 20 * u, ez(sg(e, 0.3f, 3.2f))), t, run = 0.7f, armR = r, armL = l, carry = 1, wrench = false) }
        e < 4.2f -> P(standX - 20 * u, t, armR = 120f, armL = 100f, grin = 0.5f, wrench = false)
        e < 7.0f -> { // leze po štaflích – schod po schodu, trochu se třese
            val k = (e - 4.2f) / 0.9f
            val n = min(3f, kotlin.math.floor(k) + ez(k - kotlin.math.floor(k)))
            P(lp(standX - 20 * u, standX, sg(e, 4.2f, 4.8f)) + 1.5f * u * sin(t * 30f) * (if (e < 6.9f) 1f else 0f), t,
                run = if (k - kotlin.math.floor(k) < 0.6f && e < 6.9f) 0.35f else 0f, lift = n * step, armR = 150f, armL = 130f, grin = -0.6f)
        }
        e < 9.4f -> { // utahuje ventil
            val ph = ((e - 7.0f) % 0.6f) / 0.6f
            P(standX + 4 * u, t, lift = 3 * step, armR = 120f + 40f * sin(ph * 2 * PI.toFloat()), armL = 140f, lean = 0.15f, grin = if (e > 9f) 1f else -0.4f)
        }
        e < 10.6f -> P(standX + 4 * u + 3 * u * sin(e * 18f), t, lift = 3 * step, armR = 160f, armL = -160f, grin = -0.9f, tilt = 8f * sin(e * 18f)) // vrávorá
        e < 12.8f -> { // visí na trubce a kope nohama
            P(px - 26 * u + 2 * u * sin(t * 4f), t, lift = 3 * step + 18 * u, armR = 175f, armL = 170f, run = 0.8f, grin = -0.9f, tilt = 4f * sin(t * 3f), wrench = false)
        }
        e < 13.6f -> { // pád dolů
            val k = sg(e, 12.8f, 13.15f)
            P(px - 26 * u, t, lift = (3 * step + 18 * u) * (1 - k * k), sit = sg(e, 13.0f, 13.3f), armR = 160f, armL = -160f, closed = true, tilt = -20f * k)
        }
        e < 14.6f -> P(px - 26 * u, t, sit = 1f - sg(e, 14.0f, 14.6f), dazed = 1f - sg(e, 13.6f, 14.6f), wrench = false)
        e < 15.4f -> P(px - 26 * u, t, thumb = true, grin = 1f, wink = true, armR = 150f, carry = 1, wrench = false)
        else -> { val (r, l) = walkArms(t); P(lp(px - 26 * u, w + 80 * u, ez(sg(e, 15.4f, 18.5f))), t, run = 0.6f, armR = r, armL = l, carry = 1, grin = 0.5f, wrench = false) }
    }
}

// ---------------------------------------------------------------------------------------------
// 4) Odpad pod dřezem
// ---------------------------------------------------------------------------------------------

private fun DrawScope.actSink(e: Float, t: Float, floorY: Float, u: Float, fade: Float, sm: PoseSmoother) {
    val w = size.width
    val cx = w * 0.64f
    val cw = 100 * u
    val ch = 64 * u
    val left = cx - cw / 2
    val top = floorY - ch
    val doorsOpen = e in 3.6f..14f
    withAlpha(fade) {
        // pracovní deska, dřez a baterie
        drawRoundRect(Color(0xFF241A14), Offset(left - 6 * u, top - 9 * u), Size(cw + 12 * u, 10 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE9EEF3), Color(0xFFAFBBC7))), Offset(left - 5 * u, top - 8 * u), Size(cw + 10 * u, 8 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
        val tap = Path().apply {
            moveTo(cx + 10 * u, top - 8 * u); lineTo(cx + 10 * u, top - 30 * u)
            cubicTo(cx + 10 * u, top - 40 * u, cx - 6 * u, top - 40 * u, cx - 6 * u, top - 30 * u)
            lineTo(cx - 6 * u, top - 24 * u)
        }
        drawPath(tap, Color(0xFF241A14), style = Stroke(6 * u, cap = StrokeCap.Round))
        drawPath(tap, Color(0xFFDCE4EC), style = Stroke(4 * u, cap = StrokeCap.Round))
        // skříňka – vnitřek
        drawRect(Color(0xFF14202E), Offset(left, top), Size(cw, ch))
        // sifon
        val sif = Path().apply {
            moveTo(cx - 4 * u, top); lineTo(cx - 4 * u, top + 20 * u)
            cubicTo(cx - 4 * u, top + 34 * u, cx + 16 * u, top + 34 * u, cx + 16 * u, top + 20 * u)
            lineTo(cx + 16 * u, top + 14 * u); lineTo(cx + 40 * u, top + 14 * u)
        }
        val siphonOff = e in 10.0f..14f
        if (!siphonOff) {
            drawPath(sif, Color(0xFF241A14), style = Stroke(8 * u, cap = StrokeCap.Round))
            drawPath(sif, Color(0xFFCAD3DC), style = Stroke(6 * u, cap = StrokeCap.Round))
        }
        // kapky ze sifonu (padají Kutilovi na bříško)
        if (e in 5f..10f) for (i in 0 until 2) {
            val q = ((t * 1.1f + i * 0.5f) % 1f)
            drawDrop(Offset(cx + 6 * u - 30 * u * 0, top + 32 * u + 24 * u * q * q), 2f * u, 1.2f)
        }
        // vylitá voda
        if (e in 10f..15f) {
            val k = sg(e, 10f, 11f)
            drawOval(Brush.radialGradient(listOf(Color(0x9954B6FF), Color.Transparent), center = Offset(cx - 30 * u, floorY), radius = 80 * u * k), Offset(cx - 110 * u * k, floorY - 4 * u), Size(160 * u * k, 9 * u))
            if (e < 10.8f) for (i in 0 until 8) {
                val q = sg(e, 10f, 10.8f)
                drawDrop(Offset(cx - 10 * u - i * 9 * u * q, top + 25 * u - 30 * u * q * (1 - q) * 3 + i * u), 2.4f * u, 1f)
            }
        }
    }
    // Kutil (při práci leží s hlavou ve skříňce)
    drawPlumber(sm.smooth(poseSink(e, t, left, cx, w, u), t, e), floorY, u)
    withAlpha(fade) {
        // korpus a otevřená dvířka přes postavu (hlava je „uvnitř“)
        val wall = Color(0xFFDCE4EC)
        drawRect(Color(0xFF241A14), Offset(left - 2 * u, top), Size(cw + 4 * u, ch), style = Stroke(4 * u))
        drawRect(wall, Offset(left - 1 * u, top + 1 * u), Size(6 * u, ch - 2 * u))
        drawRect(wall, Offset(left + cw - 5 * u, top + 1 * u), Size(6 * u, ch - 2 * u))
        drawRect(Color(0xFF9AA8B8), Offset(left, floorY - 6 * u), Size(cw, 6 * u))
        if (doorsOpen) {
            for (side in listOf(-1, 1)) {
                val hx = if (side < 0) left else left + cw
                val p = Path().apply {
                    moveTo(hx, top + 2 * u); lineTo(hx + side * 22 * u, top + 8 * u)
                    lineTo(hx + side * 22 * u, floorY - 4 * u); lineTo(hx, floorY - 6 * u); close()
                }
                drawPath(p, Brush.horizontalGradient(listOf(Color(0xFFF2F5F8), Color(0xFFC3CDD8))))
                drawPath(p, Color(0xFF241A14), style = Stroke(1.6f * u))
                drawCircle(Color(0xFF55606C), 1.6f * u, Offset(hx + side * 18 * u, top + 30 * u))
            }
        } else {
            for (i in 0..1) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFF2F5F8), Color(0xFFC3CDD8))), Offset(left + i * cw / 2 + 2 * u, top + 2 * u), Size(cw / 2 - 4 * u, ch - 10 * u))
                drawRect(Color(0xFF241A14), Offset(left + i * cw / 2 + 2 * u, top + 2 * u), Size(cw / 2 - 4 * u, ch - 10 * u), style = Stroke(1.6f * u))
            }
        }
    }
}

private fun poseSink(e: Float, t: Float, left: Float, cx: Float, w: Float, u: Float): Pose {
    // ležení na zádech: obráceně otočený a překlopený o 90° → hlava doprava, bříško nahoru
    val lieX = left + 26 * u - 62 * u
    return when {
        e < 3.2f -> { val (r, l) = walkArms(t); P(lp(-60 * u, left - 40 * u, ez(sg(e, 0.3f, 3.2f))), t, run = 0.7f, armR = r, armL = l) }
        e < 4.2f -> P(left - 40 * u, t, armR = 110f, lean = 0.3f, grin = 0.3f)
        e < 5.0f -> { // lehne si
            val k = ez(sg(e, 4.2f, 5.0f))
            P(lp(left - 40 * u, lieX, k), t, facing = -1f, tilt = -90f * k, lift = 13 * u * k, armR = 60f)
        }
        e < 10.0f -> { // pracuje pod dřezem, kope nohou, kape mu na bříško
            P(lieX + 1 * u * sin(t * 2f), t, facing = -1f, tilt = -90f, lift = 13 * u, armR = 150f + 25f * sin(t * 8f), armL = 30f + 8f * sin(t * 3f),
                run = if ((t % 3f) < 0.8f) 0.35f else 0f, wet = e > 6f)
        }
        e < 10.6f -> { // sifon povolí – lekne se a bouchne hlavou
            P(lieX + 4 * u * sin(e * 30f), t, facing = -1f, tilt = -90f + 12f * sin(sg(e, 10f, 10.6f) * PI.toFloat()), lift = 13 * u, armR = 170f, armL = 170f, wet = true, closed = true)
        }
        e < 11.8f -> { // vyleze ven a sedne si, hvězdičky
            val k = ez(sg(e, 10.6f, 11.6f))
            P(lp(lieX, left - 50 * u, k), t, facing = -1f, tilt = lp(-90f, 0f, k), lift = 13 * u * (1 - k), sit = k, dazed = 1f, wet = true)
        }
        e < 13.2f -> P(left - 50 * u, t, sit = 1f - sg(e, 12.2f, 13.0f), dazed = 1f - sg(e, 11.8f, 13.2f), wet = true, armR = 150f, wrench = false)
        e < 14.2f -> P(left - 50 * u, t, thumb = true, grin = 1f, wink = true, armR = 150f, wet = true, wrench = false)
        else -> { val (r, l) = walkArms(t); P(lp(left - 50 * u, -80 * u, ez(sg(e, 14.2f, 17.5f))), t, facing = -1f, run = 0.6f, armR = r, armL = l, wet = true, grin = 0.4f) }
    }
}

// ---------------------------------------------------------------------------------------------
// 5) U vany – usne a vzbudí ho kachnička
// ---------------------------------------------------------------------------------------------

private fun DrawScope.actTub(e: Float, t: Float, floorY: Float, u: Float, fade: Float, sm: PoseSmoother) {
    val w = size.width
    val tx = w * 0.66f
    val tw = 150 * u
    val th = 50 * u
    val left = tx - tw / 2
    val top = floorY - 12 * u - th
    // kachnička: plave ve vaně, pak skočí Kutilovi na záda
    val duckHome = Offset(tx + 20 * u, top - 4 * u + 1.5f * u * sin(t * 2.5f))
    val pose = sm.smooth(poseTub(e, t, left, w, u), t, e)
    withAlpha(fade) {
        // vana na nožičkách
        for (fx in listOf(left + 18 * u, left + tw - 18 * u)) {
            val foot = Path().apply {
                moveTo(fx - 6 * u, floorY - 13 * u); cubicTo(fx - 8 * u, floorY - 4 * u, fx - 3 * u, floorY, fx + 3 * u, floorY)
                lineTo(fx + 6 * u, floorY - 13 * u); close()
            }
            drawPath(foot, Color(0xFFE0B04A)); drawPath(foot, Color(0xFF241A14), style = Stroke(1.4f * u))
        }
        val tub = Path().apply {
            moveTo(left, top)
            lineTo(left + tw, top)
            cubicTo(left + tw, top + th * 0.7f, left + tw - 16 * u, top + th, left + tw - 34 * u, top + th)
            lineTo(left + 34 * u, top + th)
            cubicTo(left + 16 * u, top + th, left, top + th * 0.7f, left, top)
            close()
        }
        drawPath(tub, Brush.verticalGradient(listOf(Color.White, Color(0xFFC9D4DF)), startY = top, endY = top + th))
        drawPath(tub, Color(0xFF241A14), style = Stroke(1.8f * u))
        drawRoundRect(Color(0xFFEFF3F7), Offset(left - 4 * u, top - 4 * u), Size(tw + 8 * u, 7 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.5f * u))
        drawRoundRect(Color(0xFF241A14), Offset(left - 4 * u, top - 4 * u), Size(tw + 8 * u, 7 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.5f * u), style = Stroke(1.6f * u))
        // pěna
        for (i in 0 until 9) {
            val bx = left + 14 * u + i * 15 * u
            val r = (5f + 2f * sin(t * 2f + i)) * u
            drawCircle(Color.White, r, Offset(bx, top - 4 * u - (i % 3) * 2 * u))
            drawCircle(Color(0xFFB8D8F0), r, Offset(bx, top - 4 * u - (i % 3) * 2 * u), style = Stroke(1f * u))
        }
        // sprcha
        drawLine(Color(0xFF241A14), Offset(left + tw - 10 * u, top - 4 * u), Offset(left + tw - 10 * u, top - 64 * u), strokeWidth = 5 * u, cap = StrokeCap.Round)
        drawLine(Color(0xFFDCE4EC), Offset(left + tw - 10 * u, top - 4 * u), Offset(left + tw - 10 * u, top - 64 * u), strokeWidth = 3 * u, cap = StrokeCap.Round)
        drawArc(Color(0xFFDCE4EC), 180f, 180f, true, Offset(left + tw - 26 * u, top - 72 * u), Size(22 * u, 14 * u))
        // kachnička
        when {
            e < 12.0f -> drawDuck(duckHome, 1f * u, -1f)
            e < 12.3f -> {
                val k = sg(e, 12.0f, 12.3f)
                val target = Offset(pose.x + 30 * u, floorY - 38 * u)
                drawDuck(Offset(lp(duckHome.x, target.x, k), lp(duckHome.y, target.y, k) - 40 * u * sin(k * PI.toFloat())), 1f * u, -1f)
            }
            e < 13.4f -> drawDuck(Offset(pose.x + 30 * u, floorY - 30 * u + 60 * u * ez(sg(e, 12.3f, 12.8f)) * 0 - 22 * u * sin(sg(e, 12.3f, 13.4f) * PI.toFloat()) + 22 * u * sg(e, 12.6f, 13.4f)), 1f * u, 1f)
        }
    }
    drawPlumber(pose, floorY, u)
}

internal fun DrawScope.drawDuck(c: Offset, s: Float, facing: Float) {
    val f = facing
    val body = Path().apply {
        moveTo(c.x - 10 * s * f, c.y)
        cubicTo(c.x - 13 * s * f, c.y - 8 * s, c.x - 6 * s * f, c.y - 5 * s, c.x - 3 * s * f, c.y - 5 * s)
        cubicTo(c.x + 4 * s * f, c.y - 6 * s, c.x + 10 * s * f, c.y - 4 * s, c.x + 9 * s * f, c.y + 2 * s)
        cubicTo(c.x + 8 * s * f, c.y + 7 * s, c.x - 7 * s * f, c.y + 7 * s, c.x - 10 * s * f, c.y)
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(Color(0xFFFFE066), Color(0xFFF2B705)), startY = c.y - 8 * s, endY = c.y + 7 * s))
    drawPath(body, Color(0xFF241A14), style = Stroke(1.2f * s))
    val head = Offset(c.x + 5 * s * f, c.y - 9 * s)
    drawCircle(Color(0xFFFFE066), 5.5f * s, head)
    drawCircle(Color(0xFF241A14), 5.5f * s, head, style = Stroke(1.2f * s))
    val beak = Path().apply {
        moveTo(head.x + 4 * s * f, head.y - 1 * s); lineTo(head.x + 10 * s * f, head.y + 0.5f * s); lineTo(head.x + 4 * s * f, head.y + 2.5f * s); close()
    }
    drawPath(beak, Color(0xFFF2922A)); drawPath(beak, Color(0xFF241A14), style = Stroke(1f * s))
    drawCircle(Color(0xFF241A14), 1.2f * s, Offset(head.x + 1.5f * s * f, head.y - 1.5f * s))
    drawCircle(Color.White, 0.5f * s, Offset(head.x + 1.2f * s * f, head.y - 1.9f * s))
}

internal fun DrawScope.drawZ(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x - r, c.y - r); lineTo(c.x + r, c.y - r); lineTo(c.x - r, c.y + r); lineTo(c.x + r, c.y + r)
    }
    drawPath(p, color, style = Stroke(r * 0.35f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

private fun poseTub(e: Float, t: Float, left: Float, w: Float, u: Float): Pose {
    // ležení na břiše: hlava doprava k vaně, ruka šmátrá pod vanou
    val lieX = left + 8 * u - 62 * u
    return when {
        e < 3.2f -> { val (r, l) = walkArms(t); P(lp(-60 * u, left - 46 * u, ez(sg(e, 0.3f, 3.2f))), t, run = 0.7f, armR = r, armL = l, grin = 0.3f) }
        e < 4.2f -> { val k = ez(sg(e, 3.2f, 4.2f)); P(lp(left - 46 * u, lieX, k), t, tilt = 90f * k, lift = 20 * u * k, armR = 90f + 90f * k) }
        e < 8.0f -> P(lieX, t, tilt = 90f, lift = 20 * u, armR = 180f + 10f * sin(t * 7f), armL = 160f, run = if ((t % 2.5f) < 0.7f) 0.3f else 0f, grin = -0.4f)
        e < 12.2f -> P(lieX, t, tilt = 90f, lift = 20 * u, armR = 170f, armL = 150f, closed = true, zzz = true, wrench = false) // usnul a chrápe
        e < 13.4f -> { // kachnička! vyskočí leknutím
            val k = sg(e, 12.2f, 13.0f)
            P(lieX + 40 * u * k, t, tilt = lp(90f, 0f, ez(k)), lift = lp(20 * u, 0f, k) + 34 * u * sin(k * PI.toFloat()), armR = 170f, armL = -170f, capLift = 24 * u * sin(k * PI.toFloat()), grin = -1f, wrench = false)
        }
        e < 14.0f -> P(lieX + 40 * u, t, armR = 40f, grin = -0.3f, wrench = false)
        e < 15.4f -> P(lieX + 40 * u, t, carry = 2, grin = 1f, wink = e > 14.5f, armR = 60f, wrench = false) // usměje se na kachničku
        else -> { val (r, l) = walkArms(t); P(lp(lieX + 40 * u, w + 80 * u, ez(sg(e, 15.4f, 19f))), t, run = 0.6f, armR = 60f, armL = l, carry = 2, grin = 0.7f, wrench = false) }
    }
}

// ---------------------------------------------------------------------------------------------
// Po práci: křeslo, televize, pivo – a od osmi večer spánek
// ---------------------------------------------------------------------------------------------

private fun DrawScope.actLounge(e: Float, t: Float, floorY: Float, u: Float, sleep: Boolean, sm: PoseSmoother) {
    val w = size.width
    val chairX = w * 0.34f
    val tvX = w * 0.76f
    // televize na stolku
    val tvW = 84 * u
    val tvH = 56 * u
    val tvTop = floorY - 40 * u - tvH
    drawRoundRect(Color(0xFF241A14), Offset(tvX - 40 * u, floorY - 40 * u), Size(80 * u, 40 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * u))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF8A5A36), Color(0xFF5E3A20))), Offset(tvX - 38 * u, floorY - 38 * u), Size(76 * u, 37 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
    drawLine(Color(0xFF3E2716), Offset(tvX - 30 * u, floorY - 20 * u), Offset(tvX + 30 * u, floorY - 20 * u), strokeWidth = 1.5f * u)
    drawRoundRect(Color(0xFF15191F), Offset(tvX - tvW / 2, tvTop), Size(tvW, tvH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5 * u))
    val scr = Rect(tvX - tvW / 2 + 5 * u, tvTop + 5 * u, tvX + tvW / 2 - 5 * u, tvTop + tvH - 5 * u)
    if (!sleep) {
        // hokej: led, mantinely, červená a modré čáry, kruhy, hráči s hokejkami a puk; obrazovka trochu bliká
        val flick = 0.92f + 0.08f * sin(t * 23f)
        drawRect(Brush.verticalGradient(listOf(Color(0xFFEAF4FB).copy(alpha = flick), Color(0xFFC9DCEA))), scr.topLeft, scr.size)
        // mantinel s reklamním pruhem
        drawRect(Color(0xFF1E3A5F), scr.topLeft, Size(scr.width, 4 * u))
        drawRect(Color(0xFFFFC107), Offset(scr.left + 8 * u, scr.top + 1 * u), Size(14 * u, 2 * u))
        drawRect(Color(0xFFE53935), Offset(scr.left + 30 * u, scr.top + 1 * u), Size(14 * u, 2 * u))
        drawLine(Color(0xFFD32F2F).copy(alpha = 0.8f), Offset(scr.center.x, scr.top + 4 * u), Offset(scr.center.x, scr.bottom), strokeWidth = 1.4f * u)
        for (k in listOf(-1, 1)) {
            val lx = scr.center.x + k * scr.width * 0.22f
            drawLine(Color(0xFF1565C0).copy(alpha = 0.8f), Offset(lx, scr.top + 4 * u), Offset(lx, scr.bottom), strokeWidth = 1.4f * u)
            // brankové kruhy a branky
            drawCircle(Color(0xFFD32F2F).copy(alpha = 0.55f), 6 * u, Offset(scr.center.x + k * scr.width * 0.36f, scr.center.y + 2 * u), style = Stroke(0.8f * u))
            val gx = scr.center.x + k * scr.width * 0.45f
            drawRect(Color(0xFFD32F2F), Offset(gx - 1.5f * u, scr.center.y - 3 * u), Size(3 * u, 6 * u), style = Stroke(0.8f * u))
        }
        drawCircle(Color(0xFF1565C0).copy(alpha = 0.6f), 7 * u, Offset(scr.center.x, scr.center.y + 2 * u), style = Stroke(0.8f * u))
        // puk kličkuje po ledu
        val bx = scr.left + scr.width * (0.5f + 0.40f * sin(t * 1.3f))
        val by = scr.top + 4 * u + (scr.height - 4 * u) * (0.55f + 0.32f * sin(t * 2.3f))
        for (i in 0 until 4) {
            val home = i % 2 == 0
            val px = scr.left + scr.width * (0.5f + 0.38f * sin(t * 1.3f - 0.35f - i * 0.45f))
            val py = scr.top + 4 * u + (scr.height - 4 * u) * (0.25f + 0.17f * i)
            val dir = if (bx > px) 1f else -1f
            drawLine(Color(0xFF3E2716), Offset(px, py + 1 * u), Offset(px + dir * 3.5f * u, py + 3.5f * u), strokeWidth = 0.9f * u) // hokejka
            drawCircle(if (home) Color(0xFFD32F2F) else Color(0xFF1565C0), 2.2f * u, Offset(px, py))
            drawCircle(Color(0xFF263238), 1.0f * u, Offset(px, py - 1.6f * u)) // helma
        }
        drawCircle(Color(0xFF111111), 1.5f * u, Offset(bx, by))
        if (e in 17.6f..19.6f) { // GÓL – obrazovka bliká
            drawRect(Color.White.copy(alpha = 0.35f * abs(sin(t * 14f))), scr.topLeft, scr.size)
        }
        // záře obrazovky do pokoje
        drawCircle(Brush.radialGradient(listOf(Color(0x3360C0FF), Color.Transparent), center = scr.center, radius = 140 * u), 140 * u, scr.center)
    } else {
        drawRect(Brush.linearGradient(listOf(Color(0xFF1C232C), Color(0xFF0E1116)), start = scr.topLeft, end = scr.bottomRight), scr.topLeft, scr.size)
        drawLine(Color.White.copy(alpha = 0.12f), Offset(scr.left + 6 * u, scr.top + 4 * u), Offset(scr.left + 24 * u, scr.top + 22 * u), strokeWidth = 3 * u)
        drawCircle(Color(0xFFE53935), 1.4f * u, Offset(tvX + tvW / 2 - 8 * u, tvTop + tvH - 3 * u)) // pohotovostní kontrolka
    }
    // křeslo – zadní opěradlo
    val chair = Color(0xFF9C3B2E)
    val chairDark = Color(0xFF6A241B)
    drawRoundRect(Color(0xFF241A14), Offset(chairX - 44 * u, floorY - 86 * u), Size(30 * u, 80 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12 * u))
    drawRoundRect(Brush.horizontalGradient(listOf(chairDark, chair)), Offset(chairX - 42 * u, floorY - 84 * u), Size(26 * u, 76 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(11 * u))
    // Kutil sedí
    val pose = sm.smooth(poseLounge(e, t, chairX, u, sleep), t, e)
    drawPlumber(pose, floorY - 14 * u, u)
    // sedák a područka přes nohy
    drawRoundRect(Color(0xFF241A14), Offset(chairX - 44 * u, floorY - 30 * u), Size(70 * u, 26 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(9 * u))
    drawRoundRect(Brush.verticalGradient(listOf(chair, chairDark)), Offset(chairX - 42 * u, floorY - 28 * u), Size(66 * u, 22 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8 * u))
    drawRoundRect(Color(0xFF241A14), Offset(chairX - 44 * u, floorY - 6 * u), Size(70 * u, 6 * u))
    for (fx in listOf(chairX - 36 * u, chairX + 16 * u)) drawRect(Color(0xFF3E2716), Offset(fx, floorY - 2 * u), Size(5 * u, 4 * u))
    // prázdné láhve u křesla (přibývají během večera)
    val bottles = if (sleep) 4 else 2
    for (i in 0 until bottles) {
        val bx = chairX + 34 * u + i * 9 * u
        val lying = sleep && i == bottles - 1
        rotate(if (lying) 90f else 0f, Offset(bx, floorY - 3 * u)) {
            drawRoundRect(Color(0xFF241A14), Offset(bx - 3.5f * u, floorY - 20 * u), Size(7 * u, 20 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * u))
            drawRoundRect(Color(0xFF6B4A12), Offset(bx - 2.5f * u, floorY - 19 * u), Size(5 * u, 18 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * u))
            drawRect(Color(0xFFE8D8A8), Offset(bx - 2.5f * u, floorY - 12 * u), Size(5 * u, 5 * u))
        }
    }
}

private fun poseLounge(e: Float, t: Float, chairX: Float, u: Float, sleep: Boolean): Pose {
    val x = chairX - 10 * u
    if (sleep) {
        // spí s pootevřenou pusou, hlava mu padá, ruka s korbelem na bříšku
        return P(x, t, sit = 1f, tilt = -10f + 2f * sin(t * 0.8f), lean = -0.15f, armR = 30f, armL = 10f, closed = true, zzz = true, grin = 0f, carry = 3)
    }
    return when {
        e < 3f -> P(x, t, sit = 1f, armR = 35f, armL = 15f, grin = 0.3f, carry = 3)
        e < 5.5f -> { // napije se
            val k = sin(sg(e, 3f, 5.5f) * PI.toFloat())
            P(x, t, sit = 1f, armR = lp(35f, 165f, k), armL = 15f, tilt = -8f * k, grin = 0f, carry = 3)
        }
        e < 9.0f -> P(x, t, sit = 1f, armR = 35f, armL = 15f, grin = 0.5f, carry = 3)
        e < 10.2f -> P(x, t + 0f, sit = 1f, armR = 40f, armL = 15f, tilt = 4f * sin(t * 30f) * (1 - sg(e, 9.2f, 10.2f)), grin = 1f, carry = 3) // krk
        e < 13f -> P(x, t, sit = 1f, armR = 35f, armL = 55f + 10f * sin(t * 8f), grin = 0.6f, carry = 3) // drbe se na bříšku
        e < 15.5f -> P(x, t, sit = 1f, armR = 35f, armL = 15f, tilt = 3f * sin(t * 22f), grin = 1f, carry = 3) // směje se
        e < 17.6f -> P(x, t, sit = 1f, armR = 40f, armL = 20f, grin = -0.5f, lean = 0.2f, carry = 3) // napjatě sleduje
        e < 19.6f -> P(x, t, sit = 1f - 0.4f * sin(sg(e, 17.6f, 19.6f) * PI.toFloat()), armR = 170f, armL = -165f, grin = 1f, carry = 3) // GÓÓÓL!
        e < 22f -> { val k = sin(sg(e, 19.8f, 22f) * PI.toFloat()); P(x, t, sit = 1f, armR = lp(35f, 165f, k), armL = 15f, tilt = -8f * k, carry = 3) }
        else -> P(x, t, sit = 1f, armR = 35f, armL = 15f, grin = 0.4f, carry = 3)
    }
}
