package cz.kutil.dochazka.core

import java.time.LocalDate

/** České státní svátky a ostatní svátky (dny pracovního klidu) včetně pohyblivých Velikonoc. */
object CzechHolidays {

    /** Velikonoční neděle – gregoriánský (Meeus/Jones/Butcher) algoritmus. */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    fun holidays(year: Int): Map<LocalDate, String> {
        val easter = easterSunday(year)
        val map = LinkedHashMap<LocalDate, String>()
        map[LocalDate.of(year, 1, 1)] = "Nový rok, Den obnovy samostatného českého státu"
        map[easter.minusDays(2)] = "Velký pátek"
        map[easter.plusDays(1)] = "Velikonoční pondělí"
        map[LocalDate.of(year, 5, 1)] = "Svátek práce"
        map[LocalDate.of(year, 5, 8)] = "Den vítězství"
        map[LocalDate.of(year, 7, 5)] = "Den slovanských věrozvěstů Cyrila a Metoděje"
        map[LocalDate.of(year, 7, 6)] = "Den upálení mistra Jana Husa"
        map[LocalDate.of(year, 9, 28)] = "Den české státnosti"
        map[LocalDate.of(year, 10, 28)] = "Den vzniku samostatného československého státu"
        map[LocalDate.of(year, 11, 17)] = "Den boje za svobodu a demokracii"
        map[LocalDate.of(year, 12, 24)] = "Štědrý den"
        map[LocalDate.of(year, 12, 25)] = "1. svátek vánoční"
        map[LocalDate.of(year, 12, 26)] = "2. svátek vánoční"
        return map
    }

    fun name(date: LocalDate): String? = holidays(date.year)[date]

    fun isHoliday(date: LocalDate): Boolean = name(date) != null
}
