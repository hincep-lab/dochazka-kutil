package cz.kutil.dochazka.track

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.data.EventSource
import cz.kutil.dochazka.data.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val result = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: Exception) {
            // Přijímač nesmí shodit aplikaci; stav zůstává v databázi a dá se dořešit ručně.
        } finally {
            result.finish()
        }
    }
}

/**
 * Přechody domácí zóny (ENTER/EXIT) z Geofencing API – fungují i při zamčené obrazovce a ukončené aplikaci.
 * Nic nezapisuje bez potvrzení uživatele. Vyhodnocuje přesnost polohy: nepřesný odchod se nejdřív ověří.
 */
class ZoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val event = GeofencingEvent.fromIntent(intent) ?: return
        async {
            val dao = Graph.realDb(app).dao()
            val settings = dao.settings()
            if (settings?.trackingEnabled != true) return@async
            if (event.hasError()) {
                val text = if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                    "Sledování zóny je pozastavené – poloha v telefonu je vypnutá. Po zapnutí otevři aplikaci, zóna se obnoví."
                } else {
                    "Chyba sledování zóny (kód ${event.errorCode})."
                }
                AppPrefs.noteZoneResult(app, false, text)
                TrackingEngine.diagnostic(app, text)
                return@async
            }
            val now = System.currentTimeMillis()
            // Čas polohy, která přechod spustila, je blíž skutečnosti než čas doručení – pokud je věrohodný.
            val location = event.triggeringLocation
            val locTime = location?.time
            val at = if (locTime != null && locTime <= now && now - locTime <= 10 * 60_000L) locTime else now
            val accuracy = location?.takeIf { it.hasAccuracy() }?.accuracy
            val radius = dao.zone()?.radiusM ?: 200f
            // Chyba polohy větší než 60 % poloměru zóny = přechod mohl vzniknout jen skokem GPS.
            val lowAccuracy = accuracy != null && accuracy > radius * 0.6f
            val wifi = WifiHelper.isOnHomeWifi(app)
            val reason = when {
                wifi == true -> "Android ohlásil odchod, ale telefon byl na domácí Wi-Fi."
                lowAccuracy -> "Nepřesná poloha (±${accuracy?.roundToInt()} m)."
                else -> ""
            }
            val meta = EventMeta(
                source = if (wifi != null) EventSource.GPS_WIFI else EventSource.GPS,
                accuracyM = accuracy,
                uncertain = lowAccuracy,
                note = reason,
            )
            when (event.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_EXIT ->
                    TrackingEngine.handle(app, TrackEvent.GeoExit(Instant.ofEpochMilli(at), wifi, lowAccuracy), meta = meta)
                Geofence.GEOFENCE_TRANSITION_ENTER ->
                    TrackingEngine.handle(
                        app, TrackEvent.GeoEnter(Instant.ofEpochMilli(at)),
                        meta = meta.copy(note = if (lowAccuracy) "Nepřesná poloha (±${accuracy?.roundToInt()} m)." else ""),
                    )
                else -> Unit
            }
        }
    }
}

/** Odpověď ANO / NE přímo z oznámení (i ze zamčené obrazovky a z hodinek). */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getLongExtra(Notifier.EXTRA_PENDING_ID, -1L)
        if (id == Notifier.TEST_PENDING_ID) {
            Notifier.cancelTest(app)
            return
        }
        if (id < 0 || !intent.hasExtra(Notifier.EXTRA_YES)) return
        val yes = intent.getBooleanExtra(Notifier.EXTRA_YES, false)
        async {
            TrackingEngine.handle(app, TrackEvent.Answer(id, yes, Instant.now()))
            Notifier.cancel(app, id)
        }
    }
}

/** „Stále pracuji“ z upozornění na dlouhou směnu. Směnu nikdy neukončuje. */
class ShiftActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_STILL_WORKING) return
        val app = context.applicationContext
        val shiftId = intent.getLongExtra(Notifier.EXTRA_SHIFT_ID, -1L)
        if (shiftId >= 0) AppPrefs.setLongShiftAck(app, shiftId, System.currentTimeMillis())
        Notifier.cancelLongShift(app)
    }
}

/** Po restartu telefonu nebo aktualizaci aplikace Android zóny zahodí – je nutné je zaregistrovat znovu. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        async {
            ZoneHealthWorker.ensureScheduled(app)
            LongShiftWorker.ensureScheduled(app)
            val settings = Graph.realDb(app).dao().settings()
            if (settings?.trackingEnabled == true) {
                val result = GeofenceManager.register(app, selfHeal = true)
                if (!result.ok) TrackingEngine.diagnostic(app, "Po restartu se zónu nepodařilo obnovit: ${result.message}")
            }
            TrackingEngine.repostPending(app)
            LongShiftWorker.checkNow(app)
        }
    }
}
