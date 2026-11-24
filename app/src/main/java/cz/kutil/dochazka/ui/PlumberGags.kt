package cz.kutil.dochazka.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// =============================================================================================
// Další Kutilovy scénky (podle námětů z balíku „loutkové gagy“), kreslené stejným Kutilem.
// Časy v sekundách od začátku scénky; zvuky k nim jsou v PlumberStage.eventsOf().
// =============================================================================================

private const val PIf = PI.toFloat()

// ---------------------------------------------------------------------------------------------
// pomocné kreslení
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.drawHPipe(pipes: PipeImages, x0: Float, x1: Float, y: Float, d: Float) {
    drawImage(
        pipes.h, IntOffset.Zero, IntSize(pipes.h.width, pipes.h.height),
        IntOffset(x0.toInt(), y.toInt()), IntSize((x1 - x0).toInt().coerceAtLeast(1), d.toInt()),
    )
}

private fun DrawScope.drawClamp(pipes: PipeImages, cx: Float, y: Float, d: Float) {
    val k = d / 64f
    drawImage(
        pipes.clampH, IntOffset.Zero, IntSize(pipes.clampH.width, pipes.clampH.height),
        IntOffset((cx - pipes.clampH.width * k / 2).toInt(), (y + d / 2 - pipes.clampH.height * k / 2).toInt()),
        IntSize((pipes.clampH.width * k).toInt(), (pipes.clampH.height * k).toInt()),
    )
}

private fun bez(a: Offset, c: Offset, b: Offset, q: Float): Offset {
    val m = 1f - q
    return Offset(m * m * a.x + 2 * m * q * c.x + q * q * b.x, m * m * a.y + 2 * m * q * c.y + q * q * b.y)
}

/** Proud vody z [from] do [to] (mírný oblouk), s kapkami a odstřikem v cíli. [k] = síla 0..1. */
internal fun DrawScope.drawJet(from: Offset, to: Offset, k: Float, t: Float, u: Float, arc: Float = 12f) {
    if (k <= 0.02f) return
    val c = Offset((from.x + to.x) / 2, min(from.y, to.y) - arc * u * k)
    val path = Path().apply {
        moveTo(from.x, from.y)
        cubicTo(from.x + (c.x - from.x) * 0.66f, from.y + (c.y - from.y) * 0.66f, to.x + (c.x - to.x) * 0.66f, to.y + (c.y - to.y) * 0.66f, to.x, to.y)
    }
    drawPath(path, Color(0x5554B6FF), style = Stroke(8f * k * u, cap = StrokeCap.Round))
    drawPath(path, Color(0xCCDDF2FF), style = Stroke(3.8f * k * u, cap = StrokeCap.Round))
    drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(1.1f * u, cap = StrokeCap.Round))
    for (i in 0 until 7) {
        val q = (t * 2.8f + i / 7f) % 1f
        val p = bez(from, c, to, q)
        drawCircle(Color.White.copy(alpha = 0.8f * k), 1.4f * u, Offset(p.x, p.y + 2.5f * u * sin(i * 1.7f + t * 9f)))
    }
    for (i in 0 until 8) {
        val q = (t * 3.1f + i * 0.125f) % 1f
        val a = i * 0.8f + 2.2f
        drawCircle(Color(0xCCBFE6FF).copy(alpha = (1f - q) * k), 1.7f * u, Offset(to.x + cos(a) * 18 * u * q, to.y + sin(a) * 10 * u * q - 10 * u * q * (1 - q) * 3))
    }
}

private fun DrawScope.drawPuddle(c: Offset, r: Float, u: Float) {
    if (r <= 1f) return
    drawOval(Brush.radialGradient(listOf(Color(0x9954B6FF), Color(0x3340A0FF), Color.Transparent), center = c, radius = r), Offset(c.x - r, c.y - 5 * u), Size(r * 2, 10 * u))
}

/** Mosazný ventil s červeným kolečkem z boku; [spin] otáčí paprsky kolečka (stupně). */
private fun DrawScope.drawValve(c: Offset, u: Float, spin: Float) {
    drawRoundRect(Outline, Offset(c.x - 7 * u, c.y - 9 * u), Size(14 * u, 18 * u), cornerRadius = CornerRadius(3 * u))
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFB8860B), Color(0xFFF6D365), Color(0xFFA87308)), startX = c.x - 6 * u, endX = c.x + 6 * u), Offset(c.x - 6 * u, c.y - 8 * u), Size(12 * u, 16 * u), cornerRadius = CornerRadius(2.5f * u))
    drawLine(Outline, Offset(c.x, c.y - 9 * u), Offset(c.x, c.y - 17 * u), strokeWidth = 4 * u)
    drawLine(Color(0xFFC9D1D9), Offset(c.x, c.y - 9 * u), Offset(c.x, c.y - 17 * u), strokeWidth = 2 * u)
    val wc = Offset(c.x, c.y - 19 * u)
    drawOval(Outline, Offset(wc.x - 13 * u, wc.y - 4.5f * u), Size(26 * u, 9 * u), style = Stroke(5 * u))
    drawOval(Color(0xFFD63A2F), Offset(wc.x - 13 * u, wc.y - 4.5f * u), Size(26 * u, 9 * u), style = Stroke(3 * u))
    val a = spin / 180f * PIf
    for (k in 0 until 2) {
        val ang = a + k * PIf / 2
        drawLine(Color(0xFFD63A2F), Offset(wc.x - 12 * u * cos(ang), wc.y - 4 * u * sin(ang)), Offset(wc.x + 12 * u * cos(ang), wc.y + 4 * u * sin(ang)), strokeWidth = 2 * u)
    }
}

private fun headOf(x: Float, facing: Float, floorY: Float, u: Float, lift: Float = 0f, sit: Float = 0f) =
    Offset(x + 4 * u * facing, floorY - 74 * u - lift + sit * 12 * u)

