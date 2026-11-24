package cz.kutil.dochazka.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kutil.dochazka.core.Interval
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.painterResource
import cz.kutil.dochazka.R
import cz.kutil.dochazka.data.AutoBackup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kutil.dochazka.core.Anomalies
import cz.kutil.dochazka.core.Anomaly
import cz.kutil.dochazka.core.AnomalyKind
import cz.kutil.dochazka.core.LongShift
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.Rounding
import cz.kutil.dochazka.core.TrackState
import cz.kutil.dochazka.core.WorkCalc
import cz.kutil.dochazka.data.AppPrefs
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

/**
 * Hlavní obrazovka: stav (Právě pracuješ / Doma / Čeká na potvrzení) s živým časovačem,
 * velká tlačítka, dnešní intervaly, upozornění na nesrovnalosti a stav sledování.
 * Měsíční přehled je v Kalendáři a Výkazu.
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    state: UiState,
    onOpenDay: (LocalDate) -> Unit,
    onOpenTab: (Tab) -> Unit,
    onEndWork: () -> Unit,
    onOpenDiag: () -> Unit,
) {
    val context = LocalContext.current
    val perm = rememberPermStatus()
    val today = Fmt.today()
    val month = remember(state.shifts, state.overrides, today) { state.month(YearMonth.from(today)) }
    val day = month.day(today.dayOfMonth)
    val open = state.openShift
    val pending = state.openPending
    var addDialog by remember { mutableStateOf(false) }
    var ackTick by remember { mutableIntStateOf(0) }
    // Panel ukazuje minuty – stačí obnovovat po minutě; při návratu do aplikace se čas přepočítá hned.
    val now = rememberNow(60_000L)
    val resumeTick = rememberResumeTick()
    var healthDialog by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    val backupInfo by vm.autoBackupInfo.collectAsStateWithLifecycle()
    LaunchedEffect(backupInfo) { backingUp = false }
    val healthIn = remember(state.settings, state.zone, state.demo, perm, resumeTick, minute(now)) { readHealthInput(context, state, perm) }
    val gps = TrackingHealth.location(healthIn)
    val wifi = TrackingHealth.wifi(healthIn)
    // Velké systémové písmo nebo úzký displej: kompaktnější panel, aby se nic neořezávalo.
    val big = LocalDensity.current.fontScale > 1.15f || LocalConfiguration.current.screenWidthDp < 390
    val limit = remember { AppPrefs.longShiftHours(context) }
    val minute = now / 60_000

    val anomalies = remember(state.shifts, state.overrides, state.pending, minute, ackTick) {
        val ov = HashMap<LocalDate, String>()
        state.overrides.forEach { o -> runCatching { LocalDate.parse(o.date) }.getOrNull()?.let { ov[it] = o.type } }
        val list = Anomalies.find(Instant.ofEpochMilli(now), state.shifts.map { it.toData() }, ov, pending.size, limit)
        // „Stále pracuji“ ztiší upozornění na dlouhou směnu na 4 hodiny
        val ack = AppPrefs.longShiftAck(context)
        val acked = open != null && ack.first == open.id && now - ack.second < LongShift.REPEAT_AFTER_ACK_HOURS * 3_600_000L
        // Nevyřízené návrhy už ukazuje stavový panel nahoře.
        list.filter { !(acked && it.kind == AnomalyKind.FORGOTTEN_END) && it.kind != AnomalyKind.PENDING_GPS }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // --- záhlaví s logem ----------------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_handyman), null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append("Docházka ")
                        withStyle(SpanStyle(color = Ind.orange)) { append("Kutil") }
                    },
                    fontSize = if (big) 24.sp else 27.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, maxLines = 1, color = Ind.onBg,
                    style = PanelText,
                )
                Text(state.settings.organization, fontSize = 13.sp, color = Ind.onBgDim, maxLines = 1, style = PanelText)
            }
            IconButton(onClick = { onOpenTab(Tab.SETTINGS) }) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Nastavení", tint = Ind.onBg, modifier = Modifier.size(28.dp))
            }
        }
        if (state.demo) {
            Text("Demo režim – ukázková data. Vypneš ho v Nastavení.", color = Ind.amber, fontSize = 13.sp)
        }

        // --- stavový panel v trubkovém rámu ---------------------------------------------------
        when {
            open != null -> {
                val reportStart = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
                val startDay = Fmt.zoned(open.rawStart).toLocalDate()
                StatusPanel(PanelTone.WORKING) {
                    PanelHeadline(R.drawable.ic_engineering, "PRÁVĚ PRACUJEŠ", drop = true, big = big)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (!big) {
                            Icon(painterResource(R.drawable.ic_schedule), null, tint = Color.White, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.size(8.dp))
                        }
                        PanelValue(
                            "Začátek (výkaz)", Fmt.time(reportStart), Modifier.weight(0.95f), big,
                            note = (if (startDay != today) "${Fmt.dateShort(startDay)} · " else "") + "skutečně ${Fmt.time(open.rawStart)}",
                        )
                        Box(Modifier.padding(horizontal = 8.dp).size(1.5.dp, 56.dp).background(Color.White.copy(alpha = 0.6f)))
                        PanelValue("Odpracováno", formatHoursMinutes(now - reportStart), Modifier.weight(1.3f), big, note = "od začátku ve výkazu")
                    }
                }
            }
            pending.isNotEmpty() -> {
                val p = pending.first()
                StatusPanel(PanelTone.PENDING) {
                    PanelHeadline(R.drawable.ic_notifications_active, "ČEKÁ NA POTVRZENÍ", drop = false, big = big)
                    Text(Notifier.title(p), fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = PanelText)
                    Text(Notifier.detail(p), fontSize = 15.sp, color = Color.White, style = PanelText)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigActionButton("ANO", ActionKind.GO, Modifier.weight(1f), height = 52.dp) { vm.answer(p.id, true) }
                        BigActionButton("NE", ActionKind.STOP, Modifier.weight(1f), height = 52.dp) { vm.answer(p.id, false) }
                    }
                    if (pending.size > 1) {
                        TextButton(onClick = { onOpenTab(Tab.PENDING) }) { Text("Další události: ${pending.size - 1}", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            else -> {
                val away = state.settings.trackState == "AWAY" || state.settings.trackState == "OUTSIDE_PENDING"
                StatusPanel(PanelTone.IDLE) {
                    PanelHeadline(if (away) R.drawable.ic_location_on else R.drawable.ic_home, if (away) "MIMO DOMOV" else "DOMA", drop = true, big = big)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (!big) {
                            Icon(painterResource(R.drawable.ic_schedule), null, tint = Color.White, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.size(8.dp))
                        }
                        PanelValue("Dnes odpracováno", hm(day.minutes), Modifier.weight(1.3f), big)
                        Box(Modifier.padding(horizontal = 8.dp).size(1.5.dp, 48.dp).background(Color.White.copy(alpha = 0.6f)))
                        PanelValue("Poslední konec", day.pieces.lastOrNull()?.endText() ?: "–", Modifier.weight(0.9f), big)
                    }
                }
            }
        }

        // --- dnešek a hlavní ovládání ---------------------------------------------------------
        SteelCard(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_calendar_month), null, tint = Ind.textDim, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("Dnes: ") }
                        append("${Fmt.dowLong(today)} ${today.dayOfMonth}. ${Fmt.monthGenitive(today)}")
                    },
                    fontSize = 16.sp, color = Ind.text,
                )
            }
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { healthDialog = true }.semantics { contentDescription = "Stav sledování: poloha ${gps.health.label}, Wi-Fi ${wifi.health.label}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_location_on), null, tint = Ind.textDim, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("Stav: ") }
                        append(placeText(state))
                    },
                    fontSize = 16.sp, color = Ind.text, modifier = Modifier.weight(1f), maxLines = 1,
                )
                HealthIcon(R.drawable.ic_wifi, wifi.health)
                Spacer(Modifier.size(6.dp))
                HealthIcon(R.drawable.ic_gps_fixed, gps.health)
            }
            if (open != null) {
                BigActionButton("Ukončit práci", ActionKind.STOP, Modifier.fillMaxWidth(), leading = { StopGlyph() }, height = 56.dp, onClick = onEndWork)
            } else {
                BigActionButton("Začít práci", ActionKind.GO, Modifier.fillMaxWidth(), leading = { PlayGlyph() }, height = 56.dp) { vm.startWork() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigActionButton("Upravit čas", ActionKind.METAL, Modifier.weight(1f), iconRes = R.drawable.ic_edit, height = 48.dp) { onOpenDay(today) }
                BigActionButton("Přidat výjezd", ActionKind.METAL, Modifier.weight(1f), iconRes = R.drawable.ic_local_shipping, height = 48.dp) { addDialog = true }
            }
        }

        // --- upozornění ----------------------------------------------------------------------
        if (anomalies.isNotEmpty() && !state.demo) {
            SteelCard {
                SectionTitle("Ke kontrole", iconRes = R.drawable.ic_warning)
                for (a in anomalies.take(4)) AnomalyRow(a, open?.id, vm, onOpenDay, onEndWork) { ackTick++ }
            }
        }

        // --- dnešní intervaly ------------------------------------------------------------------
        val runningToday = open != null
        val runStart = open?.let { it.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(it.rawStart)).toEpochMilli() }
        // Běžící interval se do dneška počítá od půlnoci (směna mohla začít včera) a sjednotí se
        // s uzavřenými záznamy, aby se překryv nezapočítal dvakrát.
        val dayStartMs = today.atStartOfDay(PRAGUE).toInstant().toEpochMilli()
        val totalToday = remember(day, runStart, minute(now)) {
            val list = day.pieces.map { it.interval() }.toMutableList()
            if (runStart != null && now > maxOf(runStart, dayStartMs)) list += Interval(Instant.ofEpochMilli(maxOf(runStart, dayStartMs)), Instant.ofEpochMilli(now))
            WorkCalc.unionMinutes(list)
        }
        SteelCard(padding = 12.dp, onClick = { onOpenDay(today) }) {
            SectionTitle("Dnešní práce", iconRes = R.drawable.ic_schedule) {
                Text(hm(totalToday), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Ind.text)
            }
            day.holidayName?.let { Text("Státní svátek: $it", color = Ind.amberText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            day.override?.let { Text(it.label, color = Ind.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            if (day.pieces.isEmpty() && !runningToday) {
                Text("Dnes zatím žádný pracovní interval. Klepnutím otevřeš detail dne.", color = Ind.textDim, fontSize = 14.sp)
            }
            for (piece in day.pieces) {
                IntervalRow("${piece.startText()} – ${piece.endText()}", hm(piece.minutes), running = false)
            }
            if (runStart != null) {
                val from = if (runStart < dayStartMs) "00:00" else Fmt.time(runStart)
                IntervalRow("$from – běží", formatHoursMinutes(now - maxOf(runStart, dayStartMs)), running = true)
            }
            if (day.workLabels.isNotEmpty()) Text(day.workLabels.joinToString(" · "), color = Ind.amberText, fontSize = 13.sp)
        }

        // --- rychlé akce ----------------------------------------------------------------------
        SteelCard(screws = true, padding = 12.dp) {
            SectionTitle("Rychlé akce", iconRes = R.drawable.ic_handyman)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile("Záznam dne", AppIcons.DAY_RECORD, Modifier.weight(1f)) { onOpenDay(today) }
                QuickTile("Výkaz\n(Word)", AppIcons.REPORT, Modifier.weight(1f)) { onOpenTab(Tab.MONTH) }
                QuickTile(if (backingUp) "Zálohuji…" else "Záloha\ndat", AppIcons.BACKUP, Modifier.weight(1f)) {
                    if (AutoBackup.folder(context) != null) {
                        backingUp = true
                        vm.runAutoBackupNow()
                    } else {
                        vm.message.value = "Nejdřív vyber složku pro zálohy (Nastavení → Automatická záloha)."
                        onOpenTab(Tab.SETTINGS)
                    }
                }
                QuickTile("Nastavení", AppIcons.SETTINGS, Modifier.weight(1f)) { onOpenTab(Tab.SETTINGS) }
            }
            val backup = remember(backupInfo, resumeTick) { AppPrefs.autoBackupResult(context) }
            val folderSet = remember(backupInfo, resumeTick) { AutoBackup.folder(context) != null }
            when {
                backingUp -> Text("Zálohuji a ověřuji…", fontSize = 13.sp, color = Ind.textDim)
                !folderSet -> Text("Záloha: není vybraná složka – data zatím nejsou zálohovaná mimo aplikaci.", fontSize = 13.sp, color = Color(0xFFC62828), fontWeight = FontWeight.SemiBold)
                backup.second.isNotBlank() -> Text("Záloha selhala ${if (backup.first > 0) Fmt.dateTime(backup.first) else ""}: ${backup.second}", fontSize = 13.sp, color = Color(0xFFC62828), fontWeight = FontWeight.SemiBold)
                backup.first > 0 -> Text("✓ Poslední záloha ověřena ${Fmt.dateTime(backup.first)}", fontSize = 13.sp, color = Ind.greenDeep, fontWeight = FontWeight.SemiBold)
                else -> Text("Záloha zatím neproběhla.", fontSize = 13.sp, color = Ind.amberText)
            }
        }

        // --- stav sledování ----------------------------------------------------------------------
        if (!state.demo) {
            val dot = gps.health.color
            val text = when (gps.health) {
                Health.OK -> "Sledování funguje"
                Health.LIMITED -> "Sledování: omezená spolehlivost"
                Health.BROKEN -> "Sledování nefunguje – oprav v Stavu sledování"
                Health.OFF -> "Automatické sledování je vypnuté – zapisuješ ručně"
            }
            val lastExit = remember(state.settings.lastDiagnosticAt) { AppPrefs.lastExitAt(context) }
            val lastEnter = remember(state.settings.lastDiagnosticAt) { AppPrefs.lastEnterAt(context) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x66081426)).clickable(onClick = onOpenDiag).padding(vertical = 10.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(dot)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ind.onBg)
                    Text(
                        "Odchod ${if (lastExit > 0) Fmt.dateTime(lastExit) else "–"} · návrat ${if (lastEnter > 0) Fmt.dateTime(lastEnter) else "–"}",
                        fontSize = 12.sp, color = Ind.onBgDim,
                    )
                }
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Stav sledování", tint = Ind.onBgDim)
            }
        }
        DrainFooter()
    }

    if (healthDialog) {
        HealthDialog(gps, wifi, onDismiss = { healthDialog = false }, onOpenDiag = {
            healthDialog = false
            onOpenDiag()
        })
    }

    if (addDialog) {
        val nowT = LocalTime.now(PRAGUE).withSecond(0).withNano(0)
        val endDefault = LocalTime.of(nowT.hour, if (nowT.minute < 30) 0 else 30)
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

private fun hm(minutes: Long): String = "${minutes / 60} h ${"%02d".format(minutes % 60)} min"

private fun placeText(state: UiState): String = when {
    state.demo -> "ukázková data"
    state.openShift != null -> if (state.settings.trackState == "RETURN_PENDING") "zpět doma" else "v práci / u zákazníka"
    !state.settings.trackingEnabled -> "ruční režim"
    state.settings.trackState == "AWAY" || state.settings.trackState == "OUTSIDE_PENDING" -> "mimo domov"
    else -> "doma"
}

private fun minute(ms: Long) = ms / 60_000

@Composable
private fun PanelHeadline(icon: Int, text: String, drop: Boolean, big: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(if (big) 30.dp else 38.dp))
        Spacer(Modifier.size(10.dp))
        Text(
            text, fontSize = if (big) shrink(21f) else 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = PanelText,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (drop && !big) Icon(painterResource(R.drawable.ic_water_drop), null, tint = Color(0xFF8FD3FF), modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun PanelValue(label: String, value: String, modifier: Modifier, big: Boolean, note: String? = null) {
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.92f), style = PanelText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = if (big) shrink(24f) else 29.sp, lineHeight = if (big) shrink(29f) else 33.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = PanelText, maxLines = 1)
        if (note != null) Text(note, fontSize = 12.sp, color = Color.White.copy(alpha = 0.88f), style = PanelText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HealthIcon(res: Int, h: Health) {
    Icon(painterResource(res), null, tint = h.color, modifier = Modifier.size(24.dp))
}

@Composable
private fun HealthDialog(gps: HealthItem, wifi: HealthItem, onDismiss: () -> Unit, onOpenDiag: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stav automatického sledování") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((icon, item) in listOf(R.drawable.ic_gps_fixed to gps, R.drawable.ic_wifi to wifi)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(painterResource(icon), null, tint = item.health.color, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text("${item.title}: ${item.health.label}", fontWeight = FontWeight.Bold)
                            Text(item.detail, fontSize = 14.sp)
                        }
                    }
                }
                Text(
                    "Zelená = ověřeno, oranžová = omezená spolehlivost, červená = nefunguje, šedá = vypnuto nebo nelze zjistit.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onOpenDiag) { Text("Stav sledování a opravy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}

@Composable
private fun IntervalRow(times: String, duration: String, running: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(Ind.green, size = 11.dp)
        Spacer(Modifier.size(10.dp))
        Text(times, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ind.text, modifier = Modifier.weight(1f))
        Text(duration, fontSize = 15.sp, color = if (running) Ind.greenDeep else Ind.textDim, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AnomalyRow(
    a: Anomaly,
    openShiftId: Long?,
    vm: MainViewModel,
    onOpenDay: (LocalDate) -> Unit,
    onEndWork: () -> Unit,
    onAck: () -> Unit,
) {
    val color = when (a.kind) {
        AnomalyKind.FORGOTTEN_END, AnomalyKind.OVERLAP -> Ind.red
        else -> Ind.orange
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.size(10.dp))
            Text(a.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ind.text)
        }
        Text(a.detail, fontSize = 13.sp, color = Ind.textDim, modifier = Modifier.padding(start = 20.dp))
        Row(Modifier.padding(start = 10.dp)) {
            if (a.kind == AnomalyKind.FORGOTTEN_END && openShiftId != null) {
                TextButton(onClick = onEndWork) { Text("Ukončit práci", color = Ind.redDeep, fontWeight = FontWeight.Bold) }
                TextButton(onClick = {
                    vm.stillWorking(openShiftId)
                    onAck()
                }) { Text("Stále pracuji", color = Ind.textDim) }
            } else if (a.date != null) {
                val d = a.date
                TextButton(onClick = { onOpenDay(d) }) {
                    Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null, tint = Ind.blue, modifier = Modifier.size(16.dp))
                    Text(" Otevřít den", color = Ind.blue, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
