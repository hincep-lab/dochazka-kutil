package cz.kutil.dochazka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.track.WifiHelper

private enum class Level { OK, WARN, BAD, OFF }

@Composable
private fun levelColor(level: Level): Color = when (level) {
    Level.OK -> OkGreen
    Level.WARN -> WarnOrange
    Level.BAD -> MaterialTheme.colorScheme.error
    Level.OFF -> MaterialTheme.colorScheme.outline
}

@Composable
private fun StatusRow(level: Level, title: String, detail: String, action: String? = null, onAction: (() -> Unit)? = null) {
    SteelCard(padding = 0.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.padding(top = 4.dp).size(16.dp).clip(CircleShape).background(levelColor(level)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
                if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

private fun whenText(ms: Long): String = if (ms <= 0) "zatím žádný" else Fmt.dateTime(ms)

/**
 * Stav sledování na jednom místě. Všechny údaje se čtou ze systému v okamžiku zobrazení
 * (oprávnění, zapnutá poloha, omezení baterie, připojená Wi-Fi) nebo jsou to skutečně zaznamenané
 * výsledky (poslední odpověď Androidu na registraci zóny, poslední přijatý odchod a návrat).
 */
@Composable
fun DiagnosticsScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val perm = rememberPermStatus(tick)
    val diagKey = state.settings.lastDiagnosticAt + tick
    val zoneOkAt = remember(diagKey) { AppPrefs.lastZoneOkAt(context) }
    val zoneError = remember(diagKey) { AppPrefs.lastZoneError(context) }
    val lastExit = remember(diagKey) { AppPrefs.lastExitAt(context) }
    val lastEnter = remember(diagKey) { AppPrefs.lastEnterAt(context) }
    val currentSsid = remember(diagKey) { WifiHelper.currentSsid(context) }
    val wifiConnected = remember(diagKey) { WifiHelper.isWifiConnected(context) }
    val zone = state.zone
    val tracking = state.settings.trackingEnabled

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.demo) {
            Text("Je zapnutý demo režim. Údaje níže se týkají skutečného sledování, ne ukázkových dat.", color = MaterialTheme.colorScheme.tertiary)
        }
        val allOk = tracking && zone != null && perm.trackingReady && perm.notifications && zoneOkAt > 0 && zoneError.first == 0L
        Text(
            when {
                !tracking -> "Automatické sledování je vypnuté."
                allOk -> "Sledování je v pořádku."
                else -> "Sledování potřebuje tvou pozornost – viz červené a oranžové body."
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (!tracking) MaterialTheme.colorScheme.outline else if (allOk) Ind.greenLight else MaterialTheme.colorScheme.error,
        )

        Text("Oprávnění a systém", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        StatusRow(
            if (perm.fine) Level.OK else Level.BAD, "Oprávnění k přesné poloze (GPS)",
            if (perm.fine) "Povoleno." else "Není povoleno – aplikace nepozná odchod ani návrat.",
            if (perm.fine) null else "Otevřít nastavení aplikace",
        ) { SystemScreens.appSettings(context) }
        StatusRow(
            if (perm.background) Level.OK else Level.BAD, "Poloha na pozadí („Povolit vždy“)",
            if (perm.background) "Povoleno – funguje i se zamčenou obrazovkou." else "Není povoleno. Nastavení → Oprávnění → Poloha → Povolit vždy.",
            if (perm.background) null else "Otevřít nastavení aplikace",
        ) { SystemScreens.appSettings(context) }
        StatusRow(
            if (perm.locationOn) Level.OK else Level.BAD, "Poloha v telefonu",
            if (perm.locationOn) "Zapnutá." else "Vypnutá – Android přechody zóny nehlásí a zónu zahodí.",
            if (perm.locationOn) null else "Zapnout polohu",
        ) { SystemScreens.locationSettings(context) }
        StatusRow(
            if (perm.notifications) Level.OK else Level.BAD, "Oznámení",
            if (perm.notifications) "Povolena." else "Vypnutá – dotazy uvidíš jen v aplikaci na záložce K potvrzení.",
            if (perm.notifications) null else "Otevřít nastavení oznámení",
        ) { SystemScreens.notificationSettings(context) }
        StatusRow(
            if (perm.batteryUnrestricted) Level.OK else Level.WARN, "Omezení baterie",
            if (perm.batteryUnrestricted) "Aplikace není omezována (Neomezeno)." else
                "Telefon může aplikaci uspávat a dotazy zpozdit. Doporučeno: Aplikace → Docházka Kutil → Baterie → Neomezeno.",
            if (perm.batteryUnrestricted) null else "Otevřít nastavení aplikace",
        ) { SystemScreens.appSettings(context) }

        Text("Domácí zóna", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        StatusRow(
            when {
                zone == null -> Level.BAD
                zone.radiusM < 150f -> Level.WARN
                else -> Level.OK
            }, "Domácí bod",
            when {
                zone == null -> "Není nastaven – nastav ho v Nastavení."
                zone.radiusM < 150f -> "Nastaven, poloměr ${zone.radiusM.toInt()} m. Malá zóna dává falešné odchody a návraty (skoky GPS v noci) – doporučeno 150–250 m (Nastavení → Domácí zóna)."
                else -> "Nastaven, poloměr ${zone.radiusM.toInt()} m."
            },
        )
        StatusRow(
            when {
                !tracking -> Level.OFF
                zoneError.first > 0 -> Level.BAD
                zoneOkAt > 0 -> Level.OK
                else -> Level.WARN
            },
            "Registrace GPS zóny (geofence)",
            when {
                !tracking -> "Sledování je vypnuté, zóna není zaregistrovaná."
                zoneError.first > 0 -> "Poslední pokus ${Fmt.dateTime(zoneError.first)} selhal: ${zoneError.second}"
                zoneOkAt > 0 -> "Android naposledy potvrdil registraci ${Fmt.dateTime(zoneOkAt)}."
                else -> "Registrace zatím neproběhla."
            } + " Android neumožňuje zjistit, zda je zóna v tuto chvíli aktivní – zobrazuje se výsledek poslední registrace. Obnovuje se sama každých 6 hodin, po restartu a při otevření aplikace.",
            if (tracking) "Zaregistrovat zónu znovu" else null,
        ) {
            vm.reRegisterZone()
            tick++
        }
        val ssid = zone?.wifiSsid.orEmpty()
        StatusRow(
            when {
                ssid.isBlank() -> Level.OFF
                currentSsid == ssid -> Level.OK
                !wifiConnected -> Level.OFF
                currentSsid == null -> Level.WARN
                else -> Level.OFF
            },
            "Domácí Wi-Fi",
            when {
                ssid.isBlank() -> "Není nastavena (je nepovinná)."
                currentSsid == ssid -> "Nastavena „$ssid“ – telefon je k ní právě připojen."
                !wifiConnected -> "Nastavena „$ssid“. Telefon teď není připojený k žádné Wi-Fi (jsi mimo domov nebo je Wi-Fi vypnutá) – v pořádku."
                currentSsid == null -> "Nastavena „$ssid“. Název připojené sítě teď nejde přečíst (Wi-Fi vypnutá, nepřipojeno, nebo to Android nedovolil). Bere se to jako „nevím“, nikdy jako důkaz, že nejsi doma."
                else -> "Nastavena „$ssid“ – telefon je teď připojen k jiné síti."
            },
        )

        Text("Zaznamenané události", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        StatusRow(if (lastExit > 0) Level.OK else Level.OFF, "Poslední zaznamenaný odchod", whenText(lastExit))
        StatusRow(if (lastEnter > 0) Level.OK else Level.OFF, "Poslední zaznamenaný návrat", whenText(lastEnter))
        StatusRow(
            Level.OFF, "Stav automatu",
            trackStateText(state.settings.trackState) + " · nevyřízených návrhů: ${state.openPending.size}" +
                (state.openShift?.let { " · otevřená směna od ${Fmt.dateTime(it.rawStart)}" } ?: ""),
        )
        if (state.settings.lastDiagnostic.isNotBlank()) {
            val bad = state.settings.lastDiagnostic.startsWith("Zóna není aktivní") || state.settings.lastDiagnostic.startsWith("Chyba") ||
                state.settings.lastDiagnostic.startsWith("Sledování zóny je pozastavené") || state.settings.lastDiagnostic.startsWith("Po restartu")
            StatusRow(
                if (bad) Level.BAD else Level.OFF, if (bad) "Chyba sledování" else "Poslední zpráva sledování",
                "${Fmt.dateTime(state.settings.lastDiagnosticAt)}: ${state.settings.lastDiagnostic}",
            )
        }

        Button(onClick = { vm.sendTestNotification() }, modifier = Modifier.fillMaxWidth()) { Text("Poslat zkušební oznámení") }
        Text("Zkušební oznámení má tlačítka ANO / NE, ale nic nezapíše. Hodí se i k ověření hodinek a zamčené obrazovky.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { tick++ }, modifier = Modifier.fillMaxWidth()) { Text("Načíst stav znovu") }
        Text(
            "Limity Androidu: přechody zóny chodí se zpožděním (běžně 1–5 minut, v úsporném režimu i déle) a výjimečně nepřijdou vůbec. " +
                "Po „Vynutit ukončení“ aplikace se sledování obnoví až po jejím dalším otevření. Aplikace nepoužívá nepřetržité GPS – " +
                "polohu si jednorázově vyžádá jen při ověřování nejistého odchodu.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
