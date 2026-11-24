package cz.kutil.dochazka.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Pozadí a kov
// ---------------------------------------------------------------------------------------------

val IndustrialBackground: Brush = Brush.verticalGradient(listOf(Ind.bgTop, Ind.bg, Ind.bgDeep))

/** Kovový lesk trubky napříč (shora dolů): tmavý okraj, světlý odlesk, stín. */
private fun metalBrush(top: Float, height: Float, base: Color = Ind.metalMid): Brush = Brush.verticalGradient(
    0f to Ind.metalDark,
    0.32f to Ind.metalLight,
    0.55f to base,
    1f to Ind.metalDark,
    startY = top,
    endY = top + height,
)

private fun metalBrushH(left: Float, width: Float): Brush = Brush.horizontalGradient(
    0f to Ind.metalDark,
    0.4f to Ind.metalLight,
    1f to Ind.metalDark,
    startX = left,
    endX = left + width,
)

/**
 * Nerezové potrubí přes horní hranu panelu: kolena na obou koncích, dvě příruby a uprostřed
 * červené ruční kolečko ventilu. Kreslí se vektorově (Canvas), takže je ostré a nic nestojí.
 */
@Composable
fun PipeWithValve(modifier: Modifier = Modifier, valveColor: Color = Ind.red) {
    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        val pipe = 14.dp.toPx()
        val y = size.height - pipe - 4.dp.toPx()
        val inset = 10.dp.toPx()
        // svislé konce (kolena) dolů do panelu
        drawRoundRect(metalBrushH(inset, pipe), Offset(inset, y), Size(pipe, size.height - y + 2), CornerRadius(pipe / 2))
        drawRoundRect(metalBrushH(size.width - inset - pipe, pipe), Offset(size.width - inset - pipe, y), Size(pipe, size.height - y + 2), CornerRadius(pipe / 2))
        // vodorovná trubka
        drawRoundRect(metalBrush(y, pipe), Offset(inset, y), Size(size.width - 2 * inset, pipe), CornerRadius(pipe / 2))
        // příruby se šrouby
        for (fx in listOf(0.22f, 0.78f)) {
            val w = 9.dp.toPx()
            val h = pipe + 8.dp.toPx()
            val x = size.width * fx - w / 2
            drawRoundRect(metalBrushH(x, w), Offset(x, y - 4.dp.toPx()), Size(w, h), CornerRadius(2.dp.toPx()))
            drawCircle(Ind.metalDark, 1.4.dp.toPx(), Offset(x + w / 2, y - 1.dp.toPx()))
            drawCircle(Ind.metalDark, 1.4.dp.toPx(), Offset(x + w / 2, y + pipe + 1.dp.toPx()))
        }
        // ventil: mosazné vřeteno a ruční kolečko
        val cx = size.width / 2
        drawRect(Brush.horizontalGradient(listOf(Color(0xFF8C6A2A), Color(0xFFE6C77E), Color(0xFF8C6A2A)), cx - 4.dp.toPx(), cx + 4.dp.toPx()),
            Offset(cx - 4.dp.toPx(), y - 8.dp.toPx()), Size(8.dp.toPx(), 10.dp.toPx()))
        val wheelY = y - 10.dp.toPx()
        drawWheel(cx, wheelY, valveColor)
    }
}

private fun DrawScope.drawWheel(cx: Float, cy: Float, color: Color) {
    val r = 11.dp.toPx()
    drawOval(color, Offset(cx - r * 1.9f, cy - r * 0.45f), Size(r * 3.8f, r * 0.9f), style = Stroke(3.dp.toPx()))
    drawLine(color, Offset(cx - r * 1.8f, cy), Offset(cx + r * 1.8f, cy), 2.dp.toPx())
    drawCircle(Color(0xFF6E1410), 2.5.dp.toPx(), Offset(cx, cy))
}

