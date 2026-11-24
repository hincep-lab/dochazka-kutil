package cz.kutil.dochazka

import cz.kutil.dochazka.core.CzechHolidays
import cz.kutil.dochazka.core.DayFlag
import cz.kutil.dochazka.core.DemoData
import cz.kutil.dochazka.core.Interval
import cz.kutil.dochazka.core.OverrideType
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.Rounding
import cz.kutil.dochazka.core.ShiftData
import cz.kutil.dochazka.core.ShiftWarning
import cz.kutil.dochazka.core.WorkCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Instant =
    LocalDateTime.of(y, mo, d, h, mi, s).atZone(PRAGUE).toInstant()

private fun hm(i: Instant): String = i.atZone(PRAGUE).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

class RoundingTest {
    private fun r(h: Int, m: Int, s: Int = 0) = hm(Rounding.roundToHalfHour(t(2026, 11, 12, h, m, s)))

    @Test fun zadaneHodnoty() {
        assertEquals("07:00", r(6, 50))
        assertEquals("15:30", r(15, 15))
        assertEquals("15:30", r(15, 40))
        assertEquals("16:00", r(15, 50))
    }

    @Test fun presnaPolovinaNahoru() {
        assertEquals("07:30", r(7, 15))
        assertEquals("08:00", r(7, 45))
        assertEquals("07:00", r(7, 14, 59))
        assertEquals("07:30", r(7, 44, 59))
    }

    @Test fun presneCasyZustavaji() {
        assertEquals("07:00", r(7, 0))
        assertEquals("15:30", r(15, 30))
    }

    @Test fun zaokrouhleniPresPulnoc() {
        val rounded = Rounding.roundToHalfHour(t(2026, 11, 12, 23, 50)).atZone(PRAGUE)
        assertEquals(LocalDate.of(2026, 11, 13), rounded.toLocalDate())
        assertEquals(0, rounded.hour)
    }

    @Test fun rawHodnotyZachovane() {
        val s = ShiftData(1, t(2026, 11, 12, 6, 50), t(2026, 11, 12, 15, 40))
        val eff = WorkCalc.effective(s)!!
        assertEquals("07:00", hm(eff.start))
        assertEquals("15:30", hm(eff.end))
        assertEquals("06:50", hm(s.rawStart))
        assertEquals("15:40", hm(s.rawEnd!!))
        assertEquals(510, eff.minutes)
    }

    @Test fun rucniOpravaMaPrednost() {
        val s = ShiftData(1, t(2026, 11, 12, 6, 50), t(2026, 11, 12, 15, 40), editedEnd = t(2026, 11, 12, 16, 0))
        val eff = WorkCalc.effective(s)!!
        assertEquals("07:00", hm(eff.start))
        assertEquals("16:00", hm(eff.end))
    }
}

class HolidaysTest {
    @Test fun velikonoce() {
        assertEquals(LocalDate.of(2026, 4, 5), CzechHolidays.easterSunday(2026))
        assertEquals(LocalDate.of(2025, 4, 20), CzechHolidays.easterSunday(2025))
        assertEquals(LocalDate.of(2027, 3, 28), CzechHolidays.easterSunday(2027))
        assertEquals("Velký pátek", CzechHolidays.name(LocalDate.of(2026, 4, 3)))
        assertEquals("Velikonoční pondělí", CzechHolidays.name(LocalDate.of(2026, 4, 6)))
        assertNull(CzechHolidays.name(LocalDate.of(2026, 4, 5)))
    }

    @Test fun pevneSvatky() {
        assertEquals(13, CzechHolidays.holidays(2026).size)
        assertTrue(CzechHolidays.isHoliday(LocalDate.of(2026, 11, 17)))
        assertTrue(CzechHolidays.isHoliday(LocalDate.of(2026, 12, 24)))
        assertTrue(CzechHolidays.isHoliday(LocalDate.of(2026, 1, 1)))
        assertFalse(CzechHolidays.isHoliday(LocalDate.of(2026, 11, 18)))
    }

    @Test fun vikendyASvatekListopad2026() {
        assertEquals(setOf(DayFlag.SATURDAY), WorkCalc.flags(LocalDate.of(2026, 11, 14)))
        assertEquals(setOf(DayFlag.SUNDAY), WorkCalc.flags(LocalDate.of(2026, 11, 15)))
        assertEquals(setOf(DayFlag.HOLIDAY), WorkCalc.flags(LocalDate.of(2026, 11, 17)))
        assertTrue(WorkCalc.flags(LocalDate.of(2026, 11, 16)).isEmpty())
    }

