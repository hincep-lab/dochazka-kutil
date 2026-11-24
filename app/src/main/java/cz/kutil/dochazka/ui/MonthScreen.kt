@file:OptIn(ExperimentalMaterial3Api::class)

package cz.kutil.dochazka.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kutil.dochazka.core.DayFlag
import cz.kutil.dochazka.core.DaySummary
import cz.kutil.dochazka.core.DocxExporter
import cz.kutil.dochazka.core.FormPainter
import cz.kutil.dochazka.core.OverrideType
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.WorkCalc
import cz.kutil.dochazka.data.Origin
import cz.kutil.dochazka.data.ShiftEntity
import cz.kutil.dochazka.data.ShiftStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val PDF_MIME = "application/pdf"

/** Dny, ve kterých něco čeká na potvrzení: nevyřízený návrh nebo neuzavřená směna. */
private fun unconfirmedDates(state: UiState): Set<LocalDate> {
    val set = HashSet<LocalDate>()
    for (p in state.openPending) set.add(Fmt.zoned(p.eventDetectedAt).toLocalDate())
    for (s in state.shifts) if (s.status == ShiftStatus.OPEN) set.add(Fmt.zoned(s.rawStart).toLocalDate())
    return set
}

@Composable
fun MonthScreen(vm: MainViewModel, state: UiState, onOpenDay: (LocalDate) -> Unit) {
    val ym by vm.selectedMonth.collectAsStateWithLifecycle()
    val month = remember(state.shifts, state.overrides, ym) { state.month(ym) }
    val unconfirmed = remember(state.pending, state.shifts) { unconfirmedDates(state) }
    var onlyUnconfirmed by rememberSaveable { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var vacationDialog by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
        if (uri != null) vm.exportPdf(uri, ym)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DOCX_MIME)) { uri ->
        if (uri != null) vm.export(uri, ym)
    }

    val days = if (onlyUnconfirmed) month.days.filter { it.date in unconfirmed } else month.days

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.selectedMonth.value = ym.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Předchozí měsíc")
            }
            Text(Fmt.month(ym), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.selectedMonth.value = YearMonth.now(PRAGUE) }) { Text("Dnes") }
            IconButton(onClick = { vm.selectedMonth.value = ym.plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Další měsíc")
            }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Odpracováno celkem: ${WorkCalc.formatHm(month.totalMinutes)} h", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val workedDays = month.days.count { it.pieces.isNotEmpty() }
                Text("Z toho přesčas (po 15:30 v pracovní dny): ${WorkCalc.formatHm(month.overtimeMinutes)} h · dnů s prací: $workedDays", style = MaterialTheme.typography.bodySmall)
                if (month.openShifts > 0) {
                    Text("Pozor: ${month.openShifts} neuzavřená směna není v součtu.", color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { exportDialog = true }) { Text("Export / odeslat") }
                    FilterChip(
                        selected = onlyUnconfirmed,
                        onClick = { onlyUnconfirmed = !onlyUnconfirmed },
                        label = { Text("Jen nepotvrzené") },
                    )
                }
                val vacationDays = month.days.count { it.override == OverrideType.DOVOLENA }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vacationDialog = true }) { Text("Dovolená") }
                    if (vacationDays > 0) Text("V tomto měsíci: $vacationDays dní")
                }
            }
        }
        if (days.isEmpty()) {
            Text("Žádné dny s chybějícím potvrzením.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(days, key = { it.date.toString() }) { d ->
                DayRow(d, d.date in unconfirmed) { onOpenDay(d.date) }
                HorizontalDivider()
            }
        }
    }

    if (vacationDialog) {
        val today = Fmt.today()
        VacationDialog(
            initial = if (YearMonth.from(today) == ym) today else ym.atDay(1),
            onDismiss = { vacationDialog = false },
            onApply = { from, to, skipFree, remove ->
                vm.setVacation(from, to, skipFree, remove)
                vacationDialog = false
            },
        )
    }

    if (exportDialog) {
        val overflow = month.days.filter { it.pieces.size > DocxExporter.SLOTS_PER_DAY }
        val pendingInMonth = unconfirmed.count { YearMonth.from(it) == ym }
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("Export – ${Fmt.month(ym)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Docházka se vyplní do kopie původního formuláře (Pavel_ORIGINAL.docx). Originál se nemění.")
                    Text("${state.settings.organization} · ${state.settings.employee}")
                    Text("Celkem: ${WorkCalc.formatHm(month.totalMinutes)} h, z toho přesčas ${WorkCalc.formatHm(month.overtimeMinutes)} h", fontWeight = FontWeight.Bold)
                    if (overflow.isNotEmpty()) {
                        Text(
                            "Dny ${overflow.joinToString(", ") { Fmt.dateShort(it.date) }} mají víc než ${DocxExporter.SLOTS_PER_DAY} výjezdy. " +
                                "Formulář má jen dva páry sloupců, proto budou všechny intervaly těchto dnů vypsané v příloze na druhé straně " +
                                "a u dne bude „viz příloha“. Hodiny jsou započtené celé.",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (pendingInMonth > 0 || month.openShifts > 0) {
                        Text(
                            "V měsíci jsou nepotvrzené návrhy nebo neuzavřené směny – do exportu se nedostanou, dokud je nevyřešíš.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("Odeslat e-mailem", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            exportDialog = false
                            vm.shareByEmail(ym)
                        }, modifier = Modifier.weight(1f)) { Text("Word") }
                        Button(onClick = {
                            exportDialog = false
                            vm.shareByEmail(ym, pdf = true)
                        }, modifier = Modifier.weight(1f)) { Text("PDF") }
                    }
                    Text("Uložit do telefonu", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportDialog = false
                            exportLauncher.launch(DocxExporter.fileName(ym))
                        }, modifier = Modifier.weight(1f)) { Text("Word") }
                        OutlinedButton(onClick = {
                            exportDialog = false
                            pdfLauncher.launch(FormPainter.fileName(ym))
                        }, modifier = Modifier.weight(1f)) { Text("PDF") }
                    }
                    Text(
                        "Word je vyplněný původní formulář. PDF je jeho věrné překreslení pro tisk a odeslání bez Wordu.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "E-mail se otevře ve tvé poštovní aplikaci s přiloženým výkazem; odeslání potvrdíš tam. Příjemce nastavíš v Nastavení.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { exportDialog = false }) { Text("Zavřít") } },
        )
    }
}

