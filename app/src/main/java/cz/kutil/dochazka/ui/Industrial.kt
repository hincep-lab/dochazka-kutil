package cz.kutil.dochazka.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kutil.dochazka.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// =============================================================================================
// Kovové prvky jsou předrenderované obrázky (chrom, mosaz, měď, litina) – viz tools/render_assets.py.
// Tady se jen skládají do rámů a dekorací v potřebné velikosti.
// =============================================================================================

/** Průměr trubky na obrazovce. Obrázky jsou kreslené pro průměr 64 px. */
val PipeD: Dp = 18.dp
private const val SRC_D = 64f

class PipeImages(
    val h: ImageBitmap,
    val v: ImageBitmap,
    val tl: ImageBitmap,
    val tr: ImageBitmap,
    val bl: ImageBitmap,
    val br: ImageBitmap,
    val clampH: ImageBitmap,
    val clampV: ImageBitmap,
    val copper: ImageBitmap,
    val valve: ImageBitmap,
)

@Composable
fun rememberPipeImages(): PipeImages {
    val h = ImageBitmap.imageResource(R.drawable.pipe_h)
    val v = ImageBitmap.imageResource(R.drawable.pipe_v)
    val tl = ImageBitmap.imageResource(R.drawable.pipe_elbow_tl)
    val tr = ImageBitmap.imageResource(R.drawable.pipe_elbow_tr)
    val bl = ImageBitmap.imageResource(R.drawable.pipe_elbow_bl)
    val br = ImageBitmap.imageResource(R.drawable.pipe_elbow_br)
    val ch = ImageBitmap.imageResource(R.drawable.pipe_clamp_h)
    val cv = ImageBitmap.imageResource(R.drawable.pipe_clamp_v)
    val cu = ImageBitmap.imageResource(R.drawable.pipe_copper_h)
    val va = ImageBitmap.imageResource(R.drawable.pipe_valve)
    return remember(h, v, tl, tr, bl, br, ch, cv, cu, va) { PipeImages(h, v, tl, tr, bl, br, ch, cv, cu, va) }
}

private fun DrawScope.img(image: ImageBitmap, x: Float, y: Float, w: Float, h: Float) {
    if (w <= 0f || h <= 0f) return
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        filterQuality = FilterQuality.High,
    )
}

/** Uzavřený rám z chromových trubek s koleny v rozích, objímkami a případně šoupátkem nahoře. */
fun DrawScope.drawPipeFrame(p: PipeImages, r: Rect, d: Float, valve: Boolean, valveAt: Float = 0.24f) {
    val k = d / SRC_D
    val s = p.tl.width * k
    // rovné úseky (kreslí se jako první, kolena je překryjí)
    img(p.h, r.left + s - 1, r.top, r.width - 2 * s + 2, d)
    img(p.h, r.left + s - 1, r.bottom - d, r.width - 2 * s + 2, d)
    img(p.v, r.left, r.top + s - 1, d, r.height - 2 * s + 2)
    img(p.v, r.right - d, r.top + s - 1, d, r.height - 2 * s + 2)
    img(p.tl, r.left, r.top, s, s)
    img(p.tr, r.right - s, r.top, s, s)
    img(p.bl, r.left, r.bottom - s, s, s)
    img(p.br, r.right - s, r.bottom - s, s, s)
    // objímky
    val cw = p.clampH.width * k
    val chh = p.clampH.height * k
    val xs = listOf(r.left + s + d * 0.3f, r.right - s - d * 0.3f - cw)
    for (x in xs) {
        img(p.clampH, x, r.top + d / 2 - chh / 2, cw, chh)
        img(p.clampH, x, r.bottom - d / 2 - chh / 2, cw, chh)
    }
    if (r.width > 600f) img(p.clampH, r.left + r.width * 0.66f, r.top + d / 2 - chh / 2, cw, chh)
    val vw = p.clampV.width * k
    val vh = p.clampV.height * k
    if (r.height > 2 * s + 3 * vh) {
        for (y in listOf(r.top + s + d * 0.3f, r.bottom - s - d * 0.3f - vh)) {
            img(p.clampV, r.left + d / 2 - vw / 2, y, vw, vh)
            img(p.clampV, r.right - d / 2 - vw / 2, y, vw, vh)
        }
    }
    if (valve) {
        val w = p.valve.width * k * 1.05f
        val h = p.valve.height * k * 1.05f
        // spodek hlavy ventilu (cca 150/168 výšky obrázku) sedí na ose trubky
        img(p.valve, r.left + r.width * valveAt - w / 2, r.top + d / 2 - h * 0.9f, w, h)
    }
}

/** Výška, o kterou šoupátko přesahuje rám nahoru. */
val ValveOverhang: Dp = 40.dp

@Composable
fun PipeFrame(modifier: Modifier = Modifier, valve: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val pipes = rememberPipeImages()
    Box(
        modifier
            .padding(top = if (valve) ValveOverhang else 0.dp)
            .drawWithContent {
                drawContent()
                drawPipeFrame(pipes, Rect(0f, 0f, size.width, size.height), PipeD.toPx(), valve)
            }
            .padding(PipeD / 2 + 1.dp),
        content = content,
    )
}

