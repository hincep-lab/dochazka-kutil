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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kutil.dochazka.core.PRAGUE
import kotlin.math.roundToInt
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.data.AutoBackup
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState, onOpenDiag: () -> Unit) {
    val context = LocalContext.current
    val perm = rememberPermStatus()
    var organization by rememberSaveable(state.settings.organization) { mutableStateOf(state.settings.organization) }
    var employee by rememberSaveable(state.settings.employee) { mutableStateOf(state.settings.employee) }
    var zoneEditing by rememberSaveable { mutableStateOf(false) }
    var reminder by remember { mutableStateOf(AppPrefs.reminderEnabled(context)) }
    var email by rememberSaveable { mutableStateOf(AppPrefs.reportEmail(context)) }
    val restorePreview by vm.restorePreview.collectAsStateWithLifecycle()
    var longShiftHours by remember { mutableIntStateOf(AppPrefs.longShiftHours(context)) }
    var backupReminder by remember { mutableStateOf(AppPrefs.backupReminder(context)) }
    var statusNotif by remember { mutableStateOf(AppPrefs.statusNotification(context)) }
    var autoUpdate by remember { mutableStateOf(AppPrefs.autoUpdateCheck(context)) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setAutoBackupFolder(uri)
    }
    val lastMessage by vm.message.collectAsStateWithLifecycle()
    val lastBackup = remember(lastMessage, restorePreview) { AppPrefs.lastBackupAt(context) }
    val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.backup(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.inspectBackup(uri)
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
                Button(onClick = onOpenDiag, modifier = Modifier.fillMaxWidth()) { Text("Stav sledování (diagnostika)") }
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

        SectionCard("Vzhled") {
            var decor by remember { mutableStateOf(AppPrefs.decorAnimations(context)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dekorativní animace vody", Modifier.weight(1f))
                Switch(checked = decor, onCheckedChange = {
                    decor = it
                    AppPrefs.setDecorAnimations(context, it)
                })
            }
            Text(
                "Kapající kohoutek, nádoba plnící se k 8 hodinám, proudění v potrubí a vír u vpusti. Běží jen když je aplikace na obrazovce, " +
                    "nejvýš 30 snímků za sekundu. Při vypnutí (nebo když má telefon zapnuté „Odebrat animace“) zůstane klidný statický vzhled. " +
                    "Na docházku nemají animace žádný vliv.",
                style = MaterialTheme.typography.bodySmall,
            )
            var sounds by remember { mutableStateOf(AppPrefs.waterSounds(context)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zvuky vody", Modifier.weight(1f))
                Switch(checked = sounds, onCheckedChange = {
                    sounds = it
                    AppPrefs.setWaterSounds(context, it)
                })
            }
            Text(
                "Tiché ťuknutí kapky a v přesčasu šumění přetékající vody. Hraje jen při otevřené aplikaci a nikdy v tichém režimu ani na vibracích.",
                style = MaterialTheme.typography.bodySmall,
            )
            var gag by remember { mutableStateOf(AppPrefs.plumberGag(context)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Instalatér Kutil (3D gagy)", Modifier.weight(1f))
                Switch(checked = gag, onCheckedChange = {
                    gag = it
                    AppPrefs.setPlumberGag(context, it)
                })
            }
            Text(
                "Během směny Kutil dole na přehledu sám předvádí deset loutkových gagů, mimo směnu občas mávne a od 20:00 spí. Ovládání pod ním funguje normálně.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Kontrola pracovní doby") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ukazovat v liště „V práci od …“", Modifier.weight(1f))
                Switch(checked = statusNotif, onCheckedChange = {
                    statusNotif = it
                    vm.setStatusNotification(it)
                })
            }
            Text(
                "Dokud běží směna, visí v oznámeních trvalý řádek s časem začátku, běžícími hodinami a tlačítkem „Ukončit práci teď“.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Upozornit, když směna běží déle než $longShiftHours hodin", fontWeight = FontWeight.Bold)
            Slider(
                value = longShiftHours.toFloat(),
                onValueChange = {
                    longShiftHours = it.roundToInt()
                    AppPrefs.setLongShiftHours(context, longShiftHours)
                },
                valueRange = 4f..24f,
                steps = 19,
            )
            Text(
                "Ochrana proti zapomenutému ukončení práce. Přijde oznámení s volbami „Konec v … (čas návratu)“, „Konec teď“ a „Stále pracuji“ – vyřídíš je přímo v oznámení. " +
                    "Směna se nikdy neukončí sama; při ukončení aplikace nabídne čas návratu domů, pokud ho zaznamenala.",
                style = MaterialTheme.typography.bodySmall,
            )
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

        if (!state.demo) {
            SectionCard("Aktualizace aplikace") {
                val upd by vm.update.collectAsStateWithLifecycle()
                Text("Nainstalováno: verze $versionName", fontWeight = FontWeight.Bold)
                when (val u = upd) {
                    UpdateState.Idle -> Unit
                    UpdateState.Checking -> Text("Zjišťuji…")
                    is UpdateState.UpToDate -> Text("Máš nejnovější verzi.", color = OkGreen)
                    is UpdateState.Available -> {
                        Text("Je k dispozici sestavení ${u.release.build} (${u.release.size / 1_048_576} MB).", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { vm.downloadUpdate(u.release) }, modifier = Modifier.fillMaxWidth()) { Text("Stáhnout a nainstalovat") }
                    }
                    is UpdateState.Downloading -> {
                        Text("Stahuji sestavení ${u.release.build}…")
                        if (u.progress >= 0f) LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.Ready -> {
                        Text("Staženo a ověřeno (otisk souhlasí). Potvrď instalaci v systémovém okně.")
                        Button(onClick = { vm.installUpdate(u.file) }, modifier = Modifier.fillMaxWidth()) { Text("Nainstalovat") }
                    }
                    is UpdateState.Failed -> Text(u.message, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = { vm.checkUpdate() }, modifier = Modifier.fillMaxWidth()) { Text("Zkontrolovat aktualizace") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Jednou denně zkontrolovat a dát vědět", Modifier.weight(1f))
                    Switch(checked = autoUpdate, onCheckedChange = {
                        autoUpdate = it
                        vm.setAutoUpdateCheck(it)
                    })
                }
                Text(
                    "Aktualizace se stahují z GitHubu (hincep-lab/dochazka-kutil). Stažený soubor se ověří otiskem a Android navíc ověří podpis. " +
                        "Napoprvé Android požádá o povolení „Instalovat neznámé aplikace“ pro Docházku. Data zůstávají.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("Automatická záloha") {
                val autoTick by vm.autoBackupInfo.collectAsStateWithLifecycle()
                val folderName = remember(autoTick) { AutoBackup.folderName(context) }
                val result = remember(autoTick) { AppPrefs.autoBackupResult(context) }
                if (folderName == null) {
                    Text("Vypnutá. Vyber složku a aplikace do ní bude každý den sama ukládat zálohu.")
                    Button(onClick = { folderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Vybrat složku a zapnout") }
                } else {
                    Text("Složka: $folderName", fontWeight = FontWeight.Bold)
                    when {
                        result.second.isNotBlank() -> Text("Poslední pokus ${Fmt.dateTime(result.first)} selhal: ${result.second}", color = MaterialTheme.colorScheme.error)
                        result.first > 0 -> Text("Poslední automatická záloha: ${Fmt.dateTime(result.first)}", color = OkGreen)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.runAutoBackupNow() }) { Text("Zálohovat teď") }
                        OutlinedButton(onClick = { folderLauncher.launch(null) }) { Text("Jiná složka") }
                    }
                    TextButton(onClick = { vm.setAutoBackupFolder(null) }) { Text("Vypnout automatickou zálohu") }
                }
                Text(
                    "Jednou denně, jen když se od poslední zálohy něco změnilo. Soubory Dochazka_auto_RRRR-MM-DD.json, ponechá se posledních ${AutoBackup.KEEP} (starší automatické se smažou, jiné soubory ve složce zůstávají). " +
                        "Nejlépe vyber složku v telefonu, např. Dokumenty – cloudové složky Android často nepovolí.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("Widget a rychlé nastavení") {
                Text(
                    "Widget: podrž prst na ploše → Widgety → Docházka Kutil. Ukazuje stav, dnešní a měsíční hodiny a tlačítko Začít / Ukončit práci.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Dlaždice: stáhni lištu dvakrát dolů → tužka (Upravit) → přetáhni dlaždici Docházka mezi aktivní. Klepnutím začneš nebo ukončíš práci; na zamčeném telefonu se nejdřív odemkne.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard("Záloha a obnova") {
            Text(
                "Záloha je soubor JSON, který si uložíš, kam chceš (telefon, karta, vlastní disk). Aplikace sama nic nikam neposílá." +
                    if (state.demo) " V demo režimu se zálohují jen ukázková data." else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Záloha obsahuje nastavení, domácí zónu, všechny pracovní intervaly včetně ručních oprav, označené dny a nevyřízené události. " +
                    "Soubor má kontrolní otisk – poškozená záloha se při obnově odmítne.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.demo) Text(if (lastBackup > 0) "Poslední záloha: ${Fmt.dateTime(lastBackup)}" else "Zatím žádná záloha.", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jednou měsíčně připomenout zálohu", Modifier.weight(1f))
                Switch(checked = backupReminder, onCheckedChange = {
                    backupReminder = it
                    AppPrefs.setBackupReminder(context, it)
                })
            }
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
            Text("Docházka Kutil, verze $versionName", fontWeight = FontWeight.Bold)
            Text(
                "• Odchod a návrat hlásí Android (Geofencing). Hlášení chodí se zpožděním, obvykle 1–5 minut, při úsporném režimu i déle, a výjimečně nepřijde vůbec.\n" +
                    "• Po restartu telefonu se zóna obnoví sama; po „Vynutit ukončení“ aplikace až po jejím dalším otevření.\n" +
                    "• Domácí Wi-Fi je jen pojistka. To, že název sítě nejde přečíst, se nikdy nebere jako důkaz, že nejsi doma.\n" +
                    "• Nic se nezapíše bez tvého ANO. Všechny časy jdou opravit, původně zaznamenané časy zůstávají uložené.\n" +
                    "• Časy do výkazu se zaokrouhlují na půlhodiny (6:50 → 7:00, 15:40 → 15:30, čtvrt a tři čtvrtě nahoru).\n" +
                    "• Přesčas = čas odpracovaný po 15:30 v pracovní dny (po–pá mimo svátky). Příplatky ani právní nároky aplikace nepočítá.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    restorePreview?.let { preview ->
        ConfirmDialog(
            title = "Obnovit ze zálohy?",
            text = "Záloha je v pořádku (kontrolní otisk souhlasí)." +
                (if (preview.exportedAt > 0) " Vytvořena ${Fmt.dateTime(preview.exportedAt)}." else "") +
                "\n\nObsahuje: ${preview.shifts} pracovních intervalů, ${preview.pending} událostí, ${preview.overrides} označených dnů." +
                "\n\nPOZOR: všech ${preview.currentShifts} současných intervalů a ostatní data v aplikaci se PŘEPÍŠOU obsahem zálohy. " +
                "Tento krok nejde vrátit – pokud si nejsi jistý, udělej si nejdřív zálohu současného stavu.",
            confirmLabel = "Přepsat data zálohou",
            onConfirm = {
                vm.restore(preview.uri)
                vm.restorePreview.value = null
            },
            onDismiss = { vm.restorePreview.value = null },
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
