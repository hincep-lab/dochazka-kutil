package cz.kutil.dochazka.widget

import android.content.Context
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.Rounding
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.core.WorkCalc
import cz.kutil.dochazka.data.Graph
import cz.kutil.dochazka.track.Notifier
import cz.kutil.dochazka.track.TrackingEngine
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Stav pro widget a dlaždici – vždy ze skutečné databáze (ne z dema). */
data class QuickStatus(
    val working: Boolean,
    val sinceText: String,
    val todayMinutes: Long,
    val monthMinutes: Long,
    val pending: Int,
)

object QuickActions {
    private fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(PRAGUE).let { "%d:%02d".format(it.hour, it.minute) }

    suspend fun status(ctx: Context): QuickStatus {
        val dao = Graph.realDb(ctx.applicationContext).dao()
        val open = dao.openShift()
        val today = LocalDate.now(PRAGUE)
        val shifts = dao.allShifts().map { it.toData() }
        val month = WorkCalc.month(YearMonth.from(today), shifts)
        // Dnešek včetně běžící směny (do teď), aby widget ukazoval průběžný čas.
        var todayMin = month.day(today.dayOfMonth).minutes
        var monthMin = month.totalMinutes
        if (open != null) {
            val start = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
            val dayStart = today.atStartOfDay(PRAGUE).toInstant().toEpochMilli()
            val running = ((System.currentTimeMillis() - maxOf(start, dayStart)) / 60_000L).coerceAtLeast(0)
            todayMin += running
            monthMin += running
        }
        return QuickStatus(
            working = open != null,
            sinceText = open?.let { hm(it.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(it.rawStart)).toEpochMilli()) } ?: "",
            todayMinutes = todayMin,
            monthMinutes = monthMin,
            pending = dao.openPending().count { !it.deferred },
        )
    }

    /** Začít práci / ukončit práci teď – z widgetu nebo dlaždice. Potvrzení přijde krátkým oznámením. */
    suspend fun toggle(ctx: Context) {
        val app = ctx.applicationContext
        val open = Graph.realDb(app).dao().openShift()
        val now = Instant.now()
        if (open == null) {
            TrackingEngine.handle(app, TrackEvent.ManualStart(now))
            Notifier.showDone(app, "Začátek práce zapsán", "Od ${hm(now.toEpochMilli())}, do výkazu ${hm(Rounding.roundToHalfHour(now).toEpochMilli())}.")
        } else {
            TrackingEngine.handle(app, TrackEvent.ManualEnd(now))
            Notifier.showDone(app, "Konec práce zapsán", "Konec ${hm(now.toEpochMilli())}, do výkazu ${hm(Rounding.roundToHalfHour(now).toEpochMilli())}. Čas můžeš opravit v aplikaci.")
        }
    }
}
