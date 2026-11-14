package cz.kutil.dochazka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.kutil.dochazka.data.PendingEventEntity
import cz.kutil.dochazka.track.Notifier
import java.time.LocalDate

private fun statusText(p: PendingEventEntity): String = when (p.status) {
    "YES" -> "ANO"
    "NO" -> "NE"
    "SUPERSEDED" -> "zrušeno (už nebylo potřeba)"
    else -> if (p.deferred) "ověřuje se (domácí Wi-Fi)" else "čeká"
}

/** Nevyřízené návrhy (i ty, u kterých uživatel oznámení ignoroval nebo zavřel) a jejich historie. */
@Composable
fun PendingScreen(vm: MainViewModel, state: UiState, onOpenDay: (LocalDate) -> Unit) {
    val open = state.openPending
    val history = state.pending.filter { it.status != "PENDING" }.take(30)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Čeká na potvrzení", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (open.isEmpty()) {
            Text("Nic nečeká. Když opustíš domácí zónu nebo se vrátíš, objeví se tu dotaz – i kdybys oznámení přehlédl.")
        }
        for (p in open) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Notifier.title(p), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(Notifier.detail(p))
                    Text(
                        "Událost zjištěna ${Fmt.dateTime(p.eventDetectedAt)}, dotaz vytvořen ${Fmt.time(p.promptCreatedAt)}. " +
                            "Čas z polohy je přibližný – po potvrzení ho můžeš opravit.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.answer(p.id, true) }, modifier = Modifier.weight(1f)) { Text("ANO") }
                        OutlinedButton(onClick = { vm.answer(p.id, false) }, modifier = Modifier.weight(1f)) { Text("NE") }
                    }
                    TextButton(onClick = {
                        vm.answer(p.id, true)
                        onOpenDay(Fmt.zoned(p.eventDetectedAt).toLocalDate())
                    }) { Text("ANO a upravit čas") }
                }
            }
        }

        if (history.isNotEmpty()) {
            Text("Historie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            for (p in history) {
                Column {
                    Text("${Fmt.dateTime(p.eventDetectedAt)} · ${if (p.type == "END") "návrat" else "odchod"} · ${statusText(p)}")
                    p.answeredAt?.let { Text("vyřízeno ${Fmt.dateTime(it)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
