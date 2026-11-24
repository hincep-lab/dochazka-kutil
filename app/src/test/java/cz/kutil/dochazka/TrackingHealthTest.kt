package cz.kutil.dochazka

import cz.kutil.dochazka.ui.Health
import cz.kutil.dochazka.ui.HealthInput
import cz.kutil.dochazka.ui.PermStatus
import cz.kutil.dochazka.ui.TrackingHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/** Barvy ikon polohy a Wi-Fi musí odpovídat skutečnému stavu, ne jen zapnuté GPS. */
class TrackingHealthTest {
    private val okPerm = PermStatus(fine = true, background = true, notifications = true, locationOn = true, batteryUnrestricted = true)
    private val base = HealthInput(
        demo = false, trackingEnabled = true, zoneSet = true, zoneRadiusM = 200f, homeSsid = "Doma",
        perm = okPerm, zoneOkAt = 1000, zoneErrorAt = 0, zoneErrorText = "", lastExitAt = 2000, lastEnterAt = 3000,
        wifiConnected = true, currentSsid = "Doma",
    )

    @Test fun vseVPoradku() {
        assertEquals(Health.OK, TrackingHealth.location(base).health)
        assertEquals(Health.OK, TrackingHealth.wifi(base).health)
    }

    @Test fun vypnuteSledovaniJeSede() {
        assertEquals(Health.OFF, TrackingHealth.location(base.copy(trackingEnabled = false)).health)
        assertEquals(Health.OFF, TrackingHealth.wifi(base.copy(trackingEnabled = false)).health)
    }

    @Test fun zapnutaGpsBezRegistraceZonyNeniZelena() {
        assertEquals(Health.LIMITED, TrackingHealth.location(base.copy(zoneOkAt = 0)).health)
    }

    @Test fun neoverenoSkutecnymOdchodemJeOranzove() {
        assertEquals(Health.LIMITED, TrackingHealth.location(base.copy(lastExitAt = 0, lastEnterAt = 0)).health)
    }

    @Test fun chybejiciOpravneniJeCervene() {
        assertEquals(Health.BROKEN, TrackingHealth.location(base.copy(perm = okPerm.copy(background = false))).health)
        assertEquals(Health.BROKEN, TrackingHealth.location(base.copy(perm = okPerm.copy(locationOn = false))).health)
        assertEquals(Health.BROKEN, TrackingHealth.location(base.copy(zoneSet = false)).health)
    }

    @Test fun novejsiChybaRegistraceJeCervena() {
        assertEquals(Health.BROKEN, TrackingHealth.location(base.copy(zoneErrorAt = 5000, zoneErrorText = "1000")).health)
        assertEquals(Health.OK, TrackingHealth.location(base.copy(zoneErrorAt = 500, zoneOkAt = 1000)).health)
    }

    @Test fun omezeniBaterieJeOranzove() {
        assertEquals(Health.LIMITED, TrackingHealth.location(base.copy(perm = okPerm.copy(batteryUnrestricted = false))).health)
    }

    @Test fun wifi() {
        assertEquals(Health.OFF, TrackingHealth.wifi(base.copy(homeSsid = "")).health)
        assertEquals(Health.OFF, TrackingHealth.wifi(base.copy(wifiConnected = false)).health)
        assertEquals(Health.LIMITED, TrackingHealth.wifi(base.copy(currentSsid = null)).health)
        assertEquals(Health.OK, TrackingHealth.wifi(base.copy(currentSsid = "Kavarna")).health)
    }
}
