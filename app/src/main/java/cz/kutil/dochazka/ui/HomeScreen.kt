package cz.kutil.dochazka.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // --- záhlaví s logem ----------------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Build, contentDescription = null, tint = Ind.orange, modifier = Modifier.size(34.dp))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append("Docházka ")
                        withStyle(SpanStyle(color = Ind.orange)) { append("Kutil") }
                    },
                    fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, color = Ind.text,
                )
                Text(
                    "${Fmt.dowLong(today).replaceFirstChar { it.uppercase() }} ${Fmt.date(today)} · ${state.settings.organization}",
                    fontSize = 12.sp, color = Ind.textDim, maxLines = 1,
                )
            }
            IconButton(onClick = { onOpenTab(Tab.SETTINGS) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Nastavení", tint = Ind.text)
            }
        }
        if (state.demo) {
            Text("Demo režim – ukázková data. Vypneš ho v Nastavení.", color = Ind.amber, fontSize = 13.sp)
        }

        // --- stavový panel ------------------------------------------------------------------
        when {
            open != null -> {
                val reportStart = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
                StatusPanel(PanelTone.WORKING) {
                    Text("PRÁVĚ PRACUJEŠ", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.5.sp)
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Začátek směny", fontSize = 13.sp, color = Color(0xFFE3F5E5))
                            Text(Fmt.time(reportStart), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("zaznamenáno ${Fmt.time(open.rawStart)}", fontSize = 12.sp, color = Color(0xFFD2EED6))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Odpracováno", fontSize = 13.sp, color = Color(0xFFE3F5E5))
                            Text(formatDuration(now - reportStart), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("od začátku směny", fontSize = 12.sp, color = Color(0xFFD2EED6))
                        }
                    }
                }
            }
            pending.isNotEmpty() -> {
                val p = pending.first()
                StatusPanel(PanelTone.PENDING) {
                    Text("ČEKÁ NA POTVRZENÍ", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text(Notifier.title(p), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(Notifier.detail(p), fontSize = 14.sp, color = Color(0xFFFFF1D6))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigActionButton("ANO", ActionKind.GO, Modifier.weight(1f), height = 52.dp) { vm.answer(p.id, true) }
                        BigActionButton("NE", ActionKind.METAL, Modifier.weight(1f), height = 52.dp) { vm.answer(p.id, false) }
                    }
                    if (pending.size > 1) {
                        TextButton(onClick = { onOpenTab(Tab.PENDING) }) { Text("Další události: ${pending.size - 1}", color = Color.White) }
                    }
                }
            }
            else -> {
                val away = state.settings.trackState == "AWAY" || state.settings.trackState == "OUTSIDE_PENDING"
                StatusPanel(PanelTone.IDLE) {
                    Text(if (away) "MIMO DOMOV" else "DOMA", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Dnes odpracováno", fontSize = 13.sp, color = Ind.textDim)
                            Text(WorkCalc.formatHm(day.minutes), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Poslední konec", fontSize = 13.sp, color = Ind.textDim)
                            Text(day.pieces.lastOrNull()?.endText() ?: "–", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        // --- hlavní ovládání -----------------------------------------------------------------
        if (open != null) {
            BigActionButton("Ukončit práci", ActionKind.STOP, Modifier.fillMaxWidth(), leading = { StopGlyph() }, onClick = onEndWork)
        } else {
            BigActionButton("Začít práci", ActionKind.GO, Modifier.fillMaxWidth(), leading = { PlayGlyph() }) { vm.startWork() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigActionButton("Upravit čas", ActionKind.METAL, Modifier.weight(1f), icon = Icons.Filled.Edit, height = 52.dp) { onOpenDay(today) }
            BigActionButton("Přidat výjezd", ActionKind.ACCENT, Modifier.weight(1f), icon = Icons.Filled.Add, height = 52.dp) { addDialog = true }
        }

        // --- upozornění ----------------------------------------------------------------------
        if (anomalies.isNotEmpty() && !state.demo) {
            MetalCard {
                SectionTitle("Ke kontrole", Icons.Filled.Warning)
                for (a in anomalies.take(4)) AnomalyRow(a, open?.id, vm, onOpenDay, onEndWork) { ackTick++ }
            }
        }

        // --- dnešní intervaly ------------------------------------------------------------------
        MetalCard(Modifier.clip(RoundedCornerShape(22.dp)).clickable { onOpenDay(today) }) {
            SectionTitle("Dnešní práce", Icons.Filled.DateRange) {
                Text("${WorkCalc.formatHm(day.minutes)} h", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ind.text)
            }
            day.holidayName?.let { Text("Státní svátek: $it", color = Ind.amber, fontSize = 13.sp) }
            day.override?.let { Text(it.label, color = Ind.blue, fontSize = 13.sp) }
            if (day.pieces.isEmpty() && open == null) {
                Text("Zatím žádný pracovní interval.", color = Ind.textDim, fontSize = 14.sp)
            }
            for (piece in day.pieces) {
                IntervalRow("${piece.startText()} – ${piece.endText()}", WorkCalc.formatHm(piece.minutes) + " h", running = false)
            }
            if (open != null && Fmt.zoned(open.rawStart).toLocalDate() == today) {
                val s = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
                IntervalRow("${Fmt.time(s)} – běží", formatDuration(now - s).substringBeforeLast(':') + " h", running = true)
            }
            if (day.workLabels.isNotEmpty()) Text(day.workLabels.joinToString(" · "), color = Ind.amber, fontSize = 13.sp)
        }

        HtPipeDivider()

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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpenDiag).padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(dot)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ind.text)
                    Text(
                        "Odchod ${if (lastExit > 0) Fmt.dateTime(lastExit) else "–"} · návrat ${if (lastEnter > 0) Fmt.dateTime(lastEnter) else "–"}",
                        fontSize = 12.sp, color = Ind.textDim,
                    )
                }
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Stav sledování", tint = Ind.textDim)
            }
        }
        Spacer(Modifier.height(8.dp))
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

@Composable
private fun IntervalRow(times: String, duration: String, running: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (running) Ind.greenLight else Ind.green, size = 9.dp)
        Spacer(Modifier.size(10.dp))
        Text(times, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ind.text, modifier = Modifier.weight(1f))
        Text(duration, fontSize = 15.sp, color = if (running) Ind.greenLight else Ind.textDim, fontWeight = FontWeight.SemiBold)
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
        else -> Ind.amber
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
                TextButton(onClick = onEndWork) { Text("Ukončit práci", color = Ind.orange) }
                TextButton(onClick = {
                    vm.stillWorking(openShiftId)
                    onAck()
                }) { Text("Stále pracuji", color = Ind.textDim) }
            } else if (a.date != null) {
                val d = a.date
                TextButton(onClick = { onOpenDay(d) }) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Ind.orange, modifier = Modifier.size(16.dp))
                    Text(" Otevřít den", color = Ind.orange)
                }
            }
        }
    }
}
