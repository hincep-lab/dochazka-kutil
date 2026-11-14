package cz.kutil.dochazka.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Záloha a obnova v JSON (verzované schéma). Soubor si uživatel ukládá sám přes systémový dialog
 * (Storage Access Framework); aplikace nic neodesílá.
 *
 * Formát verze 2: obálka { app, schema, exportedAt, sha256, data }, kde „data“ je text s vlastním
 * obsahem zálohy a „sha256“ jeho otisk. Při obnově se otisk přepočítá – poškozený nebo ručně
 * upravený soubor se odmítne dřív, než se sáhne na stávající data. Starší zálohy verze 1 (bez otisku) jdou obnovit také.
 */
object Backup {
    const val SCHEMA = 2
    const val APP = "cz.kutil.dochazka"

    class BackupException(message: String) : Exception(message)

    data class Summary(val shifts: Int, val pending: Int, val overrides: Int, val exportedAt: Long, val prefs: JSONObject?)

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun JSONObject.putOpt2(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun export(db: AppDatabase, prefs: JSONObject? = null): String {
        val dao = db.dao()
        val data = JSONObject()
        data.put("timeZone", "Europe/Prague")

        val s = dao.settings() ?: SettingsEntity()
        data.put(
            "settings",
            JSONObject().put("organization", s.organization).put("employee", s.employee)
                .put("onboardingDone", s.onboardingDone).put("trackingEnabled", s.trackingEnabled)
                .put("trackState", s.trackState).putOpt2("lastTransitionExit", s.lastTransitionExit)
                .putOpt2("lastTransitionAt", s.lastTransitionAt),
        )
        if (prefs != null) data.put("prefs", prefs)
        dao.zone()?.let { z ->
            data.put(
                "homeZone",
                JSONObject().put("latitude", z.latitude).put("longitude", z.longitude)
                    .put("radiusM", z.radiusM.toDouble()).put("wifiSsid", z.wifiSsid),
            )
        }
        val shifts = JSONArray()
        for (x in dao.allShifts()) {
            shifts.put(
                JSONObject().put("id", x.id).put("rawStart", x.rawStart).putOpt2("rawEnd", x.rawEnd)
                    .putOpt2("editedStart", x.editedStart).putOpt2("editedEnd", x.editedEnd)
                    .put("origin", x.origin).put("status", x.status).put("note", x.note)
                    .put("createdAt", x.createdAt).put("updatedAt", x.updatedAt),
            )
        }
        data.put("shifts", shifts)
        val pending = JSONArray()
        for (p in dao.allPending()) {
            pending.put(
                JSONObject().put("id", p.id).put("type", p.type).put("eventDetectedAt", p.eventDetectedAt)
                    .put("promptCreatedAt", p.promptCreatedAt).putOpt2("answeredAt", p.answeredAt)
                    .put("status", p.status).putOpt2("pairedEndAt", p.pairedEndAt).putOpt2("shiftId", p.shiftId)
                    .put("deferred", p.deferred).put("source", p.source).put("confidence", p.confidence)
                    .putOpt2("accuracyM", p.accuracyM?.toDouble()).putOpt2("adjustedAt", p.adjustedAt).put("note", p.note),
            )
        }
        data.put("pendingEvents", pending)
        val overrides = JSONArray()
        for (o in dao.allOverrides()) {
            overrides.put(JSONObject().put("date", o.date).put("type", o.type).put("note", o.note))
        }
        data.put("dayOverrides", overrides)

        val text = data.toString()
        return JSONObject()
            .put("app", APP)
            .put("schema", SCHEMA)
            .put("exportedAt", System.currentTimeMillis())
            .put("sha256", sha256(text))
            .put("data", text)
            .toString(2)
    }

    /** Ověří soubor (formát, verzi, otisk) a vrátí jeho obsah. Do databáze nesahá. */
    fun parse(json: String): Pair<JSONObject, Long> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw BackupException("Soubor není platná záloha (nejde přečíst jako JSON).")
        }
        if (root.optString("app") != APP) throw BackupException("Soubor není záloha aplikace Docházka Kutil.")
        val schema = root.optInt("schema", -1)
        val exportedAt = root.optLong("exportedAt", 0)
        return when (schema) {
            1 -> root to exportedAt
            2 -> {
                val text = root.optString("data", "")
                val expected = root.optString("sha256", "")
                if (text.isEmpty() || expected.isEmpty()) throw BackupException("Záloha je neúplná – chybí data nebo kontrolní otisk.")
                if (!sha256(text).equals(expected, ignoreCase = true)) {
                    throw BackupException("Záloha je poškozená nebo byla ručně upravena (nesouhlasí kontrolní otisk). Nic se neobnovilo.")
                }
                val data = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw BackupException("Záloha je poškozená (obsah nejde přečíst).")
                }
                data to exportedAt
            }
            else -> throw BackupException("Nepodporovaná verze zálohy: $schema. Aktualizuj aplikaci.")
        }
    }

    /** Přehled obsahu zálohy pro potvrzovací dialog – bez zásahu do dat. */
    fun inspect(json: String): Summary {
        val (data, exportedAt) = parse(json)
        return Summary(
            shifts = data.optJSONArray("shifts")?.length() ?: 0,
            pending = data.optJSONArray("pendingEvents")?.length() ?: 0,
            overrides = data.optJSONArray("dayOverrides")?.length() ?: 0,
            exportedAt = exportedAt,
            prefs = data.optJSONObject("prefs"),
        )
    }

    /**
     * Nahradí veškerá data obsahem zálohy. Nejdřív se celý soubor ověří a přečte; do databáze se
     * zapisuje v jediné transakci, takže při chybě zůstanou původní data beze změny.
     */
    suspend fun restore(db: AppDatabase, json: String): Summary {
        val summary = inspect(json)
        val (data, _) = parse(json)

        val shiftList = ArrayList<ShiftEntity>()
        val shifts = data.optJSONArray("shifts") ?: JSONArray()
        for (i in 0 until shifts.length()) {
            val x = shifts.getJSONObject(i)
            val rawEnd = x.optLongOrNull("rawEnd")
            shiftList.add(
                ShiftEntity(
                    id = x.optLong("id", 0),
                    rawStart = x.getLong("rawStart"),
                    rawEnd = rawEnd,
                    editedStart = x.optLongOrNull("editedStart"),
                    editedEnd = x.optLongOrNull("editedEnd"),
                    origin = x.optString("origin", Origin.MANUAL),
                    status = x.optString("status", if (rawEnd == null) ShiftStatus.OPEN else ShiftStatus.CLOSED),
                    note = x.optString("note", ""),
                    createdAt = x.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = x.optLong("updatedAt", System.currentTimeMillis()),
                )
            )
        }
        val pendingList = ArrayList<PendingEventEntity>()
        val pending = data.optJSONArray("pendingEvents") ?: JSONArray()
        for (i in 0 until pending.length()) {
            val p = pending.getJSONObject(i)
            pendingList.add(
                PendingEventEntity(
                    id = p.optLong("id", 0),
                    type = p.getString("type"),
                    eventDetectedAt = p.getLong("eventDetectedAt"),
                    promptCreatedAt = p.optLong("promptCreatedAt", p.getLong("eventDetectedAt")),
                    answeredAt = p.optLongOrNull("answeredAt"),
                    status = p.optString("status", "PENDING"),
                    pairedEndAt = p.optLongOrNull("pairedEndAt"),
                    shiftId = p.optLongOrNull("shiftId"),
                    // Ověřování, které běželo v okamžiku zálohy, už nedoběhne – návrh se ukáže rovnou.
                    deferred = false,
                    source = p.optString("source", EventSource.GPS),
                    confidence = p.optString("confidence", Confidence.CERTAIN),
                    accuracyM = if (p.isNull("accuracyM") || !p.has("accuracyM")) null else p.getDouble("accuracyM").toFloat(),
                    adjustedAt = p.optLongOrNull("adjustedAt"),
                    note = p.optString("note", ""),
                )
            )
        }
        val overrideList = ArrayList<DayOverrideEntity>()
        val overrides = data.optJSONArray("dayOverrides") ?: JSONArray()
        for (i in 0 until overrides.length()) {
            val o = overrides.getJSONObject(i)
            overrideList.add(DayOverrideEntity(o.getString("date"), o.getString("type"), o.optString("note", "")))
        }
        val zone = data.optJSONObject("homeZone")?.let { z ->
            HomeZoneEntity(
                latitude = z.getDouble("latitude"),
                longitude = z.getDouble("longitude"),
                radiusM = z.optDouble("radiusM", 200.0).toFloat(),
                wifiSsid = z.optString("wifiSsid", ""),
            )
        }
        val s = data.optJSONObject("settings")
        val hasOpen = shiftList.any { it.status == ShiftStatus.OPEN }

        val dao = db.dao()
        db.withTransaction {
            dao.deleteAllShifts()
            dao.deleteAllPending()
            dao.deleteAllOverrides()
            dao.deleteZone()
            val current = dao.settings() ?: SettingsEntity()
            dao.upsertSettings(
                current.copy(
                    organization = s?.optString("organization", current.organization) ?: current.organization,
                    employee = s?.optString("employee", current.employee) ?: current.employee,
                    onboardingDone = s?.optBoolean("onboardingDone", current.onboardingDone) ?: current.onboardingDone,
                    trackingEnabled = s?.optBoolean("trackingEnabled", false) ?: false,
                    // Stav se srovná podle skutečnosti: otevřená směna = pracuji, jinak doma.
                    trackState = if (hasOpen) "WORKING" else "HOME",
                    lastTransitionExit = null,
                    lastTransitionAt = null,
                    lastDiagnostic = "Data obnovena ze zálohy.",
                    lastDiagnosticAt = System.currentTimeMillis(),
                )
            )
            zone?.let { dao.upsertZone(it) }
            shiftList.forEach { dao.insertShift(it) }
            pendingList.forEach { dao.insertPending(it) }
            overrideList.forEach { dao.upsertOverride(it) }
        }
        return summary
    }
}
