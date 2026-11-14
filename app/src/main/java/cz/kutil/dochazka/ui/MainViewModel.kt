package cz.kutil.dochazka.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import cz.kutil.dochazka.core.DemoData
import cz.kutil.dochazka.core.DocxExporter
import cz.kutil.dochazka.core.ExportHeader
import cz.kutil.dochazka.core.Interval
import cz.kutil.dochazka.core.MonthSummary
import cz.kutil.dochazka.core.OverrideType
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.Rounding
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.core.WorkCalc
import cz.kutil.dochazka.data.Backup
import cz.kutil.dochazka.data.DayOverrideEntity
import cz.kutil.dochazka.data.Graph
import cz.kutil.dochazka.data.HomeZoneEntity
import cz.kutil.dochazka.data.Origin
import cz.kutil.dochazka.data.PendingEventEntity
import cz.kutil.dochazka.data.SettingsEntity
import cz.kutil.dochazka.data.ShiftEntity
import cz.kutil.dochazka.data.ShiftStatus
import cz.kutil.dochazka.track.GeofenceManager
import cz.kutil.dochazka.track.TrackingEngine
import cz.kutil.dochazka.track.ZoneHealthWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class UiState(
    val loaded: Boolean = false,
    val demo: Boolean = false,
    val settings: SettingsEntity = SettingsEntity(),
    val zone: HomeZoneEntity? = null,
    val shifts: List<ShiftEntity> = emptyList(),
    val pending: List<PendingEventEntity> = emptyList(),
    val overrides: List<DayOverrideEntity> = emptyList(),
) {
    val openShift: ShiftEntity? get() = shifts.lastOrNull { it.status == ShiftStatus.OPEN }

    /** Nevyřízené návrhy viditelné uživateli (bez těch, které teprve čekají na ověření Wi-Fi). */
    val openPending: List<PendingEventEntity> get() = pending.filter { it.status == "PENDING" && !it.deferred }

    fun month(ym: YearMonth): MonthSummary {
        val ov = HashMap<LocalDate, Pair<OverrideType, String>>()
        for (o in overrides) {
            val type = OverrideType.fromName(o.type) ?: continue
            val date = runCatching { LocalDate.parse(o.date) }.getOrNull() ?: continue
            ov[date] = type to o.note
        }
        return WorkCalc.month(ym, shifts.map { it.toData() }, ov)
    }

    /** Výkazové intervaly ostatních uzavřených směn – pro kontrolu překryvů. */
    fun otherIntervals(exceptId: Long?): List<Interval> =
        shifts.filter { it.id != exceptId }.mapNotNull { WorkCalc.effective(it.toData()) }.filter { it.end.isAfter(it.start) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()

    val message = MutableStateFlow<String?>(null)
    val selectedMonth = MutableStateFlow(YearMonth.now(PRAGUE))

    val state: StateFlow<UiState> = Graph.demoFlow(app).flatMapLatest { demo ->
        val dao = Graph.db(app, demo).dao()
        combine(dao.settingsFlow(), dao.zoneFlow(), dao.shiftsFlow(), dao.pendingFlow(), dao.overridesFlow()) { s, z, sh, p, o ->
            UiState(true, demo, s ?: SettingsEntity(), z, sh, p, o)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val demo: Boolean get() = state.value.demo
    private fun db() = Graph.db(ctx, demo)
    private fun dao() = db().dao()

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                message.value = "Akce se nepodařila: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // --- práce ---------------------------------------------------------------------------

    fun startWork() = launchSafe { message.value = TrackingEngine.handle(ctx, TrackEvent.ManualStart(Instant.now()), demo) }

    fun endWork() = launchSafe { message.value = TrackingEngine.handle(ctx, TrackEvent.ManualEnd(Instant.now()), demo) }

    fun answer(pendingId: Long, yes: Boolean) = launchSafe {
        message.value = TrackingEngine.handle(ctx, TrackEvent.Answer(pendingId, yes, Instant.now()), demo)
    }

    /**
     * Uloží nový interval nebo opravu existujícího. Raw časy existující směny se nikdy nepřepisují,
     * oprava jde do editedStart/editedEnd.
     */
    fun saveShift(id: Long?, start: Instant, end: Instant?, round: Boolean, note: String) = launchSafe {
        val now = System.currentTimeMillis()
        if (id == null) {
            val e = end ?: return@launchSafe
            dao().insertShift(
                ShiftEntity(
                    rawStart = start.toEpochMilli(),
                    rawEnd = e.toEpochMilli(),
                    editedStart = if (round) null else start.toEpochMilli(),
                    editedEnd = if (round) null else e.toEpochMilli(),
                    origin = if (demo) Origin.DEMO else Origin.MANUAL,
                    status = ShiftStatus.CLOSED,
                    note = note.trim(),
                )
            )
            message.value = "Interval přidán."
        } else {
            val old = dao().shift(id) ?: return@launchSafe
            val s = if (round) Rounding.roundToHalfHour(start) else start
            val e = end?.let { if (round) Rounding.roundToHalfHour(it) else it }
            dao().updateShift(
                old.copy(
                    editedStart = s.toEpochMilli(),
                    editedEnd = if (old.status == ShiftStatus.OPEN) null else e?.toEpochMilli(),
                    note = note.trim(),
                    updatedAt = now,
                )
            )
            message.value = "Čas opraven."
        }
    }

    fun deleteShift(id: Long) = launchSafe {
        dao().deleteShift(id)
        message.value = "Záznam smazán."
    }

    fun setOverride(date: LocalDate, type: OverrideType?, note: String = "") = launchSafe {
        if (type == null) dao().deleteOverride(date.toString())
        else dao().upsertOverride(DayOverrideEntity(date.toString(), type.name, note.trim()))
    }

    // --- nastavení -----------------------------------------------------------------------

    private suspend fun updateSettings(db: cz.kutil.dochazka.data.AppDatabase, change: (SettingsEntity) -> SettingsEntity) {
        db.withTransaction {
            val d = db.dao()
            d.upsertSettings(change(d.settings() ?: SettingsEntity()))
        }
    }

    fun saveHeader(organization: String, employee: String) = launchSafe {
        updateSettings(db()) { it.copy(organization = organization.trim(), employee = employee.trim()) }
        message.value = "Uloženo."
    }

    /** Domácí zóna patří vždy ke skutečným datům, nikdy k demo režimu. */
    fun saveZone(latitude: Double, longitude: Double, radiusM: Float, ssid: String) = launchSafe {
        val real = Graph.realDb(ctx)
        real.dao().upsertZone(HomeZoneEntity(latitude = latitude, longitude = longitude, radiusM = radiusM, wifiSsid = ssid.trim()))
        if (real.dao().settings()?.trackingEnabled == true) {
            val r = GeofenceManager.register(ctx, selfHeal = false)
            message.value = if (r.ok) "Domácí zóna uložena a aktivní." else "Zóna uložena, ale není aktivní: ${r.message}"
        } else {
            message.value = "Domácí zóna uložena."
        }
    }

    fun setTracking(enabled: Boolean) = launchSafe {
        val real = Graph.realDb(ctx)
        if (enabled) {
            if (real.dao().zone() == null) {
                message.value = "Nejdřív nastav domácí zónu."
                return@launchSafe
            }
            updateSettings(real) { it.copy(trackingEnabled = true) }
            ZoneHealthWorker.ensureScheduled(ctx)
            val r = GeofenceManager.register(ctx, selfHeal = false)
            if (!r.ok) TrackingEngine.diagnostic(ctx, "Zóna není aktivní: ${r.message}")
            message.value = if (r.ok) "Sledování zapnuto." else "Sledování je zapnuté, ale zóna není aktivní: ${r.message}"
        } else {
            updateSettings(real) { it.copy(trackingEnabled = false) }
            GeofenceManager.unregister(ctx)
            message.value = "Sledování vypnuto. Docházku můžeš dál zapisovat ručně."
        }
    }

    fun finishOnboarding(organization: String, employee: String, enableTracking: Boolean) = launchSafe {
        val real = Graph.realDb(ctx)
        updateSettings(real) { it.copy(organization = organization.trim(), employee = employee.trim(), onboardingDone = true) }
        if (enableTracking) setTracking(true)
    }

    fun restartOnboarding() = launchSafe { updateSettings(Graph.realDb(ctx)) { it.copy(onboardingDone = false) } }

    /** Při návratu do aplikace tiše obnoví registraci zóny (např. po vypnutí a zapnutí polohy). */
    fun refreshZone() {
        viewModelScope.launch {
            try {
                val real = Graph.realDb(ctx)
                if (real.dao().settings()?.trackingEnabled == true) {
                    val r = GeofenceManager.register(ctx, selfHeal = false)
                    val last = real.dao().settings()?.lastDiagnostic ?: ""
                    if (!r.ok) TrackingEngine.diagnostic(ctx, "Zóna není aktivní: ${r.message}")
                    else if (last.startsWith("Zóna není aktivní") || last.startsWith("Sledování zóny je pozastavené") || last.startsWith("Po restartu")) {
                        TrackingEngine.diagnostic(ctx, "Domácí zóna je znovu aktivní.")
                    }
                }
            } catch (e: Exception) {
                // tichá údržba – chyba se projeví na stavové kartě
            }
        }
    }

    suspend fun currentLocation() = GeofenceManager.currentLocation(ctx)

    // --- export, záloha -------------------------------------------------------------------

    fun export(uri: Uri, ym: YearMonth) = launchSafe {
        val st = state.value
        val report = withContext(Dispatchers.IO) {
            val template = ctx.assets.open(TEMPLATE_ASSET).use { it.readBytes() }
            val (bytes, rep) = DocxExporter.export(template, st.month(ym), ExportHeader(st.settings.organization, st.settings.employee))
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Soubor nelze otevřít pro zápis.")
            out.use { it.write(bytes) }
            rep
        }
        val extra = if (report.overflowDays.isEmpty()) "" else
            " Pozor: dny ${report.overflowDays.joinToString(", ") { "$it." }} mají víc výjezdů, než má formulář sloupců – jsou vypsané v příloze na druhé straně."
        message.value = "Export uložen (${report.fileName}), celkem ${WorkCalc.formatHm(report.totalMinutes)} h.$extra"
    }

    fun backup(uri: Uri) = launchSafe {
        withContext(Dispatchers.IO) {
            val json = Backup.export(db())
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Soubor nelze otevřít pro zápis.")
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        message.value = "Záloha uložena."
    }

    fun restore(uri: Uri) = launchSafe {
        val count = withContext(Dispatchers.IO) {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Soubor nelze otevřít.")
            val json = input.use { String(it.readBytes(), Charsets.UTF_8) }
            Backup.restore(db(), json)
        }
        if (!demo) {
            GeofenceManager.unregister(ctx)
            if (Graph.realDb(ctx).dao().settings()?.trackingEnabled == true) GeofenceManager.register(ctx, selfHeal = false)
        }
        message.value = "Obnoveno ze zálohy: $count směn."
    }

    // --- demo režim -----------------------------------------------------------------------

    /** Demo data žijí v oddělené databázi; skutečná docházka ani sledování se jich nedotknou. */
    fun setDemo(on: Boolean) = launchSafe {
        if (on) {
            val d = Graph.demoDb(ctx)
            d.withTransaction {
                val dd = d.dao()
                dd.deleteAllShifts()
                dd.deleteAllPending()
                dd.deleteAllOverrides()
                dd.upsertSettings(SettingsEntity(onboardingDone = true, trackingEnabled = false))
                for (s in DemoData.november2026()) {
                    dd.insertShift(
                        ShiftEntity(
                            rawStart = s.rawStart.toEpochMilli(),
                            rawEnd = s.rawEnd?.toEpochMilli(),
                            origin = Origin.DEMO,
                            status = ShiftStatus.CLOSED,
                        )
                    )
                }
            }
            Graph.setDemo(ctx, true)
            selectedMonth.value = DemoData.MONTH
            message.value = "Demo režim: ukázkový listopad 2026 (186:00 h). Skutečná data zůstala beze změny."
        } else {
            Graph.setDemo(ctx, false)
            selectedMonth.value = YearMonth.now(PRAGUE)
            message.value = "Demo režim vypnut."
        }
    }

    companion object {
        const val TEMPLATE_ASSET = "Pavel_ORIGINAL.docx"
    }
}
