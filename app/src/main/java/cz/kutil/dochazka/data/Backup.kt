package cz.kutil.dochazka.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Záloha a obnova v JSON (verzované schéma). Soubor si uživatel ukládá sám přes systémový dialog;
 * aplikace nic neodesílá. Záloha obsahuje i domácí zónu – je to soukromý soubor uživatele.
 */
object Backup {
    const val SCHEMA = 1
    const val APP = "cz.kutil.dochazka"

    class BackupException(message: String) : Exception(message)

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun JSONObject.putOpt2(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    suspend fun export(db: AppDatabase): String {
        val dao = db.dao()
        val root = JSONObject()
        root.put("app", APP)
        root.put("schema", SCHEMA)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("timeZone", "Europe/Prague")

        val s = dao.settings() ?: SettingsEntity()
        root.put(
            "settings",
            JSONObject().put("organization", s.organization).put("employee", s.employee)
                .put("onboardingDone", s.onboardingDone).put("trackingEnabled", s.trackingEnabled),
        )
        dao.zone()?.let { z ->
            root.put(
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
        root.put("shifts", shifts)
        val pending = JSONArray()
        for (p in dao.allPending()) {
            pending.put(
                JSONObject().put("id", p.id).put("type", p.type).put("eventDetectedAt", p.eventDetectedAt)
                    .put("promptCreatedAt", p.promptCreatedAt).putOpt2("answeredAt", p.answeredAt)
                    .put("status", p.status).putOpt2("pairedEndAt", p.pairedEndAt).putOpt2("shiftId", p.shiftId)
                    .put("deferred", p.deferred),
            )
        }
        root.put("pendingEvents", pending)
        val overrides = JSONArray()
        for (o in dao.allOverrides()) {
            overrides.put(JSONObject().put("date", o.date).put("type", o.type).put("note", o.note))
        }
        root.put("dayOverrides", overrides)
        return root.toString(2)
    }

    /** Nahradí veškerá data obsahem zálohy. Stav sledování se vrací do výchozího (HOME). */
    suspend fun restore(db: AppDatabase, json: String): Int {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw BackupException("Soubor není platná záloha (JSON).")
        }
        if (root.optString("app") != APP) throw BackupException("Soubor není záloha aplikace Docházka Kutil.")
        val schema = root.optInt("schema", -1)
        if (schema < 1 || schema > SCHEMA) throw BackupException("Nepodporovaná verze zálohy: $schema.")

        val dao = db.dao()
        var count = 0
        db.withTransaction {
            dao.deleteAllShifts()
            dao.deleteAllPending()
            dao.deleteAllOverrides()
            dao.deleteZone()

            val s = root.optJSONObject("settings")
            val current = dao.settings() ?: SettingsEntity()
            dao.upsertSettings(
                current.copy(
                    organization = s?.optString("organization", current.organization) ?: current.organization,
                    employee = s?.optString("employee", current.employee) ?: current.employee,
                    onboardingDone = s?.optBoolean("onboardingDone", current.onboardingDone) ?: current.onboardingDone,
                    trackingEnabled = s?.optBoolean("trackingEnabled", false) ?: false,
                    trackState = "HOME",
                    lastTransitionExit = null,
                    lastTransitionAt = null,
                    lastDiagnostic = "Data obnovena ze zálohy.",
                    lastDiagnosticAt = System.currentTimeMillis(),
                )
            )
            root.optJSONObject("homeZone")?.let { z ->
                dao.upsertZone(
                    HomeZoneEntity(
                        latitude = z.getDouble("latitude"),
                        longitude = z.getDouble("longitude"),
                        radiusM = z.optDouble("radiusM", 200.0).toFloat(),
                        wifiSsid = z.optString("wifiSsid", ""),
                    )
                )
            }
            val shifts = root.optJSONArray("shifts") ?: JSONArray()
            for (i in 0 until shifts.length()) {
                val x = shifts.getJSONObject(i)
                dao.insertShift(
                    ShiftEntity(
                        id = x.optLong("id", 0),
                        rawStart = x.getLong("rawStart"),
                        rawEnd = x.optLongOrNull("rawEnd"),
                        editedStart = x.optLongOrNull("editedStart"),
                        editedEnd = x.optLongOrNull("editedEnd"),
                        origin = x.optString("origin", Origin.MANUAL),
                        status = x.optString("status", if (x.optLongOrNull("rawEnd") == null) ShiftStatus.OPEN else ShiftStatus.CLOSED),
                        note = x.optString("note", ""),
                        createdAt = x.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = x.optLong("updatedAt", System.currentTimeMillis()),
                    )
                )
                count++
            }
            val pending = root.optJSONArray("pendingEvents") ?: JSONArray()
            for (i in 0 until pending.length()) {
                val p = pending.getJSONObject(i)
                dao.insertPending(
                    PendingEventEntity(
                        id = p.optLong("id", 0),
                        type = p.getString("type"),
                        eventDetectedAt = p.getLong("eventDetectedAt"),
                        promptCreatedAt = p.optLong("promptCreatedAt", p.getLong("eventDetectedAt")),
                        answeredAt = p.optLongOrNull("answeredAt"),
                        status = p.optString("status", "PENDING"),
                        pairedEndAt = p.optLongOrNull("pairedEndAt"),
                        shiftId = p.optLongOrNull("shiftId"),
                        deferred = false,
                    )
                )
            }
            val overrides = root.optJSONArray("dayOverrides") ?: JSONArray()
            for (i in 0 until overrides.length()) {
                val o = overrides.getJSONObject(i)
                dao.upsertOverride(DayOverrideEntity(o.getString("date"), o.getString("type"), o.optString("note", "")))
            }
        }
        return count
    }
}
