package cz.kutil.dochazka.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.kutil.dochazka.R
import cz.kutil.dochazka.data.AppPrefs

/**
 * Zvuky vody: ťuknutí kapky při dopadu na hladinu a tiché šumění, když nádoba v přesčasu přetéká.
 * Zvuky jsou skutečné nahrávky z BigSoundBank.com (licence CC0 – volné dílo, bez povinnosti uvádět autora),
 * zkrácené a ztišené skriptem tools/cut_sounds.py. Hrají jen když:
 *  - je zapnuté nastavení „Zvuky vody“ i dekorativní animace,
 *  - telefon není v tichém režimu ani na vibracích,
 *  - aplikace je na obrazovce (při odchodu z aplikace se vše zastaví).
 */
/** Jen pro testy: záznam, kdy aplikace přehrála jaký zvuk (pro ozvučení ukázkového videa). */
object SoundTap {
    @Volatile var listener: ((String) -> Unit)? = null
}

class WaterSounds(ctx: Context) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val drip = pool.load(ctx, R.raw.water_drip, 1)
    private val pourId = pool.load(ctx, R.raw.water_pour, 1)
    private val overflow = pool.load(ctx, R.raw.water_overflow, 1)
    /** Jednorázové zvuky scének: soubor a hlasitost (vše záměrně potichu). */
    private val oneShots: Map<GagSound, Pair<Int, Float>> = mapOf(
        GagSound.SPLASH to (pool.load(ctx, R.raw.gag_splash, 1) to 0.22f),
        GagSound.SPLASH_BIG to (pool.load(ctx, R.raw.gag_splash_big, 1) to 0.22f),
        GagSound.CLANK to (pool.load(ctx, R.raw.gag_clank, 1) to 0.16f),
        GagSound.BOING to (pool.load(ctx, R.raw.gag_boing, 1) to 0.18f),
        GagSound.THUD to (pool.load(ctx, R.raw.gag_thud, 1) to 0.26f),
        GagSound.PLOP to (pool.load(ctx, R.raw.gag_plop, 1) to 0.24f),
        GagSound.GURGLE to (pool.load(ctx, R.raw.gag_gurgle, 1) to 0.2f),
        GagSound.POP to (pool.load(ctx, R.raw.gag_pop, 1) to 0.22f),
        GagSound.FLUSH to (pool.load(ctx, R.raw.gag_flush, 1) to 0.18f),
        GagSound.SQUEAK to (pool.load(ctx, R.raw.gag_squeak, 1) to 0.18f),
        GagSound.SNORE to (pool.load(ctx, R.raw.gag_snore, 1) to 0.22f),
        GagSound.BURP to (pool.load(ctx, R.raw.gag_burp, 1) to 0.22f),
        GagSound.CHEER to (pool.load(ctx, R.raw.gag_cheer, 1) to 0.14f),
        GagSound.TAPE to (pool.load(ctx, R.raw.gag_tape, 1) to 0.24f),
        GagSound.BUCKET to (pool.load(ctx, R.raw.gag_bucket, 1) to 0.2f),
        GagSound.STIR to (pool.load(ctx, R.raw.gag_stir, 1) to 0.24f),
        GagSound.COFFEE_POUR to (pool.load(ctx, R.raw.gag_coffee_pour, 1) to 0.2f),
        GagSound.DRILL to (pool.load(ctx, R.raw.gag_drill, 1) to 0.12f),
        GagSound.SIGH to (pool.load(ctx, R.raw.gag_sigh, 1) to 0.24f),
        GagSound.KEYS to (pool.load(ctx, R.raw.gag_keys, 1) to 0.22f),
        GagSound.CREAK to (pool.load(ctx, R.raw.gag_creak, 1) to 0.16f),
        GagSound.METAL_FALL to (pool.load(ctx, R.raw.gag_metal_fall, 1) to 0.16f),
        GagSound.TOOL_DROP to (pool.load(ctx, R.raw.gag_tool_drop, 1) to 0.2f),
    )
    private var overflowStream = 0
    private var pourStream = 0
    private var sprayStream = 0

    /** Zvuky scénky s instalatérem. */
    fun gag(s: GagSound) {
        SoundTap.listener?.invoke(s.name)
        if (!allowed()) {
            stopSpray(); return
        }
        when (s) {
            // stříkající trubka: šumění tekoucí vody, trochu výš laděné
            GagSound.BURST_START -> if (sprayStream == 0) sprayStream = pool.play(pourId, 0.13f, 0.13f, 0, -1, 1.25f)
            GagSound.BURST_STOP -> stopSpray()
            else -> oneShots[s]?.let { (id, vol) ->
                val rate = if (s == GagSound.CLANK || s == GagSound.PLOP) 0.94f + Math.random().toFloat() * 0.12f else 1f
                pool.play(id, vol, vol, 1, 0, rate)
            }
        }
    }

    private fun stopSpray() {
        if (sprayStream != 0) { pool.stop(sprayStream); sprayStream = 0 }
    }

    private fun allowed() = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun drip() {
        SoundTap.listener?.invoke("DRIP")
        if (allowed()) pool.play(drip, 0.2f, 0.2f, 1, 0, 0.96f + Math.random().toFloat() * 0.08f)
    }

    /** Tekoucí voda z kohoutku během směny (smyčka, velmi potichu). */
    fun pour(on: Boolean) {
        SoundTap.listener?.invoke(if (on) "POUR_ON" else "POUR_OFF")
        if (on && allowed()) {
            if (pourStream == 0) pourStream = pool.play(pourId, 0.07f, 0.07f, 0, -1, 1f)
        } else if (pourStream != 0) {
            pool.stop(pourStream)
            pourStream = 0
        }
    }

    fun overflow(on: Boolean) {
        SoundTap.listener?.invoke(if (on) "OVERFLOW_ON" else "OVERFLOW_OFF")
        if (on && allowed()) {
            if (overflowStream == 0) overflowStream = pool.play(overflow, 0.12f, 0.12f, 0, -1, 1f)
        } else if (overflowStream != 0) {
            pool.stop(overflowStream)
            overflowStream = 0
        }
    }

    fun pauseAll() = pool.autoPause()
    fun resumeAll() = pool.autoResume()
    fun release() {
        overflow(false)
        pour(false)
        stopSpray()
        pool.release()
    }
}

