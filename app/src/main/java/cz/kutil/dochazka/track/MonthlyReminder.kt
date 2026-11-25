package cz.kutil.dochazka.track

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.WorkCalc
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.data.Graph
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Měsíční uzávěrka: od 1. dne v měsíci (po 7. hodině ráno) jednou připomene, že je docházka
 * za minulý měsíc připravená ke kontrole a odeslání. Kontroluje se několikrát denně, takže
 * připomenutí přijde i tehdy, když byl telefon prvního vypnutý.
 */
class MonthlyReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val now = ZonedDateTime.now(PRAGUE)
        if (now.hour < 7) return Result.success()
        backupReminder(ctx, now)
        if (!AppPrefs.reminderEnabled(ctx)) return Result.success()
        val previous = YearMonth.from(now).minusMonths(1)
        if (AppPrefs.lastReminded(ctx) == previous.toString()) return Result.success()

        val dao = Graph.realDb(ctx).dao()
        val summary = WorkCalc.month(previous, dao.allShifts().map { it.toData() })
        val hasOverrides = dao.allOverrides().any { it.date.startsWith(previous.toString()) }
        val hasData = summary.totalMinutes > 0 || summary.openShifts > 0 || hasOverrides
        if (!hasData) {
            // Prázdný měsíc (např. těsně po instalaci) se nepřipomíná.
            AppPrefs.setLastReminded(ctx, previous.toString())
            return Result.success()
        }
        // Bez povolených oznámení se měsíc neoznačí jako připomenutý – zkusí se to příště.
        if (Notifier.showMonthly(ctx, previous, summary.totalMinutes, summary.openShifts)) {
            AppPrefs.setLastReminded(ctx, previous.toString())
        }
        return Result.success()
    }

    /**
     * Hlídání zálohy: když poslední úspěšná záloha je starší než [STALE_DAYS] dní (a v aplikaci jsou data),
     * připomene se to – nejvýš jednou denně, mezi 8. a 20. hodinou.
     */
    private suspend fun backupReminder(ctx: Context, now: ZonedDateTime) {
        if (!AppPrefs.backupReminder(ctx)) return
        if (now.hour !in 8..19) return
        val today = now.toLocalDate().toString()
        if (AppPrefs.lastBackupReminded(ctx) == today) return
        val last = AppPrefs.lastBackupAt(ctx)
        val fresh = last > 0 && System.currentTimeMillis() - last < STALE_DAYS * 24L * 3600 * 1000
        if (fresh) return
        val hasData = Graph.realDb(ctx).dao().allShifts().isNotEmpty()
        if (!hasData) return
        if (Notifier.showBackupReminder(ctx, last)) AppPrefs.setLastBackupReminded(ctx, today)
    }

    companion object {
        /** Po kolika dnech bez zálohy se Kutil ozve. */
        const val STALE_DAYS = 3

        fun ensureScheduled(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<MonthlyReminderWorker>(4, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("monthly_reminder", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