// ---------------------------------------------------------------------------------------------
// 6) Utržený ventil – kolečko zůstane v ruce, voda mu sestřelí čepici
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.actValve(e: Float, t: Float, floorY: Float, u: Float, fade: Float, pipes: PipeImages, sm: PoseSmoother) {
    val w = size.width
    val d = 16 * u
    val pipeY = floorY - 70 * u
    val x0 = w * 0.6f
    val standX = x0 - 38 * u
    val valveFloor = Offset(x0 - 100 * u, floorY - 9 * u)
    val onPipe = e < 6.0f || e >= 11.4f
    withAlpha(fade) {
        drawHPipe(pipes, x0, w + 4 * u, pipeY, d)
        drawClamp(pipes, w * 0.9f, pipeY, d)
        val wet = sg(e, 6.0f, 8f) * (1f - 0.5f * sg(e, 14f, 17f))
        drawPuddle(Offset(standX - 10 * u, floorY + 2 * u), 90 * u * wet, u)
        if (onPipe) {
            val spin = when {
                e in 3f..6f -> (e - 3f) * 160f
                e in 11.4f..13f -> -(e - 11.4f) * 260f
                else -> 0f
            }
            drawValve(Offset(x0 - 6 * u, pipeY + d / 2), u, spin)
        } else if (e < 10.6f) {
            // ventil odletí obloukem a spadne na podlahu
            val k = sg(e, 6.0f, 6.8f)
            val p = Offset(lp(x0 - 6 * u, valveFloor.x, k), lp(pipeY + d / 2, valveFloor.y, k * k) - 46 * u * sin(k * PIf))
            rotate(k * 290f, p) { drawValve(p, u, 0f) }
        }
        // ústí trubky bez ventilu
        if (!onPipe) drawOval(Color(0xFF14202E), Offset(x0 - 2 * u, pipeY + 3 * u), Size(5 * u, d - 6 * u))
    }
    val pose = poseValve(e, t, standX, valveFloor.x, w, u)
    val p = sm.smooth(pose, t, e)
    drawPlumber(p, floorY, u)
    val nozzle = Offset(x0, pipeY + d / 2)
    val head = headOf(p.x, p.facing, floorY, u, p.lift, p.sit)
    when {
        e in 6.0f..8.6f -> drawJet(nozzle, Offset(head.x + 6 * u, head.y + 4 * u), min(1f, (e - 6f) / 0.15f), t, u, arc = 4f)
        e in 8.6f..9.8f -> { // palec na díře: voda prská kolem
            drawJet(Offset(nozzle.x - 3 * u, nozzle.y - 4 * u), Offset(nozzle.x - 22 * u, nozzle.y - 34 * u), 0.3f, t, u, arc = 2f)
            drawJet(Offset(nozzle.x - 3 * u, nozzle.y + 4 * u), Offset(nozzle.x - 30 * u, floorY - 2 * u), 0.3f, t, u, arc = 0f)
        }
        e in 9.8f..11.4f -> drawJet(nozzle, Offset(p.x + 6 * u, floorY - 50 * u - p.lift), 0.9f, t, u, arc = 4f) // do zad (a jinam)
        e in 13.4f..13.75f -> drawJet(Offset(x0 - 6 * u, pipeY - 20 * u), Offset(head.x, head.y - 4 * u), 0.45f, t, u, arc = 10f)
    }
}

private fun poseValve(e: Float, t: Float, standX: Float, valveX: Float, w: Float, u: Float): Pose = when {
    e < 3f -> { val (r, l) = walkArms(t); P(lp(-60 * u, standX, ez(sg(e, 0.2f, 3f))), t, run = 0.65f, armR = r, armL = l, grin = 0.4f) }
    e < 6f -> { // točí kolečkem, jde to ztuha
        val ph = ((e - 3f) % 0.75f) / 0.75f
        P(standX + 1.2f * u * sin(t * 26f), t, armR = 105f + 30f * sin(ph * 2 * PIf), armL = 60f, lean = 0.2f, grin = -0.6f, wrench = false)
    }
    e < 8.6f -> { // proud do obličeje, čepice letí
        val k = sg(e, 6f, 6.6f)
        P(lp(standX, standX - 24 * u, k) + 1.5f * u * sin(t * 30f), t, tilt = -12f + 4f * sin(t * 13f), armR = 160f + 20f * sin(t * 20f), armL = -150f - 20f * sin(t * 17f),
            closed = true, wet = true, grin = -0.9f, capLift = 80 * u * sin(sg(e, 6f, 8.4f) * PIf), capSpin = (e - 6f) * 520f, wrench = false)
    }
    e < 9.8f -> P(lp(standX - 24 * u, standX + 6 * u, ez(sg(e, 8.6f, 8.9f))), t, armR = 92f, armL = 40f, thumb = true, grin = -0.8f, wet = true, lean = 0.1f) // palec na díře
    e < 10.4f -> { val (r, l) = walkArms(t, fast = true); P(lp(standX + 6 * u, valveX + 18 * u, ez(sg(e, 9.8f, 10.4f))), t, facing = -1f, run = 0.9f, armR = r, armL = l, wet = true, grin = -0.5f) }
    e < 10.9f -> P(valveX + 18 * u, t, facing = -1f, lean = 0.85f, armR = 50f, wet = true, grin = -0.4f, wrench = false) // shýbá se pro ventil – klasika vykoukne
    e < 11.4f -> { val (r, l) = walkArms(t, fast = true); P(lp(valveX + 18 * u, standX, ez(sg(e, 10.9f, 11.4f))), t, run = 0.9f, armR = r, armL = l, wet = true) }
    e < 13f -> { val ph = ((e - 11.4f) % 0.5f) / 0.5f; P(standX, t, armR = 100f + 35f * sin(ph * 2 * PIf), armL = 70f, lean = 0.15f, wet = true, grin = 0.2f) }
    e < 13.4f -> P(standX, t, facing = -1f, thumb = true, wink = true, grin = 1f, armR = 150f, wet = true)
    e < 14.4f -> P(standX, t, facing = -1f, armR = 120f, armL = -120f, grin = -0.6f, wet = true, closed = e < 13.8f, wrench = false) // poslední cák a pokrčení ramen
    else -> { val (r, l) = walkArms(t); P(lp(standX, -80 * u, ez(sg(e, 14.4f, 17f))), t, facing = -1f, run = 0.6f, armR = r, armL = l, wet = true, grin = 0.3f) }
}

