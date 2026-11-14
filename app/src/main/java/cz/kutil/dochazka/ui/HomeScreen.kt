package cz.kutil.dochazka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.Rounding
import cz.kutil.dochazka.core.TrackState
import cz.kutil.dochazka.core.WorkCalc
import cz.kutil.dochazka.track.Notifier
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

fun trackStateText(state: String): String = when (runCatching { TrackState.valueOf(state) }.getOrDefault(TrackState.HOME)) {
    TrackState.HOME -> "Doma"
    TrackState.OUTSIDE_PENDING -> "Mimo domov – čeká dotaz na začátek práce"
    TrackState.WORKING -> "V práci (směna běží)"
    TrackState.RETURN_PENDING -> "Doma – čeká dotaz na ukončení práce"
    TrackState.AWAY -> "Mimo domov (soukromě)"
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, primary: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp), contentPadding = ButtonDefaults.ContentPadding) { content() }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp)) { content() }
    }
}

@Composable
fun HomeScreen(vm: MainViewModel, state: UiState, onOpenDay: (LocalDate) -> Unit, onOpenTab: (Tab) -> Unit) {
    val context = LocalContext.current
    val perm = rememberPermStatus()
    val today = Fmt.today()
    val month = remember(state.shifts, state.overrides, today) { state.month(YearMonth.from(today)) }
    val day = month.day(today.dayOfMonth)
    val open = state.openShift
    var addDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- stav sledování ---------------------------------------------------------------
        SectionCard("Stav sledování") {
            if (state.demo) {
                Text("Demo režim: zobrazují se ukázková data, sledování polohy do nich nezasahuje. Vypneš ho v Nastavení.")
            } else if (!state.settings.trackingEnabled) {
                Text("Automatické sledování je vypnuté – docházku zapisuješ ručně tlačítky níže.")
                TextButton(onClick = { onOpenTab(Tab.SETTINGS) }) { Text("Otevřít nastavení") }
            } else {
                Text(trackStateText(state.settings.trackState), fontWeight = FontWeight.Bold)
                if (state.zone == null) Problem("Domácí zóna není nastavená.", "Nastavit") { onOpenTab(Tab.SETTINGS) }
                if (!perm.fine) Problem("Chybí oprávnění k přesné poloze.", "Otevřít nastavení aplikace") { SystemScreens.appSettings(context) }
                else if (!perm.background) Problem(
                    "Poloha není povolená „vždy“ – na pozadí aplikace odchod nepozná. Nastavení → Oprávnění → Poloha → Povolit vždy.",
                    "Otevřít nastavení aplikace",
                ) { SystemScreens.appSettings(context) }
                if (!perm.locationOn) Problem("Poloha v telefonu je vypnutá – sledování zóny stojí.", "Zapnout polohu") { SystemScreens.locationSettings(context) }
                if (!perm.notifications) Problem("Oznámení jsou vypnutá – dotazy uvidíš jen tady v aplikaci (záložka Návrhy).", "Povolit oznámení") {
                    SystemScreens.notificationSettings(context)
                }
                if (!perm.batteryUnrestricted) Problem(
                    "Telefon může aplikaci uspávat a dotazy zpozdit. Doporučeno: Baterie → Neomezeno.",
                    "Otevřít nastavení aplikace",
                ) { SystemScreens.appSettings(context) }
            }
            if (!state.demo && state.settings.lastDiagnostic.isNotBlank() && state.settings.lastDiagnosticAt > 0) {
                Text(
                    "Poslední událost (${Fmt.dateTime(state.settings.lastDiagnosticAt)}): ${state.settings.lastDiagnostic}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- nevyřízené návrhy ------------------------------------------------------------
        val pending = state.openPending
        if (pending.isNotEmpty()) {
            SectionCard("Čeká na potvrzení (${pending.size})") {
                val p = pending.first()
                Text(Notifier.title(p), fontWeight = FontWeight.Bold)
                Text(Notifier.detail(p))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.answer(p.id, true) }, modifier = Modifier.weight(1f)) { Text("ANO") }
                    OutlinedButton(onClick = { vm.answer(p.id, false) }, modifier = Modifier.weight(1f)) { Text("NE") }
                }
                if (pending.size > 1) TextButton(onClick = { onOpenTab(Tab.PENDING) }) { Text("Zobrazit všechny návrhy") }
            }
        }

        // --- otevřená směna ---------------------------------------------------------------
        if (open != null) {
            SectionCard("Otevřená směna") {
                val eff = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
                Text("Běží od ${Fmt.dateTime(eff)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Zaznamenáno v ${Fmt.time(open.rawStart)}; do výkazu jde čas zaokrouhlený na půlhodinu.", style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- velká tlačítka ---------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Začít práci", enabled = open == null, modifier = Modifier.weight(1f)) { vm.startWork() }
            BigButton("Ukončit práci", enabled = open != null, modifier = Modifier.weight(1f)) { vm.endWork() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Přidat výjezd", primary = false, modifier = Modifier.weight(1f)) { addDialog = true }
            BigButton("Opravit čas", primary = false, modifier = Modifier.weight(1f)) { onOpenDay(today) }
        }

        // --- dnešek -----------------------------------------------------------------------
        SectionCard("Dnes – ${Fmt.dowLong(today)} ${Fmt.date(today)}") {
            day.holidayName?.let { Text("Státní svátek: $it", color = MaterialTheme.colorScheme.tertiary) }
            if (day.pieces.isEmpty() && open == null) {
                Text("Zatím žádný pracovní záznam.")
            } else {
                for (piece in day.pieces) Text("${piece.startText()}–${piece.endText()}   (${WorkCalc.formatHm(piece.minutes)} h)", fontSize = 18.sp)
                if (day.workLabels.isNotEmpty()) Text(day.workLabels.joinToString(" · "), color = MaterialTheme.colorScheme.tertiary)
                Text("Dnes odpracováno: ${WorkCalc.formatHm(day.minutes)} h", fontWeight = FontWeight.Bold)
            }
            day.override?.let { Text("Označení dne: ${it.code} – ${it.label}") }
            TextButton(onClick = { onOpenDay(today) }) { Text("Detail dne") }
        }

        // --- měsíc ------------------------------------------------------------------------
        SectionCard(Fmt.month(month.month)) {
            Text("Odpracováno: ${WorkCalc.formatHm(month.totalMinutes)} h", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (month.openShifts > 0) Text("Neuzavřené směny se do součtu nepočítají (${month.openShifts}).", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onOpenTab(Tab.MONTH) }) { Text("Přehled měsíce a export") }
        }
    }

    if (addDialog) {
        val now = LocalTime.now(PRAGUE).withSecond(0).withNano(0)
        val endDefault = LocalTime.of(now.hour, if (now.minute < 30) 0 else 30)
        val startDefault = if (endDefault.hour >= 1) endDefault.minusHours(1) else LocalTime.MIDNIGHT
        ShiftEditDialog(
            draft = ShiftDraft(null, today, startDefault, if (endDefault.isAfter(startDefault)) endDefault else startDefault.plusMinutes(30), false, "", null),
            others = state.otherIntervals(null),
            onDismiss = { addDialog = false },
            onSave = { s, e, round, note ->
                vm.saveShift(null, s, e, round, note)
                addDialog = false
            },
        )
    }
}

@Composable
private fun Problem(text: String, action: String, onClick: () -> Unit) {
    Column {
        Text("⚠ $text", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onClick) { Text(action) }
    }
}
