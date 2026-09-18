package com.example.badmintontracker

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * One session's summary row — the lightweight fields SessionHistoryStore.loadAll()
 * (and the History list screen) actually need. Deliberately excludes the
 * per-shot log: that's [ShotEntity], a separate table, read only on demand
 * via [SessionDao.loadShotsForSession] — see that function's doc for why.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val timestamp: Long,
    val smashCount: Int,
    val clearCount: Int,
    val dropCount: Int,
    val serveCount: Int,
    val bestSpeedKph: Float,
    val rallyCount: Int,
    val longestRally: Int,
    val avgHeartRate: Double,
    val calories: Double,
    val avgRecoveryBpm: Double
)

/**
 * One logged shot, belonging to a [SessionEntity] via [sessionTimestamp].
 * `onDelete = CASCADE` means evicting a session (see
 * SessionDao.deleteSessionsNotIn) automatically deletes its shots too,
 * without SessionHistoryStore needing a separate cleanup step — the SQLite
 * engine enforces it, so there's no equivalent of the old file-store's
 * "orphaned shot-log file" failure mode if that step were ever missed.
 */
@Entity(
    tableName = "shots",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["timestamp"],
            childColumns = ["sessionTimestamp"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionTimestamp")]
)
data class ShotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionTimestamp: Long,
    val shotTimestamp: Long,
    val type: String,
    val speedKph: Float
)

fun SessionRecord.toEntity(): SessionEntity = SessionEntity(
    timestamp = timestamp,
    smashCount = smashCount,
    clearCount = clearCount,
    dropCount = dropCount,
    serveCount = serveCount,
    bestSpeedKph = bestSpeedKph,
    rallyCount = rallyCount,
    longestRally = longestRally,
    avgHeartRate = avgHeartRate,
    calories = calories,
    avgRecoveryBpm = avgRecoveryBpm
)

fun SessionEntity.toSessionRecord(): SessionRecord = SessionRecord(
    timestamp = timestamp,
    smashCount = smashCount,
    clearCount = clearCount,
    dropCount = dropCount,
    serveCount = serveCount,
    bestSpeedKph = bestSpeedKph,
    rallyCount = rallyCount,
    longestRally = longestRally,
    avgHeartRate = avgHeartRate,
    calories = calories,
    avgRecoveryBpm = avgRecoveryBpm
    // shots deliberately left at its emptyList() default — see
    // SessionRecord's doc comment and SessionDao.loadShotsForSession.
)

fun ShotLogEntry.toEntity(sessionTimestamp: Long): ShotEntity = ShotEntity(
    sessionTimestamp = sessionTimestamp,
    shotTimestamp = timestampMillis,
    type = type,
    speedKph = speedKph
)

fun ShotEntity.toShotLogEntry(): ShotLogEntry = ShotLogEntry(
    timestampMillis = shotTimestamp,
    type = type,
    speedKph = speedKph
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Insert
    suspend fun insertShots(shots: List<ShotEntity>)

    /**
     * A genuine paginated read: `limit`/`offset` map straight to SQL LIMIT/
     * OFFSET, so the UI (or a future "load more" History screen) only ever
     * pulls one page's worth of rows into memory rather than every session
     * ever recorded — Room/SQLite do the paging, not application code.
     */
    @Query("SELECT * FROM sessions ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun loadSessionsPage(limit: Int, offset: Int): List<SessionEntity>

    @Query("SELECT timestamp FROM sessions ORDER BY timestamp DESC LIMIT :keep")
    suspend fun mostRecentTimestamps(keep: Int): List<Long>

    @Query("DELETE FROM sessions WHERE timestamp NOT IN (:keepTimestamps)")
    suspend fun deleteSessionsNotIn(keepTimestamps: List<Long>)

    /**
     * One session's shot log, on demand — never as part of loading the
     * history list. This is the same "don't hold every session's shots in
     * memory at once" principle the old file-per-session store used, now
     * just a WHERE clause instead of a second file read.
     */
    @Query("SELECT * FROM shots WHERE sessionTimestamp = :sessionTimestamp ORDER BY shotTimestamp ASC")
    suspend fun loadShotsForSession(sessionTimestamp: Long): List<ShotEntity>
}

/**
 * Replaces SessionHistoryStore's earlier SharedPreferences (one big JSON
 * blob) + one-file-per-session-shot-log scheme with a real relational
 * database. That scheme worked, but Room buys three things for free that it
 * had to hand-roll:
 * - Paginated reads ([SessionDao.loadSessionsPage]) via LIMIT/OFFSET, rather
 *   than always parsing the entire stored JSON array.
 * - No possibility of a shot-log file surviving its session (see
 *   [ShotEntity]'s `onDelete = CASCADE` doc) — that was a manual, easy-to-
 *   forget cleanup step before.
 * - Versioned schema migrations (see below) instead of hand-written
 *   `optLong`/`optDouble`-with-defaults JSON parsing to stay tolerant of
 *   older saved data.
 *
 * Adding a migration later: bump `version` below, add the new
 * `Migration(oldVersion, newVersion)` to the `.addMigrations(...)` call in
 * [get], and keep the old one too — each Migration only has to handle one
 * version step. See
 * https://developer.android.com/training/data-storage/room/migrating-db-versions
 * Deliberately does NOT use fallbackToDestructiveMigration(): that would
 * silently wipe every saved session the first time the schema changes,
 * which defeats the point of a durable local history in the first place.
 */
@Database(entities = [SessionEntity::class, ShotEntity::class], version = 1, exportSchema = true)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var INSTANCE: SessionDatabase? = null

        fun get(context: Context): SessionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SessionDatabase::class.java,
                    "badminton_sessions.db"
                )
                    // No .addMigrations(...) yet — there's only ever been schema
                    // version 1 so far. Add migrations here (see class doc)
                    // rather than reaching for fallbackToDestructiveMigration().
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
