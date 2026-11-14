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
import androidx.core.content.FileProvider
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.data.Backup
import java.io.File
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

    /**
     * Skutečně zaznamenaný návrat domů během otevřené směny (pro nabídku při ukončení):
     * poslední událost „návrat“ k této směně, případně poslední přijatý vstup do zóny.
     */
    fun recordedReturn(open: ShiftEntity): Long? {
        val fromEvents = pending.filter { it.type == "END" && it.shiftId == open.id && it.effectiveAt > open.rawStart }
            .maxOfOrNull { it.effectiveAt }
        if (fromEvents != null) return fromEvents
        val at = settings.lastTransitionAt
        return if (settings.lastTransitionExit == false && at != null && at > open.rawStart) at else null
    }

    /** Výkazové intervaly ostatních uzavřených směn – pro kontrolu překryvů. */
    fun otherIntervals(exceptId: Long?): List<Interval> =
        shifts.filter { it.id != exceptId }.mapNotNull { WorkCalc.effective(it.toData()) }.filter { it.end.isAfter(it.start) }
}

data class RestorePreview(val uri: Uri, val shifts: Int, val pending: Int, val overrides: Int, val exportedAt: Long, val currentShifts: Int)

data class ShareRequest(val uri: Uri, val email: String, val subject: String, val body: String)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()

    val message = MutableStateFlow<String?>(null)

    /** Požadavek na otevření e-mailu s přiloženým výkazem (zpracuje ho obrazovka). */
    val share = MutableStateFlow<ShareRequest?>(null)
    val selectedMonth = MutableStateFlow(YearMonth.now(PRAGUE))

    /** Ověřená záloha čekající na potvrzení obnovy. */
    val restorePreview = MutableStateFlow<RestorePreview?>(null)

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

    fun endWork() = endWorkAt(Instant.now())

    /** Ukončí otevřenou směnu zvoleným časem (teď, zaznamenaný návrat, nebo ručně zadaný čas). */
    fun endWorkAt(at: Instant) = launchSafe { message.value = TrackingEngine.handle(ctx, TrackEvent.ManualEnd(at), demo) }

    /** „Stále pracuji“ – směna zůstává otevřená, upozornění se na pár hodin odmlčí. */
    fun stillWorking(shiftId: Long) {
        AppPrefs.setLongShiftAck(ctx, shiftId, System.currentTimeMillis())
        cz.kutil.dochazka.track.Notifier.cancelLongShift(ctx)
        message.value = "Dobře, směna běží dál. Zeptám se znovu za ${cz.kutil.dochazka.core.LongShift.REPEAT_AFTER_ACK_HOURS} hodiny."
    }

    /** Úprava času nevyřízené události; původně zjištěný čas zůstává uložený. */
    fun adjustPending(pendingId: Long, at: Instant) = launchSafe {
        TrackingEngine.adjustPending(ctx, pendingId, at, demo)
        message.value = "Čas události upraven."
    }

    fun reRegisterZone() = launchSafe {
        val r = GeofenceManager.register(ctx, selfHeal = false)
        if (!r.ok) TrackingEngine.diagnostic(ctx, "Zóna není aktivní: ${r.message}") else TrackingEngine.diagnostic(ctx, "Domácí zóna je znovu aktivní.")
        message.value = r.message
    }

    fun sendTestNotification() {
        message.value = if (cz.kutil.dochazka.track.Notifier.showTest(ctx)) "Zkušební oznámení odesláno – podívej se do oznámení (a na hodinky)."
        else "Oznámení nejde zobrazit – nejsou povolená."
    }

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

    /**
     * Dovolená na jeden den nebo na období. skipFree = víkendy a svátky se vynechají.
     * remove = dovolenou v období zrušit (jiná označení dnů zůstanou).
     */
    fun setVacation(from: LocalDate, to: LocalDate, skipFree: Boolean, remove: Boolean) = launchSafe {
        val start = if (to.isBefore(from)) to else from
        val end = if (to.isBefore(from)) from else to
        var count = 0
        val d = db()
        d.withTransaction {
            val dao = d.dao()
            val existing = dao.allOverrides().associateBy { it.date }
            var day = start
            while (!day.isAfter(end) && count < 370) {
                val key = day.toString()
                if (remove) {
                    if (existing[key]?.type == OverrideType.DOVOLENA.name) {
                        dao.deleteOverride(key)
                        count++
                    }
                } else if (!skipFree || WorkCalc.flags(day).isEmpty()) {
                    dao.upsertOverride(DayOverrideEntity(key, OverrideType.DOVOLENA.name, ""))
                    count++
                }
                day = day.plusDays(1)
            }
        }
        message.value = if (remove) "Dovolená zrušena: $count dní." else "Dovolená zapsána: $count dní."
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

    /**
     * Vyplní formulář do dočasného souboru a otevře výběr e-mailové aplikace s přílohou.
     * Samotné odeslání potvrzuje uživatel ve své poštovní aplikaci – aplikace sama nic neposílá.
     */
    fun shareByEmail(ym: YearMonth) = launchSafe {
        val st = state.value
        val (file, report) = withContext(Dispatchers.IO) {
            val template = ctx.assets.open(TEMPLATE_ASSET).use { it.readBytes() }
            val (bytes, rep) = DocxExporter.export(template, st.month(ym), ExportHeader(st.settings.organization, st.settings.employee))
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, rep.fileName)
            f.writeBytes(bytes)
            f to rep
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val monthName = DocxExporter.MONTHS[ym.monthValue - 1]
        val total = WorkCalc.formatHm(report.totalMinutes)
        val extra = if (report.overflowDays.isEmpty()) "" else
            "\nDny ${report.overflowDays.joinToString(", ") { "$it." }} mají více výjezdů – jsou rozepsané v příloze na druhé straně."
        share.value = ShareRequest(
            uri = uri,
            email = AppPrefs.reportEmail(ctx),
            subject = "Docházka $monthName ${ym.year} – ${st.settings.employee}",
            body = "Dobrý den,\n\nv příloze posílám docházku za $monthName ${ym.year}. Odpracováno celkem $total h.$extra\n\n${st.settings.employee}",
        )
    }

    fun backup(uri: Uri) = launchSafe {
        withContext(Dispatchers.IO) {
            val json = Backup.export(db(), if (demo) null else AppPrefs.toJson(ctx))
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Soubor nelze otevřít pro zápis.")
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            // Kontrola po zápisu: soubor se přečte zpět a ověří se jeho otisk.
            val check = ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            if (check == null) throw IOException("Zálohu se nepodařilo po uložení ověřit.")
            Backup.inspect(check)
        }
        if (!demo) AppPrefs.setLastBackupAt(ctx, System.currentTimeMillis())
        message.value = "Záloha uložena a ověřena."
    }

    private suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Soubor nelze otevřít.")
        input.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /** Před obnovou: ověří soubor a připraví přehled toho, co se přepíše. Do dat nesahá. */
    fun inspectBackup(uri: Uri) = launchSafe {
        val summary = Backup.inspect(readText(uri))
        restorePreview.value = RestorePreview(uri, summary.shifts, summary.pending, summary.overrides, summary.exportedAt, state.value.shifts.size)
    }

    fun restore(uri: Uri) = launchSafe {
        val summary = Backup.restore(db(), readText(uri))
        if (!demo) {
            summary.prefs?.let { AppPrefs.fromJson(ctx, it) }
            GeofenceManager.unregister(ctx)
            if (Graph.realDb(ctx).dao().settings()?.trackingEnabled == true) GeofenceManager.register(ctx, selfHeal = false)
        }
        message.value = "Obnoveno ze zálohy: ${summary.shifts} směn, ${summary.pending} událostí, ${summary.overrides} označených dnů."
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
