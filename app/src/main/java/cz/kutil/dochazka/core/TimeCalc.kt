package cz.kutil.dochazka.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** Jediné časové pásmo aplikace. */
val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")

private const val HALF_HOUR_MS = 30L * 60_000L

object Rounding {
    /**
     * Zaokrouhlení na nejbližší půlhodinu, přesná polovina (xx:15, xx:45) nahoru.
     * Počítá se nad epochou – posun Europe/Prague je vždy celá hodina, takže výsledek
     * odpovídá místnímu času a je jednoznačný i při změně času.
     */
    fun roundToHalfHour(instant: Instant): Instant {
        val ms = instant.toEpochMilli()
        val rounded = Math.floorDiv(ms + HALF_HOUR_MS / 2, HALF_HOUR_MS) * HALF_HOUR_MS
        return Instant.ofEpochMilli(rounded)
    }
}

/** Uzavřený pracovní interval. */
data class Interval(val start: Instant, val end: Instant) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}

/** Data směny bez závislosti na Androidu/Room. */
data class ShiftData(
    val id: Long,
    val rawStart: Instant,
    val rawEnd: Instant?,
    val editedStart: Instant? = null,
    val editedEnd: Instant? = null,
    val note: String = "",
)

/** Část intervalu spadající do jednoho kalendářního dne. */
data class DayPiece(
    val shiftId: Long,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    /** true = interval končí přesně o půlnoci (zapisuje se jako 24:00). */
    val endsAtMidnight: Boolean,
    val minutes: Long,
    val startInstant: Instant,
) {
    fun startText(): String = fmtTime(start)
    fun endText(): String = if (endsAtMidnight) "24:00" else fmtTime(end)
}