// ---------------------------------------------------------------------------------------------
// 7) Izolační páska všechno vyřeší – trubka se nafoukne jako balón a praskne
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.actTape(e: Float, t: Float, floorY: Float, u: Float, fade: Float, pipes: PipeImages, sm: PoseSmoother) {
    val w = size.width
    val d = 16 * u
    val pipeY = floorY - 82 * u
    val lx = w * 0.56f
    val standX = lx - 36 * u
    val popped = e >= 10.4f
    withAlpha(fade) {
        drawHPipe(pipes, -4 * u, w + 4 * u, pipeY, d)
        drawClamp(pipes, w * 0.12f, pipeY, d)
        drawClamp(pipes, w * 0.9f, pipeY, d)
        drawPuddle(Offset(lx, floorY + 2 * u), (30 + 80 * sg(e, 10.4f, 11f)) * u * (1f - 0.4f * sg(e, 14f, 17f)), u)
        val c = Offset(lx, pipeY + d / 2)
        if (e < 3.2f || e > 13f) for (i in 0 until 2) { // kape
            val q = (t * 1.2f + i * 0.5f) % 1f
            drawDrop(Offset(lx + 2 * u, pipeY + d + (floorY - pipeY - d) * q * q), 2.2f * u, 1.2f)
        }
        if (!popped && e >= 3f) {
            val tw = 6 * u + 18 * u * sg(e, 3f, 7f)
            val rb = d / 2 + 30 * u * ez(sg(e, 6.2f, 10.3f)) + if (e > 8.6f) 1.4f * u * sin(t * 25f) else 0f
            val rx = max(tw / 2, rb * 1.3f)
            drawOval(Outline, Offset(c.x - rx - 1.5f * u, c.y - rb - 1.5f * u), Size(rx * 2 + 3 * u, rb * 2 + 3 * u))
            drawOval(Brush.radialGradient(listOf(Color(0xFFE6EAEE), Color(0xFF9AA0A8), Color(0xFF6E757E)), center = Offset(c.x - rx * 0.3f, c.y - rb * 0.4f), radius = rx * 1.4f), Offset(c.x - rx, c.y - rb), Size(rx * 2, rb * 2))
            // pruhy pásky
            for (i in -2..2) {
                val x = c.x + i * rx * 0.35f
                drawLine(Color(0x55303A46), Offset(x, c.y - rb * 0.95f * sqrt1(1f - (i * 0.35f) * (i * 0.35f))), Offset(x + 2 * u, c.y + rb * 0.95f * sqrt1(1f - (i * 0.35f) * (i * 0.35f))), strokeWidth = 1.2f * u)
            }
            drawCircle(Color.White.copy(alpha = 0.5f), rb * 0.18f, Offset(c.x - rx * 0.45f, c.y - rb * 0.45f))
        }
        if (popped) {
            // cáry pásky a výbuch kapek
            for (s in listOf(-1, 1)) drawLine(Color(0xFF9AA0A8), Offset(c.x + s * 4 * u, c.y), Offset(c.x + s * 12 * u, c.y + 9 * u + 2 * u * sin(t * 6f + s)), strokeWidth = 3 * u, cap = StrokeCap.Round)
            val q = sg(e, 10.4f, 11.3f)
            if (q < 1f) for (i in 0 until 16) {
                val a = i / 16f * 2 * PIf
                drawCircle(Color(0xCCBFE6FF).copy(alpha = 1f - q), (2.4f - q) * u, Offset(c.x + cos(a) * 70 * u * q, c.y + sin(a) * 45 * u * q + 40 * u * q * q))
            }
        }
    }
    val p = sm.smooth(poseTape(e, t, standX, w, u), t, e)
    drawPlumber(p, floorY, u)
    if (e in 10.4f..12.2f) drawJet(Offset(lx, pipeY + d), Offset(p.x + 8 * u, floorY - 70 * u + p.sit * 12 * u), 1f - sg(e, 11.4f, 12.2f) * 0.8f, t, u, arc = 2f)
    if (e in 13.9f..14.8f) { // odhozená páska
        val q = sg(e, 13.9f, 14.6f)
        val o = Offset(lp(standX + 20 * u, w + 30 * u, q), floorY - 60 * u - 50 * u * sin(q * PIf) + 50 * u * q)
        drawCircle(Outline, 6.4f * u, o, style = Stroke(4.6f * u)); drawCircle(Color(0xFFA9AFB7), 6.4f * u, o, style = Stroke(3f * u))
    }
}

private fun sqrt1(v: Float) = kotlin.math.sqrt(v.coerceAtLeast(0f))

private fun poseTape(e: Float, t: Float, standX: Float, w: Float, u: Float): Pose = when {
    e < 3f -> { val (r, l) = walkArms(t); P(lp(-60 * u, standX, ez(sg(e, 0.2f, 3f))), t, run = 0.65f, armR = r, armL = l, grin = 0.5f, tape = true) }
    e < 7f -> P(standX, t, armR = 95f + 38f * sin(t * 9f), armL = 70f + 20f * sin(t * 9f + 1.6f), tape = true, grin = 0.3f, lean = 0.1f) // omotává
    e < 8.6f -> P(lp(standX, standX - 20 * u, ez(sg(e, 7f, 7.5f))), t, facing = -1f, thumb = true, wink = true, grin = 1f, armR = 150f) // hotovo!
    e < 10.4f -> P(standX - 20 * u + 1.2f * u * sin(t * 30f), t, armR = 155f, armL = 150f, grin = -1f, tape = true) // ... to se nafukuje
    e < 11f -> { val k = ez(sg(e, 10.4f, 10.9f)); P(lp(standX - 20 * u, standX - 50 * u, k), t, sit = k, tilt = -20f * (1 - k), wet = true, closed = true, capLift = 50 * u * sin(sg(e, 10.4f, 11.6f) * PIf), capSpin = (e - 10.4f) * 600f, armR = 170f, armL = -170f, wrench = false) }
    e < 13f -> P(standX - 50 * u, t, sit = 1f - sg(e, 12.4f, 13f), dazed = 1f - sg(e, 11f, 13f), wet = true, capLift = 50 * u * sin(sg(e, 10.4f, 11.6f) * PIf), capSpin = (e - 10.4f) * 600f, wrench = false)
    e < 13.9f -> P(standX - 50 * u, t, armR = 95f, tape = true, wet = true, grin = -0.4f) // kouká na pásku
    e < 14.3f -> P(standX - 50 * u, t, armR = 175f, wet = true, grin = 0.4f, wrench = false) // a zahodí ji
    e < 15f -> P(standX - 50 * u, t, facing = -1f, armR = 120f, armL = -120f, wet = true, grin = 0.5f, wrench = false)
    else -> { val (r, l) = walkArms(t); P(lp(standX - 50 * u, -80 * u, ez(sg(e, 15f, 17f))), t, facing = -1f, run = 0.6f, armR = r, armL = l, wet = true) }
}

