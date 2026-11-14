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
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.data.Graph
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Odchod byl zaznamenán, ale telefon byl ještě na domácí Wi-Fi. Za pár minut se ověří znovu:
 * stále doma = falešný poplach (bez oznámení); jinak se dotaz zobrazí s původním časem události.
 */
class WifiRecheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val stillHome = WifiHelper.isOnHomeWifi(applicationContext) == true
        TrackingEngine.handle(applicationContext, TrackEvent.WifiRecheck(id, stillHome, Instant.now()))
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "pending_id"
        fun schedule(ctx: Context, pendingId: Long) {
            val request = OneTimeWorkRequestBuilder<WifiRecheckWorker>()
                .setInitialDelay(4, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_ID to pendingId))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("wifi_recheck_$pendingId", ExistingWorkPolicy.REPLACE, request)
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
