package cz.kutil.dochazka.track

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.data.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

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

/** Přechody domácí zóny (ENTER/EXIT) z Geofencing API. Nic nezapisuje bez potvrzení uživatele. */
class ZoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val event = GeofencingEvent.fromIntent(intent) ?: return
        async {
            val settings = Graph.realDb(app).dao().settings()
            if (settings?.trackingEnabled != true) return@async
            if (event.hasError()) {
                val text = if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                    "Sledování zóny je pozastavené – poloha v telefonu je vypnutá. Po zapnutí otevři aplikaci, zóna se obnoví."
                } else {
                    "Chyba sledování zóny (kód ${event.errorCode})."
                }
                TrackingEngine.diagnostic(app, text)
                return@async
            }
            val now = System.currentTimeMillis()
            // Čas polohy, která přechod spustila, je blíž skutečnosti než čas doručení – pokud je věrohodný.
            val locTime = event.triggeringLocation?.time
            val at = if (locTime != null && locTime <= now && now - locTime <= 10 * 60_000L) locTime else now
            when (event.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_EXIT ->
                    TrackingEngine.handle(app, TrackEvent.GeoExit(Instant.ofEpochMilli(at), WifiHelper.isOnHomeWifi(app)))
                Geofence.GEOFENCE_TRANSITION_ENTER ->
                    TrackingEngine.handle(app, TrackEvent.GeoEnter(Instant.ofEpochMilli(at)))
                else -> Unit
            }
        }
    }
}

/** Odpověď ANO / NE přímo z oznámení. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getLongExtra(Notifier.EXTRA_PENDING_ID, -1L)
        if (id < 0 || !intent.hasExtra(Notifier.EXTRA_YES)) return
        val yes = intent.getBooleanExtra(Notifier.EXTRA_YES, false)
        async {
            TrackingEngine.handle(app, TrackEvent.Answer(id, yes, Instant.now()))
            Notifier.cancel(app, id)
        }
    }
}

/** Po restartu telefonu nebo aktualizaci aplikace Android zóny zahodí – je nutné je zaregistrovat znovu. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        async {
            ZoneHealthWorker.ensureScheduled(app)
            val settings = Graph.realDb(app).dao().settings()
            if (settings?.trackingEnabled == true) {
                val result = GeofenceManager.register(app, selfHeal = true)
                if (!result.ok) TrackingEngine.diagnostic(app, "Po restartu se zónu nepodařilo obnovit: ${result.message}")
            }
            TrackingEngine.repostPending(app)
        }
    }
}
