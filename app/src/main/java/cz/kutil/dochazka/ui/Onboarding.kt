package cz.kutil.dochazka.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.kutil.dochazka.track.WifiHelper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Rozpozná „49.2312, 13.5210“ i „49,2312 13,5210“ (např. zkopírované z mapy). */
fun parseCoordinates(text: String): Pair<Double, Double>? {
    val numbers = Regex("-?\\d{1,3}[.,]\\d+").findAll(text).map { it.value.replace(',', '.').toDoubleOrNull() }.toList()
    if (numbers.size != 2) return null
    val lat = numbers[0] ?: return null
    val lon = numbers[1] ?: return null
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
    return lat to lon
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, missingText: String) {
    Text(
        (if (ok) "✓ " else "✗ ") + (if (ok) okText else missingText),
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * Nastavení domácího bodu, poloměru a domácí Wi-Fi. Používá se v průvodci i v Nastavení.
 * Souřadnice se ukládají jen do databáze v telefonu; do logů ani zdrojových souborů se nedostanou.
 */
@Composable
fun ZoneEditor(vm: MainViewModel, state: UiState, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm = rememberPermStatus()
    val zone = state.zone
    var coords by rememberSaveable { mutableStateOf(zone?.let { "%.6f, %.6f".format(java.util.Locale.US, it.latitude, it.longitude) } ?: "") }
    var radius by rememberSaveable { mutableFloatStateOf(zone?.radiusM ?: 200f) }
    var ssid by rememberSaveable { mutableStateOf(zone?.wifiSsid ?: "") }
    var locating by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var wifiInfo by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Domácí bod", fontWeight = FontWeight.Bold)
        Button(
            enabled = perm.fine && !locating,
            onClick = {
                locating = true
                info = null
                scope.launch {
                    val loc = vm.currentLocation()
                    locating = false
                    if (loc == null) {
                        info = "Polohu se nepodařilo zjistit. Zkus to venku nebo u okna, případně zadej souřadnice ručně."
                    } else {
                        coords = "%.6f, %.6f".format(java.util.Locale.US, loc.latitude, loc.longitude)
                        info = "Poloha načtena (přesnost asi ±${loc.accuracy.roundToInt()} m)."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (locating) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
            Text(if (locating) "Zjišťuji polohu…" else "Použít aktuální polohu (jsem doma)")
        }
        if (!perm.fine) Text("Pro tlačítko výše je potřeba oprávnění k přesné poloze.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = coords,
            onValueChange = { coords = it },
            label = { Text("Souřadnice (šířka, délka)") },
            supportingText = { Text("Můžeš je zkopírovat z mapy: podrž prst na svém domě a zkopíruj čísla, např. 49.231200, 13.521000.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Poloměr zóny: ${radius.roundToInt()} m", fontWeight = FontWeight.Bold)
        Slider(value = radius, onValueChange = { radius = (it / 10f).roundToInt() * 10f }, valueRange = 100f..400f)
        Text(
            "Doporučeno 150–300 m. Menší zóna dává víc falešných poplachů, větší reaguje později.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Domácí Wi-Fi (jen název sítě, nepovinné)", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("Název sítě (SSID)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
        }
        FilledTonalButton(
            onClick = {
                val current = WifiHelper.currentSsid(context)
                if (current == null) {
                    wifiInfo = "Název připojené sítě se nepodařilo přečíst. Připoj se k domácí Wi-Fi, zapni polohu a povol oprávnění k poloze – nebo název napiš ručně."
                } else {
                    ssid = current
                    wifiInfo = "Použita právě připojená síť „$current“."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Použít právě připojenou Wi-Fi") }
        wifiInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Heslo se nikdy nezadává ani neukládá. Wi-Fi slouží jen jako pojistka proti falešným poplachům: " +
                "když Android ohlásí odchod, ale telefon je stále na domácí síti, dotaz se nezobrazí.",
            style = MaterialTheme.typography.bodySmall,
        )

        val parsed = parseCoordinates(coords)
        Button(
            enabled = parsed != null,
            onClick = {
                parsed?.let {
                    vm.saveZone(it.first, it.second, radius, ssid)
                    onSaved()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Uložit domácí zónu") }
        if (parsed == null && coords.isNotBlank()) {
            Text("Souřadnice nejsou ve správném tvaru.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun OnboardingScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var organization by rememberSaveable { mutableStateOf(state.settings.organization) }
    var employee by rememberSaveable { mutableStateOf(state.settings.employee) }
    var permTick by remember { mutableIntStateOf(0) }
    val perm = rememberPermStatus(permTick)
    val lastStep = 5

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Docházka Kutil", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { (step + 1) / (lastStep + 1f) }, modifier = Modifier.fillMaxWidth())
            Text("Krok ${step + 1} z ${lastStep + 1}", style = MaterialTheme.typography.labelLarge)

            when (step) {
                0 -> {
                    Text("Vítej!", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Aplikace pozná, že odcházíš z domu nebo se vracíš, a zeptá se: „Začal jsi pracovat?“ / „Ukončil jsi práci?“. " +
                            "Nic nezapíše bez tvého ANO. Na konci měsíce vyplní docházku přímo do původního formuláře ve Wordu."
                    )
                    Text("Všechna data zůstávají jen v tomto telefonu – žádný cloud, žádné přihlašování, žádné reklamy.")
                    OutlinedTextField(organization, { organization = it }, label = { Text("Organizace") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(employee, { employee = it }, label = { Text("Jméno do výkazu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                1 -> {
                    Text("Oznámení", style = MaterialTheme.typography.titleLarge)
                    Text("Dotazy na začátek a konec práce přijdou jako oznámení s tlačítky ANO / NE. Bez oznámení by ses o nich dozvěděl až po otevření aplikace.")
                    StatusLine(perm.notifications, "Oznámení jsou povolená.", "Oznámení zatím nejsou povolená.")
                    if (!perm.notifications) {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else SystemScreens.notificationSettings(context)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Povolit oznámení") }
                        OutlinedButton(onClick = { SystemScreens.notificationSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Otevřít nastavení oznámení")
                        }
                        Text("Pokud se dialog neobjeví, otevři nastavení a oznámení zapni tam.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                2 -> {
                    Text("Poloha", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Aby aplikace poznala odchod z domu i se zhasnutým displejem, potřebuje polohu „Povolit vždy“. " +
                            "Android to dovoluje jen ve dvou krocích: nejdřív polohu při používání aplikace, potom v nastavení přepnout na „Povolit vždy“."
                    )
                    StatusLine(perm.fine, "1. Přesná poloha je povolená.", "1. Přesná poloha zatím není povolená.")
                    if (!perm.fine) {
                        Button(onClick = {
                            fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Povolit polohu") }
                        Text("V dialogu zvol „Přesná“ a „Při používání aplikace“.", style = MaterialTheme.typography.bodySmall)
                    }
                    StatusLine(perm.background, "2. Poloha je povolená vždy.", "2. Poloha „Povolit vždy“ zatím chybí.")
                    if (perm.fine && !perm.background) {
                        Button(onClick = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Přepnout na „Povolit vždy“")
                        }
                        Text(
                            "Otevře se nastavení oprávnění. Zvol „Povolit vždy“ a vrať se zpět. Kdyby se neotevřelo: " +
                                "Nastavení → Aplikace → Docházka Kutil → Oprávnění → Poloha → Povolit vždy.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { SystemScreens.appSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Otevřít nastavení aplikace")
                        }
                    }
                    StatusLine(perm.locationOn, "Poloha v telefonu je zapnutá.", "Poloha v telefonu je vypnutá.")
                    if (!perm.locationOn) {
                        OutlinedButton(onClick = { SystemScreens.locationSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("Zapnout polohu") }
                    }
                    Text("Bez polohy aplikace funguje dál – jen budeš začátek a konec práce zadávat ručně tlačítky.", style = MaterialTheme.typography.bodySmall)
                }
                3 -> {
                    Text("Domácí zóna", style = MaterialTheme.typography.titleLarge)
                    Text("Nastav bod, kde bydlíš. Nejjednodušší je být právě doma a použít aktuální polohu.")
                    ZoneEditor(vm, state)
                    StatusLine(state.zone != null, "Domácí zóna je uložená.", "Domácí zóna zatím není uložená.")
                }
                4 -> {
                    Text("Baterie", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Samsung rád uspává aplikace na pozadí – oznámení pak chodí pozdě nebo vůbec. Doporučuji nastavit pro Docházku Kutil " +
                            "baterii „Neomezeno“: Nastavení → Aplikace → Docházka Kutil → Baterie → Neomezeno."
                    )
                    StatusLine(perm.batteryUnrestricted, "Aplikace není omezována kvůli baterii.", "Aplikace může být kvůli baterii uspávána.")
                    if (!perm.batteryUnrestricted) {
                        Button(onClick = { SystemScreens.appSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("Otevřít nastavení aplikace") }
                        OutlinedButton(onClick = { SystemScreens.batterySettings(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Seznam optimalizace baterie")
                        }
                    }
                }
                else -> {
                    Text("Hotovo", style = MaterialTheme.typography.titleLarge)
                    StatusLine(perm.notifications, "Oznámení povolena", "Oznámení nejsou povolena – dotazy uvidíš jen v aplikaci")
                    StatusLine(perm.fine && perm.background, "Poloha povolena vždy", "Poloha není povolena vždy – automatické dotazy nebudou fungovat")
                    StatusLine(perm.locationOn, "Poloha v telefonu zapnutá", "Poloha v telefonu vypnutá")
                    StatusLine(state.zone != null, "Domácí zóna nastavená", "Domácí zóna není nastavená")
                    StatusLine(!state.zone?.wifiSsid.isNullOrBlank(), "Domácí Wi-Fi nastavená", "Domácí Wi-Fi nenastavená (nevadí, je nepovinná)")
                    Text(
                        "Na rovinu: Android doručuje přechody zóny se zpožděním (běžně 1–5 minut, v úsporném režimu i déle) a výjimečně je nedoručí vůbec. " +
                            "Proto se časy dají vždy opravit a práci lze kdykoli zapsat ručně.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val canTrack = state.zone != null && perm.trackingReady
                    Button(
                        enabled = canTrack,
                        onClick = { vm.finishOnboarding(organization, employee, true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Dokončit a zapnout sledování") }
                    OutlinedButton(onClick = { vm.finishOnboarding(organization, employee, false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Dokončit bez sledování (jen ruční zápis)")
                    }
                    if (!canTrack) {
                        Text("Sledování půjde zapnout později v Nastavení, až doplníš chybějící body.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Zpět") } else Text("")
                if (step < lastStep) Button(onClick = { step++ }) { Text("Pokračovat") }
            }
        }
    }
}