    @Test fun svatekOVikenduMaObaStitkyAleCasJednou() {
        // 28. 9. 2024 = sobota a zároveň státní svátek
        val d = LocalDate.of(2024, 9, 28)
        assertEquals(setOf(DayFlag.SATURDAY, DayFlag.HOLIDAY), WorkCalc.flags(d))
        val m = WorkCalc.month(YearMonth.of(2024, 9), listOf(ShiftData(1, t(2024, 9, 28, 9, 0), t(2024, 9, 28, 12, 0))))
        assertEquals(listOf("Práce v sobotu", "Práce ve svátek"), m.day(28).workLabels)
        assertEquals(180, m.day(28).minutes)
        assertEquals(180, m.totalMinutes)
    }
}

class WorkCalcTest {
    @Test fun dvaVyjezdyZaDen() {
        val shifts = listOf(
            ShiftData(1, t(2026, 11, 12, 7, 0), t(2026, 11, 12, 15, 30)),
            ShiftData(2, t(2026, 11, 12, 17, 0), t(2026, 11, 12, 19, 0)),
        )
        val day = WorkCalc.month(YearMonth.of(2026, 11), shifts).day(12)
        assertEquals(2, day.pieces.size)
        assertEquals("10:30", WorkCalc.formatHm(day.minutes))
        assertEquals("17:00", day.pieces[1].startText())
    }

    @Test fun beznaPraceAVecerniVyjezdJe11Hodin() {
        val shifts = listOf(
            ShiftData(1, t(2026, 11, 12, 7, 0), t(2026, 11, 12, 15, 30)),
            ShiftData(2, t(2026, 11, 12, 17, 0), t(2026, 11, 12, 19, 30)),
        )
        val day = WorkCalc.month(YearMonth.of(2026, 11), shifts).day(12)
        assertEquals("11:00", WorkCalc.formatHm(day.minutes))
        assertEquals(150, day.overtimeMinutes)
    }

    @Test fun neomezenyPocetIntervaluZaDen() {
        val shifts = (0 until 8).map { ShiftData(it.toLong(), t(2026, 11, 12, 6 + 2 * it, 0), t(2026, 11, 12, 7 + 2 * it, 0)) }
        val day = WorkCalc.month(YearMonth.of(2026, 11), shifts).day(12)
        assertEquals(8, day.pieces.size)
        assertEquals(480, day.minutes)
    }

    @Test fun simulovanyListopad2026Je186Hodin() {
        val m = WorkCalc.month(DemoData.MONTH, DemoData.november2026())
        assertEquals(21, m.days.count { it.pieces.isNotEmpty() && it.flags.none { f -> f == DayFlag.SATURDAY || f == DayFlag.SUNDAY } })
        assertEquals("186:00", WorkCalc.formatHm(m.totalMinutes))
        // svátek 17. 11. je započten právě jednou
        assertEquals(510, m.day(17).minutes)
        assertEquals(listOf("Práce ve svátek"), m.day(17).workLabels)
        assertEquals(listOf("Práce v sobotu"), m.day(14).workLabels)
        assertEquals(listOf("Práce v neděli"), m.day(15).workLabels)
        assertEquals(180, m.day(14).minutes)
        assertEquals(150, m.day(15).minutes)
        assertTrue(m.day(16).workLabels.isEmpty())
    }

    @Test fun rozdeleniPresPulnoc() {
        val pieces = WorkCalc.splitByDay(1, Interval(t(2026, 11, 12, 22, 0), t(2026, 11, 13, 2, 0)))
        assertEquals(2, pieces.size)
        assertEquals(LocalDate.of(2026, 11, 12), pieces[0].date)
        assertEquals("22:00", pieces[0].startText())
        assertEquals("24:00", pieces[0].endText())
        assertEquals(120, pieces[0].minutes)
        assertEquals(LocalDate.of(2026, 11, 13), pieces[1].date)
        assertEquals("00:00", pieces[1].startText())
        assertEquals("02:00", pieces[1].endText())
        assertEquals(120, pieces[1].minutes)
    }

    @Test fun pulnocniVyjezdNaPrelomuMesice() {
        val s = ShiftData(1, t(2026, 11, 30, 22, 0), t(2026, 12, 1, 3, 0))
        assertEquals(120, WorkCalc.month(YearMonth.of(2026, 11), listOf(s)).totalMinutes)
        assertEquals(180, WorkCalc.month(YearMonth.of(2026, 12), listOf(s)).totalMinutes)
    }