/** Zvuky vody pro celou hlavní obrazovku; null = vypnuto. Při odchodu z aplikace se pozastaví. */
@Composable
fun rememberWaterSounds(enabled: Boolean): WaterSounds? {
    if (!enabled) return null
    val ctx = LocalContext.current
    val sounds = remember { WaterSounds(ctx.applicationContext) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> sounds.pauseAll()
                Lifecycle.Event.ON_RESUME -> sounds.resumeAll()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            sounds.release()
        }
    }
    return sounds
}

/** Napojení zvuků na animaci: kapka zazní přesně při dopadu, přetékání hraje po dobu přesčasu. */
@Composable
fun WaterSoundEffects(sounds: WaterSounds?, clock: State<Float>, drip: DripSync, dripping: Boolean, pouring: Boolean, overflowing: Boolean) {
    if (sounds == null) return
    DisposableEffect(sounds) { onDispose { sounds.overflow(false); sounds.pour(false) } }
    LaunchedEffect(pouring) { sounds.pour(pouring) }
    LaunchedEffect(overflowing) { sounds.overflow(overflowing) }
    LaunchedEffect(dripping) {
        if (!dripping) return@LaunchedEffect
        var prev = -1f
        snapshotFlow { clock.value }.collect { t ->
            val phase = t % DRIP_PERIOD_S
            val hit = drip.impactPhase
            if (hit > 0f && prev >= 0f && prev < hit && phase >= hit) sounds.drip()
            prev = phase
        }
    }
}

fun soundsEnabled(ctx: Context): Boolean = AppPrefs.waterSounds(ctx)
