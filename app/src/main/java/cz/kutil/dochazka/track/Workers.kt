package cz.kutil.dochazka.track

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cz.kutil.dochazka.core.LongShift
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.data.EventSource
import cz.kutil.dochazka.data.Graph
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Ověření nejistého odchodu (telefon byl na domácí Wi-Fi nebo byla poloha nepřesná).
 * Po krátké prodlevě se JEDNORÁZOVĚ zjistí čerstvá poloha – žádné průběžné sledování GPS:
 *  - domácí Wi-Fi stále připojená, nebo poloha jistě uvnitř zóny → falešný poplach, bez oznámení;
 *  - poloha jistě mimo zónu → dotaz se zobrazí s původním časem události;
 *  - polohu ani Wi-Fi nejde zjistit (výpadek) → dotaz se zobrazí označený jako nejistý.
 */
class VerifyExitWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val dao = Graph.realDb(app).dao()
        val p = dao.pending(id) ?: return Result.success()
        if (p.status != "PENDING" || !p.deferred) return Result.success()

        val wifi = WifiHelper.isOnHomeWifi(app)
        var stillHome = wifi == true
        var uncertain = false
        var note = if (stillHome) "Telefon je stále na domácí Wi-Fi." else ""
        if (!stillHome) {
            val zone = dao.zone()
            val loc = GeofenceManager.currentLocation(app)
            if (loc != null && zone != null) {
                val distance = GeofenceManager.distanceM(loc.latitude, loc.longitude, zone.latitude, zone.longitude)
                val accuracy = if (loc.hasAccuracy()) loc.accuracy else 100f
                when {
                    distance + accuracy <= zone.radiusM -> {
                        stillHome = true
                        note = "Čerstvá poloha je uvnitř domácí zóny."
                    }
                    distance - accuracy > zone.radiusM ->
                        note = "Odchod ověřen čerstvou polohou (asi ${distance.roundToInt()} m od domova, přesnost ±${accuracy.roundToInt()} m)."
                    else -> {
                        uncertain = true
                        note = "Poloha je na hranici zóny (přesnost ±${accuracy.roundToInt()} m) – zkontroluj čas."
                    }
                }
            } else {
                uncertain = true
                note = "Polohu ani domácí Wi-Fi se nepodařilo ověřit (výpadek GPS nebo Wi-Fi)."
            }
        }
        TrackingEngine.handle(
            app,
            TrackEvent.WifiRecheck(id, stillHome, Instant.now(), uncertain),
            meta = EventMeta(source = if (wifi != null) EventSource.GPS_WIFI else EventSource.GPS, note = note, uncertain = uncertain),
        )
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "pending_id"
        const val DELAY_MINUTES = 3L

        fun schedule(ctx: Context, pendingId: Long) {
            val request = OneTimeWorkRequestBuilder<VerifyExitWorker>()
                .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_ID to pendingId))
                .build()
            // KEEP: opakované naplánování téhož ověření (např. po restartu) nevytvoří druhé.
            WorkManager.getInstance(ctx).enqueueUniqueWork("verify_exit_$pendingId", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Pojistka: Android zónu zahodí např. po vypnutí polohy nebo smazání dat Google Play služeb.
 * Jednou za pár hodin se proto registrace obnoví (bez vynuceného okamžitého vyhodnocení,
 * aby v noci nevznikaly falešné dotazy).
 */
class ZoneHealthWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val settings = Graph.realDb(applicationContext).dao().settings()
        if (settings?.trackingEnabled == true) {
            val result = GeofenceManager.register(applicationContext, selfHeal = false)
            if (!result.ok) TrackingEngine.diagnostic(applicationContext, "Zóna není aktivní: ${result.message}")
        }
        return Result.success()
    }

    companion object {
        fun ensureScheduled(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<ZoneHealthWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("zone_health", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Ochrana proti zapomenutému ukončení práce. Každou půlhodinu zkontroluje otevřenou směnu;
 * překročí-li nastavený limit, pošle upozornění. Směnu NIKDY neukončuje sama.
 */
class LongShiftWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        checkNow(applicationContext)
        return Result.success()
    }

    companion object {
        fun ensureScheduled(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<LongShiftWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("long_shift", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        suspend fun checkNow(ctx: Context) {
            val app = ctx.applicationContext
            val dao = Graph.realDb(app).dao()
            val open = dao.openShift()
            if (open == null) {
                Notifier.cancelLongShift(app)
                return
            }
            val now = System.currentTimeMillis()
            val (ackId, ackAt) = AppPrefs.longShiftAck(app)
            val (notifiedId, notifiedAt) = AppPrefs.longShiftNotified(app)
            val due = LongShift.isDue(
                nowMs = now,
                startMs = open.rawStart,
                limitHours = AppPrefs.longShiftHours(app),
                ackAtMs = if (ackId == open.id) ackAt else null,
                notifiedAtMs = if (notifiedId == open.id) notifiedAt else null,
            )
            if (!due) return
            val returnAt = dao.lastReturnFor(open.id)?.effectiveAt?.takeIf { it > open.rawStart }
            val hours = (now - open.rawStart) / 3_600_000L
            if (Notifier.showLongShift(app, open.id, open.rawStart, hours, returnAt)) {
                AppPrefs.setLongShiftNotified(app, open.id, now)
            }
        }
    }
}