    @Test fun zmenaCasuPocitaSkutecnouDelku() {
        // 25. 10. 2026 ve 3:00 SELČ -> 2:00 SEČ, noc má o hodinu víc
        val start = t(2026, 10, 24, 22, 0)
        val end = t(2026, 10, 25, 6, 0)
        assertEquals(9 * 60, Interval(start, end).minutes)
        assertTrue(ShiftWarning.DST in WorkCalc.validate(start, end))
        assertEquals(9 * 60, WorkCalc.splitByDay(1, Interval(start, end)).sumOf { it.minutes })
        // 29. 3. 2026 ve 2:00 -> 3:00, noc má o hodinu míň
        assertEquals(7 * 60, Interval(t(2026, 3, 28, 22, 0), t(2026, 3, 29, 6, 0)).minutes)
    }

    @Test fun varovani() {
        assertEquals(listOf(ShiftWarning.NEGATIVE), WorkCalc.validate(t(2026, 11, 12, 15, 0), t(2026, 11, 12, 7, 0)))
        assertTrue(ShiftWarning.SHORT in WorkCalc.validate(t(2026, 11, 12, 7, 0), t(2026, 11, 12, 7, 10)))
        assertTrue(ShiftWarning.ZERO in WorkCalc.validate(t(2026, 11, 12, 7, 0), t(2026, 11, 12, 7, 0)))
        assertTrue(ShiftWarning.MIDNIGHT in WorkCalc.validate(t(2026, 11, 12, 22, 0), t(2026, 11, 13, 1, 0)))
        assertTrue(ShiftWarning.LONG in WorkCalc.validate(t(2026, 11, 12, 5, 0), t(2026, 11, 12, 23, 0)))
        assertTrue(WorkCalc.validate(t(2026, 11, 12, 7, 0), t(2026, 11, 12, 15, 30)).isEmpty())
        val other = Interval(t(2026, 11, 12, 7, 0), t(2026, 11, 12, 15, 30))
        assertTrue(ShiftWarning.OVERLAP in WorkCalc.validate(t(2026, 11, 12, 15, 0), t(2026, 11, 12, 17, 0), listOf(other)))
        assertFalse(ShiftWarning.OVERLAP in WorkCalc.validate(t(2026, 11, 12, 15, 30), t(2026, 11, 12, 17, 0), listOf(other)))
    }

    @Test fun otevrenaSmenaSeNepocita() {
        val m = WorkCalc.month(YearMonth.of(2026, 11), listOf(ShiftData(1, t(2026, 11, 12, 7, 0), null)))
        assertEquals(0, m.totalMinutes)
        assertEquals(1, m.openShifts)
    }

    @Test fun volnoBezFalesnychHodin() {
        val ov = mapOf(
            LocalDate.of(2026, 11, 20) to (OverrideType.DOVOLENA to ""),
            LocalDate.of(2026, 11, 23) to (OverrideType.NEMOC to ""),
        )
        val m = WorkCalc.month(YearMonth.of(2026, 11), emptyList(), ov)
        assertEquals(0, m.totalMinutes)
        assertEquals(OverrideType.DOVOLENA, m.day(20).override)
        assertEquals("D", m.day(20).override!!.code)
    }

    @Test fun prescasJeCasPo1530VPracovniDen() {
        val shifts = listOf(
            ShiftData(1, t(2026, 11, 10, 7, 0), t(2026, 11, 10, 17, 0)),   // úterý: 1:30 přesčas
            ShiftData(2, t(2026, 11, 11, 6, 0), t(2026, 11, 11, 15, 30)),  // středa: dřívější začátek přesčas není
            ShiftData(3, t(2026, 11, 12, 7, 0), t(2026, 11, 12, 15, 30)),
            ShiftData(4, t(2026, 11, 12, 17, 0), t(2026, 11, 12, 19, 0)),  // čtvrtek: večerní výjezd 2:00
            ShiftData(5, t(2026, 11, 14, 14, 0), t(2026, 11, 14, 18, 0)),  // sobota: nepočítá se sem
            ShiftData(6, t(2026, 11, 17, 14, 0), t(2026, 11, 17, 18, 0)),  // svátek v úterý: není pracovní den
            ShiftData(7, t(2026, 11, 19, 22, 0), t(2026, 11, 20, 2, 0)),   // čtvrtek 22–24 = 2:00, pátek 0–2 nic
        )
        val m = WorkCalc.month(YearMonth.of(2026, 11), shifts)
        assertEquals(90, m.day(10).overtimeMinutes)
        assertEquals(0, m.day(11).overtimeMinutes)
        assertEquals(120, m.day(12).overtimeMinutes)
        assertEquals(0, m.day(14).overtimeMinutes)
        assertEquals(0, m.day(17).overtimeMinutes)
        assertEquals(120, m.day(19).overtimeMinutes)
        assertEquals(0, m.day(20).overtimeMinutes)
        assertEquals(330, m.overtimeMinutes)
        assertEquals(120, WorkCalc.month(DemoData.MONTH, DemoData.november2026()).overtimeMinutes)
    }

