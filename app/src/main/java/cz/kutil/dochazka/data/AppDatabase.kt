package cz.kutil.dochazka.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Dao
interface AppDao {
    // --- směny ---
    @Query("SELECT * FROM shifts ORDER BY rawStart")
    fun shiftsFlow(): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM shifts ORDER BY rawStart")
    suspend fun allShifts(): List<ShiftEntity>

    @Query("SELECT * FROM shifts WHERE status = 'OPEN' ORDER BY rawStart DESC LIMIT 1")
    suspend fun openShift(): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun shift(id: Long): ShiftEntity?

    @Insert
    suspend fun insertShift(shift: ShiftEntity): Long

    @Update
    suspend fun updateShift(shift: ShiftEntity)

    @Query("DELETE FROM shifts WHERE id = :id")
    suspend fun deleteShift(id: Long)

    @Query("DELETE FROM shifts")
    suspend fun deleteAllShifts()

    // --- návrhy ---
    @Query("SELECT * FROM pending_events WHERE shiftId = :shiftId AND type = 'END' ORDER BY eventDetectedAt DESC LIMIT 1")
    suspend fun lastReturnFor(shiftId: Long): PendingEventEntity?

    @Query("SELECT * FROM pending_events ORDER BY eventDetectedAt DESC LIMIT 300")
    fun pendingFlow(): Flow<List<PendingEventEntity>>

    @Query("SELECT * FROM pending_events ORDER BY eventDetectedAt")
    suspend fun allPending(): List<PendingEventEntity>

    @Query("SELECT * FROM pending_events WHERE status = 'PENDING' ORDER BY eventDetectedAt")
    suspend fun openPending(): List<PendingEventEntity>

    @Query("SELECT * FROM pending_events WHERE id = :id")
    suspend fun pending(id: Long): PendingEventEntity?

    /**
     * Úklid historie: smaže zrušené události (falešné poplachy, už nepotřebné dotazy) starší než [cutoff].
     * Potvrzené a odmítnuté zůstávají, aby šla dohledat případná chyba.
     */
    @Query("DELETE FROM pending_events WHERE status = 'SUPERSEDED' AND COALESCE(answeredAt, eventDetectedAt) < :cutoff")
    suspend fun deleteOldSuperseded(cutoff: Long): Int

    @Insert
    suspend fun insertPending(event: PendingEventEntity): Long

    @Update
    suspend fun updatePending(event: PendingEventEntity)

    @Query("DELETE FROM pending_events")
    suspend fun deleteAllPending()

    // --- domácí zóna ---
    @Query("SELECT * FROM home_zone WHERE id = 1")
    fun zoneFlow(): Flow<HomeZoneEntity?>

    @Query("SELECT * FROM home_zone WHERE id = 1")
    suspend fun zone(): HomeZoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertZone(zone: HomeZoneEntity)

    @Query("DELETE FROM home_zone")
    suspend fun deleteZone()

    // --- nastavení ---
    @Query("SELECT * FROM settings WHERE id = 1")
    fun settingsFlow(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun settings(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: SettingsEntity)

    // --- označení dnů ---
    @Query("SELECT * FROM day_overrides ORDER BY date")
    fun overridesFlow(): Flow<List<DayOverrideEntity>>

    @Query("SELECT * FROM day_overrides ORDER BY date")
    suspend fun allOverrides(): List<DayOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(override: DayOverrideEntity)

    @Query("DELETE FROM day_overrides WHERE date = :date")
    suspend fun deleteOverride(date: String)

    @Query("DELETE FROM day_overrides")
    suspend fun deleteAllOverrides()
}

@Database(
    entities = [ShiftEntity::class, PendingEventEntity::class, HomeZoneEntity::class, SettingsEntity::class, DayOverrideEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /** Verze 2 (aplikace 9): u návrhů přibyl zdroj, jistota, přesnost polohy, ručně upravený čas a poznámka. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_events ADD COLUMN source TEXT NOT NULL DEFAULT 'GPS'")
                db.execSQL("ALTER TABLE pending_events ADD COLUMN confidence TEXT NOT NULL DEFAULT 'CERTAIN'")
                db.execSQL("ALTER TABLE pending_events ADD COLUMN accuracyM REAL")
                db.execSQL("ALTER TABLE pending_events ADD COLUMN adjustedAt INTEGER")
                db.execSQL("ALTER TABLE pending_events ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }

        fun build(ctx: Context, name: String): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
    }
}

/**
 * Přístup k databázím. Skutečná docházka je v „dochazka.db“, ukázková data demo režimu
 * v úplně oddělené „dochazka_demo.db“. Sledování polohy pracuje vždy jen se skutečnou databází.
 */
object Graph {
    private const val FLAGS = "app_flags"
    private const val KEY_DEMO = "demo_mode"

    @Volatile private var real: AppDatabase? = null
    @Volatile private var demo: AppDatabase? = null
    private val demoState = MutableStateFlow(false)
    @Volatile private var demoLoaded = false

    fun realDb(ctx: Context): AppDatabase = real ?: synchronized(this) {
        real ?: AppDatabase.build(ctx, "dochazka.db").also { real = it }
    }

    fun demoDb(ctx: Context): AppDatabase = demo ?: synchronized(this) {
        demo ?: AppDatabase.build(ctx, "dochazka_demo.db").also { demo = it }
    }

    /** Zavře databáze – při dalším použití se otevřou znovu ze souboru (používají testy „restartu“). */
    fun closeAll() = synchronized(this) {
        real?.close()
        demo?.close()
        real = null
        demo = null
    }

    fun db(ctx: Context, demoMode: Boolean): AppDatabase = if (demoMode) demoDb(ctx) else realDb(ctx)

    fun demoFlow(ctx: Context): StateFlow<Boolean> {
        if (!demoLoaded) {
            demoState.value = ctx.applicationContext.getSharedPreferences(FLAGS, Context.MODE_PRIVATE).getBoolean(KEY_DEMO, false)
            demoLoaded = true
        }
        return demoState
    }

    fun setDemo(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(FLAGS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEMO, on).apply()
        demoLoaded = true
        demoState.value = on
    }
}
