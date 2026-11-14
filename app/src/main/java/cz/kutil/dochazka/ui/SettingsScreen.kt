package cz.kutil.dochazka.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.data.AppPrefs
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    val perm = rememberPermStatus()
    var organization by rememberSaveable(state.settings.organization) { mutableStateOf(state.settings.organization) }
    var employee by rememberSaveable(state.settings.employee) { mutableStateOf(state.settings.employee) }
    var zoneEditing by rememberSaveable { mutableStateOf(false) }
    var reminder by remember { mutableStateOf(AppPrefs.reminderEnabled(context)) }
    var email by rememberSaveable { mutableStateOf(AppPrefs.reportEmail(context)) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.backup(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Údaje do výkazu") {
            OutlinedTextField(organization, { organization = it }, label = { Text("Organizace") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(employee, { employee = it }, label = { Text("Jméno") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.saveHeader(organization, employee) }) { Text("Uložit") }
        }

        if (!state.demo) {
            SectionCard("Automatické sledování") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ptát se při odchodu z domu a návratu", Modifier.weight(1f))
                    Switch(checked = state.settings.trackingEnabled, onCheckedChange = { vm.setTracking(it) })
                }
                Text("Stav: ${trackStateText(state.settings.trackState)}", style = MaterialTheme.typography.bodySmall)
                Check(perm.fine, "Přesná poloha")
                Check(perm.background, "Poloha povolena vždy (na pozadí)")
                Check(perm.locationOn, "Poloha v telefonu zapnutá")
                Check(perm.notifications, "Oznámení povolena")
                Check(perm.batteryUnrestricted, "Baterie bez omezení (doporučeno)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { SystemScreens.appSettings(context) }) { Text("Nastavení aplikace") }
                    OutlinedButton(onClick = { SystemScreens.locationSettings(context) }) { Text("Poloha") }
                }
                TextButton(onClick = { vm.restartOnboarding() }) { Text("Spustit znovu úvodního průvodce") }
            }

            SectionCard("Domácí zóna a Wi-Fi") {
                val zone = state.zone
                if (zone == null) {
                    Text("Domácí zóna zatím není nastavená.")
                } else {
                    Text("Zóna je nastavená, poloměr ${zone.radiusM.toInt()} m.")
                    Text(if (zone.wifiSsid.isBlank()) "Domácí Wi-Fi: nenastavena" else "Domácí Wi-Fi: ${zone.wifiSsid}")
                }
                if (zoneEditing || zone == null) {
                    ZoneEditor(vm, state, onSaved = { zoneEditing = false })
                } else {
                    OutlinedButton(onClick = { zoneEditing = true }) { Text("Změnit zónu nebo Wi-Fi") }
                }
            }
        }

        SectionCard("Měsíční uzávěrka") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Připomenout 1. den v měsíci", Modifier.weight(1f))
                Switch(checked = reminder, onCheckedChange = {
                    reminder = it
                    AppPrefs.setReminderEnabled(context, it)
                })
            }
            Text(
                "Prvního (po 7. hodině) přijde oznámení. Klepnutím otevřeš přehled minulého měsíce, projdeš dny, doplníš co chybí " +
                    "a výkaz odešleš e-mailem nebo uložíš. Když je telefon prvního vypnutý, připomenutí přijde dodatečně.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail, kam se výkaz posílá") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                AppPrefs.setReportEmail(context, email)
                vm.message.value = "E-mail uložen."
            }) { Text("Uložit e-mail") }
        }

        SectionCard("Záloha a obnova") {
            Text(
                "Záloha je soubor JSON, který si uložíš, kam chceš (telefon, karta, vlastní disk). Aplikace sama nic nikam neposílá." +
                    if (state.demo) " V demo režimu se zálohují jen ukázková data." else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { backupLauncher.launch("Dochazka_zaloha_${LocalDate.now(PRAGUE)}.json") }) { Text("Zálohovat") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Text("Obnovit ze zálohy")
                }
            }
        }

        SectionCard("Demo režim") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ukázková data (listopad 2026)", Modifier.weight(1f))
                Switch(checked = state.demo, onCheckedChange = { vm.setDemo(it) })
            }
            Text(
                "Demo má vlastní oddělenou databázi. Skutečná docházka se nemění a sledování polohy do dema nic nezapisuje. " +
                    "Hodí se na vyzkoušení exportu: 21 všedních dnů 7:00–15:30, 12. 11. druhý výjezd, sobota 14. 11., neděle 15. 11., svátek 17. 11. = 186:00 h.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Co aplikace umí a neumí") {
            Text(
                "• Odchod a návrat hlásí Android (Geofencing). Hlášení chodí se zpožděním, obvykle 1–5 minut, při úsporném režimu i déle, a výjimečně nepřijde vůbec.\n" +
                    "• Po restartu telefonu se zóna obnoví sama; po „Vynutit ukončení“ aplikace až po jejím dalším otevření.\n" +
                    "• Domácí Wi-Fi je jen pojistka. To, že název sítě nejde přečíst, se nikdy nebere jako důkaz, že nejsi doma.\n" +
                    "• Nic se nezapíše bez tvého ANO. Všechny časy jdou opravit, původně zaznamenané časy zůstávají uložené.\n" +
                    "• Časy do výkazu se zaokrouhlují na půlhodiny (6:50 → 7:00, 15:40 → 15:30, čtvrt a tři čtvrtě nahoru).\n" +
                    "• Přesčasy, příplatky ani právní nároky aplikace nepočítá.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    restoreUri?.let { uri ->
        ConfirmDialog(
            title = "Obnovit ze zálohy?",
            text = "Všechny současné záznamy v aplikaci budou nahrazeny obsahem zálohy. Tento krok nejde vrátit – pokud si nejsi jistý, udělej si nejdřív zálohu současného stavu.",
            confirmLabel = "Nahradit data",
            onConfirm = {
                vm.restore(uri)
                restoreUri = null
            },
            onDismiss = { restoreUri = null },
        )
    }
}

@Composable
private fun Check(ok: Boolean, label: String) {
    Text(
        (if (ok) "✓ " else "✗ ") + label,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Medium,
    )
}
