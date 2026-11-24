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
 * Zvuky jsou syntetizované (tools/render_sounds.py), krátké a tiché. Hrají jen když:
 *  - je zapnuté nastavení „Zvuky vody“ i dekorativní animace,
 *  - telefon není v tichém režimu ani na vibracích,
 *  - aplikace je na obrazovce (při odchodu z aplikace se vše zastaví).
 */
class WaterSounds(ctx: Context) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val drip = pool.load(ctx, R.raw.water_drip, 1)
    private val overflow = pool.load(ctx, R.raw.water_overflow, 1)
    private val clank = pool.load(ctx, R.raw.gag_clank, 1)
    private val splash = pool.load(ctx, R.raw.gag_splash, 1)
    private val boing = pool.load(ctx, R.raw.gag_boing, 1)
    private val thud = pool.load(ctx, R.raw.gag_thud, 1)
    private val spray = pool.load(ctx, R.raw.gag_spray, 1)
    private var overflowStream = 0
    private var sprayStream = 0

    /** Zvuky scénky s instalatérem. */
    fun gag(s: GagSound) {
        if (!allowed()) {
            stopSpray(); return
        }
        when (s) {
            GagSound.BURST_START -> if (sprayStream == 0) sprayStream = pool.play(spray, 0.3f, 0.3f, 0, -1, 1f)
            GagSound.BURST_STOP -> stopSpray()
            GagSound.SPLASH -> pool.play(splash, 0.5f, 0.5f, 1, 0, 1f)
            GagSound.CLANK -> pool.play(clank, 0.45f, 0.45f, 1, 0, 0.95f + Math.random().toFloat() * 0.1f)
            GagSound.BOING -> pool.play(boing, 0.45f, 0.45f, 1, 0, 1f)
            GagSound.THUD -> pool.play(thud, 0.5f, 0.5f, 1, 0, 1f)
        }
    }

    private fun stopSpray() {
        if (sprayStream != 0) { pool.stop(sprayStream); sprayStream = 0 }
    }

    private fun allowed() = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun drip() {
        if (allowed()) pool.play(drip, 0.35f, 0.35f, 1, 0, 0.96f + Math.random().toFloat() * 0.08f)
    }

    fun overflow(on: Boolean) {
        if (on && allowed()) {
            if (overflowStream == 0) overflowStream = pool.play(overflow, 0.22f, 0.22f, 0, -1, 1f)
        } else if (overflowStream != 0) {
            pool.stop(overflowStream)
            overflowStream = 0
        }
    }

    fun pauseAll() = pool.autoPause()
    fun resumeAll() = pool.autoResume()
    fun release() {
        overflow(false)
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
fun WaterSoundEffects(sounds: WaterSounds?, clock: State<Float>, drip: DripSync, dripping: Boolean, overflowing: Boolean) {
    if (sounds == null) return
    DisposableEffect(sounds) { onDispose { sounds.overflow(false) } }
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
