@file:OptIn(ExperimentalMaterial3Api::class)

package cz.kutil.dochazka.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
        state.demo -> Triple("DEMO", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        open != null -> {
            val start = open.editedStart ?: Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
            Triple("V práci od ${Fmt.time(start)}", scheme.primary, scheme.onPrimary)
        }
        state.openPending.isNotEmpty() -> Triple("Čeká dotaz", scheme.errorContainer, scheme.onErrorContainer)
        !state.settings.trackingEnabled -> Triple("Ruční režim", scheme.surfaceVariant, scheme.onSurfaceVariant)
        state.settings.trackState == "AWAY" || state.settings.trackState == "OUTSIDE_PENDING" ->
            Triple("Mimo domov", scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> Triple("Doma", scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
    Surface(shape = RoundedCornerShape(50), color = triple.second, modifier = Modifier.padding(end = 12.dp)) {
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

enum class Tab(val label: String) { HOME("Dnes"), MONTH("Měsíc"), PENDING("Návrhy"), SETTINGS("Nastavení") }

@Composable
fun AppRoot(vm: MainViewModel, requestedTab: MutableState<String?>) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var detailDate by rememberSaveable { mutableStateOf<String?>(null) }

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
                type = DOCX_MIME
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
    BackHandler(enabled = detail != null || tab != Tab.HOME) {
        if (detail != null) detailDate = null else tab = Tab.HOME
    }

    Scaffold(
        topBar = {
            val today = Fmt.today()
            val (title, subtitle) = when {
                detail != null -> "${Fmt.dowLong(detail).replaceFirstChar { it.uppercase() }} ${Fmt.date(detail)}" to "Detail dne"
                tab == Tab.HOME -> "Docházka Kutil" to "${Fmt.dowLong(today)} ${Fmt.date(today)}"
                tab == Tab.MONTH -> "Přehled měsíce" to state.settings.employee
                tab == Tab.PENDING -> "Návrhy k potvrzení" to "Čeká: ${state.openPending.size}"
                else -> "Nastavení" to state.settings.organization
            }
            TopAppBar(
                navigationIcon = {
                    if (detail != null) {
                        IconButton(onClick = { detailDate = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                        }
                    }
                },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = { StatusPill(state) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t && detail == null,
                        onClick = {
                            tab = t
                            detailDate = null
                        },
                        icon = {
                            val icon = when (t) {
                                Tab.HOME -> Icons.Filled.Home
                                Tab.MONTH -> Icons.Filled.DateRange
                                Tab.PENDING -> Icons.Filled.Notifications
                                Tab.SETTINGS -> Icons.Filled.Settings
                            }
                            val count = state.openPending.size
                            if (t == Tab.PENDING && count > 0) {
                                BadgedBox(badge = { Badge { Text(count.toString()) } }) { Icon(icon, contentDescription = t.label) }
                            } else {
                                Icon(icon, contentDescription = t.label)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (detail != null) {
                DayDetailScreen(vm, state, detail, onBack = { detailDate = null })
            } else {
                when (tab) {
                    Tab.HOME -> HomeScreen(vm, state, onOpenDay = { detailDate = it.toString() }, onOpenTab = { tab = it })
                    Tab.MONTH -> MonthScreen(vm, state, onOpenDay = { detailDate = it.toString() })
                    Tab.PENDING -> PendingScreen(vm, state, onOpenDay = { detailDate = it.toString() })
                    Tab.SETTINGS -> SettingsScreen(vm, state)
                }
            }
        }
    }
}
