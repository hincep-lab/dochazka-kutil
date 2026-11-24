package cz.kutil.dochazka.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import cz.kutil.dochazka.data.AppPrefs
import cz.kutil.dochazka.track.WifiHelper

/**
 * Skutečný stav automatického sledování pro ikony na hlavní obrazovce.
 *
 * Zelená poloha neznamená „GPS je zapnutá“, ale: zóna je Androidem potvrzeně zaregistrovaná,
 * aplikace má potřebná oprávnění a sledování už aspoň jednou zaznamenalo odchod nebo návrat.
 */
enum class Health(val color: Color, val label: String) {
    OK(Color(0xFF2E9A35), "funguje"),
    LIMITED(Color(0xFFE08600), "omezená spolehlivost"),
    BROKEN(Color(0xFFD32F2F), "nefunguje"),
    OFF(Color(0xFF8A96A6), "vypnuto / nelze zjistit"),
}

data class HealthItem(val health: Health, val title: String, val detail: String)

/** Vše, co se pro vyhodnocení čte ze systému a z uložených výsledků – jedno místo, snadno testovatelné. */
data class HealthInput(
    val demo: Boolean,
    val trackingEnabled: Boolean,
    val zoneSet: Boolean,
    val zoneRadiusM: Float,
    val homeSsid: String,
    val perm: PermStatus,
    val zoneOkAt: Long,
    val zoneErrorAt: Long,
    val zoneErrorText: String,
    val lastExitAt: Long,
    val lastEnterAt: Long,
    val wifiConnected: Boolean,
    val currentSsid: String?,
)

fun readHealthInput(ctx: Context, state: UiState, perm: PermStatus): HealthInput {
    val err = AppPrefs.lastZoneError(ctx)
    return HealthInput(
        demo = state.demo,
        trackingEnabled = state.settings.trackingEnabled,
        zoneSet = state.zone != null,
        zoneRadiusM = state.zone?.radiusM ?: 0f,
        homeSsid = state.zone?.wifiSsid.orEmpty(),
        perm = perm,
        zoneOkAt = AppPrefs.lastZoneOkAt(ctx),
        zoneErrorAt = err.first,
        zoneErrorText = err.second,
        lastExitAt = AppPrefs.lastExitAt(ctx),
        lastEnterAt = AppPrefs.lastEnterAt(ctx),
        wifiConnected = WifiHelper.isWifiConnected(ctx),
        currentSsid = WifiHelper.currentSsid(ctx),
    )
}

object TrackingHealth {
    fun location(i: HealthInput): HealthItem {
        val t = "Poloha (domácí zóna)"
        return when {
            i.demo -> HealthItem(Health.OFF, t, "Demo režim – skutečné sledování se nezobrazuje.")
            !i.trackingEnabled -> HealthItem(Health.OFF, t, "Automatické sledování je vypnuté, docházku zapisuješ ručně.")
            !i.zoneSet -> HealthItem(Health.BROKEN, t, "Není nastavená domácí zóna. Nastav ji v Nastavení → Domácí zóna.")
            !i.perm.fine || !i.perm.background ->
                HealthItem(Health.BROKEN, t, "Chybí oprávnění k poloze „Povolit vždy“. Bez něj Android odchod z domova aplikaci neoznámí.")
            !i.perm.locationOn -> HealthItem(Health.BROKEN, t, "Poloha v telefonu je vypnutá.")
            i.zoneErrorAt > 0 && i.zoneErrorAt >= i.zoneOkAt ->
                HealthItem(Health.BROKEN, t, "Android odmítl registraci zóny: ${i.zoneErrorText}")
            i.zoneOkAt <= 0 -> HealthItem(Health.LIMITED, t, "Android zatím nepotvrdil registraci domácí zóny.")
            !i.perm.notifications -> HealthItem(Health.LIMITED, t, "Oznámení jsou vypnutá – dotazy na začátek a konec práce nepřijdou.")
            !i.perm.batteryUnrestricted -> HealthItem(Health.LIMITED, t, "Úspora baterie může odchody a návraty zpozdit nebo vynechat.")
            i.zoneRadiusM < 150f -> HealthItem(Health.LIMITED, t, "Domácí zóna má jen ${i.zoneRadiusM.toInt()} m – hrozí falešné odchody. Doporučeno 150–250 m.")
            i.lastExitAt <= 0 && i.lastEnterAt <= 0 ->
                HealthItem(Health.LIMITED, t, "Zóna je zaregistrovaná, ale zatím nebyl zaznamenán žádný odchod ani návrat – funkčnost ještě není ověřená.")
            else -> HealthItem(
                Health.OK, t,
                "Zóna zaregistrovaná (${Fmt.dateTime(i.zoneOkAt)}), oprávnění v pořádku. Poslední odchod ${whenText(i.lastExitAt)}, návrat ${whenText(i.lastEnterAt)}.",
            )
        }
    }

    fun wifi(i: HealthInput): HealthItem {
        val t = "Domácí Wi-Fi (ověření návratu)"
        return when {
            i.demo -> HealthItem(Health.OFF, t, "Demo režim.")
            !i.trackingEnabled -> HealthItem(Health.OFF, t, "Automatické sledování je vypnuté.")
            i.homeSsid.isBlank() -> HealthItem(Health.OFF, t, "Domácí síť není nastavená – návrat se ověřuje jen polohou.")
            !i.wifiConnected -> HealthItem(Health.OFF, t, "Telefon teď není připojený k Wi-Fi – nelze zjistit. To je mimo domov normální.")
            i.currentSsid == null -> HealthItem(Health.LIMITED, t, "Android neprozradil název připojené sítě (chybí oprávnění k poloze?) – návrat nepůjde ověřit Wi-Fi.")
            i.currentSsid == i.homeSsid -> HealthItem(Health.OK, t, "Připojeno k domácí síti „${i.homeSsid}“.")
            else -> HealthItem(Health.OK, t, "Připojeno k jiné síti („${i.currentSsid}“). Domácí síť „${i.homeSsid}“ se kontroluje při návratu.")
        }
    }

    private fun whenText(ms: Long) = if (ms > 0) Fmt.dateTime(ms) else "–"
}
