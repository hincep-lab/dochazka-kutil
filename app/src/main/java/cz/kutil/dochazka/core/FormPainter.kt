package cz.kutil.dochazka.core

/**
 * Kreslicí plocha pro PDF (jednotky = typografické body, 1/72 palce; y roste dolů).
 * V aplikaci ji implementuje Android Canvas (PdfDocument), v testech jednoduchý záznamník.
 */
interface FormCanvas {
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float)
    fun fill(x: Float, y: Float, w: Float, h: Float, gray: Float)
    /** y = účaří textu, x = levý okraj textu. */
    fun text(s: String, x: Float, y: Float, size: Float, bold: Boolean, italic: Boolean)
    fun width(s: String, size: Float, bold: Boolean, italic: Boolean): Float
}

/**
 * Výkaz pro PDF: stejný formulář jako Pavel_ORIGINAL.docx (rozměry sloupců a řádků podle originálu,
 * stejné nadpisy, záhlaví, podpisová pole a vysvětlivky) a stejné vyplnění jako export do Wordu.
 * Word zůstává originálem; PDF je jeho věrné překreslení pro tisk a odeslání bez Wordu.
 */
object FormPainter {
    const val PAGE_W = 595.28f
    const val PAGE_H = 841.89f

    private fun tw(t: Int) = t / 20f

    /** Šířky sloupců řádku dne (Den, 10 × Příchod/Odchod, Hodin odprac.) v bodech – součet = šířka tabulky originálu. */
    private val COLS = listOf(tw(680)) + List(10) { tw(794) } + listOf(tw(1931))
    val TABLE_W = COLS.sum()
    val X0 = (PAGE_W - TABLE_W) / 2

    private const val HEAD1 = 20f
    private const val HEAD2 = 26f
    const val ROW = 13f
    private const val FOOT_ROW = 17f
    const val TABLE_TOP = 140f
    private const val GRAY = 0.78f

    private fun colX(i: Int): Float = X0 + COLS.take(i).sum()

    fun pages(month: MonthSummary): Int = if (month.days.any { it.pieces.size > DocxExporter.SLOTS_PER_DAY }) 2 else 1

    fun draw(c: FormCanvas, page: Int, month: MonthSummary, header: ExportHeader) {
        if (page == 0) drawForm(c, month, header) else drawAppendix(c, month, header)
    }

    // ---------------------------------------------------------------------------------------

    private fun center(c: FormCanvas, s: String, cx: Float, y: Float, size: Float, bold: Boolean, italic: Boolean = false) =
        c.text(s, cx - c.width(s, size, bold, italic) / 2, y, size, bold, italic)

    private fun right(c: FormCanvas, s: String, rx: Float, y: Float, size: Float, bold: Boolean) =
        c.text(s, rx - c.width(s, size, bold, false), y, size, bold, false)

    private fun box(c: FormCanvas, x: Float, y: Float, w: Float, h: Float, width: Float = 0.5f) {
        c.line(x, y, x + w, y, width)
        c.line(x, y + h, x + w, y + h, width)
        c.line(x, y, x, y + h, width)
        c.line(x + w, y, x + w, y + h, width)
    }

