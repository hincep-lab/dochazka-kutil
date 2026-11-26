@file:OptIn(ExperimentalMaterial3Api::class)

package cz.kutil.dochazka.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import cz.kutil.dochazka.R
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.kutil.dochazka.core.Rounding
import java.time.Instant
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth

/** Stav docházky na první pohled – vpravo v horní liště na všech obrazovkách. */
@Composable
private fun StatusPill(state: UiState) {
    val scheme = MaterialTheme.colorScheme
    val open = state.openShift
    val triple = when {
        state.demo -> Triple("DEMO", Ind.orange, Ind.onOrange)
        open != null -> {
            val start = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
            Triple("V práci od ${Fmt.time(start)}", Color(0xFF2E9A35), Color.White)
        }
        state.openPending.isNotEmpty() -> Triple("Čeká dotaz", Ind.orange, Ind.onOrange)
        !state.settings.trackingEnabled -> Triple("Ruční režim", scheme.surfaceVariant, scheme.onSurfaceVariant)
        state.settings.trackState == "AWAY" || state.settings.trackState == "OUTSIDE_PENDING" ->
            Triple("Mimo domov", scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> Triple("Doma", scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
    Surface(shape = RoundedCornerShape(50), color = triple.second, modifier = Modifier.padding(start = 6.dp)) {
        Text(
            triple.first,
            color = triple.third,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

enum class Tab(val label: String) { HOME("Přehled"), CALENDAR("Kalendář"), MONTH("Výkaz"), PENDING("Potvrdit"), SETTINGS("Více") }

@Composable
fun AppRoot(vm: MainViewModel, requestedTab: MutableState<String?>) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var detailDate by rememberSaveable { mutableStateOf<String?>(null) }
    var diag by rememberSaveable { mutableStateOf(false) }
    var endDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            vm.message.value = null
            snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    LaunchedEffect(requestedTab.value) {
        val requested = requestedTab.value
        if (requested == "pending") {
            tab = Tab.PENDING
            detailDate = null
            diag = false
            requestedTab.value = null
        } else if (requested == "endshift") {
            tab = Tab.HOME
            detailDate = null
            diag = false
            endDialog = true
            requestedTab.value = null
        } else if (requested == "calendar") {
            tab = Tab.CALENDAR
            detailDate = null
            diag = false
            requestedTab.value = null
        } else if (requested == "settings") {
            tab = Tab.SETTINGS
            detailDate = null
            diag = false
            requestedTab.value = null
        } else if (requested != null && requested.startsWith("month")) {
            runCatching { YearMonth.parse(requested.substringAfter("month:", "")) }.getOrNull()?.let { vm.selectedMonth.value = it }
            tab = Tab.MONTH
            detailDate = null
            requestedTab.value = null
        }
    }
    val share by vm.share.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(share) {
        val req = share
        if (req != null) {
            vm.share.value = null
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = req.mime
                putExtra(Intent.EXTRA_STREAM, req.uri)
                if (req.email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(req.email))
                putExtra(Intent.EXTRA_SUBJECT, req.subject)
                putExtra(Intent.EXTRA_TEXT, req.body)
                clipData = ClipData.newRawUri("Docházka", req.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Odeslat docházku"))
            } catch (e: Exception) {
                vm.message.value = "V telefonu není aplikace, kterou by šlo výkaz odeslat."
            }
        }
    }

    if (!state.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (!state.settings.onboardingDone && !state.demo) {
        Box(Modifier.fillMaxSize()) {
            OnboardingScreen(vm, state)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    val detail = detailDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    BackHandler(enabled = detail != null || diag || tab != Tab.HOME) {
        if (detail != null) detailDate = null else if (diag) diag = false else tab = Tab.HOME
    }

    val openShift = state.openShift
    if (endDialog && openShift != null) {
        EndShiftDialog(
            startMs = openShift.rawStart,
            returnAtMs = state.recordedReturn(openShift),
            onDismiss = { endDialog = false },
            onEnd = {
                vm.endWorkAt(it)
                endDialog = false
            },
        )
    }
    // Dialog bez otevřené směny nemá smysl (např. směnu mezitím ukončila odpověď z oznámení).
    LaunchedEffect(endDialog, openShift?.id) {
        if (endDialog && openShift == null) endDialog = false
    }

    val showTopBar = detail != null || diag || tab != Tab.HOME
    Box(Modifier.fillMaxSize()) {
    WetBackground()
    Scaffold(
        containerColor = Color.Transparent,
        // Text přímo na tmavém pozadí (nadpisy mimo karty) musí být světlý.
        contentColor = Ind.onBg,
        topBar = {
            if (showTopBar) {
                val (title, subtitle) = when {
                    detail != null -> "${Fmt.dowLong(detail).replaceFirstChar { it.uppercase() }} ${Fmt.date(detail)}" to "Detail dne"
                    diag -> "Stav sledování" to "Diagnostika"
                    tab == Tab.CALENDAR -> "Kalendář" to "Pracovní dny a nepřítomnosti"
                    tab == Tab.MONTH -> "Výkaz" to "Záznamy, export do Wordu a PDF"
                    tab == Tab.PENDING -> "K potvrzení" to "Nevyřízeno: ${state.openPending.size}"
                    else -> "Nastavení" to state.settings.organization
                }
                IndTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = when {
                        detail != null -> ({ detailDate = null })
                        diag -> ({ diag = false })
                        else -> ({ tab = Tab.HOME })
                    },
                    trailing = { StatusPill(state) },
                )
            }
        },
        bottomBar = {
            IndBottomBar(
                selected = if (detail == null && !diag) tab else null,
                pendingCount = state.openPending.size,
                onSelect = { t ->
                    tab = t
                    diag = false
                    detailDate = null
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (detail != null) {
                DayDetailScreen(vm, state, detail, onBack = { detailDate = null })
            } else if (diag) {
                DiagnosticsScreen(vm, state)
            } else {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        vm, state,
                        onOpenDay = { detailDate = it.toString() },
                        onOpenTab = { tab = it },
                        onEndWork = { endDialog = true },
                        onOpenDiag = { diag = true },
                    )
                    Tab.CALENDAR -> CalendarScreen(vm, state, onOpenDay = { detailDate = it.toString() })
                    Tab.MONTH -> MonthScreen(vm, state, onOpenDay = { detailDate = it.toString() })
                    Tab.PENDING -> PendingScreen(vm, state, onOpenDay = { detailDate = it.toString() })
                    Tab.SETTINGS -> SettingsScreen(vm, state, onOpenDiag = { diag = true })
                }
            }
        }
    }
    }
}

private val BarText = PanelText

@Composable
private fun IndTopBar(title: String, subtitle: String, onBack: () -> Unit, trailing: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xE60B1A30), Color(0x990B1A30), Color.Transparent)))
            .statusBarsPadding(),
    ) {
        TopBarPipe(Modifier.align(Alignment.TopEnd))
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 64.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět", tint = Ind.onBg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ind.onBg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = BarText)
                Text(subtitle, fontSize = 13.sp, color = Ind.onBgDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

@Composable
private fun IndBottomBar(selected: Tab?, pendingCount: Int, onSelect: (Tab) -> Unit) {
    // plovoucí lišta ve tvaru tablety; vybraná položka má světlé „pilulkové“ pozadí
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp)) {
        val bar = RoundedCornerShape(28.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(16.dp, bar, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(bar)
                .background(Color(0xF20B1626))
                .border(1.dp, Color.White.copy(alpha = 0.09f), bar)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (t in Tab.entries) {
                val sel = t == selected
                val icon = when (t) {
                    Tab.HOME -> AppIcons.HOME
                    Tab.CALENDAR -> AppIcons.CALENDAR
                    Tab.MONTH -> AppIcons.REPORT
                    Tab.PENDING -> AppIcons.CONFIRM
                    Tab.SETTINGS -> AppIcons.MORE
                }
                val shape = RoundedCornerShape(22.dp)
                val bg by androidx.compose.animation.animateColorAsState(if (sel) Color(0xFF2F6FE0) else Color.Transparent, label = "tabBg")
                Column(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(bg)
                        .clickable { onSelect(t) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = if (sel) Color.White else Color(0xFF9DB0C8)
                    if (t == Tab.PENDING && pendingCount > 0) {
                        BadgedBox(badge = { Badge(containerColor = Ind.red, contentColor = Color.White) { Text(pendingCount.toString()) } }) {
                            Icon(painterResource(icon), contentDescription = t.label, tint = tint, modifier = Modifier.size(24.dp))
                        }
                    } else {
                        Icon(painterResource(icon), contentDescription = t.label, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Text(t.label, fontSize = 11.5.sp, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.SemiBold, color = tint, maxLines = 1)
                }
            }
        }
    }
}
