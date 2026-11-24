package cz.kutil.dochazka.track

import android.content.Context
import androidx.room.withTransaction
import cz.kutil.dochazka.core.Action
import cz.kutil.dochazka.core.LastTransition
import cz.kutil.dochazka.core.OpenShiftInfo
import cz.kutil.dochazka.core.Snapshot
import cz.kutil.dochazka.core.StateMachine
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.core.TrackState
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.data.Confidence
import cz.kutil.dochazka.data.EventSource
import cz.kutil.dochazka.data.Graph
import cz.kutil.dochazka.data.Origin
import cz.kutil.dochazka.data.PendingEventEntity
import cz.kutil.dochazka.data.SettingsEntity
import cz.kutil.dochazka.data.ShiftEntity
import cz.kutil.dochazka.data.ShiftStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Doplňující údaje k události z polohy: odkud informace pochází a jak je spolehlivá. */
data class EventMeta(
    val source: String = EventSource.GPS,
    val accuracyM: Float? = null,
    val uncertain: Boolean = false,
    val note: String = "",
)

/**
 * Propojuje čistý stavový automat s Room databází a oznámeními.
 * Všechny události (geofence, odpovědi, ruční tlačítka) jdou přes jeden zámek a jednu transakci,
 * takže opakované nebo souběžné doručení nevytvoří duplicitní záznamy. Veškerý stav je v databázi –
 * po restartu aplikace i telefonu se pokračuje přesně tam, kde se skončilo.
 */
object TrackingEngine {
    private val mutex = Mutex()

    suspend fun handle(ctx: Context, event: TrackEvent, demo: Boolean = false, meta: EventMeta? = null): String = mutex.withLock {
        val app = ctx.applicationContext
        val db = Graph.db(app, demo)
        val dao = db.dao()
        val notify = ArrayList<Long>()
        val cancel = ArrayList<Long>()
        val verify = ArrayList<Long>()
        var reRegister = false
        var shiftClosed = false
        var note = ""
        var acceptedAt: Long? = null
        val m = meta ?: EventMeta()

        db.withTransaction {
            val settings = dao.settings() ?: SettingsEntity()
            val open = dao.openShift()
            val last = settings.lastTransitionAt?.let { at ->
                settings.lastTransitionExit?.let { exit -> LastTransition(exit, Instant.ofEpochMilli(at)) }
            }
            val snapshot = Snapshot(
                state = runCatching { TrackState.valueOf(settings.trackState) }.getOrDefault(TrackState.HOME),
                pending = dao.openPending().map { it.toInfo() },
                open = open?.let { OpenShiftInfo(it.id, Instant.ofEpochMilli(it.rawStart)) },
                last = last,
            )
            val r = StateMachine.reduce(snapshot, event)
            if (r.newLast != null && r.newLast != last) acceptedAt = r.newLast.at.toEpochMilli()
            val now = System.currentTimeMillis()
            for (a in r.actions) {
                when (a) {
                    is Action.CreatePending -> {
                        val id = dao.insertPending(
                            PendingEventEntity(
                                type = a.type.name,
                                eventDetectedAt = a.detectedAt.toEpochMilli(),
                                promptCreatedAt = now,
                                shiftId = a.shiftId,
                                deferred = a.deferred,
                                source = m.source,
                                confidence = if (m.uncertain) Confidence.UNCERTAIN else Confidence.CERTAIN,
                                accuracyM = m.accuracyM,
                                note = m.note,
                            )
                        )
                        if (a.deferred) verify.add(id) else notify.add(id)
                    }
                    is Action.ResolvePending -> {
                        dao.pending(a.id)?.let {
                            dao.updatePending(it.copy(status = a.status.name, answeredAt = a.answeredAt.toEpochMilli(), deferred = false))
                        }
                        cancel.add(a.id)
                    }
                    is Action.SetPairedEnd -> {
                        dao.pending(a.id)?.let { dao.updatePending(it.copy(pairedEndAt = a.endAt.toEpochMilli())) }
                        notify.add(a.id)
                    }
                    is Action.ConfirmDeferred -> {
                        dao.pending(a.id)?.let {
                            dao.updatePending(
                                it.copy(
                                    deferred = false,
                                    promptCreatedAt = now,
                                    confidence = if (a.uncertain) Confidence.UNCERTAIN else Confidence.CERTAIN,
                                    source = if (m.source == EventSource.GPS_WIFI) EventSource.GPS_WIFI else it.source,
                                    note = m.note.ifBlank { it.note },
                                )
                            )
                        }
                        notify.add(a.id)
                    }
                    is Action.MarkConflict -> {
                        dao.pending(a.id)?.let { dao.updatePending(it.copy(confidence = Confidence.CONFLICT, note = a.reason)) }
                    }
                    is Action.OpenShift -> dao.insertShift(
                        ShiftEntity(
                            rawStart = a.start.toEpochMilli(),
                            rawEnd = null,
                            origin = if (demo) Origin.DEMO else if (a.manual) Origin.MANUAL else Origin.GEOFENCE,
                            status = ShiftStatus.OPEN,
                        )
                    )
                    is Action.CloseShift -> {
                        dao.shift(a.shiftId)?.let {
                            dao.updateShift(it.copy(rawEnd = a.end.toEpochMilli(), status = ShiftStatus.CLOSED, updatedAt = now))
                        }
                        shiftClosed = true
                    }
                    is Action.CreateClosedShift -> dao.insertShift(
                        ShiftEntity(
                            rawStart = a.start.toEpochMilli(),
                            rawEnd = a.end.toEpochMilli(),
                            origin = if (demo) Origin.DEMO else Origin.GEOFENCE,
                            status = ShiftStatus.CLOSED,
                        )
                    )
                    Action.ReRegisterZone -> reRegister = true
                }
            }
            dao.upsertSettings(
                (dao.settings() ?: settings).copy(
                    trackState = r.state.name,
                    lastTransitionExit = r.newLast?.exit,
                    lastTransitionAt = r.newLast?.at?.toEpochMilli(),
                    lastDiagnostic = r.note,
                    lastDiagnosticAt = now,
                )
            )
            note = r.note
        }

        if (!demo) {
            // Diagnostika: skutečně přijaté přechody zóny (i ty, které automat vyhodnotil jako duplicitu).
            when (event) {
                is TrackEvent.GeoExit -> AppPrefs.noteTransition(app, true, event.at.toEpochMilli())
                is TrackEvent.GeoEnter -> AppPrefs.noteTransition(app, false, event.at.toEpochMilli())
                else -> Unit
            }
            // Pro přehled jen přechody, které automat skutečně přijal jako nový odchod / návrat.
            if (acceptedAt != null && (event is TrackEvent.GeoExit || event is TrackEvent.GeoEnter)) {
                val evAt = if (event is TrackEvent.GeoExit) event.at else (event as TrackEvent.GeoEnter).at
                if (acceptedAt == evAt.toEpochMilli()) AppPrefs.noteRealTransition(app, event is TrackEvent.GeoExit, evAt.toEpochMilli())
            }
            cancel.forEach { Notifier.cancel(app, it) }
            notify.filter { it !in cancel }.forEach { id -> dao.pending(id)?.let { Notifier.show(app, it) } }
            verify.forEach { VerifyExitWorker.schedule(app, it) }
            if (shiftClosed) Notifier.cancelLongShift(app)
            refreshStatus(app)
            if (reRegister) GeofenceManager.register(app, selfHeal = false)
        }
        note
    }

