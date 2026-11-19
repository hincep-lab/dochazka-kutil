package cz.kutil.dochazka

import cz.kutil.dochazka.core.DemoData
import cz.kutil.dochazka.core.DocxExporter
import cz.kutil.dochazka.core.DocxFormatException
import cz.kutil.dochazka.core.ExportHeader
import cz.kutil.dochazka.core.OverrideType
import cz.kutil.dochazka.core.ShiftData
import cz.kutil.dochazka.core.WorkCalc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipInputStream

/**
 * Export se testuje nad SKUTEČNÝM originálem z app/src/main/assets/Pavel_ORIGINAL.docx –
 * stejný soubor, jaký je přibalený v APK.
 */
class DocxExportTest {
    private val header = ExportHeader("Vodoinstalace Kutil s.r.o.", "Hink")

    private fun template(): ByteArray {
        val candidates = listOf("src/main/assets/Pavel_ORIGINAL.docx", "app/src/main/assets/Pavel_ORIGINAL.docx")
        val f = candidates.map { File(it) }.firstOrNull { it.exists() } ?: error("Originál formuláře nenalezen")
        return f.readBytes()
    }

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                map[e.name] = z.readBytes()
            }
        }
        return map
    }

    private fun doc(bytes: ByteArray) = String(unzip(bytes)[DocxExporter.DOCUMENT]!!, Charsets.UTF_8)

    /** Texty buněk tabulky po řádcích. */
    private fun table(xml: String): List<List<String>> {
        val tbl = xml.substring(xml.indexOf("<w:tbl>"), xml.indexOf("</w:tbl>"))
        return tbl.split("</w:tr>").dropLast(1).map { row ->
            row.split("</w:tc>").dropLast(1).map { DocxExporter.text(it.substring(it.indexOf("<w:tc>"))) }
        }
    }

    private fun count(s: String, n: String) = s.split(n).size - 1

    private fun november() = WorkCalc.month(DemoData.MONTH, DemoData.november2026())

    @Test fun listopad2026Vyplneni() {
        val original = template()
        val before = original.copyOf()
        val (out, report) = DocxExporter.export(original, november(), header)
        assertArrayEquals("Originál se nesmí změnit", before, original)
        assertEquals("Dochazka_2026-11.docx", report.fileName)
        assertEquals(186 * 60L, report.totalMinutes)
        assertTrue(report.overflowDays.isEmpty())

        val xml = doc(out)
        val t = table(xml)
        assertEquals(36, t.size)
        // běžný den: pondělí 2. 11.
        assertEquals(listOf("2", "07:00", "15:30", "", "", "", "", "", "", "", "", "8:30"), t[3])
        // volné víkendy: bez textu, proškrtnuté čarou – 1. 11., 7.–8., 21.–22., 28.–29. = 4 čáry
        assertEquals(listOf("1", "", "", "", "", "", "", "", "", "", "", ""), t[2])
        assertEquals(4, count(xml, "<v:line "))
        assertTrue(xml.contains("from=\"-3.5pt,26.0pt\" to=\"393.5pt,0pt\""))
        assertTrue(xml.contains("from=\"-3.5pt,13.0pt\" to=\"393.5pt,0pt\""))
        // 12. 11. dva intervaly ve správných polích (Dopoledne + Odpoledne)
        assertEquals(listOf("12", "07:00", "15:30", "17:00", "19:00", "", "", "", "", "", "", "10:30"), t[13])
        assertEquals(listOf("14", "09:00", "12:00", "", "", "", "", "", "", "", "", "3:00  (práce v sobotu)"), t[15])
        assertEquals(listOf("15", "10:00", "12:30", "", "", "", "", "", "", "", "", "2:30  (práce v neděli)"), t[16])
        // svátek 17. 11. započten jednou
        assertEquals(listOf("17", "07:00", "15:30", "", "", "", "", "", "", "", "", "8:30  (práce ve svátek)"), t[18])
        assertEquals("30", t[31][0])
        assertEquals("8:30", t[31][11])
        // den 31 v listopadu neexistuje -> prázdný
        assertEquals(listOf("31", "", "", "", "", "", "", "", "", "", "", ""), t[32])
        // součty: 12. 11. výjezd 17:00–19:00 je po 15:30 v pracovní den = 2 h přesčas
        assertEquals("184:00", t[33].last())
        assertEquals("2:00", t[34].last())
        assertEquals("186:00", t[35].last())
        // součet denních hodin = celkem
        val sum = (2..32).sumOf { r -> t[r][11].substringBefore(" ").takeIf { it.matches(Regex("\\d+:\\d\\d")) }?.let { it.substringBefore(":").toLong() * 60 + it.substringAfter(":").toLong() } ?: 0L }
        assertEquals(186 * 60L, sum)

        val text = DocxExporter.text(xml)
        assertTrue(text.contains("EVIDENCE DOCHÁZKY"))
        assertTrue(text.contains("ORGANIZACE:   Vodoinstalace Kutil s.r.o."))
        assertTrue(text.contains("Jméno: Hink"))
        assertTrue(text.contains("Měsíc: listopad"))
        assertTrue(text.contains("Rok: 2026"))
        assertFalse(text.contains("2021"))
        assertTrue(text.contains("Práce v sobotu: 14. 11."))
        assertTrue(text.contains("Práce v neděli: 15. 11."))
        assertTrue(text.contains("Práce ve svátek: 17. 11."))
        assertTrue(text.contains("neomluvená absence"))
        assertTrue(text.contains("náhradní volno za práci přesčas"))
        assertTrue(text.contains("Schválil"))
        assertFalse(text.contains("PŘÍLOHA"))
    }

    @Test fun strukturaFormulareZustavaBajtovePuvodni() {
        val original = template()
        val (out, _) = DocxExporter.export(original, november(), header)
        val a = unzip(original)
        val b = unzip(out)
        assertEquals(a.keys.toList(), b.keys.toList())
        for ((name, bytes) in a) {
            if (name != DocxExporter.DOCUMENT) assertArrayEquals("Soubor $name se změnil", bytes, b[name])
        }
        // jediná povolená změna mimo vložený text: odebrání prázdného číslování (neviditelný tabulátor u nadpisu)
        val oXml = String(a[DocxExporter.DOCUMENT]!!, Charsets.UTF_8).replace(DocxExporter.NUM_PR, "").replace(DocxExporter.PG_MAR, "")
        val nXml = String(b[DocxExporter.DOCUMENT]!!, Charsets.UTF_8)
        for (tag in listOf("<w:tbl>", "<w:tr>", "<w:tc>", "<w:gridSpan", "<w:vMerge", "<w:tcBorders>", "<w:gridCol", "<w:p ", "<w:p>", "<w:sectPr")) {
            assertEquals("Počet $tag", count(oXml, tag), count(nXml, tag))
        }
        // Po odebrání vložených běhů musí být dokument totožný s originálem – kromě řádku Jméno/Měsíc/Rok.
        // okraje stránky: tabulka (10551 twipů) přesně na středu A4 (11906 twipů)
        val margin = DocxExporter.PG_MAR.find(nXml)!!.value
        assertTrue(margin, margin.contains("w:left=\"677\"") && margin.contains("w:right=\"677\""))
        val stripped = nXml.replace(DocxExporter.PG_MAR, "").replace(Regex("<w:r w:rsidR=\"${DocxExporter.MARK}\">[\\s\\S]*?</w:r>"), "")
            .replace(">2:00</w:t>", ">---------</w:t>") // hodnota přesčasu nahrazuje původní pomlčky
        // porovnává se vše před odstavcem Jméno/Měsíc/Rok a vše za ním
        fun cut(x: String, needle: String) = x.indexOf(needle).let { x.substring(0, x.lastIndexOf("<w:p ", it)) to x.substring(x.indexOf("</w:p>", it)) }
        val cutO = cut(oXml, "Jméno :")
        val cutN = cut(stripped, "Jméno:")
        assertEquals(cutO.first, cutN.first)
        assertEquals(cutO.second, cutN.second)
        assertEquals(0, count(nXml, "<w:numPr>"))
        // výstup je dobře utvořené XML
        javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(b[DocxExporter.DOCUMENT]))
    }

    @Test fun viceIntervaluNezStiSloupcuJdeDoPrilohy() {
        val shifts = DemoData.november2026() + listOf(
            ShiftData(900, DemoData.at(LocalDate.of(2026, 11, 12), 20, 0), DemoData.at(LocalDate.of(2026, 11, 12), 21, 0)),
            ShiftData(901, DemoData.at(LocalDate.of(2026, 11, 12), 22, 0), DemoData.at(LocalDate.of(2026, 11, 12), 23, 0)),
        )
        val month = WorkCalc.month(DemoData.MONTH, shifts)
        val (out, report) = DocxExporter.export(template(), month, header)
        assertEquals(listOf(12), report.overflowDays)
        assertEquals(188 * 60L, report.totalMinutes)
        val xml = doc(out)
        val t = table(xml)
        assertEquals(listOf("12", "07:00", "15:30", "17:00", "19:00", "", "", "", "", "", "", "12:30  viz příloha"), t[13])
        assertEquals("188:00", t[35].last())
        assertEquals("4:00", t[34].last()) // 17–19, 20–21, 22–23
        val text = DocxExporter.text(xml)
        assertTrue(text.contains("PŘÍLOHA K EVIDENCI DOCHÁZKY"))
        // žádný interval se neztratil
        assertTrue(text.contains("07:00–15:30,  17:00–19:00,  20:00–21:00,  22:00–23:00   =   12:30 h"))
        assertTrue(text.contains("Více výjezdů v jednom dni – viz příloha: 12. 11."))
        // příloha je mimo tabulku, tabulka je stále jediná
        assertEquals(1, count(xml, "<w:tbl>"))
        assertTrue(xml.indexOf("PŘÍLOHA") > xml.indexOf("</w:tbl>"))
        assertTrue(xml.contains("<w:pageBreakBefore w:val=\"1\"/>"))
    }

    @Test fun volnaAZkratkyBezHodin() {
        val ov = mapOf(
            LocalDate.of(2026, 11, 20) to (OverrideType.DOVOLENA to ""),
            LocalDate.of(2026, 11, 23) to (OverrideType.NEMOC to ""),
            LocalDate.of(2026, 11, 24) to (OverrideType.LEKAR to ""),
            LocalDate.of(2026, 11, 25) to (OverrideType.NAHRADNI_VOLNO to ""),
        )
        val shifts = listOf(ShiftData(1, DemoData.at(LocalDate.of(2026, 11, 24), 10, 0), DemoData.at(LocalDate.of(2026, 11, 24), 15, 30)))
        val (out, report) = DocxExporter.export(template(), WorkCalc.month(DemoData.MONTH, shifts, ov), header)
        val t = table(doc(out))
        // dovolená se píše slovem do sloupce hodin, bez zkratky a bez hodin
        assertEquals(listOf("20", "", "", "", "", "", "", "", "", "", "", "Dovolená"), t[21])
        assertEquals("N", t[24][1])
        // lékař + odpracovaný zbytek dne: zkratka v dalším volném poli, hodiny jen za práci
        assertEquals(listOf("24", "10:00", "15:30", "P", "", "", "", "", "", "", "", "5:30"), t[25])
        assertEquals("NV", t[26][1])
        // svátek ve všední den bez práce = S + „st. svátek“, bez hodin
        assertEquals(listOf("17", "S", "", "", "", "", "", "", "", "", "", "st. svátek"), t[18])
        // bez přesčasu zůstávají v poli původní pomlčky
        assertEquals("---------", t[34].last())
        assertEquals(330L, report.totalMinutes)
        assertEquals("5:30", t[35].last())
    }

    @Test fun svatekBezPraceVzdyStSvatek_iOVikendu() {
        // 28. 9. 2024 je sobota a státní svátek, 2. 9. 2024 pondělí bez svátku
        val (out, _) = DocxExporter.export(template(), WorkCalc.month(YearMonth.of(2024, 9), emptyList()), header)
        val xml = doc(out)
        val t = table(xml)
        assertEquals(listOf("28", "", "", "", "", "", "", "", "", "", "", "st. svátek"), t[29]) // bez „S“ – přes sloupce vede čára
        assertEquals("", t[30][11]) // neděle 29. 9. – jen proškrtnutá
        assertTrue(xml.contains("<v:line ")) // víkend je proškrtnutý
        // svátek ve všední den: S + st. svátek
        val (out2, _) = DocxExporter.export(template(), WorkCalc.month(YearMonth.of(2026, 11), emptyList()), header)
        assertEquals(listOf("17", "S", "", "", "", "", "", "", "", "", "", "st. svátek"), table(doc(out2))[18])
    }

    @Test fun pulnocniVyjezdVExportu() {
        val shifts = listOf(ShiftData(1, DemoData.at(LocalDate.of(2026, 11, 12), 22, 0), DemoData.at(LocalDate.of(2026, 11, 13), 2, 0)))
        val (out, _) = DocxExporter.export(template(), WorkCalc.month(DemoData.MONTH, shifts), header)
        val t = table(doc(out))
        assertEquals(listOf("12", "22:00", "24:00"), t[13].take(3))
        assertEquals("2:00", t[13][11])
        assertEquals(listOf("13", "00:00", "02:00"), t[14].take(3))
        assertEquals("4:00", t[35].last())
    }

    @Test fun unorAMesicSJednatriceti() {
        val feb = WorkCalc.month(YearMonth.of(2027, 2), emptyList())
        val (out, report) = DocxExporter.export(template(), feb, ExportHeader("Vodoinstalace Kutil s.r.o.", "Jan Novák-Dlouhý"))
        assertEquals("Dochazka_2027-02.docx", report.fileName)
        val text = DocxExporter.text(doc(out))
        assertTrue(text.contains("Měsíc: únor"))
        assertTrue(text.contains("Rok: 2027"))
        assertTrue(text.contains("Jméno: Jan Novák-Dlouhý"))
        assertEquals("0:00", table(doc(out))[35].last())
    }

    @Test fun jizVyplnenaNeboCiziSablonaSeOdmitne() {
        val (out, _) = DocxExporter.export(template(), november(), header)
        try {
            DocxExporter.export(out, november(), header)
            fail("Vyplněný dokument nesmí být přijat jako šablona")
        } catch (e: DocxFormatException) {
            // očekáváno
        }
    }
}