fun fmtTime(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

enum class DayFlag { SATURDAY, SUNDAY, HOLIDAY }

/** Ruční označení dne podle vysvětlivek originálního formuláře. */
enum class OverrideType(val code: String, val label: String) {
    DOVOLENA("D", "Dovolená"),
    VOLNO("S", "Volno (svátky a ostatní dny volna)"),
    NEMOC("N", "Nemoc"),
    OSETROVANI("O", "Ošetřování člena rodiny"),
    LEKAR("P", "Lékař / osobní překážka v práci"),
    NAHRADNI_VOLNO("NV", "Náhradní volno"),
    OBECNY_ZAJEM("V", "Volno z důvodu obecného zájmu"),
    ABSENCE("A", "Neomluvená absence");

    companion object {
        fun fromName(name: String?): OverrideType? = entries.firstOrNull { it.name == name }
    }
}

data class DaySummary(
    val date: LocalDate,
    val pieces: List<DayPiece>,
    val minutes: Long,
    val flags: Set<DayFlag>,
    val holidayName: String?,
    val override: OverrideType?,
    val overrideNote: String = "",
) {
    /** Štítky „Práce v sobotu / v neděli / ve svátek“ – jen pokud se ten den pracovalo. */
    val workLabels: List<String>
        get() = if (pieces.isEmpty()) emptyList() else buildList {
            if (DayFlag.SATURDAY in flags) add("Práce v sobotu")
            if (DayFlag.SUNDAY in flags) add("Práce v neděli")
            if (DayFlag.HOLIDAY in flags) add("Práce ve svátek")
        }
}

data class MonthSummary(
    val month: YearMonth,
    val days: List<DaySummary>,
    val totalMinutes: Long,
    /** Počet neuzavřených směn začínajících v měsíci (do součtu se nepočítají). */
    val openShifts: Int,
) {
    fun day(dayOfMonth: Int): DaySummary = days[dayOfMonth - 1]
}

enum class ShiftWarning(val text: String) {
    NEGATIVE("Konec je dřív než začátek."),
    ZERO("Po zaokrouhlení má interval nulovou délku."),
    SHORT("Velmi krátký interval (méně než 30 minut)."),
    LONG("Neobvykle dlouhý interval (více než 16 hodin)."),
    MIDNIGHT("Interval přechází přes půlnoc – rozdělí se do dvou dnů."),
    DST("V intervalu nebo ten den se mění čas (letní/zimní) – zkontroluj délku."),
    OVERLAP("Interval se překrývá s jiným záznamem."),
}

object WorkCalc {

    /** Časy, které jdou do výkazu: ruční oprava má přednost, jinak zaokrouhlený raw čas. */
    fun effective(s: ShiftData): Interval? {
        val rawEnd = s.rawEnd ?: return null
        val start = s.editedStart ?: Rounding.roundToHalfHour(s.rawStart)
        val end = s.editedEnd ?: Rounding.roundToHalfHour(rawEnd)
        return Interval(start, end)
    }

    fun effectiveStart(s: ShiftData): Instant = s.editedStart ?: Rounding.roundToHalfHour(s.rawStart)

    /** Rozdělí interval na části podle kalendářních dnů (půlnoc v Europe/Prague). */
    fun splitByDay(shiftId: Long, interval: Interval, zone: ZoneId = PRAGUE): List<DayPiece> {
        val out = ArrayList<DayPiece>()
        var cur = interval.start
        while (cur.isBefore(interval.end)) {
            val z = cur.atZone(zone)
            val nextMidnight = z.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            val pieceEnd = if (interval.end.isAfter(nextMidnight)) nextMidnight else interval.end
            val atMidnight = pieceEnd == nextMidnight
            out.add(
                DayPiece(
                    shiftId = shiftId,
                    date = z.toLocalDate(),
                    start = z.toLocalTime().withSecond(0).withNano(0),
                    end = if (atMidnight) LocalTime.MIDNIGHT else pieceEnd.atZone(zone).toLocalTime().withSecond(0).withNano(0),
                    endsAtMidnight = atMidnight,
                    minutes = Duration.between(cur, pieceEnd).toMinutes(),
                    startInstant = cur,
                )
            )
            cur = pieceEnd
        }
        return out
    }

    fun flags(date: LocalDate): Set<DayFlag> = buildSet {
        if (date.dayOfWeek == DayOfWeek.SATURDAY) add(DayFlag.SATURDAY)
        if (date.dayOfWeek == DayOfWeek.SUNDAY) add(DayFlag.SUNDAY)
        if (CzechHolidays.name(date) != null) add(DayFlag.HOLIDAY)
    }

    /**
     * Měsíční přehled. Každá minuta se počítá právě jednou – svátek o víkendu má dva štítky,
     * ale čas se nenásobí. Neuzavřené směny se do součtu nepočítají.
     */
    fun month(
        month: YearMonth,
        shifts: List<ShiftData>,
        overrides: Map<LocalDate, Pair<OverrideType, String>> = emptyMap(),
        zone: ZoneId = PRAGUE,
    ): MonthSummary {
        val byDay = HashMap<LocalDate, MutableList<DayPiece>>()
        var open = 0
        for (s in shifts) {
            val eff = effective(s)
            if (eff == null) {
                if (YearMonth.from(effectiveStart(s).atZone(zone)) == month) open++
                continue
            }
            if (!eff.end.isAfter(eff.start)) continue
            for (p in splitByDay(s.id, eff, zone)) {
                if (YearMonth.from(p.date) == month) byDay.getOrPut(p.date) { ArrayList() }.add(p)
            }
        }
        val days = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            val pieces = (byDay[date] ?: emptyList<DayPiece>()).sortedBy { it.startInstant }
            val ov = overrides[date]
            DaySummary(
                date = date,
                pieces = pieces,
                minutes = pieces.sumOf { it.minutes },
                flags = flags(date),
                holidayName = CzechHolidays.name(date),
                override = ov?.first,
                overrideNote = ov?.second ?: "",
            )
        }
        return MonthSummary(month, days, days.sumOf { it.minutes }, open)
    }

    /** 510 -> "8:30", 11160 -> "186:00". */
    fun formatHm(minutes: Long): String {
        val sign = if (minutes < 0) "-" else ""
        val m = Math.abs(minutes)
        return "%s%d:%02d".format(sign, m / 60, m % 60)
    }

    fun hasDstChange(start: Instant, end: Instant, zone: ZoneId = PRAGUE): Boolean {
        val from = start.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val to = end.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return zone.rules.getOffset(from) != zone.rules.getOffset(to)
    }

    /** Varování k intervalu, která musí uživatel výslovně potvrdit. */
    fun validate(
        start: Instant,
        end: Instant,
        others: List<Interval> = emptyList(),
        zone: ZoneId = PRAGUE,
    ): List<ShiftWarning> {
        val w = ArrayList<ShiftWarning>()
        if (end.isBefore(start)) {
            w.add(ShiftWarning.NEGATIVE)
            return w
        }
        val minutes = Duration.between(start, end).toMinutes()
        if (minutes == 0L) w.add(ShiftWarning.ZERO)
        else if (minutes < 30) w.add(ShiftWarning.SHORT)
        if (minutes > 16 * 60) w.add(ShiftWarning.LONG)
        if (minutes > 0 && splitByDay(0, Interval(start, end), zone).size > 1) w.add(ShiftWarning.MIDNIGHT)
        if (hasDstChange(start, end, zone)) w.add(ShiftWarning.DST)
        if (others.any { overlaps(it, Interval(start, end)) }) w.add(ShiftWarning.OVERLAP)
        return w
    }

    fun overlaps(a: Interval, b: Interval): Boolean = a.start.isBefore(b.end) && b.start.isBefore(a.end)
}
