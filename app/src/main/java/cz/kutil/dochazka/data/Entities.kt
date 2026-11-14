package cz.kutil.dochazka.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import cz.kutil.dochazka.core.PendingInfo
import cz.kutil.dochazka.core.PendingType
import cz.kutil.dochazka.core.ShiftData
import java.time.Instant

object Origin {
    const val GEOFENCE = "GEOFENCE"
    const val MANUAL = "MANUAL"
    const val DEMO = "DEMO"
}

object EventSource {
    const val GPS = "GPS"
    const val GPS_WIFI = "GPS+WIFI"
}

object Confidence {
    const val CERTAIN = "CERTAIN"
    const val UNCERTAIN = "UNCERTAIN"
    const val CONFLICT = "CONFLICT"
}

object ShiftStatus {
    const val OPEN = "OPEN"
    const val CLOSED = "CLOSED"
}

/** Pracovní interval. Časy jsou epoch ms (Instant); raw = zaznamenané, edited = ruční oprava. */
@Entity(tableName = "shifts")
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawStart: Long,
    val rawEnd: Long?,
    val editedStart: Long? = null,
    val editedEnd: Long? = null,
    val origin: String = Origin.MANUAL,
    val status: String = ShiftStatus.CLOSED,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toData() = ShiftData(
        id = id,
        rawStart = Instant.ofEpochMilli(rawStart),
        rawEnd = rawEnd?.let { Instant.ofEpochMilli(it) },
        editedStart = editedStart?.let { Instant.ofEpochMilli(it) },
        editedEnd = editedEnd?.let { Instant.ofEpochMilli(it) },
        note = note,
    )
}

/**
 * Návrh čekající na potvrzení. Odděluje čas zjištění události (eventDetectedAt),
 * čas vytvoření dotazu (promptCreatedAt) a čas odpovědi (answeredAt).
 */
@Entity(tableName = "pending_events")
data class PendingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val eventDetectedAt: Long,
    val promptCreatedAt: Long,
    val answeredAt: Long? = null,
    val status: String = "PENDING",
    val pairedEndAt: Long? = null,
    val shiftId: Long? = null,
    val deferred: Boolean = false,
    /** Zdroj informace: GPS, GPS+WIFI. */
    val source: String = EventSource.GPS,
    /** CERTAIN = jistá, UNCERTAIN = nejistá (nepřesná poloha / neověřeno), CONFLICT = v rozporu s dalším vývojem. */
    val confidence: String = Confidence.CERTAIN,
    /** Přesnost polohy, která událost spustila (metry), pokud ji Android poskytl. */
    val accuracyM: Float? = null,
    /** Čas, který uživatel ručně nastavil místo zjištěného (původní eventDetectedAt se nepřepisuje). */
    val adjustedAt: Long? = null,
    /** Lidsky čitelné vysvětlení (proč je událost nejistá nebo konfliktní). */
    val note: String = "",
) {
    /** Čas, se kterým se počítá: ruční úprava má přednost před zjištěným časem. */
    val effectiveAt: Long get() = adjustedAt ?: eventDetectedAt

    fun toInfo() = PendingInfo(
        id = id,
        type = PendingType.valueOf(type),
        detectedAt = Instant.ofEpochMilli(effectiveAt),
        pairedEndAt = pairedEndAt?.let { Instant.ofEpochMilli(it) },
        shiftId = shiftId,
        deferred = deferred,
    )
}

/** Domácí zóna – jediný řádek. Heslo Wi-Fi se nikdy neukládá, jen název sítě. */
@Entity(tableName = "home_zone")
data class HomeZoneEntity(
    @PrimaryKey val id: Int = 1,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Float = 200f,
    val wifiSsid: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val organization: String = "Vodoinstalace Kutil s.r.o.",
    val employee: String = "Hink",
    val onboardingDone: Boolean = false,
    val trackingEnabled: Boolean = false,
    val trackState: String = "HOME",
    val lastTransitionExit: Boolean? = null,
    val lastTransitionAt: Long? = null,
    val lastDiagnostic: String = "",
    val lastDiagnosticAt: Long = 0,
)

/** Ruční označení dne (dovolená, nemoc, lékař…). Datum jako ISO text (yyyy-MM-dd). */
@Entity(tableName = "day_overrides")
data class DayOverrideEntity(
    @PrimaryKey val date: String,
    val type: String,
    val note: String = "",
)
