package cz.kutil.dochazka.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocxFormatException(message: String) : Exception(message)

data class ExportHeader(val organization: String, val employee: String)

data class ExportReport(
    val fileName: String,
    val totalMinutes: Long,
    /** Dny, které mají víc intervalů, než se vejde do formuláře – jsou vypsané v příloze. */
    val overflowDays: List<Int>,
    /** Text doplňující poznámky pod vysvětlivkami (prázdný, pokud žádná není). */
    val noteText: String,
)

/**
 * Export do kopie originálního formuláře Pavel_ORIGINAL.docx.
 *
 * Záměrně nepoužívá žádnou OOXML knihovnu: z archivu se změní jediný soubor word/document.xml
 * a v něm se pouze VKLÁDAJÍ textové běhy (w:r) do existujících prázdných buněk a odstavců.
 * Tabulka, sloučené buňky (gridSpan/vMerge), šířky, styly, podpisy i vysvětlivky zůstávají
 * bajt po bajtu původní. Před zápisem se ověřuje, že struktura odpovídá očekávanému formuláři –
 * pokud ne, export skončí chybou místo zápisu „naslepo“.
 */
object DocxExporter {
    const val DOCUMENT = "word/document.xml"

    /** Značka (rsid) všech vložených běhů – umožňuje ověřit, že se jinak nic nezměnilo. */
    const val MARK = "00D0C4A2"

    /** Kolik intervalů se vejde do jednoho řádku formuláře (Dopoledne + Odpoledne). */
    const val SLOTS_PER_DAY = 2

    val MONTHS = listOf(
        "leden", "únor", "březen", "duben", "květen", "červen",
        "červenec", "srpen", "září", "říjen", "listopad", "prosinec",
    )
    private val WEEKDAYS = mapOf(
        DayOfWeek.MONDAY to "pondělí", DayOfWeek.TUESDAY to "úterý", DayOfWeek.WEDNESDAY to "středa",
        DayOfWeek.THURSDAY to "čtvrtek", DayOfWeek.FRIDAY to "pátek", DayOfWeek.SATURDAY to "sobota",
        DayOfWeek.SUNDAY to "neděle",
    )

    private val EXPECTED_SPANS = listOf(1, 1, 2, 1, 2, 1, 2, 1, 1, 1, 1, 1)
    private val EXPECTED_HEADER = listOf(
        "Den", "Příchod", "Odchod", "Příchod", "Odchod", "Odchod", "Příchod", "Odchod", "Příchod", "Odchod", "Příchod", "",
    )

    // Indexy buněk v řádku dne (ověřené proti záhlaví formuláře).
    private const val C_AM_IN = 1
    private const val C_AM_OUT = 2
    private const val C_PM_IN = 3
    private const val C_PM_OUT = 4
    private const val C_BREAK1 = 5
    private const val C_HOURS = 11

    /** Proškrtnutí volného víkendu (dlouhé pomlčky splývají v čáru). */
    const val DASH = "——"
    const val DASH_WIDE = "———————"

    // Tabulátory řádku Jméno / Měsíc / Rok (v twipech od levého okraje textu).
    private const val TAB_MONTH = 4300
    private const val TAB_YEAR = 7900

    private const val ARIAL = "<w:rFonts w:ascii=\"Arial\" w:cs=\"Arial\" w:eastAsia=\"Arial\" w:hAnsi=\"Arial\"/>"
    private const val RPR_CELL = "$ARIAL<w:b w:val=\"1\"/>"
    private const val RPR_CELL_SMALL = "$ARIAL<w:sz w:val=\"14\"/><w:szCs w:val=\"14\"/>"
    
    fun fileName(month: YearMonth): String = "Dochazka_%04d-%02d.docx".format(month.year, month.monthValue)