// ---------------------------------------------------------------------------------------------
// 8) Hasák a kbelík – chytá vodu do kbelíku, až ho má na hlavě
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.actBucket(e: Float, t: Float, floorY: Float, u: Float, fade: Float, pipes: PipeImages, sm: PoseSmoother) {
    val w = size.width
    val d = 16 * u
    val pipeY = floorY - 104 * u
    val lx = w * 0.58f
    val standX = lx - 34 * u
    val pose = poseBucket(e, t, standX, lx, w, u)
    val p = sm.smooth(pose, t, e)
    val head = headOf(p.x, p.facing, floorY, u, p.lift, p.sit)
    // kde je kbelík: na zemi pod kapající trubkou, nad hlavou, na hlavě (kreslí ho postava), odhozený
    val bucketUp = Offset(head.x, head.y - 40 * u)
    val bucketPos: Offset? = when {
        e < 6.4f -> Offset(lx, floorY - 12 * u)
        e < 7.2f -> Offset(lp(lx, bucketUp.x, ez(sg(e, 6.4f, 7.2f))), lp(floorY - 12 * u, bucketUp.y, ez(sg(e, 6.4f, 7.2f))))
        e < 9f -> bucketUp
        e < 12.4f -> null
        e < 13.2f -> Offset(head.x, head.y - 36 * u * ez(sg(e, 12.4f, 12.9f)))
        else -> { val q = sg(e, 13.2f, 13.7f); Offset(lp(head.x, head.x + 60 * u, q), lp(head.y - 36 * u, floorY - 12 * u, q * q) - 30 * u * sin(q * PIf)) }
    }
    val fill = when {
        e < 5.2f -> 0.15f + 0.1f * sg(e, 0f, 5f)
        e < 6.4f -> 0.25f + 0.5f * sg(e, 5.2f, 6.4f)
        e < 9f -> 0.75f + 0.25f * sg(e, 7.2f, 8.6f)
        else -> 0f
    }
    withAlpha(fade) {
        drawHPipe(pipes, -4 * u, w + 4 * u, pipeY, d)
        drawClamp(pipes, w * 0.18f, pipeY, d)
        drawClamp(pipes, w * 0.88f, pipeY, d)
        drawPuddle(Offset(lx - 20 * u, floorY + 2 * u), (24 + 90 * sg(e, 5.2f, 9.5f)) * u * (1f - 0.4f * sg(e, 13f, 16.5f)), u)
        val tilt = if (e in 8.6f..9f) 160f * sg(e, 8.6f, 9f) else if (e > 13.2f) 70f * sg(e, 13.2f, 13.7f) else 2.5f * sin(t * 7f) * sg(e, 7.2f, 7.4f)
        bucketPos?.let { b -> rotate(tilt, b) { drawBucket(b, u, fill, t) } }
    }
    val leak = Offset(lx + 2 * u, pipeY + d)
    when {
        e < 5.2f -> for (i in 0 until 2) {
            val q = (t * 1.2f + i * 0.5f) % 1f
            drawDrop(Offset(leak.x, leak.y + (floorY - 26 * u - leak.y) * q * q), 2.2f * u, 1.2f)
        }
        e < 9.2f -> {
            val target = bucketPos?.let { Offset(it.x, it.y - 14 * u) } ?: Offset(leak.x, floorY)
            drawJet(leak, Offset(lp(leak.x, target.x, 1f), target.y), 0.85f, t, u, arc = 0f)
        }
        else -> for (i in 0 until 2) {
            val q = (t * 1.2f + i * 0.5f) % 1f
            drawDrop(Offset(leak.x, leak.y + (floorY - leak.y) * q * q), 2.2f * u, 1.2f)
        }
    }
    drawPlumber(p, floorY, u)
    if (e in 9f..9.6f) { // voda z kbelíku do všech stran
        val q = sg(e, 9f, 9.6f)
        for (i in 0 until 14) {
            val a = PIf * (0.1f + 0.8f * i / 13f)
            drawCircle(Color(0xCCBFE6FF).copy(alpha = 1f - q), 2.2f * u, Offset(head.x + cos(a) * 40 * u * q, head.y + 8 * u - sin(a) * 18 * u * q + 50 * u * q * q))
        }
    }
}