    private fun drawForm(c: FormCanvas, month: MonthSummary, header: ExportHeader) {
        val mid = PAGE_W / 2
        // --- nadpis a záhlaví -------------------------------------------------------------------
        val title = "EVIDENCE DOCHÁZKY"
        center(c, title, mid, 78f, 15f, true)
        val tWidth = c.width(title, 15f, true, false)
        c.line(mid - tWidth / 2, 80.5f, mid + tWidth / 2, 80.5f, 1f)
        center(c, "ORGANIZACE:   ${header.organization}", mid, 110f, 12.5f, true)
        val monthName = DocxExporter.MONTHS[month.month.monthValue - 1]
        c.text("Jméno: ${header.employee}", X0 + 4f, 128f, 11f, true, false)
        c.text("Měsíc: $monthName", X0 + tw(4300), 128f, 11f, true, false)
        c.text("Rok: ${month.month.year}", X0 + tw(7900), 128f, 11f, true, false)

        // --- záhlaví tabulky --------------------------------------------------------------------
        val top = TABLE_TOP
        val headBottom = top + HEAD1 + HEAD2
        c.fill(X0, top, TABLE_W, HEAD1 + HEAD2, GRAY)
        val groups = listOf("Dopoledne", "Odpoledne", "Přerušení", "Přerušení", "Přerušení")
        for ((g, name) in groups.withIndex()) {
            val x = colX(1 + 2 * g)
            box(c, x, top, COLS[1] * 2, HEAD1)
            center(c, name, x + COLS[1], top + 14f, 9f, true)
        }
        val sub = listOf("Příchod", "Odchod", "Příchod", "Odchod", "Odchod", "Příchod", "Odchod", "Příchod", "Odchod", "Příchod")
        for ((i, name) in sub.withIndex()) {
            val x = colX(1 + i)
            box(c, x, top + HEAD1, COLS[1], HEAD2)
            center(c, name, x + COLS[1] / 2, top + HEAD1 + 17f, 7.5f, true)
        }
        box(c, X0, top, COLS[0], HEAD1 + HEAD2)
        center(c, "Den", X0 + COLS[0] / 2, top + HEAD1 + 17f, 8f, true)
        box(c, colX(11), top, COLS[11], HEAD1 + HEAD2)
        center(c, "Hodin odprac.", colX(11) + COLS[11] / 2, top + 27f, 9.5f, true)

        // --- dny -----------------------------------------------------------------------------
        val daysTop = headBottom
        for (d in 1..31) {
            val y = daysTop + (d - 1) * ROW
            c.fill(X0, y, COLS[0], ROW, GRAY)
            for (i in 0..11) box(c, colX(i), y, COLS[i], ROW)
            center(c, d.toString(), X0 + COLS[0] / 2, y + 10f, 9f, true)
        }
        val freeWeekend = ArrayList<Int>()
        for (day in month.days) {
            val d = day.date.dayOfMonth
            val y = daysTop + (d - 1) * ROW
            val base = y + 10f
            fun cell(i: Int, s: String) = center(c, s, colX(i) + COLS[i] / 2, base, 9f, true)
            val pieces = day.pieces
            pieces.getOrNull(0)?.let { cell(1, it.startText()); cell(2, it.endText()) }
            pieces.getOrNull(1)?.let { cell(3, it.startText()); cell(4, it.endText()) }
            val weekend = DayFlag.SATURDAY in day.flags || DayFlag.SUNDAY in day.flags
            val free = pieces.isEmpty() && day.override == null
            if (free && weekend) freeWeekend.add(d)
            val freeHoliday = free && DayFlag.HOLIDAY in day.flags
            val vacation = day.override == OverrideType.DOVOLENA
            val code = if (vacation) null else day.override?.code ?: if (freeHoliday && !weekend) "S" else null
            if (code != null) cell(when (pieces.size) { 0 -> 1; 1 -> 3; else -> 5 }, code)

            val notes = ArrayList<String>()
            if (pieces.size > DocxExporter.SLOTS_PER_DAY) notes.add("viz příloha")
            if (pieces.isNotEmpty() && vacation) notes.add("dovolená")
            val label = day.workLabels.joinToString(", ") { it.replaceFirstChar { ch -> ch.lowercase() } }
            if (label.isNotEmpty()) notes.add("($label)")
            val hx = colX(11)
            val left = when {
                notes.isNotEmpty() -> notes.joinToString(" ")
                pieces.isEmpty() && vacation -> "Dovolená"
                pieces.isEmpty() && freeHoliday -> "st. svátek"
                else -> ""
            }
            if (left.isNotEmpty()) c.text(left, hx + 3.5f, base - 0.5f, 7f, false, false)
            if (pieces.isNotEmpty()) right(c, WorkCalc.formatHm(day.minutes), hx + COLS[11] - 3.5f, base, 9.5f, true)
        }
        // Volné víkendy: jedna čára z levého dolního do pravého horního rohu přes sloupce Příchod/Odchod.
        var i = 0
        while (i < freeWeekend.size) {
            var j = i
            while (j + 1 < freeWeekend.size && freeWeekend[j + 1] == freeWeekend[j] + 1) j++
            val yTop = daysTop + (freeWeekend[i] - 1) * ROW
            val yBottom = daysTop + freeWeekend[j] * ROW
            c.line(colX(1), yBottom, colX(11), yTop, 0.75f)
            i = j + 1
        }
        val daysBottom = daysTop + 31 * ROW

        // --- podpisy a součty -------------------------------------------------------------------
        val fw = listOf(tw(1474), tw(1588), tw(1588), tw(1588), tw(2382), tw(1931))
        fun fx(k: Int) = X0 + fw.take(k).sum()
        val left1 = listOf("Podpis", "Podpis", "Datum")
        val mid1 = listOf("Schválil", "Podpis", "datum")
        val sums = listOf("ODPRAC.  HODINY", "PŘESČAS.  HODINY", "CELKEM")
        val regular = month.totalMinutes - month.overtimeMinutes
        val values = listOf(
            WorkCalc.formatHm(regular),
            if (month.overtimeMinutes > 0) WorkCalc.formatHm(month.overtimeMinutes) else "---------",
            WorkCalc.formatHm(month.totalMinutes),
        )
        for (r in 0..2) {
            val y = daysBottom + r * FOOT_ROW
            c.fill(fx(0), y, fw[0], FOOT_ROW, GRAY)
            c.fill(fx(2), y, fw[2], FOOT_ROW, GRAY)
            c.fill(fx(4), y, fw[4], FOOT_ROW, GRAY)
            for (k in 0..5) box(c, fx(k), y, fw[k], FOOT_ROW)
            c.text(left1[r], fx(0) + 3.5f, y + 12f, 9.5f, true, false)
            c.text(mid1[r], fx(2) + 3.5f, y + 12f, 9.5f, true, false)
            c.text(sums[r], fx(4) + 3.5f, y + 12f, 9.5f, true, false)
            right(c, values[r], fx(5) + fw[5] - 3.5f, y + 12f, 10f, true)
        }
        val tableBottom = daysBottom + 3 * FOOT_ROW
        // silné čáry: obrys, pod záhlavím, před sloupcem hodin, nad podpisy
        box(c, X0, top, TABLE_W, tableBottom - top, 1.5f)
        c.line(X0, headBottom, X0 + TABLE_W, headBottom, 1.5f)
        c.line(colX(11), top, colX(11), daysBottom, 1.5f)
        c.line(X0, daysBottom, X0 + TABLE_W, daysBottom, 1.5f)
        c.line(fx(5), daysBottom, fx(5), tableBottom, 1.5f)

        // --- vysvětlivky a poznámky ------------------------------------------------------------
        var y = tableBottom + 13f
        val head = "Vysvětlivky:    v docházce uvádějte tyto zkratky"
        c.text(head, X0, y, 9f, true, true)
        c.line(X0, y + 1.5f, X0 + c.width(head, 9f, true, true), y + 1.5f, 0.6f)
        val legend = listOf(
            listOf("D - " to true, "dovolená       " to false, "S -  " to true, "svátky a ostatní dny volna      " to false, "N -  " to true, "nemoc      " to false, "O -  " to true, "ošetřování člena rodiny" to false),
            listOf("P -  " to true, "pracovní volno - § 21 Nař.vlády  – důležité osobní překážky v práci, např.lékař,stěhování,svatba" to false),
            listOf("NV -  " to true, "náhradní volno za práci přesčas" to false),
            listOf("V -  " to true, "volno z důvodů obecného zájmu, vojenská služba /§ 14 – 20 Nařízení vlády č.108/1994 Sb./" to false),
            listOf("A -  " to true, "neomluvená absence" to false),
        )
        for (line in legend) {
            y += 11.5f
            var x = X0
            for ((s, bold) in line) {
                c.text(s, x, y, 9f, bold, true)
                x += c.width(s, 9f, bold, true)
            }
        }
        val note = DocxExporter.buildLabelNote(month)
        val overflow = month.days.filter { it.pieces.size > DocxExporter.SLOTS_PER_DAY }.map { it.date.dayOfMonth }
        val overflowNote = if (overflow.isEmpty()) "" else
            "Více výjezdů v jednom dni – viz příloha: " + overflow.joinToString(", ") { "$it. ${month.month.monthValue}." }
        for (n in listOf(note, overflowNote).filter { it.isNotEmpty() }) {
            y += 11.5f
            var size = 8.5f
            while (size > 6f && c.width(n, size, true, true) > TABLE_W) size -= 0.5f
            c.text(n, X0, y, size, true, true)
        }
    }

