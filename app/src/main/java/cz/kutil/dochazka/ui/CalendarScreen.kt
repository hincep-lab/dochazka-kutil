package cz.kutil.dochazka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kutil.dochazka.core.DayFlag
import cz.kutil.dochazka.core.DaySummary
import cz.kutil.dochazka.core.OverrideType
import cz.kutil.dochazka.core.WorkCalc
import cz.kutil.dochazka.data.ShiftStatus
import java.time.LocalDate
import java.time.YearMonth

/**
 * Kalendář pracovních dnů: měsíční mřížka s tečkami (zelená = práce, modrá = dovolená/nepřítomnost,
 * oranžová = čeká na potvrzení / běžící směna), červená čísla pro víkendy a svátky. Klepnutí na den
 * ukáže jeho intervaly a nabídne úpravu; dovolenou jde zapsat na den i období.
 */
@Composable
fun CalendarScreen(vm: MainViewModel, state: UiState, onOpenDay: (LocalDate) -> Unit) {
    val ym by vm.selectedMonth.collectAsStateWithLifecycle()
    val month = remember(state.shifts, state.overrides, ym) { state.month(ym) }
    val today = Fmt.today()
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    val sel = runCatching { LocalDate.parse(selected) }.getOrNull()?.takeIf { YearMonth.from(it) == ym } ?: ym.atDay(1)
    var vacationDialog by remember { mutableStateOf(false) }
    val attention = remember(state.pending, state.shifts) {
        val set = HashSet<LocalDate>()
        state.openPending.forEach { set.add(Fmt.zoned(it.effectiveAt).toLocalDate()) }
        state.shifts.filter { it.status == ShiftStatus.OPEN }.forEach { set.add(Fmt.zoned(it.rawStart).toLocalDate()) }
        set
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MetalCard(padding = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.selectedMonth.value = ym.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Předchozí měsíc", tint = Ind.text)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.month(ym), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ind.text)
                    Text("Odpracováno ${WorkCalc.formatHm(month.totalMinutes)} h", fontSize = 13.sp, color = Ind.textDim)
                }
                IconButton(onClick = { vm.selectedMonth.value = ym.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Další měsíc", tint = Ind.text)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                for ((i, d) in listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne").withIndex()) {
                    Text(
                        d, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (i >= 5) Color(0xFFD32F2F) else Ind.textDim,
                    )
                }
            }
            val offset = ym.atDay(1).dayOfWeek.value - 1
            val cells = offset + ym.lengthOfMonth()
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until 7) {
                        val n = r * 7 + c - offset + 1
                        if (n < 1 || n > ym.lengthOfMonth()) {
                            Spacer(Modifier.weight(1f).aspectRatio(1f))
                        } else {
                            val d = month.day(n)
                            DayCell(
                                d = d,
                                isToday = d.date == today,
                                isSelected = d.date == sel,
                                attention = d.date in attention,
                                modifier = Modifier.weight(1f),
                            ) { selected = d.date.toString() }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                Legend(Ind.green, "práce")
                Legend(Ind.blue, "nepřítomnost")
                Legend(Ind.orange, "ke kontrole")
            }
        }

        // --- vybraný den ---------------------------------------------------------------------
        val d = month.day(sel.dayOfMonth)
        MetalCard {
            SectionTitle("${Fmt.dowLong(sel).replaceFirstChar { it.uppercase() }} ${Fmt.date(sel)}", iconRes = AppIcons.CALENDAR) {
                Text("${WorkCalc.formatHm(d.minutes)} h", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ind.text)
            }
            d.holidayName?.let { Text("Státní svátek: $it", color = Ind.amberText, fontSize = 13.sp) }
            d.override?.let { Text(if (it == OverrideType.DOVOLENA) "Dovolená" else "${it.code} – ${it.label}", color = Ind.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            if (d.pieces.isEmpty() && d.override == null) {
                Text(
                    if (DayFlag.SATURDAY in d.flags || DayFlag.SUNDAY in d.flags || DayFlag.HOLIDAY in d.flags) "Volno." else "Žádný pracovní záznam.",
                    color = Ind.textDim, fontSize = 14.sp,
                )
            }
            for (p in d.pieces) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Ind.green, size = 9.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("${p.startText()} – ${p.endText()}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ind.text, modifier = Modifier.weight(1f))
                    Text("${WorkCalc.formatHm(p.minutes)} h", fontSize = 15.sp, color = Ind.textDim)
                }
            }
            if (d.pieces.isNotEmpty()) {
                Text(
                    "Začátek ${d.pieces.first().startText()} · konec ${d.pieces.last().endText()}" +
                        (if (d.overtimeMinutes > 0) " · přesčas ${WorkCalc.formatHm(d.overtimeMinutes)} h" else ""),
                    fontSize = 13.sp, color = Ind.textDim,
                )
            }
            if (d.workLabels.isNotEmpty()) Text(d.workLabels.joinToString(" · "), color = Ind.amberText, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigActionButton("Upravit", ActionKind.ACCENT, Modifier.weight(1f), iconRes = AppIcons.EDIT, height = 50.dp) { onOpenDay(sel) }
                BigActionButton("Dovolená", ActionKind.METAL, Modifier.weight(1f), iconRes = AppIcons.VACATION, height = 50.dp) { vacationDialog = true }
            }
            Text(
                "Nemoc, lékař, náhradní volno a další nepřítomnosti zadáš v detailu dne (Upravit / doplnit → Označení dne).",
                fontSize = 12.sp, color = Ind.textMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (vacationDialog) {
        VacationDialog(
            initial = sel,
            onDismiss = { vacationDialog = false },
            onApply = { from, to, skipFree, remove ->
                vm.setVacation(from, to, skipFree, remove)
                vacationDialog = false
            },
        )
    }
}

@Composable
private fun DayCell(d: DaySummary, isToday: Boolean, isSelected: Boolean, attention: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val weekendOrHoliday = d.flags.isNotEmpty()
    val dot = when {
        attention -> Ind.orange
        d.pieces.isNotEmpty() -> Ind.green
        d.override != null -> Ind.blue
        else -> null
    }
    val bg = when {
        isSelected -> Ind.orange
        isToday -> Color(0xFF2F6FE0)
        else -> Color(0xFFF8FAFD)
    }
    val fg = when {
        isSelected -> Ind.onOrange
        isToday -> Color.White
        weekendOrHoliday -> Color(0xFFD32F2F)
        else -> Ind.text
    }
    val desc = "${d.date.dayOfMonth}. " + when {
        d.pieces.isNotEmpty() -> "odpracováno ${WorkCalc.formatHm(d.minutes)} hodin"
        d.override != null -> d.override.label
        d.holidayName != null -> "svátek"
        else -> "bez záznamu"
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, if (isToday && !isSelected) Color(0xFF1F5FCC) else Color(0x33000000), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d.date.dayOfMonth.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
            Box(Modifier.padding(top = 3.dp).size(6.dp).clip(CircleShape).background(if (dot == null) Color.Transparent else if (isSelected) Ind.onOrange else dot))
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, size = 8.dp)
        Spacer(Modifier.size(6.dp))
        Text(text, fontSize = 12.sp, color = Ind.textDim)
    }
}
