package cz.kutil.dochazka.track

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import cz.kutil.dochazka.core.TrackState
import cz.kutil.dochazka.data.Graph
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ZoneResult(val ok: Boolean, val message: String)

/** Registrace domácí zóny přes oficiální Android Geofencing API (Google Play služby). */
object GeofenceManager {
    private const val REQUEST_ID = "home_zone"
    const val ACTION_GEOFENCE = "cz.kutil.dochazka.GEOFENCE"
    const val MIN_RADIUS = 100f
    const val MAX_RADIUS = 500f

    fun hasFine(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isLocationEnabled
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 11,
        Intent(ctx, ZoneReceiver::class.java).setAction(ACTION_GEOFENCE),
        // Geofencing API do intentu doplňuje data události, proto musí být MUTABLE.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * Zaregistruje (nebo obnoví) zónu.
     * selfHeal = true: požádá o okamžité vyhodnocení podle toho, co si aplikace myslí –
     * tím se dožene případně ztracená událost (např. po restartu telefonu).
     */
    @SuppressLint("MissingPermission")
    suspend fun register(ctx: Context, selfHeal: Boolean): ZoneResult {
        val dao = Graph.realDb(ctx).dao()
        val zone = dao.zone() ?: return ZoneResult(false, "Domácí zóna ještě není nastavená.")
        if (!hasFine(ctx)) return ZoneResult(false, "Chybí oprávnění k přesné poloze.")
        if (!hasBackground(ctx)) return ZoneResult(false, "Chybí oprávnění k poloze „Povolit vždy“ – bez něj Android zónu na pozadí nesleduje.")
        if (!locationEnabled(ctx)) return ZoneResult(false, "Poloha je v telefonu vypnutá.")

        val state = runCatching { TrackState.valueOf(dao.settings()?.trackState ?: "HOME") }.getOrDefault(TrackState.HOME)
        val initial = when {
            !selfHeal -> 0
            state == TrackState.HOME || state == TrackState.RETURN_PENDING -> GeofencingRequest.INITIAL_TRIGGER_EXIT
            else -> GeofencingRequest.INITIAL_TRIGGER_ENTER
        }
        val fence = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(zone.latitude, zone.longitude, zone.radiusM.coerceIn(MIN_RADIUS, MAX_RADIUS))
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(60_000)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
        val request = GeofencingRequest.Builder().setInitialTrigger(initial).addGeofence(fence).build()
        val client = LocationServices.getGeofencingClient(ctx.applicationContext)
        return suspendCancellableCoroutine { cont ->
            try {
                client.addGeofences(request, pendingIntent(ctx))
                    .addOnSuccessListener { if (cont.isActive) cont.resume(ZoneResult(true, "Domácí zóna je aktivní.")) }
                    .addOnFailureListener { e -> if (cont.isActive) cont.resume(ZoneResult(false, describe(e))) }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(ZoneResult(false, describe(e)))
            }
        }
    }

    suspend fun unregister(ctx: Context): Boolean = suspendCancellableCoroutine { cont ->
        try {
            LocationServices.getGeofencingClient(ctx.applicationContext).removeGeofences(listOf(REQUEST_ID))
                .addOnCompleteListener { if (cont.isActive) cont.resume(it.isSuccessful) }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }

    fun describe(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
                "Geofencing není dostupný – zapni polohu (a přesnost polohy Google) v nastavení telefonu."
            GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
                "Chybí oprávnění k poloze „Povolit vždy“."
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> "Příliš mnoho zón."
            else -> "Registrace zóny selhala" + (code?.let { " (kód $it)." } ?: ".")
        }
    }

    /** Jednorázové zjištění aktuální polohy pro nastavení domácího bodu. Poloha se nikam neloguje. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ctx: Context): Location? {
        if (!hasFine(ctx)) return null
        val client = LocationServices.getFusedLocationProviderClient(ctx.applicationContext)
        val cts = CancellationTokenSource()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cts.cancel() }
            try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