    /** Vrátí bajty vyplněné kopie; šablonu (originál) nijak nemění. */
    fun export(template: ByteArray, month: MonthSummary, header: ExportHeader): Pair<ByteArray, ExportReport> {
        val out = ByteArrayOutputStream(template.size + 16_384)
        var report: ExportReport? = null
        ZipInputStream(ByteArrayInputStream(template)).use { zin ->
            ZipOutputStream(out).use { zout ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val bytes = zin.readBytes()
                    zout.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == DOCUMENT) {
                        val (xml, rep) = fill(String(bytes, Charsets.UTF_8), month, header)
                        report = rep
                        zout.write(xml.toByteArray(Charsets.UTF_8))
                    } else {
                        zout.write(bytes)
                    }
                    zout.closeEntry()
                }
            }
        }
        val rep = report ?: throw DocxFormatException("Šablona neobsahuje word/document.xml.")
        return out.toByteArray() to rep
    }

    // ---------------------------------------------------------------------------------------
    // Úprava document.xml
    // ---------------------------------------------------------------------------------------

    private class Edit(val start: Int, val end: Int, val text: String)
    private class Cell(val start: Int, val end: Int, val text: String, val span: Int)
    private class Row(val cells: List<Cell>)

    fun fill(xml: String, month: MonthSummary, header: ExportHeader): Pair<String, ExportReport> {
        val edits = ArrayList<Edit>()
        val rows = parseAndValidateTable(xml)

        // --- záhlaví -------------------------------------------------------------------
        edits.add(organizationEdit(xml, header.organization))
        edits.add(nameMonthYearEdit(xml, header.employee, month.month))

        // --- dny -----------------------------------------------------------------------
        val overflow = ArrayList<Int>()
        for (day in month.days) {
            val row = rows[day.date.dayOfMonth + 1]
            val pieces = day.pieces
            pieces.getOrNull(0)?.let {
                edits.add(cellText(xml, row.cells[C_AM_IN], it.startText(), RPR_CELL))
                edits.add(cellText(xml, row.cells[C_AM_OUT], it.endText(), RPR_CELL))
            }
            pieces.getOrNull(1)?.let {
                edits.add(cellText(xml, row.cells[C_PM_IN], it.startText(), RPR_CELL))
                edits.add(cellText(xml, row.cells[C_PM_OUT], it.endText(), RPR_CELL))
            }
            val weekend = DayFlag.SATURDAY in day.flags || DayFlag.SUNDAY in day.flags
            val free = pieces.isEmpty() && day.override == null
            if (free && weekend) {
                // Volný víkend se proškrtne přes celý řádek.
                for (c in C_AM_IN until C_HOURS) edits.add(cellText(xml, row.cells[c], DASH, RPR_CELL))
                edits.add(cellText(xml, row.cells[C_HOURS], DASH_WIDE, RPR_CELL))
            }
            // Svátek ve všední den bez práce: zkratka „S“ podle vysvětlivek a slovně „st. svátek“.
            val freeHoliday = free && !weekend && DayFlag.HOLIDAY in day.flags
            if (freeHoliday) edits.add(cellText(xml, row.cells[C_HOURS], "st. svátek", RPR_CELL))
            val code = day.override?.code ?: if (freeHoliday) "S" else null
            if (code != null) {
                val target = when (pieces.size) {
                    0 -> C_AM_IN
                    1 -> C_PM_IN
                    else -> C_BREAK1
                }
                edits.add(cellText(xml, row.cells[target], code, RPR_CELL))
            }
            if (pieces.isNotEmpty()) {
                val hoursCell = row.cells[C_HOURS]
                if (pieces.size > SLOTS_PER_DAY) {
                    overflow.add(day.date.dayOfMonth)
                    val pos = insertionPoint(xml, hoursCell)
                    edits.add(
                        Edit(pos, pos, run(WorkCalc.formatHm(day.minutes), RPR_CELL) + run("  viz příloha", RPR_CELL_SMALL))
                    )
                } else {
                    edits.add(cellText(xml, hoursCell, WorkCalc.formatHm(day.minutes), RPR_CELL))
                }
            }
        }

        // --- součty (PŘESČAS. HODINY se nevyplňuje – definice není zadaná) --------------
        val total = WorkCalc.formatHm(month.totalMinutes)
        edits.add(cellText(xml, rows[33].cells.last(), total, RPR_CELL))
        edits.add(cellText(xml, rows[35].cells.last(), total, RPR_CELL))

        // --- doplňující poznámky ve dvou existujících prázdných odstavcích pod vysvětlivkami ---
        val labelNote = buildLabelNote(month)
        val overflowNote = if (overflow.isEmpty()) "" else
            "Více výjezdů v jednom dni – viz příloha: " + overflow.joinToString(", ") { "$it. ${month.month.monthValue}." }
        val notePoints = notesInsertionPoints(xml)
        if (labelNote.isNotEmpty()) edits.add(Edit(notePoints[0], notePoints[0], run(labelNote, noteRpr(labelNote))))
        if (overflowNote.isNotEmpty()) edits.add(Edit(notePoints[1], notePoints[1], run(overflowNote, noteRpr(overflowNote))))
        val note = listOf(labelNote, overflowNote).filter { it.isNotEmpty() }.joinToString("\n")

        // --- příloha (další list) pro dny s více intervaly -------------------------------
        if (overflow.isNotEmpty()) {
            val pos = xml.lastIndexOf("<w:sectPr")
            if (pos < 0) throw DocxFormatException("Šablona nemá oddíl w:sectPr.")
            edits.add(Edit(pos, pos, appendix(month, header, overflow)))
        }

        // --- prázdné číslování z originálu ----------------------------------------------------
        // Nadpis a několik popisků mají v originálu „číslování“ bez čísla. Některé prohlížeče za něj
        // vkládají tabulátor, takže se nadpis posune z osy a podtržení začíná už před textem.
        // Odebrání nemění text ani tabulku, jen tento neviditelný tabulátor.
        for (m in NUM_PR.findAll(xml)) edits.add(Edit(m.range.first, m.range.last + 1, ""))

        val sb = StringBuilder(xml)
        // Stejná pozice = zachovat pořadí vložení, proto stabilní řazení sestupně podle pozice.
        val ordered = edits.withIndex().sortedWith(compareByDescending<IndexedValue<Edit>> { it.value.start }.thenByDescending { it.index })
        for (e in ordered) sb.replace(e.value.start, e.value.end, e.value.text)
        return sb.toString() to ExportReport(fileName(month.month), month.totalMinutes, overflow, note)
    }

    private fun parseAndValidateTable(xml: String): List<Row> {
        val tables = count(xml, "<w:tbl>") + count(xml, "<w:tbl ")
        if (tables != 1) throw DocxFormatException("Formulář má obsahovat právě jednu tabulku, nalezeno: $tables.")
        val tblStart = xml.indexOf("<w:tbl>").let { if (it >= 0) it else xml.indexOf("<w:tbl ") }
        val tblEnd = xml.indexOf("</w:tbl>", tblStart)
        if (tblEnd < 0) throw DocxFormatException("Tabulka formuláře není ukončená.")
        val rows = elements(xml, "tr", tblStart, tblEnd).map { (rs, re) ->
            Row(elements(xml, "tc", rs, re).map { (cs, ce) ->
                val frag = xml.substring(cs, ce)
                val span = Regex("<w:gridSpan w:val=\"(\\d+)\"/>").find(frag)?.groupValues?.get(1)?.toInt() ?: 1
                Cell(cs, ce, text(frag), span)
            })
        }
        if (rows.size != 36) throw DocxFormatException("Tabulka má mít 36 řádků, má ${rows.size}.")
        val head = rows[1].cells.map { it.text.trim() }
        if (head != EXPECTED_HEADER) throw DocxFormatException("Záhlaví tabulky neodpovídá formuláři: $head")
        for (d in 1..31) {
            val r = rows[d + 1]
            if (r.cells.size != 12) throw DocxFormatException("Řádek dne $d má ${r.cells.size} buněk místo 12.")
            if (r.cells[0].text.trim() != d.toString()) throw DocxFormatException("Řádek ${d + 1} není den $d.")
            if (r.cells.map { it.span } != EXPECTED_SPANS) throw DocxFormatException("Řádek dne $d má jiné sloučení buněk.")
            if (r.cells.drop(1).any { it.text.isNotBlank() }) throw DocxFormatException("Řádek dne $d už je vyplněný – šablona není prázdný originál.")
        }
        fun label(rowIndex: Int): String = rows[rowIndex].cells.let { it[it.size - 2].text }
        if (!label(33).contains("ODPRAC")) throw DocxFormatException("Nenalezen řádek ODPRAC. HODINY.")
        if (!label(34).contains("PŘESČAS")) throw DocxFormatException("Nenalezen řádek PŘESČAS. HODINY.")
        if (!label(35).contains("CELKEM")) throw DocxFormatException("Nenalezen řádek CELKEM.")
        if (rows[33].cells.last().text.isNotBlank() || rows[35].cells.last().text.isNotBlank()) {
            throw DocxFormatException("Součtová pole už jsou vyplněná – šablona není prázdný originál.")
        }
        return rows
    }

    private fun organizationEdit(xml: String, organization: String): Edit {
        val idx = xml.indexOf("ORGANIZACE:")
        if (idx < 0) throw DocxFormatException("Nenalezeno pole ORGANIZACE.")
        val runStart = xml.lastIndexOf("<w:r ", idx)
        val runEnd = xml.indexOf("</w:r>", idx)
        if (runStart < 0 || runEnd < 0) throw DocxFormatException("Pole ORGANIZACE má nečekanou strukturu.")
        val runXml = xml.substring(runStart, runEnd)
        val rPr = Regex("<w:rPr>([\\s\\S]*?)</w:rPr>").find(runXml)?.groupValues?.get(1) ?: "$ARIAL<w:b w:val=\"1\"/>"
        val pos = runEnd + "</w:r>".length
        return Edit(pos, pos, run(organization, rPr))
    }

    private fun nameMonthYearEdit(xml: String, employee: String, month: YearMonth): Edit {
        val idx = xml.indexOf("Jméno :")
        if (idx < 0) throw DocxFormatException("Nenalezen řádek Jméno / Měsíc / Rok.")
        val pStart = xml.lastIndexOf("<w:p ", idx)
        val pEnd = xml.indexOf("</w:p>", idx)
        if (pStart < 0 || pEnd < 0) throw DocxFormatException("Řádek Jméno má nečekanou strukturu.")
        val p = xml.substring(pStart, pEnd)
        val t = "<w:t xml:space=\"preserve\">"
        val re = Regex(
            "(" + Regex.escape(t) + ")Jméno :[^<]*(</w:t>(?:<w:tab/>)*" + Regex.escape(t) + ")[^<]*Měsíc:[^<]*Rok:[^<]*" +
                "(</w:t></w:r><w:r [^>]*><w:rPr>[\\s\\S]*?</w:rPr>" + Regex.escape(t) + ")\\d+(</w:t>)"
        )
        re.find(p) ?: throw DocxFormatException("Řádek Jméno / Měsíc / Rok neodpovídá originálu.")
        // Originál má tři údaje oddělené mezerami, takže se po vyplnění rozjíždějí. Řádek se proto
        // skládá znovu: stejné písmo jako v originálu, ale údaje zarovnané na pevné tabulátory.
        val openEnd = p.indexOf('>') + 1
        val rPr = Regex("<w:r [^>]*><w:rPr>([\\s\\S]*?)</w:rPr>").find(p)?.groupValues?.get(1)
            ?: throw DocxFormatException("Řádek Jméno / Měsíc / Rok nemá očekávané písmo.")
        val monthName = MONTHS[month.monthValue - 1]
        val content = "<w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"$TAB_MONTH\"/><w:tab w:val=\"left\" w:pos=\"$TAB_YEAR\"/></w:tabs>" +
            "<w:rPr><w:vertAlign w:val=\"baseline\"/></w:rPr></w:pPr>" +
            "<w:r><w:rPr>$rPr</w:rPr>" +
            t + "Jméno: " + esc(employee) + "</w:t><w:tab/>" +
            t + "Měsíc: " + monthName + "</w:t><w:tab/>" +
            t + "Rok: " + month.year + "</w:t></w:r>"
        return Edit(pStart + openEnd, pEnd, content)
    }

    /** Dva prázdné odstavce, které originál má pod vysvětlivkami – poznámka se píše do nich, nic se nepřidává. */
    private fun notesInsertionPoints(xml: String): List<Int> {
        val idx = xml.indexOf("neomluvená absence")
        if (idx < 0) throw DocxFormatException("Nenalezeny vysvětlivky formuláře.")
        val afterLegend = xml.indexOf("</w:p>", idx) + "</w:p>".length
        val limit = xml.lastIndexOf("<w:sectPr").let { if (it < 0) xml.length else it }
        val paragraphs = elements(xml, "p", afterLegend, limit)
        if (paragraphs.size < 2) throw DocxFormatException("Pod vysvětlivkami chybí volné odstavce pro poznámku.")
        return paragraphs.take(2).map { (ps, pe) ->
            if (text(xml.substring(ps, pe)).isNotBlank()) throw DocxFormatException("Odstavec pro poznámku není prázdný.")
            pe - "</w:p>".length
        }
    }

    /** Poznámka se musí vejít na jeden řádek, aby se formulář nepřelil na další stranu. */
    private fun noteRpr(text: String): String {
        val size = when {
            text.length <= 105 -> 18
            text.length <= 125 -> 16
            else -> 14
        }
        return "<w:b w:val=\"1\"/><w:i w:val=\"1\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>"
    }

    private fun buildLabelNote(month: MonthSummary): String {
        val m = month.month.monthValue
        fun dates(days: List<DaySummary>) = days.joinToString(", ") { "${it.date.dayOfMonth}." } + " $m."
        val parts = ArrayList<String>()
        val worked = month.days.filter { it.pieces.isNotEmpty() }
        worked.filter { DayFlag.SATURDAY in it.flags }.takeIf { it.isNotEmpty() }?.let { parts.add("Práce v sobotu: ${dates(it)}") }
        worked.filter { DayFlag.SUNDAY in it.flags }.takeIf { it.isNotEmpty() }?.let { parts.add("Práce v neděli: ${dates(it)}") }
        worked.filter { DayFlag.HOLIDAY in it.flags }.takeIf { it.isNotEmpty() }?.let { parts.add("Práce ve svátek: ${dates(it)}") }
        return if (parts.isEmpty()) "" else "Poznámky:  " + parts.joinToString(";  ")
    }

    private fun appendix(month: MonthSummary, header: ExportHeader, overflow: List<Int>): String {
        val monthName = MONTHS[month.month.monthValue - 1]
        val sb = StringBuilder()
        sb.append(appendixParagraph("PŘÍLOHA K EVIDENCI DOCHÁZKY", "$ARIAL<w:b w:val=\"1\"/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/>", true))
        sb.append(
            appendixParagraph(
                "${header.organization}  –  ${header.employee}  –  $monthName ${month.month.year}",
                "$ARIAL<w:b w:val=\"1\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/>",
            )
        )
        sb.append(
            appendixParagraph(
                "Dny s více pracovními intervaly, než kolik má formulář sloupců. Všechny uvedené intervaly " +
                    "jsou započteny v hodinách daného dne i v celkovém součtu na první straně.",
                "$ARIAL<w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/>",
            )
        )
        for (d in overflow) {
            val day = month.day(d)
            val list = day.pieces.joinToString(",  ") { "${it.startText()}–${it.endText()}" }
            sb.append(
                appendixParagraph(
                    "$d. ${month.month.monthValue}. ${month.month.year} (${WEEKDAYS[day.date.dayOfWeek]}):   $list   =   ${WorkCalc.formatHm(day.minutes)} h",
                    "$ARIAL<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/>",
                )
            )
        }
        return sb.toString()
    }

    private fun appendixParagraph(text: String, rPr: String, pageBreakBefore: Boolean = false): String =
        "<w:p><w:pPr>" + (if (pageBreakBefore) "<w:pageBreakBefore w:val=\"1\"/>" else "") + "<w:spacing w:after=\"120\" w:before=\"0\"/><w:ind w:left=\"0\" w:right=\"1418\"/></w:pPr>" + run(text, rPr) + "</w:p>"

    // ---------------------------------------------------------------------------------------
    // Pomocné funkce
    // ---------------------------------------------------------------------------------------

    private fun cellText(xml: String, cell: Cell, text: String, rPr: String): Edit {
        val pos = insertionPoint(xml, cell)
        return Edit(pos, pos, run(text, rPr))
    }

    /** Místo těsně před koncem posledního odstavce buňky. */
    private fun insertionPoint(xml: String, cell: Cell): Int {
        val pos = xml.lastIndexOf("</w:p>", cell.end)
        if (pos < cell.start) throw DocxFormatException("Buňka formuláře neobsahuje odstavec.")
        return pos
    }

    private fun run(text: String, rPr: String): String =
        "<w:r w:rsidR=\"$MARK\"><w:rPr>$rPr</w:rPr><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun count(s: String, needle: String): Int {
        var n = 0
        var i = s.indexOf(needle)
        while (i >= 0) {
            n++
            i = s.indexOf(needle, i + needle.length)
        }
        return n
    }

    /** Najde elementy <w:name ...>…</w:name> (bez vnoření stejného jména) v daném rozsahu. */
    private fun elements(xml: String, name: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val open = "<w:$name"
        val close = "</w:$name>"
        val res = ArrayList<Pair<Int, Int>>()
        var i = from
        while (true) {
            val s = xml.indexOf(open, i)
            if (s < 0 || s >= to) break
            val next = xml[s + open.length]
            if (next != '>' && next != ' ') {
                i = s + open.length
                continue
            }
            val e = xml.indexOf(close, s)
            if (e < 0 || e > to) throw DocxFormatException("Neukončený element w:$name.")
            res.add(s to e + close.length)
            i = e + close.length
        }
        return res
    }

    val NUM_PR = Regex("<w:numPr><w:ilvl w:val=\"\\d+\"/><w:numId w:val=\"\\d+\"/></w:numPr>")

    private val TEXT_RE = Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>")

    /** Viditelný text fragmentu XML. */
    fun text(fragment: String): String = TEXT_RE.findAll(fragment).joinToString("") { it.groupValues[1] }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