private fun DrawScope.drawBucket(b: Offset, u: Float, fill: Float, t: Float) {
    val top = b.y - 13 * u
    val bot = b.y + 12 * u
    val body = Path().apply {
        moveTo(b.x - 15 * u, top); lineTo(b.x + 15 * u, top); lineTo(b.x + 11 * u, bot); lineTo(b.x - 11 * u, bot); close()
    }
    drawArc(Outline, 190f, 160f, false, Offset(b.x - 15 * u, top - 16 * u), Size(30 * u, 26 * u), style = Stroke(1.6f * u))
    outlined(body, Brush.horizontalGradient(listOf(Color(0xFF8C96A2), Color(0xFFE4E9EE), Color(0xFF9AA4B0), Color(0xFF6E7884)), startX = b.x - 15 * u, endX = b.x + 15 * u), u)
    if (fill > 0.05f) drawOval(Color(0xCC54B6FF), Offset(b.x - 13 * u, top - 1.5f * u + (1f - fill) * 6 * u + 0.8f * u * sin(t * 9f)), Size(26 * u, 4 * u))
    drawLine(Outline, Offset(b.x - 16 * u, top), Offset(b.x + 16 * u, top), strokeWidth = 3.4f * u, cap = StrokeCap.Round)
    drawLine(Color(0xFFC9D1D9), Offset(b.x - 16 * u, top), Offset(b.x + 16 * u, top), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
}

private fun poseBucket(e: Float, t: Float, standX: Float, lx: Float, w: Float, u: Float): Pose = when {
    e < 3f -> { val (r, l) = walkArms(t); P(lp(-60 * u, standX, ez(sg(e, 0.2f, 3f))), t, run = 0.65f, armR = r, armL = l, grin = 0.4f) }
    e < 5.2f -> { val ph = ((e - 3f) % 0.7f) / 0.7f; P(standX, t, armR = 175f - 80f * ez(abs(sin(ph * PIf))), armL = -20f, grin = -0.3f) } // bouchá hasákem do trubky
    e < 6.4f -> P(standX - 6 * u + 1.5f * u * sin(t * 28f), t, armR = 150f, armL = -150f, grin = -1f, wet = true) // ...a ono to začne téct
    e < 7.2f -> P(standX + 4 * u, t, lean = 0.7f * (1f - sg(e, 6.8f, 7.2f)), armR = lp(60f, 172f, sg(e, 6.8f, 7.2f)), armL = lp(50f, 168f, sg(e, 6.8f, 7.2f)), wet = true, wrench = false)
    e < 9f -> P(standX + 4 * u + 3 * u * sin(t * 6f) * sg(e, 7.2f, 9f), t, armR = 172f, armL = 168f, grin = -0.6f, tilt = 4f * sin(t * 6f) * sg(e, 7.2f, 9f), wet = true, wrench = false)
    e < 11f -> { val (r, l) = walkArms(t); P(lp(standX + 4 * u, w - 40 * u, sg(e, 9.2f, 11f)), t, run = 0.4f, armR = 90f + r * 0.2f, armL = 90f + l * 0.2f, wet = true, bucket = true, wrench = false) } // naslepo
    e < 12.4f -> P(lp(w - 40 * u, w - 70 * u, ez(sg(e, 11f, 11.6f))), t, tilt = -10f * (1f - sg(e, 11f, 12f)), dazed = 1f - sg(e, 11f, 12.4f), armR = 150f, armL = -150f, wet = true, bucket = true, wrench = false)
    e < 13.2f -> P(w - 70 * u, t, armR = 172f, armL = 168f, wet = true, grin = -0.3f, wrench = false) // sundá si kbelík
    e < 14f -> P(w - 70 * u, t, facing = -1f, thumb = true, wink = true, grin = 1f, armR = 150f, wet = true)
    else -> { val (r, l) = walkArms(t); P(lp(w - 70 * u, w + 80 * u, ez(sg(e, 14f, 16.5f))), t, run = 0.6f, armR = r, armL = l, wet = true, grin = 0.4f) }
}

// ---------------------------------------------------------------------------------------------
// 9) Povodeň v koupelně – Kutil pluje na obří kachně
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.actFlood(e: Float, t: Float, floorY: Float, u: Float, fade: Float, pipes: PipeImages, sm: PoseSmoother) {
    val w = size.width
    val d = 16 * u
    val pipeY = floorY - 150 * u
    val outX = w * 0.16f
    val lev = 50 * u * ez(sg(e, 0.3f, 4f)) * (1f - ez(sg(e, 10.2f, 12.4f)))
    val surf = floorY - lev
    val duckS = 3.4f * u * (1f - 0.72f * ez(sg(e, 13.4f, 14.2f)))
    val duckX = when {
        e < 10.6f -> lp(-80 * u, w * 0.62f, ez(sg(e, 0.8f, 10.6f)))
        else -> w * 0.62f
    }
    val bob = if (lev > 4 * u) 2.5f * u * sin(t * 2.4f) else 0f
    val duckY = min(floorY - 7 * duckS, surf - 2 * duckS) + bob
    withAlpha(fade) {
        drawHPipe(pipes, -4 * u, outX, pipeY, d)
        drawOval(Color(0xFF14202E), Offset(outX - 3 * u, pipeY + 3 * u), Size(5 * u, d - 6 * u))
        // vodopád z trubky
        if (e < 10.3f) {
            val k = sg(e, 0f, 0.4f) * (1f - sg(e, 9.8f, 10.3f))
            drawJet(Offset(outX, pipeY + d / 2), Offset(outX + 24 * u, surf), k, t, u, arc = 0f)
        }
        if (e > 13.2f) drawPuddle(Offset(w * 0.5f, floorY + 2 * u), 120 * u * (1f - sg(e, 14f, 16f) * 0.5f), u)
    }
    val pose = poseFlood(e, t, duckX, duckY, duckS, floorY, w, u)
    val p = sm.smooth(pose, t, e)
    if (e < 14.2f) drawDuck(Offset(duckX, duckY), duckS, 1f)
    drawPlumber(p, floorY, u)
    if (lev > 0.5f) {
        // voda přes celé jeviště (průsvitná, s vlnkou), v odtoku uprostřed vír
        val wave = Path().apply {
            moveTo(0f, floorY + 6 * u)
            var x = 0f
            while (x <= w) { lineTo(x, surf + 2.5f * u * sin(x / (40 * u) + t * 2.2f)); x += 8 * u }
            lineTo(w, floorY + 6 * u); close()
        }
        drawPath(wave, Brush.verticalGradient(listOf(Color(0x7754B6FF), Color(0xAA1C5FA8)), startY = surf, endY = floorY))
        val crest = Path().apply {
            var x = 0f
            moveTo(0f, surf + 2.5f * u * sin(t * 2.2f))
            while (x <= w) { lineTo(x, surf + 2.5f * u * sin(x / (40 * u) + t * 2.2f)); x += 8 * u }
        }
        drawPath(crest, Color.White.copy(alpha = 0.6f), style = Stroke(1.5f * u))
        if (e > 10.2f) {
            val c = Offset(w * 0.5f, surf + 3 * u)
            for (j in 0 until 3) rotate(t * 200f + j * 120f, c) {
                drawArc(Color.White.copy(alpha = 0.6f), 0f, 120f, false, Offset(c.x - (12 + 8 * j) * u, c.y - (3 + 2 * j) * u), Size((24 + 16 * j) * u, (6 + 4 * j) * u), style = Stroke(1.4f * u))
            }
        }
    }
}

private fun poseFlood(e: Float, t: Float, duckX: Float, duckY: Float, duckS: Float, floorY: Float, w: Float, u: Float): Pose {
    // sedí na zádech kachny: boky (22 dp nad chodidly, při sedu o 12 níž) na hřbetu kachny
    val seatLift = floorY - (duckY - 5 * duckS) - 10 * u
    return when {
        e < 12.8f -> P(duckX - 5 * duckS, t, sit = 1f, lift = seatLift, armR = 130f + 35f * sin(t * 4f), armL = -40f + 30f * sin(t * 4f + 2f),
            grin = if (e > 10.2f) -0.5f else 1f, wet = true, wink = e in 5f..6f, closed = false)
        e < 13.4f -> { val k = ez(sg(e, 12.8f, 13.3f)); P(duckX - 5 * duckS - 30 * u * k, t, sit = 1f, lift = seatLift * (1f - k), tilt = -25f * sin(k * PIf), wet = true, closed = true, armR = 170f, armL = -170f) }
        e < 14.4f -> P(duckX - 5 * duckS - 30 * u, t, sit = 1f - sg(e, 13.8f, 14.4f), dazed = 1f - sg(e, 13.4f, 14.4f), wet = true, wrench = false)
        else -> { val (r, l) = walkArms(t); P(lp(duckX - 5 * duckS - 30 * u, w + 80 * u, ez(sg(e, 14.4f, 16.5f))), t, run = 0.6f, armR = r, armL = l, wet = true, carry = 2, grin = 0.7f) }
    }
}

// ---------------------------------------------------------------------------------------------
// 10) Přestávka na kávu – trubka ho vystřelí i s hrnkem
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.actCoffee(e: Float, t: Float, floorY: Float, u: Float, fade: Float, pipes: PipeImages, sm: PoseSmoother) {
    val w = size.width
    val d = 16 * u
    val pipeY = floorY - 122 * u
    val sx = w * 0.42f
    val boxH = 18 * u
    withAlpha(fade) {
        drawHPipe(pipes, -4 * u, w + 4 * u, pipeY, d)
        drawClamp(pipes, w * 0.15f, pipeY, d)
        drawClamp(pipes, w * 0.8f, pipeY, d)
        // nářaďovka jako stolička
        drawRoundRect(Outline, Offset(sx - 18 * u, floorY - boxH - 1 * u), Size(36 * u, boxH + 1 * u), cornerRadius = CornerRadius(3 * u))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE0483C), Color(0xFF9C2219))), Offset(sx - 17 * u, floorY - boxH), Size(34 * u, boxH - 1 * u), cornerRadius = CornerRadius(2.5f * u))
        drawLine(Color(0xFF6E1610), Offset(sx - 17 * u, floorY - boxH + 6 * u), Offset(sx + 17 * u, floorY - boxH + 6 * u), strokeWidth = 1.4f * u)
        drawRoundRect(Color(0xFFC9D1D9), Offset(sx - 4 * u, floorY - boxH + 3 * u), Size(8 * u, 4 * u), cornerRadius = CornerRadius(1 * u))
        drawPuddle(Offset(sx, floorY + 2 * u), 80 * u * sg(e, 9.4f, 10.4f) * (1f - 0.4f * sg(e, 14f, 17.5f)), u)
    }
    val leak = Offset(sx + 6 * u, pipeY + d)
    // kapka na hlavu
    if (e in 8f..8.6f) drawDrop(Offset(leak.x, lp(leak.y, floorY - 104 * u, sg(e, 8f, 8.6f) * sg(e, 8f, 8.6f))), 2.6f * u, 1.3f)
    if (e > 11.6f) for (i in 0 until 2) {
        val q = (t * 1.1f + i * 0.5f) % 1f
        drawDrop(Offset(leak.x, leak.y + (floorY - leak.y) * q * q), 2.2f * u, 1.2f)
    }
    // hrnek letí vzduchem a dopadne na nářaďovku
    if (e in 9.6f..14.6f) {
        val q = sg(e, 9.6f, 12.2f)
        val c = Offset(lp(sx + 14 * u, sx + 8 * u, q), lp(floorY - 66 * u, floorY - boxH - 6 * u, q) - 90 * u * sin(q * PIf))
        rotate(if (q < 1f) q * 720f else 0f, c) {
            drawRoundRect(Outline, Offset(c.x - 6 * u, c.y - 7 * u), Size(12 * u, 14 * u), cornerRadius = CornerRadius(2.5f * u))
            drawRoundRect(Color.White, Offset(c.x - 5 * u, c.y - 6 * u), Size(10 * u, 12 * u), cornerRadius = CornerRadius(2 * u))
            drawLine(Color(0xFFD63A2F), Offset(c.x - 5 * u, c.y - 1 * u), Offset(c.x + 5 * u, c.y - 1 * u), strokeWidth = 1.6f * u)
            drawArc(Outline, 270f, 180f, false, Offset(c.x + 2 * u, c.y - 4 * u), Size(8 * u, 8 * u), style = Stroke(2.4f * u))
        }
    }
    val p = sm.smooth(poseCoffee(e, t, sx, boxH, w, u), t, e)
    drawPlumber(p, floorY, u)
    if (e in 9.4f..11.6f) {
        val k = min(1f, (e - 9.4f) / 0.15f) * (1f - sg(e, 11.2f, 11.6f))
        drawJet(leak, Offset(leak.x + 4 * u, max(headOf(p.x, p.facing, floorY, u, p.lift, p.sit).y - 10 * u, leak.y + 10 * u).let { if (e > 10.2f) floorY - boxH else it }), k, t, u, arc = 0f)
    }
}

