package com.example.badmintontracker.phone

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "sessions")
data class SessionSummary(
    @PrimaryKey val timestamp: Long,
    val smashCount: Int,
    val clearCount: Int,
    val dropCount: Int,
    val serveCount: Int,
    val bestSpeedKph: Double,
    val rallyCount: Int,
    val longestRally: Int,
    val avgHeartRate: Double,
    val calories: Double,
    // Average heart-rate drop (bpm) between a rally ending and the next
    // serve, across the session — see HeartRateRecovery.kt on the watch
    // side. Defaults to 0.0 so sessions synced before this field existed
    // still load fine.
    val avgRecoveryBpm: Double = 0.0
)

@Dao
interface SessionDao {
    // IGNORE on a PrimaryKey conflict is the dedupe check the old
    // SharedPreferences implementation did by hand (looping over the JSON
    // array comparing timestamps) — the watch's synced DataItem for a
    // session can legitimately arrive more than once (e.g. the phone's
    // cleanup delete fails after a successful save, so the same item gets
    // redelivered next sync), and this makes re-inserting it a no-op
    // instead of a duplicate row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(session: SessionSummary)

    @Query("SELECT * FROM sessions ORDER BY timestamp DESC")
    fun loadAll(): List<SessionSummary>

    // Keeps only the newest [keep] rows. Written as "delete everything NOT
    // in the newest-N" rather than an OFFSET-based delete so it stays
    // correct regardless of how many rows are actually present.
    @Query(
        """
        DELETE FROM sessions WHERE timestamp NOT IN (
            SELECT timestamp FROM sessions ORDER BY timestamp DESC LIMIT :keep
        )
        """
    )
    fun trimToNewest(keep: Int)

    // Room supports @Transaction on a Kotlin interface default method that
    // calls other Dao methods on the same interface — this makes
    // insert-then-trim a single atomic SQLite transaction, so two
    // concurrent saves can't interleave into a lost update the way the old
    // hand-rolled SharedPreferences read-modify-write could. This is what
    // replaces the old @Synchronized on SessionStore.save: the atomicity
    // guarantee now lives at the database layer instead of being
    // hand-rolled at the call site.
    @Transaction
    fun insertAndTrim(session: SessionSummary, keep: Int) {
        insert(session)
        trimToNewest(keep)
    }
}

@Database(entities = [SessionSummary::class], version = 1, exportSchema = false)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var instance: SessionDatabase? = null

        fun get(context: Context): SessionDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SessionDatabase::class.java,
                    "badminton_sessions.db"
                ).build().also { instance = it }
            }
    }
}

/**
 * Local session history, backed by Room/SQLite.
 *
 * This used to be a hand-rolled JSON array under one SharedPreferences key —
 * fine for a handful of sessions, but every save() had to parse and
 * re-serialize the *entire* history, and every read had to parse the whole
 * blob just to show the last few rows. Room gives real indexed reads/writes
 * instead, and — see SessionDao.insertAndTrim above — proper transactional
 * atomicity for the insert+trim that save() does, in place of the
 * @Synchronized/apply() approach that only serialized calls within this
 * process and still risked a lost update if the process died between the
 * in-memory apply() and its asynchronous flush to disk.
 *
 * Callers are expected to invoke these off the main thread (Room throws if
 * queried on it by default) — both existing call sites already do this:
 * WearDataListenerService runs on a background Binder thread, and
 * PhoneViewModel wraps calls in Dispatchers.IO.
 */
object SessionStore {
    private const val MAX_SESSIONS = 100

    fun save(context: Context, session: SessionSummary) {
        SessionDatabase.get(context).sessionDao().insertAndTrim(session, MAX_SESSIONS)
    }

    fun loadAll(context: Context): List<SessionSummary> =
        SessionDatabase.get(context).sessionDao().loadAll()
}
