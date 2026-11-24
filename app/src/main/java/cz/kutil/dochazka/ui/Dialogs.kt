package cz.kutil.dochazka.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.kutil.dochazka.core.Interval
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.Rounding
import cz.kutil.dochazka.core.ShiftWarning
import cz.kutil.dochazka.core.WorkCalc
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

fun pickTime(context: Context, initial: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
}

fun pickDate(context: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

/**
 * Ukončení otevřené směny. Nabídne skutečný čas návratu domů, pokud ho aplikace zaznamenala,
 * aktuální čas, nebo ruční zadání. Bez výběru se nic neukončí.
 */
@Composable
fun EndShiftDialog(startMs: Long, returnAtMs: Long?, onDismiss: () -> Unit, onEnd: (Instant) -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ukončit práci") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Směna běží od ${Fmt.dateTime(startMs)}. Kdy jsi skončil?")
                if (returnAtMs != null) {
                    Button(onClick = { onEnd(Instant.ofEpochMilli(returnAtMs)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Při návratu domů: ${Fmt.dateTime(returnAtMs)}")
                    }
                    Text("Tento čas návratu aplikace zaznamenala z polohy.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { onEnd(Instant.ofEpochMilli(now)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Teď: ${Fmt.time(now)}")
                }
                OutlinedButton(onClick = {
                    val z = Fmt.zoned(returnAtMs ?: now)
                    pickDate(context, z.toLocalDate()) { d ->
                        pickTime(context, z.toLocalTime()) { t ->
                            val picked = d.atTime(t).atZone(PRAGUE).toInstant()
                            if (picked.toEpochMilli() <= startMs) error = "Konec musí být později než začátek směny."
                            else if (picked.toEpochMilli() > System.currentTimeMillis() + 60_000) error = "Konec nemůže být v budoucnosti."
                            else onEnd(picked)
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Zadat jiný čas") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Čas pak můžeš kdykoli opravit v detailu dne. Do výkazu se zaokrouhlí na půlhodinu.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zatím neukončovat") } },
    )
}

/** Zadání dovolené: jeden den (Od = Do) nebo celé období. */
@Composable
fun VacationDialog(initial: LocalDate, onDismiss: () -> Unit, onApply: (from: LocalDate, to: LocalDate, skipFree: Boolean, remove: Boolean) -> Unit) {
    val context = LocalContext.current
    var from by remember { mutableStateOf(initial) }
    var to by remember { mutableStateOf(initial) }
    var skipFree by remember { mutableStateOf(true) }
    val start = if (to.isBefore(from)) to else from
    val end = if (to.isBefore(from)) from else to
    var days = 0
    var d = start
    while (!d.isAfter(end) && days < 370) {
        if (!skipFree || WorkCalc.flags(d).isEmpty()) days++
        d = d.plusDays(1)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dovolená") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vyber první a poslední den. Pro jediný den nech obě data stejná.")
                OutlinedButton(onClick = { pickDate(context, from) { from = it; if (to.isBefore(it)) to = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Od: ${Fmt.dowLong(from)} ${Fmt.date(from)}")
                }
                OutlinedButton(onClick = { pickDate(context, to) { to = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Do: ${Fmt.dowLong(to)} ${Fmt.date(to)}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vynechat víkendy a svátky", Modifier.weight(1f))
                    Switch(checked = skipFree, onCheckedChange = { skipFree = it })
                }
                Text("Zapíše se dní: $days", fontWeight = FontWeight.Bold)
                Text("Ve výkazu bude u těchto dnů slovo „Dovolená“, bez odpracovaných hodin.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = days > 0, onClick = { onApply(from, to, skipFree, false) }, modifier = Modifier.fillMaxWidth()) { Text("Zapsat dovolenou") }
                OutlinedButton(onClick = { onApply(from, to, skipFree, true) }, modifier = Modifier.fillMaxWidth()) { Text("Zrušit dovolenou v tomto období") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}

/** Podklad pro dialog přidání / opravy intervalu. */
data class ShiftDraft(
    val id: Long?,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime?,
    /** Otevřená (běžící) směna – opravuje se jen začátek. */
    val open: Boolean,
    val note: String,
    /** Původně zaznamenané časy (jen pro informaci). */
    val rawInfo: String?,
)

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

@Composable
fun ShiftEditDialog(
    draft: ShiftDraft,
    others: List<Interval>,
    onDismiss: () -> Unit,
    onSave: (start: Instant, end: Instant?, round: Boolean, note: String) -> Unit,
) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(draft.date) }
    var start by remember { mutableStateOf(draft.start) }
    var end by remember { mutableStateOf(draft.end ?: draft.start.plusHours(1)) }
    var round by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf(draft.note) }
    var warnings by remember { mutableStateOf<List<ShiftWarning>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val startInstant = date.atTime(start).atZone(PRAGUE).toInstant()
    // Konec dřív než začátek = konec je až následující den (noční výjezd).
    val nextDay = !draft.open && end.isBefore(start)
    val endInstant = if (draft.open) null else (if (nextDay) date.plusDays(1) else date).atTime(end).atZone(PRAGUE).toInstant()
    val effStart = if (round) Rounding.roundToHalfHour(startInstant) else startInstant
    val effEnd = endInstant?.let { if (round) Rounding.roundToHalfHour(it) else it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "Přidat výjezd" else "Opravit čas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (draft.id == null) {
                    Text(
                        "Přidá do dne další pracovní interval (např. výjezd k zákazníkovi). Ukládá se jen čas od–do, " +
                            "žádné místo ani trasa. Stávající záznamy dne zůstanou, překrytý čas se do součtu nezapočítá dvakrát.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = { pickDate(context, date) { date = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Den: ${Fmt.dowLong(date)} ${Fmt.date(date)}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime(context, start) { start = it } }, modifier = Modifier.weight(1f)) {
                        Text("Od ${"%02d:%02d".format(start.hour, start.minute)}")
                    }
                    if (!draft.open) {
                        OutlinedButton(onClick = { pickTime(context, end) { end = it } }, modifier = Modifier.weight(1f)) {
                            Text("Do ${"%02d:%02d".format(end.hour, end.minute)}")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zaokrouhlit na půlhodiny", Modifier.weight(1f))
                    Switch(checked = round, onCheckedChange = { round = it })
                }
                val preview = if (effEnd == null) {
                    "Do výkazu: začátek ${Fmt.time(effStart)} (směna ještě běží)"
                } else {
                    val minutes = java.time.Duration.between(effStart, effEnd).toMinutes()
                    "Do výkazu: ${Fmt.time(effStart)}–${Fmt.time(effEnd)} (${WorkCalc.formatHm(minutes)} h)" +
                        if (nextDay) " – konec je následující den" else ""
                }
                Text(preview, fontWeight = FontWeight.Bold)
                draft.rawInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (draft.open) {
                    Text(
                        "Směna běží. Konec zadáš tlačítkem „Ukončit práci“ a případně ho potom opravíš.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Poznámka") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                error = null
                if (effEnd == null) {
                    onSave(startInstant, null, round, note)
                } else if (!effEnd.isAfter(effStart)) {
                    error = "Interval má po zaokrouhlení nulovou délku. Uprav časy nebo vypni zaokrouhlení."
                } else {
                    val w = WorkCalc.validate(effStart, effEnd, others)
                    if (ShiftWarning.OVERLAP in w) {
                        // Stejný čas nejde odpracovat dvakrát – překryv se neukládá.
                        error = "Interval se překrývá s jiným záznamem. Uprav časy, nebo nejdřív oprav ten druhý záznam."
                    } else if (w.isEmpty()) {
                        onSave(startInstant, endInstant, round, note)
                    } else {
                        warnings = w
                    }
                }
            }) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )

    warnings?.let { list ->
        ConfirmDialog(
            title = "Zkontroluj prosím",
            text = list.joinToString("\n") { "• " + it.text } + "\n\nOpravdu uložit?",
            confirmLabel = "Přesto uložit",
            onConfirm = {
                warnings = null
                onSave(startInstant, endInstant, round, note)
            },
            onDismiss = { warnings = null },
        )
    }
}