private fun poseCoffee(e: Float, t: Float, sx: Float, boxH: Float, w: Float, u: Float): Pose {
    val seat = boxH - 4 * u
    return when {
        e < 3f -> { val (r, l) = walkArms(t); P(lp(-60 * u, sx - 4 * u, ez(sg(e, 0.2f, 3f))), t, run = 0.55f, armR = 60f, armL = l, carry = 4, grin = 0.6f) }
        e < 3.6f -> { val k = ez(sg(e, 3f, 3.6f)); P(sx - 4 * u, t, sit = k, lift = seat * k, armR = 60f, carry = 4, grin = 0.6f) }
        e < 8.6f -> { // pohoda: zamíchá, usrkne, spokojeně vydechne
            val sip = sg(e, 4.9f, 5.3f) * (1f - sg(e, 5.9f, 6.2f)) + sg(e, 7.0f, 7.3f) * (1f - sg(e, 7.7f, 8f))
            P(sx - 4 * u, t, sit = 1f, lift = seat, armR = 70f + 90f * sip + if (e in 3.9f..4.6f) 6f * sin(t * 30f) else 0f, armL = if (e in 3.9f..4.6f) 75f else -15f,
                carry = 4, grin = 1f, closed = e in 6.0f..6.9f)
        }
        e < 9.4f -> P(sx - 4 * u, t, sit = 1f, lift = seat, armR = 60f, carry = 4, grin = -0.6f, wet = true, lean = -0.3f) // kapka na hlavu: kouká nahoru
        e < 11.4f -> { // vystřelí ho to – letí s přemety doprava pryč
            val q = sg(e, 9.5f, 11.2f)
            P(lp(sx - 4 * u, w + 90 * u, q), t, sit = 1f - q, lift = seat + 95 * u * sin(q * PIf * 0.8f), tilt = -540f * q, armR = 170f, armL = -170f, wet = true, closed = true, capLift = 30 * u * q, capSpin = q * 400f, wrench = false)
        }
        e < 12.8f -> P(w + 90 * u, t, wrench = false) // mimo jeviště
        e < 14.4f -> { val (r, l) = walkArms(t); P(lp(w + 70 * u, sx + 28 * u, ez(sg(e, 12.8f, 14.4f))), t, facing = -1f, run = 0.55f, armR = r, armL = l, wet = true, dazed = 0.6f * (1f - sg(e, 12.8f, 14.4f)), wrench = false) }
        e < 15f -> P(sx + 28 * u, t, facing = -1f, lean = 0.45f * (1f - sg(e, 14.7f, 15f)), armR = 60f, wet = true, carry = if (e > 14.7f) 4 else 0, wrench = false)
        e < 16.2f -> P(sx + 28 * u, t, facing = -1f, armR = if (e < 15.8f) 160f else 70f, carry = 4, wet = true, grin = if (e < 15.8f) 0.2f else -0.5f, closed = e in 15.2f..15.8f)
        e < 16.6f -> P(sx + 28 * u, t, facing = -1f, thumb = true, wink = true, grin = 1f, armR = 150f, wet = true)
        else -> { val (r, l) = walkArms(t); P(lp(sx + 28 * u, -80 * u, ez(sg(e, 16.6f, 18.5f))), t, facing = -1f, run = 0.55f, armR = 60f, armL = l, carry = 4, wet = true, grin = 0.5f) }
    }
}

