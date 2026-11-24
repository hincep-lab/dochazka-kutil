package cz.kutil.dochazka.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class AnomalyKind { FORGOTTEN_END, PENDING_GPS, OVERLAP, LONG_SHIFT, MISSING_RECORD }

/** Nesrovnalost v docházce. [date] = den, kterého se týká (pro otevření detailu dne). */
data class Anomaly(val kind: AnomalyKind, val title: String, val detail: String, val date: LocalDate? = null)

/**
 * Kontrola nesrovnalostí – jen v aplikaci, bez dalších oznámení, aby neobtěžovala.
 * Hlídá: zapomenuté ukončení práce, nevyřízené návrhy z GPS, překrývající se intervaly,
 * neobvykle dlouhé směny a pracovní dny bez záznamu (od prvního záznamu v aplikaci, max. 14 dní zpět).
 */
object Anomalies {
    const val LOOKBACK_DAYS = 14L
    const val UNUSUAL_MINUTES = 14 * 60L

    fun find(
        now: Instant,
        shifts: List<ShiftData>,
        overrides: Map<LocalDate, *>,
        pendingCount: Int,
        longShiftHours: Int,
    ): List<Anomaly> {
        val out = ArrayList<Anomaly>()
        val today = now.atZone(PRAGUE).toLocalDate()
        val from = today.minusDays(LOOKBACK_DAYS)

        // 1) zapomenuté ukončení: otevřená směna přes limit nebo přes půlnoc
        shifts.filter { it.rawEnd == null }.forEach { s ->
            val start = WorkCalc.effectiveStart(s)
            val hours = Duration.between(start, now).toHours()
            val startDay = start.atZone(PRAGUE).toLocalDate()
            if (hours >= longShiftHours || startDay.isBefore(today)) {
                out.add(
                    Anomaly(
                        AnomalyKind.FORGOTTEN_END,
                        "Směna běží už $hours h",
                        "Začala ${startDay.dayOfMonth}. ${startDay.monthValue}. Nezapomněl jsi ji ukončit?",
                        startDay,
                    )
                )
            }
        }

        // 2) nevyřízené návrhy z GPS
        if (pendingCount > 0) {
            out.add(
                Anomaly(
                    AnomalyKind.PENDING_GPS,
                    if (pendingCount == 1) "1 událost čeká na potvrzení" else "$pendingCount události čekají na potvrzení",
                    "Odchod nebo návrat zaznamenaný polohou – potvrď, odmítni nebo uprav čas.",
                )
            )
        }

        // 3) překryvy a 4) neobvykle dlouhé směny v posledních 14 dnech
        val closed = shifts.mapNotNull { s -> WorkCalc.effective(s)?.let { s to it } }
            .filter { (_, i) -> i.end.isAfter(i.start) && !i.end.atZone(PRAGUE).toLocalDate().isBefore(from) }
            .sortedBy { it.second.start }
        val overlapDays = sortedSetOf<LocalDate>()
        for (a in closed.indices) for (b in a + 1 until closed.size) {
            if (WorkCalc.overlaps(closed[a].second, closed[b].second)) overlapDays.add(closed[b].second.start.atZone(PRAGUE).toLocalDate())
        }
        if (overlapDays.isNotEmpty()) {
            out.add(Anomaly(AnomalyKind.OVERLAP, "Překrývající se záznamy", "Dny: ${days(overlapDays)} – stejný čas je zapsaný dvakrát.", overlapDays.first()))
        }
        val longDays = sortedSetOf<LocalDate>()
        closed.filter { it.second.minutes > UNUSUAL_MINUTES }.forEach { longDays.add(it.second.start.atZone(PRAGUE).toLocalDate()) }
        if (longDays.isNotEmpty()) {
            out.add(Anomaly(AnomalyKind.LONG_SHIFT, "Neobvykle dlouhá směna", "Přes 14 hodin: ${days(longDays)}. Zkontroluj konec.", longDays.first()))
        }

        // 5) pracovní dny bez záznamu (po–pá mimo svátky), jen od prvního záznamu v aplikaci
        val first = shifts.minOfOrNull { WorkCalc.effectiveStart(it) }?.atZone(PRAGUE)?.toLocalDate()
        if (first != null) {
            val worked = HashSet<LocalDate>()
            shifts.forEach { s ->
                val eff = WorkCalc.effective(s)
                if (eff != null) WorkCalc.splitByDay(s.id, eff).forEach { worked.add(it.date) }
                else worked.add(WorkCalc.effectiveStart(s).atZone(PRAGUE).toLocalDate())
            }
            val missing = sortedSetOf<LocalDate>()
            var d = maxOf(first, from)
            while (d.isBefore(today)) {
                val workday = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && !CzechHolidays.isHoliday(d)
                if (workday && d !in worked && !overrides.containsKey(d)) missing.add(d)
                d = d.plusDays(1)
            }
            if (missing.isNotEmpty()) {
                out.add(
                    Anomaly(
                        AnomalyKind.MISSING_RECORD,
                        if (missing.size == 1) "Chybí záznam za 1 pracovní den" else "Chybí záznam za ${missing.size} pracovní dny",
                        "${days(missing)} – doplň práci, nebo označ dovolenou či nemoc.",
                        missing.first(),
                    )
                )
            }
        }
        return out
    }

    private fun days(set: Collection<LocalDate>): String {
        val list = set.take(5).joinToString(", ") { "${it.dayOfMonth}. ${it.monthValue}." }
        return if (set.size > 5) "$list …" else list
    }

    /** Pomocník pro testy a UI: označení dnů v měsíci jako mapa. */
    fun overridesOf(month: MonthSummary): Map<LocalDate, OverrideType> =
        month.days.filter { it.override != null }.associate { it.date to it.override!! }

    @Suppress("unused")
    private fun monthOf(d: LocalDate) = YearMonth.from(d)
}
