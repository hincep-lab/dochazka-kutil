package cz.kutil.dochazka.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.track.Notifier
import java.util.concurrent.TimeUnit

/** Jednou denně (jen s internetem) zkontroluje, zda na GitHubu není novější sestavení, a jednou o něm dá vědět. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!AppPrefs.autoUpdateCheck(app)) return Result.success()
        val rel = try {
            Updater.latest(Updater.installedBuild(applicationContext))
        } catch (e: Exception) {
            return Result.success()
        } ?: return Result.success()
        AppPrefs.setLatestKnownBuild(app, rel.build)
        if (rel.build > Updater.installedBuild(app) && AppPrefs.lastUpdateNotified(app) < rel.build) {
            if (Notifier.showUpdate(app, rel.build)) AppPrefs.setLastUpdateNotified(app, rel.build)
        }
        return Result.success()
    }

    companion object {
        fun ensureScheduled(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update_check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