// ---------------------------------------------------------------------------------------------
// 11) Splašená čisticí spirála – omotá Kutila jako špagety
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.actSpiral(e: Float, t: Float, floorY: Float, u: Float, fade: Float, sm: PoseSmoother) {
    val w = size.width
    val mx = w * 0.56f
    val dx = w * 0.86f
    val drum = Offset(mx, floorY - 26 * u)
    val running = e in 3.6f..10.6f
    val shake = if (running) 1.2f * u * sin(t * 40f) else 0f
    val standX = mx - 40 * u
    val pose = poseSpiral(e, t, standX, w, u)
    val p = sm.smooth(pose, t, e)
    withAlpha(fade) {
        // odpad v podlaze
        drawRect(Outline, Offset(dx - 10 * u, floorY - 22 * u), Size(20 * u, 22 * u))
        drawRect(Brush.horizontalGradient(listOf(Color(0xFF8C96A2), Color(0xFFE4E9EE), Color(0xFF6E7884)), startX = dx - 9 * u, endX = dx + 9 * u), Offset(dx - 9 * u, floorY - 21 * u), Size(18 * u, 21 * u))
        drawOval(Color(0xFF14202E), Offset(dx - 8 * u, floorY - 24 * u), Size(16 * u, 5 * u))
        // stroj: rám, motor a buben
        drawLine(Outline, Offset(mx - 18 * u + shake, floorY), Offset(mx + shake, floorY - 26 * u), strokeWidth = 4 * u)
        drawLine(Outline, Offset(mx + 18 * u + shake, floorY), Offset(mx + shake, floorY - 26 * u), strokeWidth = 4 * u)
        drawRoundRect(Outline, Offset(mx - 30 * u + shake, floorY - 34 * u), Size(16 * u, 14 * u), cornerRadius = CornerRadius(2 * u))
        drawRoundRect(Color(0xFF3B4652), Offset(mx - 29 * u + shake, floorY - 33 * u), Size(14 * u, 12 * u), cornerRadius = CornerRadius(2 * u))
        drawCircle(Outline, 18 * u, drum + Offset(shake, 0f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF05A4C), Color(0xFFA8231A)), center = drum + Offset(shake - 4 * u, -4 * u), radius = 18 * u), 16.5f * u, drum + Offset(shake, 0f))
        val rot = if (running) (e - 3.6f) * 900f else 0f
        rotate(rot, drum + Offset(shake, 0f)) {
            for (k in 0 until 3) {
                val a = k * 2 * PIf / 3
                drawLine(Color(0xFF6E1610), drum + Offset(shake, 0f), drum + Offset(shake + 12 * u * cos(a), 12 * u * sin(a)), strokeWidth = 2 * u)
            }
        }
        drawCircle(Color(0xFFC9D1D9), 3 * u, drum + Offset(shake, 0f))
    }
    // spirála: do odpadu, pak se vrátí a omotá Kutila
    val start = drum + Offset(shake + 14 * u, -6 * u)
    val coil = Color(0xFF9AA4B0)
    when {
        e in 3.8f..6.4f -> {
            val q = sg(e, 3.8f, 5f)
            val end = Offset(lp(start.x, dx, q), lp(start.y, floorY - 22 * u, q))
            drawSpring(start, end, u, t, 1.5f)
        }
        e in 6.4f..7.6f -> {
            val q = sg(e, 6.4f, 7.4f)
            val tip = Offset(lp(dx, p.x + 10 * u, q), floorY - 22 * u - 70 * u * sin(q * PIf) + 2 * u * sin(t * 30f))
            val mid = Offset((start.x + tip.x) / 2, min(start.y, tip.y) - 30 * u * sin(t * 12f))
            drawSpring(start, mid, u, t, 3f); drawSpring(mid, tip, u, t, 3f)
        }
        e in 7.6f..11.6f -> drawSpring(start, Offset(p.x + 12 * u * p.facing, floorY - 40 * u - p.lift), u, t, if (running) 2.5f else 0.6f)
    }
    drawPlumber(p, floorY, u)
    // smyčky kolem bříška
    if (e in 7.4f..11.6f) {
        val n = (1 + 4 * sg(e, 7.4f, 8.6f)).toInt()
        for (i in 0 until n) {
            val y = floorY - 26 * u - i * 8 * u - p.lift
            drawOval(Outline, Offset(p.x - 22 * u, y - 4 * u), Size(44 * u, 8 * u), style = Stroke(4 * u))
            drawOval(coil, Offset(p.x - 22 * u, y - 4 * u), Size(44 * u, 8 * u), style = Stroke(2.4f * u))
        }
    }
    // spadlé smyčky na podlaze
    if (e >= 11.6f) for (i in 0 until 4) {
        val cx = standX + 6 * u + i * 5 * u
        drawOval(Outline, Offset(cx - 22 * u, floorY - 5 * u - i * 2 * u), Size(44 * u, 8 * u), style = Stroke(3.6f * u))
        drawOval(coil, Offset(cx - 22 * u, floorY - 5 * u - i * 2 * u), Size(44 * u, 8 * u), style = Stroke(2.2f * u))
    }
}