@Composable
private fun DayRow(d: DaySummary, unconfirmed: Boolean, onClick: () -> Unit) {
    val special = d.flags.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            Text(
                "${d.date.dayOfMonth}. ${Fmt.dowShort(d.date)}",
                fontWeight = FontWeight.Bold,
                color = if (special) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.weight(1f)) {
            if (d.pieces.isNotEmpty()) Text(d.pieces.joinToString(",  ") { "${it.startText()}–${it.endText()}" })
            d.override?.let { Text(if (it == OverrideType.DOVOLENA) "Dovolená" else "${it.code} – ${it.label}", style = MaterialTheme.typography.bodySmall) }
            if (d.workLabels.isNotEmpty()) {
                Text(d.workLabels.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            } else if (DayFlag.HOLIDAY in d.flags) {
                Text("Svátek: ${d.holidayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (unconfirmed) Text("Čeká na potvrzení", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(if (d.minutes > 0) WorkCalc.formatHm(d.minutes) else "", fontWeight = FontWeight.Bold)
    }
}

// -------------------------------------------------------------------------------------------
// Detail dne
// -------------------------------------------------------------------------------------------

private fun originText(origin: String): String = when (origin) {
    Origin.GEOFENCE -> "potvrzeno z oznámení"
    Origin.DEMO -> "ukázková data"
    else -> "zadáno ručně"
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun DayDetailScreen(vm: MainViewModel, state: UiState, date: LocalDate, onBack: () -> Unit) {
    val month = remember(state.shifts, state.overrides, date) { state.month(YearMonth.from(date)) }
    val day = month.day(date.dayOfMonth)
    val ids = day.pieces.map { it.shiftId }.toSet()
    val shifts = state.shifts.filter { s ->
        s.id in ids || (s.status == ShiftStatus.OPEN && Fmt.zoned(s.rawStart).toLocalDate() == date) ||
            // uzavřené záznamy s nulovou délkou po zaokrouhlení – aby šly opravit nebo smazat
            (s.status == ShiftStatus.CLOSED && Fmt.zoned(s.rawStart).toLocalDate() == date)
    }.distinctBy { it.id }

    var editing by remember { mutableStateOf<ShiftDraft?>(null) }
    var deleting by remember { mutableStateOf<ShiftEntity?>(null) }
    var overrideMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        day.holidayName?.let { Text("Státní svátek: $it", color = MaterialTheme.colorScheme.tertiary) }
        if (day.workLabels.isNotEmpty()) Text(day.workLabels.joinToString(" · "), color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
        Text("Odpracováno: ${WorkCalc.formatHm(day.minutes)} h", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (day.overtimeMinutes > 0) Text("Z toho přesčas (po 15:30): ${WorkCalc.formatHm(day.overtimeMinutes)} h")
        if (day.pieces.size > DocxExporter.SLOTS_PER_DAY) {
            Text(
                "Tento den má víc výjezdů, než má formulář sloupců – v exportu bude „viz příloha“ a všechny intervaly na druhé straně.",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (shifts.isEmpty()) Text("Žádný pracovní záznam.")
        for (s in shifts) {
            val data = s.toData()
            val eff = WorkCalc.effective(data)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (eff == null) {
                        Text("Běží od ${Fmt.time(WorkCalc.effectiveStart(data))}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    } else {
                        val minutes = eff.minutes
                        Text(
                            "${Fmt.time(eff.start)}–${Fmt.time(eff.end)}   (${WorkCalc.formatHm(maxOf(0L, minutes))} h)",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (minutes <= 0) Text("Po zaokrouhlení má nulovou délku – do výkazu se nepočítá. Oprav časy, nebo záznam smaž.", color = MaterialTheme.colorScheme.error)
                        if (Fmt.zoned(eff.end.toEpochMilli()).toLocalDate() != Fmt.zoned(eff.start.toEpochMilli()).toLocalDate() &&
                            Fmt.zoned(eff.end.toEpochMilli()).toLocalTime() != LocalTime.MIDNIGHT
                        ) {
                            Text("Přechází přes půlnoc – ve výkazu je rozdělený do dvou dnů.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Zaznamenáno: ${Fmt.dateTime(s.rawStart)}" + (s.rawEnd?.let { " – ${Fmt.dateTime(it)}" } ?: " – běží") +
                            " · ${originText(s.origin)}" + (if (s.editedStart != null || s.editedEnd != null) " · ručně opraveno" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.note.isNotBlank()) Text("Poznámka: ${s.note}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val start = eff?.start ?: WorkCalc.effectiveStart(data)
                            editing = ShiftDraft(
                                id = s.id,
                                date = Fmt.zoned(start.toEpochMilli()).toLocalDate(),
                                start = Fmt.zoned(start.toEpochMilli()).toLocalTime().withSecond(0).withNano(0),
                                end = eff?.end?.let { Fmt.zoned(it.toEpochMilli()).toLocalTime().withSecond(0).withNano(0) },
                                open = s.status == ShiftStatus.OPEN,
                                note = s.note,
                                rawInfo = "Původně zaznamenáno: ${Fmt.time(s.rawStart)}" + (s.rawEnd?.let { "–${Fmt.time(it)}" } ?: ""),
                            )
                        }) { Text("Opravit čas") }
                        TextButton(onClick = { deleting = s }) { Text("Smazat") }
                    }
                }
            }
        }
        Button(onClick = {
            editing = ShiftDraft(null, date, LocalTime.of(7, 0), LocalTime.of(15, 30), false, "", null)
        }, modifier = Modifier.fillMaxWidth()) { Text("Přidat interval") }

        // --- ruční označení dne (bez odpracovaného času) -----------------------------------
        SectionCard("Označení dne") {
            Text(day.override?.let { "${it.code} – ${it.label}" } ?: "Bez označení")
            Text("Dovolená, nemoc, lékař, náhradní volno apod. Zapisuje se zkratkou z vysvětlivek formuláře a nepřidává žádné hodiny.", style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { overrideMenu = true }) { Text("Změnit označení") }
                DropdownMenu(expanded = overrideMenu, onDismissRequest = { overrideMenu = false }) {
                    DropdownMenuItem(text = { Text("Bez označení") }, onClick = {
                        vm.setOverride(date, null)
                        overrideMenu = false
                    })
                    for (t in OverrideType.entries) {
                        DropdownMenuItem(text = { Text("${t.code} – ${t.label}") }, onClick = {
                            vm.setOverride(date, t)
                            overrideMenu = false
                        })
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        ShiftEditDialog(
            draft = draft,
            others = state.otherIntervals(draft.id),
            onDismiss = { editing = null },
            onSave = { s: Instant, e: Instant?, round: Boolean, note: String ->
                vm.saveShift(draft.id, s, e, round, note)
                editing = null
            },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Smazat záznam?",
            text = "Záznam ${Fmt.dateTime(s.rawStart)}" + (s.rawEnd?.let { " – ${Fmt.time(it)}" } ?: " (běžící směna)") + " bude trvale smazán.",
            confirmLabel = "Smazat",
            onConfirm = {
                vm.deleteShift(s.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}
