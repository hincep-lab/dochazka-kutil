package cz.kutil.dochazka

import cz.kutil.dochazka.core.DemoData
import cz.kutil.dochazka.core.ExportHeader
import cz.kutil.dochazka.core.FormCanvas
import cz.kutil.dochazka.core.FormPainter
import cz.kutil.dochazka.core.OverrideType
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.ShiftData
import cz.kutil.dochazka.core.WorkCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Záznamník kreslení: ověřuje obsah a rozložení PDF výkazu bez Androidu. */
private class Recorder : FormCanvas {
    data class T(val s: String, val x: Float, val y: Float, val size: Float, val bold: Boolean)
    data class L(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    val texts = ArrayList<T>()
    val lines = ArrayList<L>()
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) { lines.add(L(x1, y1, x2, y2)) }
    override fun fill(x: Float, y: Float, w: Float, h: Float, gray: Float) = Unit
    override fun text(s: String, x: Float, y: Float, size: Float, bold: Boolean, italic: Boolean) { texts.add(T(s, x, y, size, bold)) }
    // přibližná šířka písma (průměrná šířka znaku ~0.55 em)
    override fun width(s: String, size: Float, bold: Boolean, italic: Boolean) = s.length * size * (if (bold) 0.6f else 0.55f)
    fun has(s: String) = texts.any { it.s == s }
    fun all(s: String) = texts.filter { it.s == s }
}

class PdfFormTest {
    private val header = ExportHeader("Vodoinstalace Kutil s.r.o.", "Hink")

    @Test fun listopad2026() {
        val month = WorkCalc.month(DemoData.MONTH, DemoData.november2026())
        assertEquals(1, FormPainter.pages(month))
        val r = Recorder()
        FormPainter.draw(r, 0, month, header)
        assertTrue(r.has("EVIDENCE DOCHÁZKY"))
        assertTrue(r.has("ORGANIZACE:   Vodoinstalace Kutil s.r.o."))
        assertTrue(r.has("Jméno: Hink") && r.has("Měsíc: listopad") && r.has("Rok: 2026"))
        assertTrue(r.has("184:00") && r.has("2:00") && r.has("186:00"))
        assertTrue(r.has("(práce v sobotu)") && r.has("(práce v neděli)") && r.has("(práce ve svátek)"))
        assertTrue(r.has("17:00") && r.has("19:00") && r.has("10:30"))
        assertTrue(r.has("neomluvená absence"))
        // všechny hodiny dnů končí na stejném pravém okraji (zarovnání pod sebou)
        val hours = r.texts.filter { it.s.matches(Regex("\\d{1,2}:\\d{2}")) && it.x > FormPainter.X0 + FormPainter.TABLE_W - 60 && it.y < 600 }
        val rightEdges = hours.map { it.x + it.s.length * it.size * 0.6f }.toSet()
        assertEquals(23, hours.size) // 21 všedních dnů + sobota + neděle
        assertTrue("pravé okraje: $rightEdges", rightEdges.maxOrNull()!! - rightEdges.minOrNull()!! < 0.01f)
        // vše uvnitř stránky A4
        assertTrue(r.texts.all { it.x >= 0 && it.y <= FormPainter.PAGE_H && it.x + it.s.length * it.size * 0.6f <= FormPainter.PAGE_W + 1 })
        // tabulka na středu stránky
        assertEquals(FormPainter.PAGE_W / 2, FormPainter.X0 + FormPainter.TABLE_W / 2, 0.01f)
        // volné víkendy: 1., 7.–8., 21.–22., 28.–29. = 4 šikmé čáry
        assertEquals(4, r.lines.count { it.y1 != it.y2 && it.x1 != it.x2 })
    }

    @Test fun svatekDovolenaAPriloha() {
        val d = LocalDate.of(2026, 11, 12)
        val shifts = DemoData.november2026().filter { it.rawStart.atZone(PRAGUE).dayOfMonth !in listOf(17, 23) } +
            listOf(ShiftData(900, DemoData.at(d, 20, 0), DemoData.at(d, 21, 0)))
        val ov = mapOf(LocalDate.of(2026, 11, 23) to (OverrideType.DOVOLENA to ""))
        val month = WorkCalc.month(DemoData.MONTH, shifts, ov)
        assertEquals(2, FormPainter.pages(month))
        val r = Recorder()
        FormPainter.draw(r, 0, month, header)
        assertTrue(r.has("st. svátek"))
        assertTrue(r.has("S"))
        assertTrue(r.has("Dovolená"))
        assertTrue(r.has("viz příloha"))
        val p2 = Recorder()
        FormPainter.draw(p2, 1, month, header)
        assertTrue(p2.has("PŘÍLOHA K EVIDENCI DOCHÁZKY"))
        assertTrue(p2.texts.any { it.s.contains("07:00–15:30,  17:00–19:00,  20:00–21:00") && it.s.contains("11:30 h") })
        assertEquals("Dochazka_2026-11.pdf", FormPainter.fileName(DemoData.MONTH))
    }
}