    @Test fun formatHodin() {
        assertEquals("8:30", WorkCalc.formatHm(510))
        assertEquals("186:00", WorkCalc.formatHm(11160))
        assertEquals("0:05", WorkCalc.formatHm(5))
    }
}

class LongShiftTest {
    private val h = 3_600_000L

    @Test fun upozorniAzPoLimitu() {
        assertFalse(cz.kutil.dochazka.core.LongShift.isDue(11 * h, 0, 12, null, null))
        assertTrue(cz.kutil.dochazka.core.LongShift.isDue(12 * h, 0, 12, null, null))
        assertTrue(cz.kutil.dochazka.core.LongShift.isDue(9 * h, 0, 8, null, null)) // limit je nastavitelný
    }

    @Test fun neopakujeSeAPoPotvrzeniPockaCtyriHodiny() {
        val ls = cz.kutil.dochazka.core.LongShift
        assertFalse(ls.isDue(13 * h, 0, 12, null, 12 * h))           // už upozorněno
        assertFalse(ls.isDue(15 * h, 0, 12, 13 * h, 12 * h))         // „stále pracuji“ ve 13. hodině
        assertTrue(ls.isDue(17 * h, 0, 12, 13 * h, 12 * h))          // o 4 hodiny později znovu
        assertFalse(ls.isDue(18 * h, 0, 12, 13 * h, 17 * h))         // a zase jen jednou
        assertTrue(ls.exceeds(12 * h, 0, 12))
    }
}

class AnomaliesTest {
    private fun t(d: Int, h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, d, h, m).atZone(PRAGUE).toInstant()
    private val A = cz.kutil.dochazka.core.Anomalies

    @Test fun bezProblemu() {
        val shifts = (16..20).map { ShiftData(it.toLong(), t(it, 7), t(it, 15, 30)) }
        assertTrue(A.find(t(23, 8), shifts, emptyMap<LocalDate, Any>(), 0, 12).isEmpty()) // 21.–22. je víkend
    }

    @Test fun chybejiciDnyOdPrvnihoZaznamuASvatekSeNepocita() {
        // první záznam 16. 11.; 17. 11. svátek; 18. dovolená; 19. a 20. nic
        val shifts = listOf(ShiftData(1, t(16, 7), t(16, 15, 30)))
        val r = A.find(t(23, 8), shifts, mapOf(LocalDate.of(2026, 11, 18) to "D"), 0, 12)
        val m = r.single { it.kind == cz.kutil.dochazka.core.AnomalyKind.MISSING_RECORD }
        assertEquals("Chybí záznam za 2 pracovní dny", m.title)
        assertTrue(m.detail.startsWith("19. 11., 20. 11."))
        assertEquals(LocalDate.of(2026, 11, 19), m.date)
    }

    @Test fun zapomenuteUkonceniPrekryvDlouhaSmenaANavrhy() {
        val shifts = listOf(
            ShiftData(1, t(19, 7), t(19, 15, 30)),
            ShiftData(2, t(19, 15), t(19, 17)),          // překryv 15:00–15:30
            ShiftData(3, t(20, 5), t(20, 21)),           // 16 h
            ShiftData(4, t(23, 6, 50), null),            // běží od rána
        )
        val r = A.find(t(23, 19), shifts, emptyMap<LocalDate, Any>(), 2, 12)
        val kinds = r.map { it.kind }
        assertEquals(listOf(cz.kutil.dochazka.core.AnomalyKind.FORGOTTEN_END, cz.kutil.dochazka.core.AnomalyKind.PENDING_GPS, cz.kutil.dochazka.core.AnomalyKind.OVERLAP, cz.kutil.dochazka.core.AnomalyKind.LONG_SHIFT), kinds.filter { it != cz.kutil.dochazka.core.AnomalyKind.MISSING_RECORD })
        assertEquals("Směna běží už 12 h", r.first().title)
        assertEquals("2 události čekají na potvrzení", r[1].title)
        assertEquals(LocalDate.of(2026, 11, 19), r.first { it.kind == cz.kutil.dochazka.core.AnomalyKind.OVERLAP }.date)
        // ráno téhož dne ještě nic
        assertTrue(A.find(t(23, 10), listOf(shifts[3]), emptyMap<LocalDate, Any>(), 0, 12).none { it.kind == cz.kutil.dochazka.core.AnomalyKind.FORGOTTEN_END })
    }
}