// =============================================================================================
// Pozadí a dekorace
// =============================================================================================

/** Mokrý tmavě modrý kov s kapkami vody – pozadí celé aplikace. */
@Composable
fun WetBackground(modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.bg_wet),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize(),
    )
}

/** Kanálová vpusť s přívodní trubkou – zakončení hlavní obrazovky. */
@Composable
fun DrainFooter(modifier: Modifier = Modifier) {
    val pipes = rememberPipeImages()
    Box(modifier.fillMaxWidth().height(118.dp)) {
        Image(
            painterResource(R.drawable.drain_grate),
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(108.dp),
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val d = PipeD.toPx()
                    val k = d / SRC_D
                    val s = pipes.tl.width * k
                    val y = size.height * 0.30f
                    val x0 = size.width * 0.74f
                    // trubka přichází zprava, ohne se dolů a končí měděnou spojkou
                    img(pipes.h, x0 + s - 1, y, size.width - x0 - s + 2, d)
                    img(pipes.tl, x0, y, s, s)
                    img(pipes.v, x0, y + s - 1, d, size.height * 0.30f)
                    img(pipes.copper, x0 + s + d * 0.6f, y - d * 0.06f, d * 1.5f, d * 1.12f)
                    // vlevo krátký kus ze stěny
                    img(pipes.h, -2f, size.height * 0.62f, size.width * 0.16f, d)
                    img(pipes.clampV, size.width * 0.16f - pipes.clampV.width * k * 0.5f, size.height * 0.62f - d * 0.2f, pipes.clampV.width * k, d * 1.4f)
                },
        )
    }
}

/** Ozdobné koleno v pravém horním rohu horní lišty. */
@Composable
fun TopBarPipe(modifier: Modifier = Modifier) {
    val pipes = rememberPipeImages()
    Box(
        modifier.size(76.dp, 56.dp).drawWithContent {
            val d = PipeD.toPx()
            val k = d / SRC_D
            val s = pipes.tl.width * k
            val y = size.height * 0.14f
            val x = size.width * 0.18f
            img(pipes.h, x + s - 1, y, size.width - x - s + 2, d)
            img(pipes.tl, x, y, s, s)
            img(pipes.v, x, y + s - 1, d, size.height - y - s + 2)
            img(pipes.copper, size.width - d * 1.6f, y - d * 0.06f, d * 1.3f, d * 1.12f)
        },
    )
}

// =============================================================================================
// Karty z kartáčovaného kovu
// =============================================================================================

private val CardShape = RoundedCornerShape(20.dp)

/** Obsah světlé karty: tmavý text a světlé barvy ovládacích prvků (pole, přepínače, tlačítka). */
@Composable
fun OnSteel(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CardScheme, typography = MaterialTheme.typography) {
        CompositionLocalProvider(LocalContentColor provides Ind.text, content = content)
    }
}

@Composable
private fun brushedBrush(): Brush {
    val tex = ImageBitmap.imageResource(R.drawable.tex_brushed)
    return remember(tex) { ShaderBrush(ImageShader(tex, TileMode.Repeated, TileMode.Repeated)) }
}

private val SteelGloss = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.45f),
    0.35f to Color.White.copy(alpha = 0.05f),
    1f to Color(0xFF55657A).copy(alpha = 0.22f),
)
private val SteelEdge = Brush.verticalGradient(listOf(Color.White, Color(0xFFB4BFCC), Color(0xFF7E8C9E)))

