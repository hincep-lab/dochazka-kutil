package cz.kutil.dochazka.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Ukázková (akceptační) data – listopad 2026. Používají se jen v testech a v odděleném
 * demo režimu aplikace (samostatná databáze), nikdy se nemíchají se skutečnou docházkou.
 */
object DemoData {
    val MONTH: YearMonth = YearMonth.of(2026, 11)

    fun at(date: LocalDate, hour: Int, minute: Int): Instant =
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute).atZone(PRAGUE).toInstant()

    /**
     * 21 všedních dnů 07:00–15:30 (včetně svátku 17. 11.), 12. 11. druhý výjezd 17:00–19:00,
     * sobota 14. 11. 09:00–12:00, neděle 15. 11. 10:00–12:30. Celkem 186:00.
     */
    fun november2026(): List<ShiftData> {
        val list = ArrayList<ShiftData>()
        var id = 1L
        fun add(day: Int, h1: Int, m1: Int, h2: Int, m2: Int) {
            val d = MONTH.atDay(day)
            list.add(ShiftData(id++, at(d, h1, m1), at(d, h2, m2)))
        }
        for (day in 1..MONTH.lengthOfMonth()) {
            val dow = MONTH.atDay(day).dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) add(day, 7, 0, 15, 30)
        }
        add(12, 17, 0, 19, 0)
        add(14, 9, 0, 12, 0)
        add(15, 10, 0, 12, 30)
        return list
    }
}
