@file:OptIn(ExperimentalMaterial3Api::class)

package cz.kutil.dochazka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.data.Confidence
import cz.kutil.dochazka.data.EventSource
import cz.kutil.dochazka.data.PendingEventEntity
import cz.kutil.dochazka.track.Notifier
import java.time.LocalDate
import kotlin.math.roundToInt

val OkGreen = Color(0xFF2E7D32)
val WarnOrange = Color(0xFFEF6C00)

fun eventTypeText(p: PendingEventEntity): String = when {
    p.type == "END" -> "Návrat domů"
    p.pairedEndAt != null -> "Odchod a návrat (celý interval mimo domov)"
    else -> "Odchod z domova"
}

fun sourceText(p: PendingEventEntity): String =
    (if (p.source == EventSource.GPS_WIFI) "GPS + domácí Wi-Fi" else "GPS") +
        (p.accuracyM?.let { ", přesnost polohy ±${it.roundToInt()} m" } ?: "")

private fun statusText(p: PendingEventEntity): String = when (p.status) {
    "YES" -> "Potvrzeno"
    "NO" -> "Odmítnuto"
    "SUPERSEDED" -> "Zrušeno (falešný poplach nebo už nebylo potřeba)"
    else -> if (p.deferred) "Ověřuje se" else "Čeká na potvrzení"
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ConfidencePill(p: PendingEventEntity) {
    when (p.confidence) {
        Confidence.UNCERTAIN -> Pill("Nejisté", WarnOrange)
        Confidence.CONFLICT -> Pill("Konfliktní", MaterialTheme.colorScheme.error)
        else -> Pill("Jisté", OkGreen)
    }
}

/**
 * Centrum nevyřízených událostí. Vše je v databázi, takže návrhy přežijí restart aplikace i telefonu,
 * i když uživatel oznámení přehlédl nebo zavřel. Odmítnuté a zrušené události zůstávají v historii.
 */
@Composable
fun PendingScreen(vm: MainViewModel, state: UiState, onOpenDay: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val open = state.openPending
    val verifying = state.pending.filter { it.status == "PENDING" && it.deferred }
    var onlyRejected by rememberSaveable { mutableStateOf(false) }
    // Zrušené události starší než den se nezobrazují (a úklid je i maže z databáze).
    val cutoff = System.currentTimeMillis() - cz.kutil.dochazka.track.TrackingEngine.SUPERSEDED_KEEP_MS
    val history = state.pending.filter { it.status != "PENDING" }
        .filter { it.status != "SUPERSEDED" || (it.answeredAt ?: it.eventDetectedAt) >= cutoff }
        .filter { !onlyRejected || it.status == "NO" }.take(100)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("K potvrzení (${open.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (open.isEmpty()) {
            Text("Nic nečeká. Když opustíš domácí zónu nebo se vrátíš, objeví se tu dotaz – i kdybys oznámení přehlédl nebo telefon restartoval.")
        }
        for (p in open) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(Notifier.title(p), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        ConfidencePill(p)
                    }
                    Text(
                        Fmt.dateTime(p.effectiveAt) + (p.pairedEndAt?.let { " – ${Fmt.time(it)}" } ?: ""),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (p.adjustedAt != null) {
                        Text("Čas jsi upravil. Původně zjištěno: ${Fmt.dateTime(p.eventDetectedAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Typ: ${eventTypeText(p)}")
                    Text("Zdroj: ${sourceText(p)}")
                    Text("Stav: ${statusText(p)}")
                    if (p.note.isNotBlank()) {
                        Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "Použije se čas události, ne čas potvrzení. Dotaz vznikl ${Fmt.dateTime(p.promptCreatedAt)}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.answer(p.id, true) }, modifier = Modifier.weight(1f)) { Text("Potvrdit") }
                        OutlinedButton(onClick = { vm.answer(p.id, false) }, modifier = Modifier.weight(1f)) { Text("Odmítnout") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val z = Fmt.zoned(p.effectiveAt)
                            pickTime(context, z.toLocalTime()) { t ->
                                vm.adjustPending(p.id, z.toLocalDate().atTime(t).atZone(PRAGUE).toInstant())
                            }
                        }) { Text("Upravit čas") }
                        TextButton(onClick = { onOpenDay(Fmt.zoned(p.effectiveAt).toLocalDate()) }) { Text("Zobrazit den") }
                    }
                }
            }
        }

        if (verifying.isNotEmpty()) {
            Text("Ověřuje se", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            for (p in verifying) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${eventTypeText(p)} · ${Fmt.dateTime(p.eventDetectedAt)}", fontWeight = FontWeight.Bold)
                        Text("Zdroj: ${sourceText(p)}")
                        Text(
                            (if (p.note.isNotBlank()) p.note + " " else "") +
                                "Během několika minut se ověří čerstvou polohou a Wi-Fi. Když to byl falešný poplach, dotaz vůbec nepřijde.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Historie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilterChip(selected = onlyRejected, onClick = { onlyRejected = !onlyRejected }, label = { Text("Jen odmítnuté") })
        }
        if (history.isEmpty()) Text("Zatím žádné vyřízené události.", style = MaterialTheme.typography.bodySmall)
        Text("Zrušené události (falešné poplachy) se po jednom dni samy mažou. Potvrzené a odmítnuté zůstávají.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (p in history) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${Fmt.dateTime(p.effectiveAt)} · ${eventTypeText(p)}", modifier = Modifier.weight(1f))
                    when (p.status) {
                        "YES" -> Pill("Potvrzeno", OkGreen)
                        "NO" -> Pill("Odmítnuto", MaterialTheme.colorScheme.error)
                        else -> Pill("Zrušeno", MaterialTheme.colorScheme.outline)
                    }
                }
                Text(
                    "Zdroj: ${sourceText(p)}" + (p.answeredAt?.let { " · vyřízeno ${Fmt.dateTime(it)}" } ?: "") +
                        (if (p.note.isNotBlank()) " · ${p.note}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
            }
        }
    }
}
