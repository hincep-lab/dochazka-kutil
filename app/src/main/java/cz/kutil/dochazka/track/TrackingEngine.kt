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
import cz.kutil.dochazka.data.Graph
import cz.kutil.dochazka.data.Origin
import cz.kutil.dochazka.data.PendingEventEntity
import cz.kutil.dochazka.data.SettingsEntity
import cz.kutil.dochazka.data.ShiftEntity
import cz.kutil.dochazka.data.ShiftStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Propojuje čistý stavový automat s Room databází a oznámeními.
 * Všechny události (geofence, odpovědi, ruční tlačítka) jdou přes jeden zámek a jednu transakci,
 * takže opakované nebo souběžné doručení nevytvoří duplicitní záznamy.
 */
object TrackingEngine {
    private val mutex = Mutex()

    suspend fun handle(ctx: Context, event: TrackEvent, demo: Boolean = false): String = mutex.withLock {
        val app = ctx.applicationContext
        val db = Graph.db(app, demo)
        val dao = db.dao()
        val notify = ArrayList<Long>()
        val cancel = ArrayList<Long>()
        val recheck = ArrayList<Long>()
        var reRegister = false
        var note = ""

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
                            )
                        )
                        if (a.deferred) recheck.add(id) else notify.add(id)
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
                        dao.pending(a.id)?.let { dao.updatePending(it.copy(deferred = false, promptCreatedAt = now)) }
                        notify.add(a.id)
                    }
                    is Action.OpenShift -> dao.insertShift(
                        ShiftEntity(
                            rawStart = a.start.toEpochMilli(),
                            rawEnd = null,
                            origin = if (demo) Origin.DEMO else if (a.manual) Origin.MANUAL else Origin.GEOFENCE,
                            status = ShiftStatus.OPEN,
                        )
                    )
                    is Action.CloseShift -> dao.shift(a.shiftId)?.let {
                        dao.updateShift(it.copy(rawEnd = a.end.toEpochMilli(), status = ShiftStatus.CLOSED, updatedAt = now))
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
            cancel.forEach { Notifier.cancel(app, it) }
            notify.filter { it !in cancel }.forEach { id -> dao.pending(id)?.let { Notifier.show(app, it) } }
            recheck.forEach { WifiRecheckWorker.schedule(app, it) }
            if (reRegister) GeofenceManager.register(app, selfHeal = false)
        }
        note
    }

    /** Zapíše diagnostickou zprávu (např. chybu geofence) – zobrazuje se na hlavní obrazovce. */
    suspend fun diagnostic(ctx: Context, message: String) = mutex.withLock {
        val dao = Graph.realDb(ctx.applicationContext).dao()
        val s = dao.settings() ?: SettingsEntity()
        dao.upsertSettings(s.copy(lastDiagnostic = message, lastDiagnosticAt = System.currentTimeMillis()))
    }

    /** Po restartu telefonu oznámení zmizí – znovu zobrazí všechny nevyřízené návrhy. */
    suspend fun repostPending(ctx: Context) {
        val app = ctx.applicationContext
        Graph.realDb(app).dao().openPending().filter { !it.deferred }.forEach { Notifier.show(app, it) }
    }
}