/** Dvojí započítání, změna dne a letní/zimní čas. */
class TimingTest {
    @Test fun prekryvSePocitaJenJednou() {
        val shifts = listOf(
            ShiftData(1, t(2026, 11, 12, 7, 0), t(2026, 11, 12, 15, 30)),
            // ručně přidaný výjezd, který leží uvnitř první směny
            ShiftData(2, t(2026, 11, 12, 10, 0), t(2026, 11, 12, 12, 0)),
            // částečný překryv na konci a přesah do přesčasu
            ShiftData(3, t(2026, 11, 12, 15, 0), t(2026, 11, 12, 17, 0)),
        )
        val d = WorkCalc.month(YearMonth.of(2026, 11), shifts).day(12)
        assertEquals(10 * 60L, d.minutes) // 7:00–17:00
        assertEquals(90L, d.overtimeMinutes) // 15:30–17:00
    }

    @Test fun sjednoceniIntervalu() {
        val a = t(2026, 11, 12, 8, 0)
        val iv = listOf(
            Interval(a, a.plusSeconds(3600)),
            Interval(a.plusSeconds(1800), a.plusSeconds(5400)),
            Interval(a.plusSeconds(7200), a.plusSeconds(9000)),
            Interval(a.plusSeconds(9000), a.plusSeconds(9000)), // nulová délka
        )
        assertEquals(90L + 30L, WorkCalc.unionMinutes(iv))
    }

    @Test fun zimniCasPocitaSkutecneOdpracovanouDobu() {
        // 25. 10. 2026 se v 3:00 vrací čas na 2:00 – noc 0:00–6:00 trvá 7 skutečných hodin
        val start = LocalDateTime.of(2026, 10, 25, 0, 0).atZone(PRAGUE).toInstant()
        val end = LocalDateTime.of(2026, 10, 25, 6, 0).atZone(PRAGUE).toInstant()
        val d = WorkCalc.month(YearMonth.of(2026, 10), listOf(ShiftData(1, start, end))).day(25)
        assertEquals(7 * 60L, d.minutes)
    }

    @Test fun letniCasPocitaSkutecneOdpracovanouDobu() {
        // 29. 3. 2026 se ve 2:00 posouvá čas na 3:00 – noc 0:00–6:00 trvá 5 skutečných hodin
        val start = LocalDateTime.of(2026, 3, 29, 0, 0).atZone(PRAGUE).toInstant()
        val end = LocalDateTime.of(2026, 3, 29, 6, 0).atZone(PRAGUE).toInstant()
        val d = WorkCalc.month(YearMonth.of(2026, 3), listOf(ShiftData(1, start, end))).day(29)
        assertEquals(5 * 60L, d.minutes)
    }

    @Test fun smenaPresPulnocSeRozdeli() {
        val shifts = listOf(ShiftData(1, t(2026, 11, 12, 22, 0), t(2026, 11, 13, 1, 30)))
        val m = WorkCalc.month(YearMonth.of(2026, 11), shifts)
        assertEquals(120L, m.day(12).minutes)
        assertEquals(90L, m.day(13).minutes)
        assertEquals("24:00", m.day(12).pieces.single().endText())
    }

    @Test fun zaokrouhleniPriZmeneCasu() {
        // 2:14 letního času = 0:14 UTC; v noci změny 25. 10. 2026 je místní 2:14 dvakrát
        val first = LocalDateTime.of(2026, 10, 25, 2, 14).atZone(PRAGUE).withEarlierOffsetAtOverlap().toInstant()
        val second = LocalDateTime.of(2026, 10, 25, 2, 14).atZone(PRAGUE).withLaterOffsetAtOverlap().toInstant()
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 0).atZone(PRAGUE).withEarlierOffsetAtOverlap().toInstant(), Rounding.roundToHalfHour(first))
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 0).atZone(PRAGUE).withLaterOffsetAtOverlap().toInstant(), Rounding.roundToHalfHour(second))
    }
}
