package cz.kutil.dochazka.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cz.kutil.dochazka.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =============================================================================================
// Kutil – 3D loutkové gagy z balíku „Dochazka_Kutil_komplet_3D_grafika_a_gagy“.
// Průhledná animovaná WebP se přehrávají jednotlivě na vrstvě, která nezachytává dotyk.
// Každé přehrání = nová instance přehrávače; po skončení klipu se vrstva skryje.
// =============================================================================================

/** Jeden klip: cesta v assets, délka (s), počet přehrání a zvukové okamžiky. */
class Clip(
    val path: String,
    val length: Float,
    val plays: Int = 1,
    val loopForever: Boolean = false,
    val sounds: List<Pair<Float, GagSound>> = emptyList(),
)

object KutilClips {
    private fun c(n: String, len: Float, vararg s: Pair<Float, GagSound>) = Clip("kutil/comedy/$n.webp", len, sounds = s.toList())

    /** Gagy během směny (délky podle manifest.json). */
    val SHIFT_GAGS = listOf(
        c("01-utrzeny-ventil", 7.333f, 2.2f to GagSound.CLANK, 2.9f to GagSound.BURST_START, 6.6f to GagSound.BURST_STOP),
        c("02-zachodovy-gejzir", 7.333f, 0.8f to GagSound.PLOP, 1.6f to GagSound.PLOP, 2.4f to GagSound.PLOP, 3.0f to GagSound.SPLASH, 5.1f to GagSound.BOING),
        c("03-zahada-ucpaneho-odpadu", 8.0f, 1.2f to GagSound.GURGLE, 5.0f to GagSound.POP, 6.6f to GagSound.BOING),
        c("04-izolacni-paska-vsechno-vyresi", 7.333f, 0.7f to GagSound.BURST_START, 5.6f to GagSound.GURGLE, 6.8f to GagSound.BURST_STOP),
        c("05-hasak-a-kbelik", 8.0f, 4.3f to GagSound.SPLASH, 5.0f to GagSound.THUD, 6.2f to GagSound.CLANK),
        c("06-povoden-v-koupelne", 7.333f, 3.6f to GagSound.SPLASH, 5.1f to GagSound.SQUEAK, 6.6f to GagSound.SQUEAK),
        c("07-zradny-zebrik", 7.333f, 2.2f to GagSound.CLANK, 4.4f to GagSound.BOING, 6.5f to GagSound.THUD),
        c("08-prestavka-na-kavu", 7.333f, 5.1f to GagSound.SPLASH, 5.3f to GagSound.BOING),
        c("09-splasena-cistici-spirala", 7.333f, 1.5f to GagSound.GURGLE, 2.9f to GagSound.BOING, 5.1f to GagSound.THUD),
    )
    val END_OF_SHIFT = c("10-konec-smeny", 8.0f, 4.4f to GagSound.BURST_START, 6.0f to GagSound.BURST_STOP, 6.4f to GagSound.CLANK)

    private fun m(n: String, len: Float, plays: Int = 1, forever: Boolean = false) = Clip("kutil/mascot/$n.webp", len, plays, forever)
    val ARRIVE = m("prijezd-dodavky", 2.8f)
    val DEPART = m("odjezd-dodavky", 2.8f)
    val CHEER = m("vitezoslavne-gesto", 2.0f)
    val SLEEP = m("spanek-na-potrubi", 3.2f, forever = true)
    val IDLE = listOf(m("mavani", 1.2f, 3), m("smich", 1.6f, 2), m("skrabani-hlavy", 2.0f, 2), m("mrkani", 2.4f, 2))
}

/**
 * Jeviště: během směny hraje gagy, mimo směnu občas mávne / zasměje se, od 20 do 6 spí na trubce.
 * Při zahájení směny přijede dodávkou, při ukončení zahraje „Konec směny“ a odjede.
 * Při omezeném pohybu (nebo vypnutých animacích) jen statický obrázek Kutila.
 */
@Composable
fun KutilStage(
    working: Boolean,
    night: Boolean,
    animate: Boolean,
    onSound: (GagSound) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!animate) {
        Box(modifier) {
            Image(
                painterResource(R.drawable.g_kutil_staticky), contentDescription = null,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 6.dp).size(70.dp, 90.dp),
            )
        }
        return
    }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var serial by remember { mutableIntStateOf(0) }
    var lastWorking by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(working, night) {
        val transition = lastWorking != null && lastWorking != working
        lastWorking = working
        suspend fun play(c: Clip) {
            clip = c
            serial++
            // zvuky ve správných okamžicích klipu
            val sounds = launch {
                var t0 = 0f
                for ((at, s) in c.sounds.sortedBy { it.first }) {
                    delay(((at - t0) * 1000).toLong())
                    t0 = at
                    onSound(s)
                }
            }
            try {
                if (c.loopForever) delay(Long.MAX_VALUE)
                delay((c.length * c.plays * 1000).toLong() + 250)
            } finally {
                // i při přerušení (konec směny, odchod z aplikace) utichne syčení vody
                sounds.cancel()
                if (c.sounds.any { it.second == GagSound.BURST_START }) onSound(GagSound.BURST_STOP)
                clip = null
            }
        }
        clip = null
        if (working) {
            if (transition) {
                play(KutilClips.ARRIVE)
                play(KutilClips.CHEER)
            }
            var order = KutilClips.SHIFT_GAGS.shuffled()
            var i = 0
            delay(if (transition) 12_000 else 5_000)
            while (true) {
                if (i >= order.size) {
                    order = KutilClips.SHIFT_GAGS.shuffled(); i = 0
                }
                play(order[i++])
                delay(18_000L + (Math.random() * 27_000).toLong())
            }
        } else {
            if (transition) {
                play(KutilClips.END_OF_SHIFT)
                play(KutilClips.DEPART)
            }
            if (night) {
                play(KutilClips.SLEEP) // ve smyčce až do rána
            } else {
                delay(6_000)
                while (true) {
                    play(KutilClips.IDLE.random())
                    delay(30_000L + (Math.random() * 40_000).toLong())
                }
            }
        }
    }
    Box(modifier) {
        clip?.let { AnimatedClip(it, serial, Modifier.fillMaxSize()) }
    }
}

/** Přehraje animované WebP s průhledností; nová instance dekodéru pro každé přehrání. */
@Composable
private fun AnimatedClip(c: Clip, serial: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    var drawable by remember(serial) { mutableStateOf<AnimatedImageDrawable?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(serial) {
        val d = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.assets, c.path)) }.getOrNull()
        } as? AnimatedImageDrawable ?: return@LaunchedEffect
        d.repeatCount = if (c.loopForever) AnimatedImageDrawable.REPEAT_INFINITE else (c.plays - 1).coerceAtLeast(0)
        d.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { frame++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        drawable = d
        d.start()
    }
    DisposableEffect(serial) {
        onDispose {
            drawable?.let { it.stop(); it.callback = null }
            drawable = null
        }
    }
    Canvas(modifier) {
        frame // čtení stavu → překreslení při každém novém snímku
        val d = drawable ?: return@Canvas
        // poměr stran 8:5 zachován, klip přisazený ke spodnímu okraji
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        val scale = minOf(size.width / iw, size.height / ih)
        val w = iw * scale
        val h = ih * scale
        val left = (size.width - w) / 2
        val top = size.height - h
        d.setBounds(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
        drawIntoCanvas { d.draw(it.nativeCanvas) }
    }
}