@Composable
fun SteelCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    screws: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tex = brushedBrush()
    Box(
        modifier
            .fillMaxWidth()
            .shadow(10.dp, CardShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CardShape)
            .background(tex)
            .background(SteelGloss)
            .border(1.5.dp, SteelEdge, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (screws) {
            val sz = 11.dp
            for (a in listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)) {
                Image(painterResource(R.drawable.screw), null, Modifier.align(a).padding(7.dp).size(sz))
            }
        }
        OnSteel {
            Column(
                Modifier.padding(horizontal = if (screws) padding + 4.dp else padding, vertical = padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** Dřívější název – zachován kvůli ostatním obrazovkám. */
@Composable
fun MetalCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) =
    SteelCard(modifier, padding, content = content)

@Composable
fun SectionTitle(text: String, icon: ImageVector? = null, @DrawableRes iconRes: Int? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            iconRes != null -> Icon(painterResource(iconRes), null, tint = Ind.orangeDeep, modifier = Modifier.size(24.dp))
            icon != null -> Icon(icon, null, tint = Ind.orangeDeep, modifier = Modifier.size(24.dp))
        }
        if (icon != null || iconRes != null) Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Ind.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// =============================================================================================
// Stavový panel (zelený / oranžový / modrý) – sedí uvnitř trubkového rámu
// =============================================================================================

enum class PanelTone { WORKING, PENDING, IDLE }

private fun toneColors(t: PanelTone): List<Color> = when (t) {
    PanelTone.WORKING -> listOf(Color(0xFF5CC250), Color(0xFF2F922F), Color(0xFF1B6720), Color(0xFFB4F59A))
    PanelTone.PENDING -> listOf(Color(0xFFF7B04A), Color(0xFFDB821B), Color(0xFFA65606), Color(0xFFFFD99A))
    PanelTone.IDLE -> listOf(Color(0xFF4774A6), Color(0xFF2A4C76), Color(0xFF183254), Color(0xFF9CC2EE))
}

val PanelText = TextStyle(shadow = Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 2f), 4f))

@Composable
fun StatusPanel(tone: PanelTone, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = toneColors(tone)
    val anim = tween<Color>(500)
    val top by animateColorAsState(c[0], anim, label = "top")
    val mid by animateColorAsState(c[1], anim, label = "mid")
    val bot by animateColorAsState(c[2], anim, label = "bot")
    val rim by animateColorAsState(c[3], anim, label = "rim")
    val shape = RoundedCornerShape(15.dp)
    PipeFrame(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(top, mid, bot)))
                .border(2.5.dp, rim, shape),
        ) {
            Image(
                painterResource(R.drawable.drops_overlay), null,
                contentScale = ContentScale.Crop, alignment = Alignment.TopEnd,
                modifier = Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.20f), 0.45f to Color.Transparent)))
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

// =============================================================================================
// Lesklá tlačítka
// =============================================================================================

enum class ActionKind { GO, STOP, ACCENT, METAL }

private class BtnStyle(val top: Color, val mid: Color, val bottom: Color, val edge: Color, val text: Color)

private fun btn(kind: ActionKind) = when (kind) {
    ActionKind.GO -> BtnStyle(Color(0xFF5CC85A), Color(0xFF2E9A35), Color(0xFF1C7426), Color(0xFF125018), Color.White)
    ActionKind.STOP -> BtnStyle(Color(0xFFF2564B), Color(0xFFD72A20), Color(0xFFA8180F), Color(0xFF6E0D07), Color.White)
    ActionKind.ACCENT -> BtnStyle(Color(0xFFFFB648), Color(0xFFF08C12), Color(0xFFC46A04), Color(0xFF7A4100), Color(0xFF1F1200))
    ActionKind.METAL -> BtnStyle(Color(0xFFF2F7FF), Color(0xFFD5E4F9), Color(0xFFB4CDEE), Color(0xFF7B9CC8), Color(0xFF0E2547))
}

@Composable
fun BigActionButton(
    text: String,
    kind: ActionKind,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    height: Dp = 62.dp,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null,
    onClick: () -> Unit,
) {
    val st = btn(kind)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .heightIn(min = height)
            .scale(scale)
            .shadow(if (pressed) 2.dp else 7.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(st.top, st.mid, st.bottom)))
            .border(1.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), st.edge)), shape)
            .clickable(interactionSource = interaction, indication = ripple(color = Color.White), enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .then(if (enabled) Modifier else Modifier.background(Color(0x88000000))),
        contentAlignment = Alignment.Center,
    ) {
        // lesk v horní polovině
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.30f), 0.5f to Color.White.copy(alpha = 0.04f), 0.51f to Color.Transparent)))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            when {
                leading != null -> {
                    leading()
                    Spacer(Modifier.width(12.dp))
                }
                iconRes != null -> {
                    Icon(painterResource(iconRes), null, tint = st.text, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                }
                icon != null -> {
                    Icon(icon, null, tint = st.text, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                text,
                color = st.text,
                fontSize = if (height >= 60.dp) 22.sp else 17.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = if (kind == ActionKind.METAL || kind == ActionKind.ACCENT) TextStyle.Default else PanelText,
            )
        }
    }
}

/** Bílé kolečko s červeným čtverečkem (stop) – jako na předloze. */
@Composable
fun StopGlyph() {
    Box(Modifier.size(34.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFD72A20)))
    }
}

@Composable
fun PlayGlyph() {
    Box(Modifier.size(34.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_play_arrow), null, tint = Color(0xFF2E9A35), modifier = Modifier.size(26.dp))
    }
}

/** Dlaždice rychlé akce s barevnou ikonou. */
@Composable
fun QuickTile(text: String, @DrawableRes iconRes: Int, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(min = 92.dp)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFFBFCFE), Color(0xFFDCE3EC))))
            .border(1.dp, Color(0xFF9AA8BA), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(iconRes), null, tint = tint, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(4.dp))
        Text(text, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, color = Ind.text, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
}

/** Aktuální čas, který se sám obnovuje (živý časovač směny). */
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

/** "H:MM:SS" */
fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** "0 h 56 min" */
fun formatHoursMinutes(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return "${m / 60} h ${"%02d".format(m % 60)} min"
}

/** Oddělovač – šedá odpadní HT trubka byla nahrazena jemnou chromovou linkou. */
@Composable
fun HtPipeDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.height(2.dp))
}