    /** Uživatel upravil čas nevyřízené události. Původně zjištěný čas zůstává uložený. */
    suspend fun adjustPending(ctx: Context, pendingId: Long, at: Instant, demo: Boolean = false) = mutex.withLock {
        val app = ctx.applicationContext
        val dao = Graph.db(app, demo).dao()
        val p = dao.pending(pendingId) ?: return@withLock
        if (p.status != "PENDING") return@withLock
        val updated = p.copy(adjustedAt = at.toEpochMilli())
        dao.updatePending(updated)
        if (!demo && !updated.deferred) Notifier.show(app, updated)
    }

    /** Jak dlouho zůstávají zrušené události v historii „K potvrzení“. */
    const val SUPERSEDED_KEEP_MS = 24L * 3600 * 1000

    /** Jak dlouho zůstávají potvrzené a odmítnuté události v historii. */
    const val RESOLVED_KEEP_MS = 30L * 24 * 3600 * 1000

    /**
     * Úklid historie „K potvrzení“ (v obou databázích): zrušené po dni, potvrzené a odmítnuté po měsíci.
     * Nevyřízené události se nemažou nikdy. Pracovní intervaly se úklidem nemění.
     */
    suspend fun cleanupHistory(ctx: Context) = mutex.withLock {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        listOf(Graph.realDb(app), Graph.demoDb(app)).sumOf { db ->
            db.dao().deleteOldSuperseded(now - SUPERSEDED_KEEP_MS) + db.dao().deleteOldResolved(now - RESOLVED_KEEP_MS)
        }
    }

    /** Zobrazí nebo skryje trvalé oznámení „V práci od …“ podle skutečné otevřené směny v databázi. */
    suspend fun refreshStatus(ctx: Context) {
        val app = ctx.applicationContext
        // Widget na ploše a dlaždice v rychlém nastavení ukazují totéž.
        runCatching { cz.kutil.dochazka.widget.DochazkaWidget.updateAll(app) }
        cz.kutil.dochazka.widget.DochazkaTile.requestRefresh(app)
        val dao = Graph.realDb(app).dao()
        val open = dao.openShift()
        if (open == null || !AppPrefs.statusNotification(app)) {
            Notifier.cancelWorking(app)
            return
        }
        val reportStart = open.editedStart ?: cz.kutil.dochazka.core.Rounding.roundToHalfHour(Instant.ofEpochMilli(open.rawStart)).toEpochMilli()
        val returnAt = dao.lastReturnFor(open.id)?.effectiveAt?.takeIf { it > open.rawStart }
        Notifier.showWorking(app, open.id, open.rawStart, reportStart, returnAt)
    }

    /** Zapíše diagnostickou zprávu (např. chybu geofence) – zobrazuje se na hlavní obrazovce. */
    suspend fun diagnostic(ctx: Context, message: String) = mutex.withLock {
        val dao = Graph.realDb(ctx.applicationContext).dao()
        val s = dao.settings() ?: SettingsEntity()
        dao.upsertSettings(s.copy(lastDiagnostic = message, lastDiagnosticAt = System.currentTimeMillis()))
    }

    /** Po restartu telefonu oznámení zmizí – znovu zobrazí všechny nevyřízené návrhy a doplánuje ověření. */
    suspend fun repostPending(ctx: Context) {
        val app = ctx.applicationContext
        for (p in Graph.realDb(app).dao().openPending()) {
            if (p.deferred) VerifyExitWorker.schedule(app, p.id) else Notifier.show(app, p)
        }
    }
}
