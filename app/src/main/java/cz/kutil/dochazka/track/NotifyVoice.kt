package cz.kutil.dochazka.track

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import cz.kutil.dochazka.R
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.data.AppPrefs

/**
 * Mluvící oznámení: po zobrazení oznámení ho Kutil krátce řekne nahlas.
 * Nahrávky jsou v aplikaci (res/raw/notif_*.ogg), nic se nestahuje.
 * Mlčí v tichém režimu, při vibracích, v režimu Nerušit a v noci 22:00–6:00.
 */
object NotifyVoice {
    enum class Line(val res: Int) {
        START(R.raw.notif_start), END(R.raw.notif_end), TRIP(R.raw.notif_trip), LONG_SHIFT(R.raw.notif_long),
        MONTHLY(R.raw.notif_month), BACKUP(R.raw.notif_backup), UPDATE(R.raw.notif_update),
        STARTED(R.raw.notif_started), ENDED(R.raw.notif_ended), TEST(R.raw.notif_test),
        BACKUP_OK(R.raw.notif_backup_ok), BACKUP_FAIL(R.raw.notif_backup_fail),
    }

    private var player: MediaPlayer? = null

    /** Zda by teď hlas zazněl (nastavení, tichý režim, Nerušit, noc). */
    fun allowed(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!AppPrefs.talkingNotifications(ctx)) return false
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return false
        val h = java.time.Instant.ofEpochMilli(nowMs).atZone(PRAGUE).hour
        return h in 6..21
    }

    /** Řekne hlášku chvilku po zvuku oznámení (ať se nepřekrývají). */
    fun say(ctx: Context, line: Line, delayMs: Long = 700) {
        val app = ctx.applicationContext
        if (!allowed(app)) return
        Handler(Looper.getMainLooper()).postDelayed({ play(app, line) }, delayMs)
    }

    private fun play(ctx: Context, line: Line) {
        runCatching {
            player?.release()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            player = MediaPlayer.create(ctx, line.res, attrs, 0)?.apply {
                setOnCompletionListener { it.release(); if (player === it) player = null }
                start()
            }
        }
    }
}
