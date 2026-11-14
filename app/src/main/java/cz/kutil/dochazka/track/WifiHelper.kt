package cz.kutil.dochazka.track

import android.content.Context
import android.net.wifi.WifiManager
import cz.kutil.dochazka.data.Graph

/**
 * Domácí Wi-Fi je jen podpůrný signál. Android čtení názvu sítě omezuje (vyžaduje polohu,
 * na pozadí často vrací „neznámé“), proto: null = nevíme. Nepřipojená/neznámá síť
 * se NIKDY nebere jako důkaz, že uživatel doma není.
 */
object WifiHelper {
    @Suppress("DEPRECATION")
    fun currentSsid(ctx: Context): String? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val raw = wm.connectionInfo?.ssid ?: return null
            if (raw.isBlank() || raw == "<unknown ssid>" || raw == "0x") null else raw.removeSurrounding("\"")
        } catch (e: Exception) {
            null
        }
    }

    /** true = právě připojen k domácí síti; null = neznámo nebo domácí síť není nastavena. */
    suspend fun isOnHomeWifi(ctx: Context): Boolean? {
        val home = Graph.realDb(ctx).dao().zone()?.wifiSsid?.takeIf { it.isNotBlank() } ?: return null
        val current = currentSsid(ctx) ?: return null
        return current == home
    }
}