/** Šedý HT odpad (PP trubka s hrdly) jako oddělovač sekcí. */
@Composable
fun HtPipeDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val h = 9.dp.toPx()
        val y = (size.height - h) / 2
        val gray = Brush.verticalGradient(0f to Color(0xFF6F7883), 0.35f to Color(0xFFD3D9DF), 1f to Color(0xFF6F7883), startY = y, endY = y + h)
        drawRoundRect(gray, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
        val socketW = 16.dp.toPx()
        val sh = h + 5.dp.toPx()
        for (fx in listOf(0.18f, 0.62f)) {
            val x = size.width * fx
            val sg = Brush.verticalGradient(0f to Color(0xFF66707B), 0.35f to Color(0xFFE2E7EC), 1f to Color(0xFF66707B), startY = y - 2.5.dp.toPx(), endY = y - 2.5.dp.toPx() + sh)
            drawRoundRect(sg, Offset(x, y - 2.5.dp.toPx()), Size(socketW, sh), CornerRadius(3.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Karty
// ---------------------------------------------------------------------------------------------

private val metalEdge = Brush.verticalGradient(listOf(Color(0xFF52688A), Color(0xFF263B5C)))

/** Zaoblená tmavá karta s kovovou hranou – základní stavební prvek obrazovek. */
@Composable
fun MetalCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Ind.surfaceHi, Ind.surface)))
            .border(1.dp, metalEdge, RoundedCornerShape(22.dp))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Nadpis sekce s malou ikonou v oranžové. */
@Composable
fun SectionTitle(text: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Ind.orange, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ind.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

enum class PanelTone { WORKING, PENDING, IDLE }

/**
 * Hlavní stavový panel s potrubím nahoře: zelený při běžící směně, jantarový když něco čeká
 * na potvrzení, ocelově modrý doma. Barva se mění plynulou animací.
 */
@Composable
fun StatusPanel(tone: PanelTone, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val (top, bottom) = when (tone) {
        PanelTone.WORKING -> Color(0xFF45AB55) to Color(0xFF1D6A2C)
        PanelTone.PENDING -> Color(0xFFD99A1E) to Color(0xFF8A5A00)
        PanelTone.IDLE -> Color(0xFF2B4B78) to Color(0xFF16294A)
    }
    val a by animateColorAsState(top, tween(450), label = "panelTop")
    val b by animateColorAsState(bottom, tween(450), label = "panelBottom")
    Column(modifier.fillMaxWidth()) {
        PipeWithValve(valveColor = if (tone == PanelTone.WORKING) Ind.red else Ind.orange)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(a, b)))
                .border(3.dp, Brush.verticalGradient(listOf(Ind.metalLight, Ind.metalDark)), RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Tlačítka
// ---------------------------------------------------------------------------------------------

enum class ActionKind { GO, STOP, ACCENT, METAL }

/**
 * Velké tlačítko pro ovládání v rukavicích: min. 60 dp, výrazný přechod, při stisku se
 * jemně zmenší. Text i ikona mají kontrast vůči pozadí tlačítka nad 4,5 : 1.
 */
@Composable
fun BigActionButton(
    text: String,
    kind: ActionKind,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    height: Dp = 60.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    val (brush, fg, edge) = when (kind) {
        ActionKind.GO -> Triple(Brush.verticalGradient(listOf(Color(0xFF4CB45C), Color(0xFF2A8A3A), Color(0xFF1D6A2C))), Color.White, Color(0xFF0F4A1C))
        ActionKind.STOP -> Triple(Brush.verticalGradient(listOf(Color(0xFFEF5246), Color(0xFFC62A20), Color(0xFFA51F17))), Color.White, Color(0xFF6E1410))
        ActionKind.ACCENT -> Triple(Brush.verticalGradient(listOf(Color(0xFFFFB534), Ind.orange, Ind.orangeDeep)), Ind.onOrange, Color(0xFF7A4500))
        ActionKind.METAL -> Triple(Brush.verticalGradient(listOf(Color(0xFFF4F7FA), Color(0xFFCBD4DE), Color(0xFFAAB5C2))), Color(0xFF0F1E33), Color(0xFF7D8896))
    }
    Row(
        modifier
            .scale(scale)
            .heightIn(min = height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) brush else Brush.verticalGradient(listOf(Ind.surfaceHi, Ind.surface)))
            .border(1.dp, if (enabled) edge else Ind.outline, RoundedCornerShape(16.dp))
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (enabled) fg else Ind.textMuted
        leading?.invoke()
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp).padding(end = 2.dp))
        if (icon != null || leading != null) Box(Modifier.size(8.dp))
        Text(text, color = color, fontSize = if (height >= 60.dp) 19.sp else 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Bílý kroužek s červeným čtverečkem (stop) do tlačítka Ukončit práci. */
@Composable
fun StopGlyph() {
    Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFC62A20)))
    }
}

/** Bílý kroužek se zeleným trojúhelníkem (start). */
@Composable
fun PlayGlyph() {
    Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(12.dp)) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close()
            }
            drawPath(p, Color(0xFF2A8A3A))
        }
    }
}

/** Barevná tečka stavu (zelená = v pořádku, oranžová = pozor, červená = problém, šedá = vypnuto). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Aktuální čas v ms, obnovovaný každou [periodMs] – pro živý časovač. Běží jen, když je obrazovka vidět. */
@Composable
fun rememberNow(periodMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMs - now % periodMs)
        }
    }
    return now
}

/** 3725 s → „1:02:05“. */
fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

