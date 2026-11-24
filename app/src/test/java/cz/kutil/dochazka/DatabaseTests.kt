package cz.kutil.dochazka

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.data.AppDatabase
import cz.kutil.dochazka.data.Backup
import cz.kutil.dochazka.data.Confidence
import cz.kutil.dochazka.data.DayOverrideEntity
import cz.kutil.dochazka.data.EventSource
import cz.kutil.dochazka.data.Graph
import cz.kutil.dochazka.data.HomeZoneEntity
import cz.kutil.dochazka.data.Origin
import cz.kutil.dochazka.data.PendingEventEntity
import cz.kutil.dochazka.data.SettingsEntity
import cz.kutil.dochazka.data.ShiftEntity
import cz.kutil.dochazka.data.ShiftStatus
import cz.kutil.dochazka.track.EventMeta
import cz.kutil.dochazka.track.TrackingEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime

private fun at(d: Int, h: Int, m: Int): Long = LocalDateTime.of(2026, 11, d, h, m).atZone(PRAGUE).toInstant().toEpochMilli()

/**
 * Testy nad skutečnou Room databází (SQLite přes Robolectric): migrace schématu, záloha a obnova,
 * chování po „restartu“ (zavření a nové otevření databáze ze souboru).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DatabaseTests {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After fun cleanup() {
        Graph.closeAll()
        ctx.deleteDatabase("dochazka.db")
        ctx.deleteDatabase("migrace_test.db")
    }

    // ------------------------------------------------------------------------------------
    // Migrace 1 -> 2 (uživatel aktualizuje z verze 8, data musí zůstat)
    // ------------------------------------------------------------------------------------

    private val v1Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `shifts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `rawStart` INTEGER NOT NULL, `rawEnd` INTEGER, `editedStart` INTEGER, `editedEnd` INTEGER, `origin` TEXT NOT NULL, `status` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `pending_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `eventDetectedAt` INTEGER NOT NULL, `promptCreatedAt` INTEGER NOT NULL, `answeredAt` INTEGER, `status` TEXT NOT NULL, `pairedEndAt` INTEGER, `shiftId` INTEGER, `deferred` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `home_zone` (`id` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `radiusM` REAL NOT NULL, `wifiSsid` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `settings` (`id` INTEGER NOT NULL, `organization` TEXT NOT NULL, `employee` TEXT NOT NULL, `onboardingDone` INTEGER NOT NULL, `trackingEnabled` INTEGER NOT NULL, `trackState` TEXT NOT NULL, `lastTransitionExit` INTEGER, `lastTransitionAt` INTEGER, `lastDiagnostic` TEXT NOT NULL, `lastDiagnosticAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `day_overrides` (`date` TEXT NOT NULL, `type` TEXT NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`date`))",
    )

    @Test fun migraceZVerze1ZachovaDataADoplniNoveSloupce() = runBlocking {
        val name = "migrace_test.db"
        ctx.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    v1Schema.forEach { db.execSQL(it) }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        val old = helper.writableDatabase
        old.execSQL("INSERT INTO shifts (id, rawStart, rawEnd, editedStart, editedEnd, origin, status, note, createdAt, updatedAt) VALUES (1, ${at(12, 6, 52)}, ${at(12, 15, 41)}, NULL, ${at(12, 16, 0)}, 'GEOFENCE', 'CLOSED', 'pozn', 1, 1)")
        old.execSQL("INSERT INTO pending_events (id, type, eventDetectedAt, promptCreatedAt, answeredAt, status, pairedEndAt, shiftId, deferred) VALUES (1, 'START', ${at(13, 6, 53)}, ${at(13, 6, 54)}, NULL, 'PENDING', NULL, NULL, 0)")
        old.execSQL("INSERT INTO settings (id, organization, employee, onboardingDone, trackingEnabled, trackState, lastTransitionExit, lastTransitionAt, lastDiagnostic, lastDiagnosticAt) VALUES (1, 'Vodoinstalace Kutil s.r.o.', 'Hink', 1, 1, 'OUTSIDE_PENDING', 1, ${at(13, 6, 53)}, '', 0)")
        old.execSQL("INSERT INTO day_overrides (date, type, note) VALUES ('2026-11-20', 'DOVOLENA', '')")
        helper.close()

        // Otevření novou verzí spustí migraci; Room poté ověří, že schéma přesně odpovídá entitám verze 2.
        val db = AppDatabase.build(ctx, name)
        val dao = db.dao()
        val shift = dao.allShifts().single()
        assertEquals(at(12, 6, 52), shift.rawStart)
        assertEquals(at(12, 16, 0), shift.editedEnd)
        val p = dao.openPending().single()
        assertEquals(at(13, 6, 53), p.eventDetectedAt)
        assertEquals(EventSource.GPS, p.source)
        assertEquals(Confidence.CERTAIN, p.confidence)
        assertNull(p.adjustedAt)
        assertNull(p.accuracyM)
        assertEquals("", p.note)
        assertEquals("Hink", dao.settings()!!.employee)
        assertEquals("DOVOLENA", dao.allOverrides().single().type)
        db.close()
    }

    // ------------------------------------------------------------------------------------
    // Záloha a obnova
    // ------------------------------------------------------------------------------------

    private suspend fun filled(): AppDatabase {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val dao = db.dao()
        dao.upsertSettings(SettingsEntity(organization = "Vodoinstalace Kutil s.r.o.", employee = "Hink", onboardingDone = true, trackingEnabled = true, trackState = "WORKING"))
        dao.upsertZone(HomeZoneEntity(latitude = 49.5, longitude = 13.5, radiusM = 220f, wifiSsid = "DomaWifi"))
        dao.insertShift(ShiftEntity(rawStart = at(12, 6, 52), rawEnd = at(12, 15, 41), editedEnd = at(12, 16, 0), origin = Origin.GEOFENCE, status = ShiftStatus.CLOSED, note = "ruční oprava konce"))
        dao.insertShift(ShiftEntity(rawStart = at(12, 17, 0), rawEnd = at(12, 19, 30), origin = Origin.MANUAL, status = ShiftStatus.CLOSED))
        dao.insertShift(ShiftEntity(rawStart = at(13, 6, 55), rawEnd = null, origin = Origin.GEOFENCE, status = ShiftStatus.OPEN))
        dao.insertPending(
            PendingEventEntity(
                type = "END", eventDetectedAt = at(13, 15, 28), promptCreatedAt = at(13, 15, 29), shiftId = 3,
                source = EventSource.GPS_WIFI, confidence = Confidence.UNCERTAIN, accuracyM = 85f, adjustedAt = at(13, 15, 30), note = "Nepřesná poloha (±85 m).",
            )
        )
        dao.insertPending(PendingEventEntity(type = "START", eventDetectedAt = at(10, 9, 0), promptCreatedAt = at(10, 9, 1), answeredAt = at(10, 9, 5), status = "NO"))
        dao.upsertOverride(DayOverrideEntity("2026-11-20", "DOVOLENA", ""))
        return db
    }

    @Test fun zalohaAObnovaVratiVsechnaData() = runBlocking {
        val source = filled()
        val json = Backup.export(source, JSONObject().put("longShiftHours", 10).put("reportEmail", "ucetni@example.cz"))

        val target = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        target.dao().insertShift(ShiftEntity(rawStart = 1, rawEnd = 2)) // stará data, která se mají přepsat
        val summary = Backup.restore(target, json)

        assertEquals(3, summary.shifts)
        assertEquals(2, summary.pending)
        assertEquals(1, summary.overrides)
        assertEquals(10, summary.prefs!!.getInt("longShiftHours"))
        val a = source.dao()
        val b = target.dao()
        // pracovní intervaly včetně ručních oprav a poznámek
        assertEquals(a.allShifts().map { listOf(it.id, it.rawStart, it.rawEnd, it.editedStart, it.editedEnd, it.origin, it.status, it.note) },
            b.allShifts().map { listOf(it.id, it.rawStart, it.rawEnd, it.editedStart, it.editedEnd, it.origin, it.status, it.note) })
        // nevyřízené i odmítnuté události včetně zdroje, jistoty a upraveného času
        assertEquals(a.allPending().map { it.copy() }, b.allPending())
        assertEquals(1, b.openPending().size)
        assertEquals(at(13, 15, 30), b.openPending().single().effectiveAt)
        assertEquals("NO", b.allPending().first { it.type == "START" }.status)
        assertEquals(a.allOverrides(), b.allOverrides())
        assertEquals(220f, b.zone()!!.radiusM)
        assertEquals("DomaWifi", b.zone()!!.wifiSsid)
        val s = b.settings()!!
        assertEquals("Hink", s.employee)
        assertTrue(s.trackingEnabled)
        assertEquals("WORKING", s.trackState) // v záloze je otevřená směna
        assertNotNull(b.openShift())
        source.close()
        target.close()
    }

    @Test fun poskozenaZalohaSeOdmitneADataZustanou() = runBlocking {
        val source = filled()
        val json = Backup.export(source)
        val target = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        target.dao().insertShift(ShiftEntity(rawStart = 111, rawEnd = 222))

        val tampered = json.replace("Hink", "Honk")
        assertTrue(tampered != json)
        for (bad in listOf(tampered, json.dropLast(40), "tohle není záloha", "{\"app\":\"jina.aplikace\",\"schema\":2}", json.replace("\"schema\": 2", "\"schema\": 99"))) {
            try {
                Backup.restore(target, bad)
                fail("Poškozená záloha nesmí projít")
            } catch (e: Backup.BackupException) {
                // očekáváno
            }
        }
        // původní data jsou nedotčená
        assertEquals(111L, target.dao().allShifts().single().rawStart)
        // nepoškozený soubor projde kontrolou
        assertEquals(3, Backup.inspect(json).shifts)
        source.close()
        target.close()
    }

    @Test fun starsiZalohaVerze1JdeObnovit() = runBlocking {
        val v1 = JSONObject()
            .put("app", Backup.APP).put("schema", 1).put("exportedAt", 5)
            .put("settings", JSONObject().put("organization", "Vodoinstalace Kutil s.r.o.").put("employee", "Hink").put("onboardingDone", true).put("trackingEnabled", false))
            .put("shifts", org.json.JSONArray().put(JSONObject().put("id", 7).put("rawStart", at(2, 7, 0)).put("rawEnd", at(2, 15, 30)).put("origin", "MANUAL").put("status", "CLOSED").put("note", "")))
            .put("pendingEvents", org.json.JSONArray()).put("dayOverrides", org.json.JSONArray())
            .toString()
        val target = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        assertEquals(1, Backup.restore(target, v1).shifts)
        assertEquals(at(2, 15, 30), target.dao().allShifts().single().rawEnd)
        target.close()
    }

    // ------------------------------------------------------------------------------------
    // Sledování nad skutečnou databází: restart, duplicity, čas události
    // ------------------------------------------------------------------------------------

    @Test fun nevyrizenyNavrhPrezijeRestartAPouzijeCasUdalosti() = runBlocking {
        ctx.deleteDatabase("dochazka.db")
        Graph.realDb(ctx).dao().upsertSettings(SettingsEntity(trackingEnabled = true))
        val exit = Instant.ofEpochMilli(at(12, 6, 53))
        TrackingEngine.handle(ctx, TrackEvent.GeoExit(exit), meta = EventMeta(source = EventSource.GPS_WIFI, accuracyM = 18f))
        TrackingEngine.handle(ctx, TrackEvent.GeoExit(exit.plusSeconds(40))) // duplicitní doručení
        assertEquals(1, Graph.realDb(ctx).dao().openPending().size)

        Graph.closeAll() // „restart telefonu“ – databáze se otevře znovu ze souboru

        val dao = Graph.realDb(ctx).dao()
        val p = dao.openPending().single()
        assertEquals(EventSource.GPS_WIFI, p.source)
        assertEquals(18f, p.accuracyM)
        assertEquals("OUTSIDE_PENDING", dao.settings()!!.trackState)
        assertTrue(dao.allShifts().isEmpty()) // nic se nezapsalo bez souhlasu

        // potvrzení v 10:00 → směna začíná v 06:53
        val ten = Instant.ofEpochMilli(at(12, 10, 0))
        TrackingEngine.handle(ctx, TrackEvent.Answer(p.id, true, ten))
        TrackingEngine.handle(ctx, TrackEvent.Answer(p.id, true, ten.plusSeconds(2))) // dvojí klepnutí
        TrackingEngine.handle(ctx, TrackEvent.ManualStart(ten.plusSeconds(5)))         // a ještě ruční start
        assertEquals(1, dao.allShifts().size)
        assertEquals(at(12, 6, 53), dao.openShift()!!.rawStart)

        Graph.closeAll() // další restart – otevřená směna zůstává

        val dao2 = Graph.realDb(ctx).dao()
        val open = dao2.openShift()!!
        assertEquals(at(12, 6, 53), open.rawStart)
        assertEquals("WORKING", dao2.settings()!!.trackState)

        // návrat: dotaz na ukončení; odmítnutí zůstává v historii; duplicitní konec nevznikne
        TrackingEngine.handle(ctx, TrackEvent.GeoEnter(Instant.ofEpochMilli(at(12, 15, 28))))
        val end = dao2.openPending().single()
        TrackingEngine.handle(ctx, TrackEvent.Answer(end.id, false, Instant.ofEpochMilli(at(12, 15, 30))))
        assertEquals("NO", dao2.pending(end.id)!!.status)
        assertNotNull(dao2.openShift())
        assertEquals(at(12, 15, 28), dao2.lastReturnFor(open.id)!!.effectiveAt) // nabídne se při ručním ukončení
        TrackingEngine.handle(ctx, TrackEvent.ManualEnd(Instant.ofEpochMilli(at(12, 15, 28))))
        TrackingEngine.handle(ctx, TrackEvent.ManualEnd(Instant.ofEpochMilli(at(12, 15, 40))))
        val closed = dao2.allShifts().single()
        assertEquals(ShiftStatus.CLOSED, closed.status)
        assertEquals(at(12, 15, 28), closed.rawEnd)
    }

    @Test fun uklidHistorie_zrusenePoDni_ostatniPoMesici_nevyrizeneNikdy() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val dao = db.dao()
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        dao.insertPending(PendingEventEntity(type = "START", eventDetectedAt = now - 2 * day, promptCreatedAt = 0, answeredAt = now - 2 * day, status = "SUPERSEDED"))
        dao.insertPending(PendingEventEntity(type = "START", eventDetectedAt = now - 3 * day, promptCreatedAt = 0, answeredAt = null, status = "SUPERSEDED"))
        dao.insertPending(PendingEventEntity(type = "START", eventDetectedAt = now - 3600_000, promptCreatedAt = 0, answeredAt = now - 3600_000, status = "SUPERSEDED"))
        dao.insertPending(PendingEventEntity(type = "START", eventDetectedAt = now - 5 * day, promptCreatedAt = 0, answeredAt = now - 5 * day, status = "NO"))
        dao.insertPending(PendingEventEntity(type = "END", eventDetectedAt = now - 5 * day, promptCreatedAt = 0, answeredAt = now - 5 * day, status = "YES"))
        dao.insertPending(PendingEventEntity(type = "START", eventDetectedAt = now - 5 * day, promptCreatedAt = 0, status = "PENDING"))
        dao.insertPending(PendingEventEntity(type = "START", eventDetectedAt = now - 40 * day, promptCreatedAt = 0, answeredAt = now - 40 * day, status = "NO"))
        dao.insertPending(PendingEventEntity(type = "END", eventDetectedAt = now - 40 * day, promptCreatedAt = 0, answeredAt = now - 40 * day, status = "YES"))
        dao.insertPending(PendingEventEntity(type = "START", eventDetectedAt = now - 90 * day, promptCreatedAt = 0, status = "PENDING"))
        dao.insertShift(ShiftEntity(rawStart = now - 90 * day, rawEnd = now - 90 * day + 3600_000))
        assertEquals(2, dao.deleteOldSuperseded(now - TrackingEngine.SUPERSEDED_KEEP_MS))
        assertEquals(2, dao.deleteOldResolved(now - TrackingEngine.RESOLVED_KEEP_MS))
        val left = dao.allPending().map { it.status }.sorted()
        // dnešní zrušená a 5 dní staré potvrzené/odmítnuté zůstávají; nevyřízené zůstávají vždy (i 90 dní staré)
        assertEquals(listOf("NO", "PENDING", "PENDING", "SUPERSEDED", "YES"), left)
        assertEquals(1, dao.allShifts().size) // docházka se nemaže
        db.close()
    }

    @Test fun upravenyCasNavrhuSePouzijeAPuvodniZustane() = runBlocking {
        ctx.deleteDatabase("dochazka.db")
        Graph.realDb(ctx).dao().upsertSettings(SettingsEntity(trackingEnabled = true))
        TrackingEngine.handle(ctx, TrackEvent.GeoExit(Instant.ofEpochMilli(at(12, 6, 53))))
        val dao = Graph.realDb(ctx).dao()
        val p = dao.openPending().single()
        TrackingEngine.adjustPending(ctx, p.id, Instant.ofEpochMilli(at(12, 6, 30)))
        TrackingEngine.handle(ctx, TrackEvent.Answer(p.id, true, Instant.ofEpochMilli(at(12, 10, 0))))
        assertEquals(at(12, 6, 30), dao.openShift()!!.rawStart)
        val stored = dao.pending(p.id)!!
        assertEquals(at(12, 6, 53), stored.eventDetectedAt) // původně zjištěný čas se nepřepsal
        assertEquals(at(12, 6, 30), stored.adjustedAt)
        assertEquals("YES", stored.status)
    }

    // ------------------------------------------------------------------------------------
    // Aktualizace: rozbor odpovědi GitHub API
    // ------------------------------------------------------------------------------------

    @Test fun rozborVydaniZGitHubu() {
        val json = """
            {"tag_name":"build-20","name":"Docházka Kutil – verze 9 (sestavení 20)","assets":[
              {"name":"DochazkaKutil-v9-20.apk","size":25700000,"digest":"sha256:ABCDEF0123",
               "browser_download_url":"https://github.com/hincep-lab/dochazka-kutil/releases/download/build-20/DochazkaKutil-v9-20.apk"}]}
        """.trimIndent()
        val r = cz.kutil.dochazka.update.Updater.parse(json)!!
        assertEquals(20, r.build)
        assertEquals("9", r.version)
        assertEquals("ABCDEF0123", r.sha256)
        assertEquals(25700000L, r.size)
        assertTrue(r.apkUrl.endsWith("DochazkaKutil-v9-20.apk"))
        // starší pojmenování APK nebo vydání bez APK → nic k instalaci
        assertNull(cz.kutil.dochazka.update.Updater.parse("""{"assets":[{"name":"DochazkaKutil-7.apk","browser_download_url":"x"}]}"""))
        assertNull(cz.kutil.dochazka.update.Updater.parse("""{"assets":[]}"""))
    }
}
