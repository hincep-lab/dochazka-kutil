package cz.kutil.dochazka.ui

import androidx.compose.foundation.clickable
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
    val now = rememberNow(if (open != null) 1000L else 30_000L)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // --- záhlaví s logem ----------------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_handyman), null, tint = Color.White, modifier = Modifier.size(50.dp))
                Icon(painterResource(R.drawable.ic_build), null, tint = Ind.orange, modifier = Modifier.size(22.dp).align(Alignment.BottomEnd))
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append("Docházka ")
                        withStyle(SpanStyle(color = Ind.orange)) { append("Kutil") }
                    },
                    fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, color = Ind.onBg,
                    style = PanelText,
                )
                Text(state.settings.organization, fontSize = 14.sp, color = Ind.onBgDim, maxLines = 1, style = PanelText)
            }
            IconButton(onClick = { onOpenTab(Tab.SETTINGS) }) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Nastavení", tint = Ind.onBg, modifier = Modifier.size(30.dp))
            }
        }
        if (state.demo) {
            Text("Demo režim – ukázková data. Vypneš ho v Nastavení.", color = Ind.amber, fontSize = 13.sp)
        }

        // --- stavový panel v trubkovém rámu ---------------------------------------------------
        when {
            open != null -> {
                val reportStart = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
                StatusPanel(PanelTone.WORKING) {
                    PanelHeadline(R.drawable.ic_engineering, "PRÁVĚ PRACUJEŠ", drop = true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_schedule), null, tint = Color.White, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.size(10.dp))
                        PanelValue("Začátek směny", Fmt.time(reportStart), Modifier.weight(0.9f))
                        Box(Modifier.padding(horizontal = 8.dp).size(1.5.dp, 52.dp).background(Color.White.copy(alpha = 0.6f)))
                        PanelValue("Odpracováno", formatHoursMinutes(now - reportStart), Modifier.weight(1.3f))
                    }
                    Text(
                        "Zaznamenán odchod v ${Fmt.time(open.rawStart)} · do výkazu ${Fmt.time(reportStart)}",
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), style = PanelText,
                    )
                }
            }
            pending.isNotEmpty() -> {
                val p = pending.first()
                StatusPanel(PanelTone.PENDING) {
                    PanelHeadline(R.drawable.ic_notifications_active, "ČEKÁ NA POTVRZENÍ", drop = false)
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
                    PanelHeadline(if (away) R.drawable.ic_location_on else R.drawable.ic_home, if (away) "MIMO DOMOV" else "DOMA", drop = true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_schedule), null, tint = Color.White, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.size(10.dp))
                        PanelValue("Dnes odpracováno", hm(day.minutes), Modifier.weight(1.3f))
                        Box(Modifier.padding(horizontal = 8.dp).size(1.5.dp, 52.dp).background(Color.White.copy(alpha = 0.6f)))
                        PanelValue("Poslední konec", day.pieces.lastOrNull()?.endText() ?: "–", Modifier.weight(0.9f))
                    }
                }
            }
        }

        // --- dnešek a hlavní ovládání ---------------------------------------------------------
        SteelCard(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_calendar_month), null, tint = Ind.textDim, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("Dnes: ") }
                        append("${Fmt.dowLong(today)} ${today.dayOfMonth}. ${Fmt.monthGenitive(today)}")
                    },
                    fontSize = 17.sp, color = Ind.text,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_location_on), null, tint = Ind.textDim, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("Stav: ") }
                        append(placeText(state))
                    },
                    fontSize = 17.sp, color = Ind.text, modifier = Modifier.weight(1f), maxLines = 1,
                )
                val on = state.settings.trackingEnabled && !state.demo
                Icon(painterResource(R.drawable.ic_wifi), "Wi-Fi", tint = if (on) Ind.green else Ind.textMuted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(6.dp))
                Icon(painterResource(R.drawable.ic_gps_fixed), "GPS", tint = if (on) Ind.green else Ind.textMuted, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(2.dp))
            if (open != null) {
                BigActionButton("Ukončit práci", ActionKind.STOP, Modifier.fillMaxWidth(), leading = { StopGlyph() }, onClick = onEndWork)
            } else {
                BigActionButton("Začít práci", ActionKind.GO, Modifier.fillMaxWidth(), leading = { PlayGlyph() }) { vm.startWork() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigActionButton("Upravit čas", ActionKind.METAL, Modifier.weight(1f), iconRes = R.drawable.ic_edit, height = 54.dp) { onOpenDay(today) }
                BigActionButton("Výjezd", ActionKind.METAL, Modifier.weight(1f), iconRes = R.drawable.ic_local_shipping, height = 54.dp) { addDialog = true }
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
        val runningToday = open != null && Fmt.zoned(open.rawStart).toLocalDate() == today
        if (day.pieces.isNotEmpty() || runningToday || day.override != null || day.holidayName != null) {
            SteelCard(onClick = { onOpenDay(today) }) {
                SectionTitle("Dnešní práce", iconRes = R.drawable.ic_assignment) {
                    Text(hm(day.minutes), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Ind.text)
                }
                day.holidayName?.let { Text("Státní svátek: $it", color = Ind.amberText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                day.override?.let { Text(it.label, color = Ind.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                for (piece in day.pieces) {
                    IntervalRow("${piece.startText()} – ${piece.endText()}", hm(piece.minutes), running = false)
                }
                if (open != null && runningToday) {
                    val s = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
                    IntervalRow("${Fmt.time(s)} – běží", formatHoursMinutes(now - s), running = true)
                }
                if (day.workLabels.isNotEmpty()) Text(day.workLabels.joinToString(" · "), color = Ind.amberText, fontSize = 13.sp)
            }
        }

        // --- rychlé akce ----------------------------------------------------------------------
        SteelCard(screws = true, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_handyman), null, tint = Ind.orangeDeep, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(8.dp))
                Text("Rychlé akce", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ind.text)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile("Záznam dne", R.drawable.ic_edit_note, Ind.orangeDeep, Modifier.weight(1f)) { onOpenDay(today) }
                QuickTile("Výkaz\n(Word)", R.drawable.ic_description, Color(0xFF1F5FCC), Modifier.weight(1f)) { onOpenTab(Tab.MONTH) }
                QuickTile("Záloha\ndat", R.drawable.ic_backup, Color(0xFF2A4C76), Modifier.weight(1f)) {
                    if (AutoBackup.folder(context) != null) vm.runAutoBackupNow() else onOpenTab(Tab.SETTINGS)
                }
                QuickTile("Nastavení", R.drawable.ic_settings, Color(0xFF55657A), Modifier.weight(1f)) { onOpenTab(Tab.SETTINGS) }
            }
        }

        // --- stav sledování ----------------------------------------------------------------------
        if (!state.demo) {
            val problem = state.settings.trackingEnabled && (state.zone == null || !perm.trackingReady || !perm.notifications)
            val (dot, text) = when {
                !state.settings.trackingEnabled -> Ind.textMuted to "Automatické sledování je vypnuté – zapisuješ ručně"
                problem -> Ind.red to "Sledování potřebuje pozornost"
                !perm.batteryUnrestricted -> Ind.amber to "Sledování běží · baterie omezuje aplikaci"
                else -> Ind.greenLight to "Sledování v pořádku"
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

private fun hm(minutes: Int): String = "${minutes / 60} h ${"%02d".format(minutes % 60)} min"

private fun placeText(state: UiState): String = when {
    state.demo -> "ukázková data"
    state.openShift != null -> if (state.settings.trackState == "RETURN_PENDING") "zpět doma" else "v práci / u zákazníka"
    !state.settings.trackingEnabled -> "ruční režim"
    state.settings.trackState == "AWAY" || state.settings.trackState == "OUTSIDE_PENDING" -> "mimo domov"
    else -> "doma"
}

@Composable
private fun PanelHeadline(icon: Int, text: String, drop: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(42.dp))
        Spacer(Modifier.size(12.dp))
        Text(text, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = PanelText, modifier = Modifier.weight(1f), maxLines = 1)
        if (drop) Icon(painterResource(R.drawable.ic_water_drop), null, tint = Color(0xFF8FD3FF), modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun PanelValue(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 14.sp, color = Color.White.copy(alpha = 0.92f), style = PanelText, maxLines = 1)
        Text(value, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = PanelText, maxLines = 1)
    }
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