    private fun drawAppendix(c: FormCanvas, month: MonthSummary, header: ExportHeader) {
        val monthName = DocxExporter.MONTHS[month.month.monthValue - 1]
        var y = 80f
        c.text("PŘÍLOHA K EVIDENCI DOCHÁZKY", X0, y, 14f, true, false)
        y += 20f
        c.text("${header.organization}  –  ${header.employee}  –  $monthName ${month.month.year}", X0, y, 11f, true, false)
        y += 18f
        c.text("Dny s více pracovními intervaly, než kolik má formulář sloupců. Všechny uvedené intervaly", X0, y, 10f, false, false)
        y += 13f
        c.text("jsou započteny v hodinách daného dne i v celkovém součtu na první straně.", X0, y, 10f, false, false)
        y += 8f
        for (day in month.days.filter { it.pieces.size > DocxExporter.SLOTS_PER_DAY }) {
            y += 16f
            val list = day.pieces.joinToString(",  ") { "${it.startText()}–${it.endText()}" }
            val text = "${day.date.dayOfMonth}. ${month.month.monthValue}. ${month.month.year} (${DocxExporter.WEEKDAYS[day.date.dayOfWeek]}):   $list   =   ${WorkCalc.formatHm(day.minutes)} h"
            var size = 11f
            while (size > 7f && c.width(text, size, false, false) > TABLE_W) size -= 0.5f
            c.text(text, X0, y, size, false, false)
        }
    }

    fun fileName(month: java.time.YearMonth): String = DocxExporter.fileName(month).removeSuffix(".docx") + ".pdf"
}
