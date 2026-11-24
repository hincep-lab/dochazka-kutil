package cz.kutil.dochazka.ui

import cz.kutil.dochazka.core.PRAGUE
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

object Fmt {
    private val SHORT = mapOf(
        DayOfWeek.MONDAY to "Po", DayOfWeek.TUESDAY to "Út", DayOfWeek.WEDNESDAY to "St", DayOfWeek.THURSDAY to "Čt",
        DayOfWeek.FRIDAY to "Pá", DayOfWeek.SATURDAY to "So", DayOfWeek.SUNDAY to "Ne",
    )
    private val LONG = mapOf(
        DayOfWeek.MONDAY to "pondělí", DayOfWeek.TUESDAY to "úterý", DayOfWeek.WEDNESDAY to "středa", DayOfWeek.THURSDAY to "čtvrtek",
        DayOfWeek.FRIDAY to "pátek", DayOfWeek.SATURDAY to "sobota", DayOfWeek.SUNDAY to "neděle",
    )
    private val MONTHS = listOf(
        "Leden", "Únor", "Březen", "Duben", "Květen", "Červen", "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec",
    )

    private val GENITIVE = listOf(
        "ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince",
    )

    /** "listopadu" – pro „úterý 24. listopadu“. */
    fun monthGenitive(d: LocalDate): String = GENITIVE[d.monthValue - 1]

    fun zoned(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(PRAGUE)
    fun time(ms: Long): String = zoned(ms).let { "%02d:%02d".format(it.hour, it.minute) }
    fun time(i: Instant): String = time(i.toEpochMilli())
    fun dowShort(d: LocalDate): String = SHORT[d.dayOfWeek] ?: ""
    fun dowLong(d: LocalDate): String = LONG[d.dayOfWeek] ?: ""
    fun date(d: LocalDate): String = "${d.dayOfMonth}. ${d.monthValue}. ${d.year}"
    fun dateShort(d: LocalDate): String = "${d.dayOfMonth}. ${d.monthValue}."
    fun dateTime(ms: Long): String = zoned(ms).let { "${dowShort(it.toLocalDate())} ${dateShort(it.toLocalDate())} ${time(ms)}" }
    fun month(m: YearMonth): String = "${MONTHS[m.monthValue - 1]} ${m.year}"
    fun today(): LocalDate = LocalDate.now(PRAGUE)
}