/** Ocelová spirála (pružina) z bodu do bodu. */
private fun DrawScope.drawSpring(a: Offset, b: Offset, u: Float, t: Float, wiggle: Float) {
    val dxv = b.x - a.x
    val dyv = b.y - a.y
    val len = kotlin.math.sqrt(dxv * dxv + dyv * dyv).coerceAtLeast(1f)
    val nx = -dyv / len
    val ny = dxv / len
    val path = Path()
    val steps = (len / (2 * u)).toInt().coerceIn(4, 200)
    for (i in 0..steps) {
        val q = i.toFloat() / steps
        val off = 3.5f * u * sin(q * len / (5 * u) * PIf) + wiggle * u * sin(q * 9f + t * 14f)
        val x = a.x + dxv * q + nx * off
        val y = a.y + dyv * q + ny * off
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Outline, style = Stroke(3.4f * u, cap = StrokeCap.Round))
    drawPath(path, Color(0xFFB9C4CF), style = Stroke(1.8f * u, cap = StrokeCap.Round))
}

private fun poseSpiral(e: Float, t: Float, standX: Float, w: Float, u: Float): Pose = when {
    e < 3f -> { val (r, l) = walkArms(t); P(lp(-60 * u, standX, ez(sg(e, 0.2f, 3f))), t, run = 0.65f, armR = r, armL = l, grin = 0.5f) }
    e < 3.8f -> P(standX, t, lean = 0.4f, armR = 70f + 40f * sg(e, 3.3f, 3.6f), grin = 0.3f, wrench = false) // zapne stroj
    e < 6.4f -> P(standX, t, armR = if (e > 5.4f) 160f + 12f * sin(t * 12f) else 30f, armL = -10f, grin = 0.8f, wink = e in 5.6f..6.2f, thumb = e < 5.4f) // jde to!
    e < 7.4f -> P(standX - 6 * u, t, armR = 155f, armL = 150f, grin = -1f) // co to...
    e < 10.6f -> { // točí se omotaný
        val f = if (sin(t * 13f) > 0f) 1f else -1f
        P(standX + 3 * u * sin(t * 7f), t, facing = f, lift = 7 * u * abs(sin(t * 9f)), armR = 170f, armL = -170f, grin = -1f, closed = e > 9f, wrench = false)
    }
    e < 11.6f -> P(standX, t, lift = 9 * u * abs(sin((e - 10.6f) * 3f * PIf)), armR = 175f, armL = -175f, dazed = 1f, grin = -0.5f, wrench = false) // poskakuje
    e < 13f -> P(standX, t, dazed = 1f - sg(e, 11.6f, 13f), armR = 20f, wrench = false)
    e < 13.8f -> P(standX, t, facing = -1f, thumb = true, wink = true, grin = 1f, armR = 150f)
    else -> { val (r, l) = walkArms(t); P(lp(standX, -80 * u, ez(sg(e, 13.8f, 16f))), t, facing = -1f, run = 0.6f, armR = r, armL = l, grin = 0.3f) }
}

// ---------------------------------------------------------------------------------------------
// 12) Konec směny – „padla!“, a trubka za ním zase stříká
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.actEndShift(e: Float, t: Float, floorY: Float, u: Float, fade: Float, pipes: PipeImages, sm: PoseSmoother) {
    val w = size.width
    val d = 16 * u
    val pipeY = floorY - 76 * u
    val jx = w * 0.64f
    val standX = jx - 34 * u
    withAlpha(fade) {
        drawHPipe(pipes, -4 * u, w + 4 * u, pipeY, d)
        drawClamp(pipes, jx, pipeY, d)
        drawClamp(pipes, w * 0.12f, pipeY, d)
        drawPuddle(Offset(jx + 20 * u, floorY + 2 * u), 70 * u * sg(e, 6f, 8f), u)
    }
    val p = sm.smooth(poseEndShift(e, t, standX, w, u), t, e)
    drawPlumber(p, floorY, u)
    if (e > 6f) {
        val k = min(1f, (e - 6f) / 0.3f)
        drawJet(Offset(jx, pipeY), Offset(jx + 46 * u + 6 * u * sin(t * 3f), floorY - 2 * u), k, t, u, arc = 46f)
        drawJet(Offset(jx, pipeY), Offset(jx - 30 * u + 5 * u * sin(t * 4f), floorY - 2 * u), k * 0.6f, t, u, arc = 34f)
    }
    // hodinky na zápěstí při kouknutí
    if (e in 2.2f..3.4f) {
        val h = headOf(p.x, p.facing, floorY, u)
        drawCircle(Outline, 3.4f * u, Offset(h.x + 14 * u, h.y + 12 * u))
        drawCircle(Color(0xFFF6D365), 2.4f * u, Offset(h.x + 14 * u, h.y + 12 * u))
    }
}

private fun poseEndShift(e: Float, t: Float, standX: Float, w: Float, u: Float): Pose = when {
    e < 2.2f -> { val ph = ((e) % 0.6f) / 0.6f; P(standX, t, armR = 170f - 70f * ez(abs(sin(ph * PIf))), armL = -10f, grin = -0.2f) }
    e < 3.4f -> P(standX, t, armR = 128f, armL = -10f, grin = 0.1f, wrench = false) // kouká na hodinky
    e < 4.2f -> P(standX, t, lift = 12 * u * sin(sg(e, 3.4f, 3.9f) * PIf), armR = 175f, armL = -175f, grin = 1f, wrench = false) // padla!
    e < 4.6f -> P(standX, t, armR = 40f, armL = -10f, grin = 1f, wrench = false) // klíče z kapsy
    e < 6.4f -> { val (r, l) = walkArms(t); P(lp(standX, w * 0.3f, ez(sg(e, 4.6f, 6.4f))), t, facing = -1f, run = 0.55f, armR = r, armL = l, grin = 0.9f, wrench = false) }
    e < 7.2f -> P(w * 0.3f, t, armR = 20f, grin = -0.7f, wrench = false) // otočí se – stříká to!
    e < 8.4f -> P(w * 0.3f, t, armR = 120f, armL = -120f, grin = 0.3f, wrench = false) // pokrčí rameny
    e < 9.2f -> P(w * 0.3f, t, armR = 165f + 15f * sin(t * 14f), grin = 1f, wink = true, wrench = false) // pápá, zítra
    else -> { val (r, l) = walkArms(t); P(lp(w * 0.3f, -80 * u, ez(sg(e, 9.2f, 12f))), t, facing = -1f, run = 0.55f, armR = r, armL = l, grin = 0.9f, wrench = false) }
}
