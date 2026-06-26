package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class TrackingDao(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "tracking_database",
    null,
    1
) {
    private val sessionsFlow = MutableStateFlow<List<Session>>(emptyList())
    private val pointFlows = mutableMapOf<String, MutableStateFlow<List<TrackingPoint>>>()

    init {
        writableDatabase
        refreshSessions()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                date TEXT NOT NULL,
                durationSeconds INTEGER NOT NULL,
                activeSeconds INTEGER NOT NULL,
                distanceMeters REAL NOT NULL,
                avgSpeed REAL NOT NULL,
                avgPace REAL NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy REAL NOT NULL,
                speed REAL NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_points_session ON points(sessionId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS points")
        db.execSQL("DROP TABLE IF EXISTS sessions")
        onCreate(db)
    }

    fun getAllSessionsFlow(): Flow<List<Session>> = sessionsFlow

    fun getPointsForSessionFlow(sessionId: String): Flow<List<TrackingPoint>> {
        return pointFlows.getOrPut(sessionId) {
            MutableStateFlow(queryPointsForSession(sessionId))
        }
    }

    suspend fun getAllSessions(): List<Session> = queryAllSessions()

    suspend fun getSessionById(sessionId: String): Session? {
        readableDatabase.query(
            "sessions",
            null,
            "id = ?",
            arrayOf(sessionId),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toSession() else null
        }
    }

    suspend fun getActiveSession(): Session? {
        readableDatabase.query(
            "sessions",
            null,
            "status = ?",
            arrayOf("active"),
            null,
            null,
            "date DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toSession() else null
        }
    }

    suspend fun getPointsForSession(sessionId: String): List<TrackingPoint> {
        return queryPointsForSession(sessionId)
    }

    suspend fun insertSession(session: Session) {
        writableDatabase.insertWithOnConflict(
            "sessions",
            null,
            session.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
        refreshSessions()
    }

    suspend fun updateSession(session: Session) = insertSession(session)

    suspend fun deleteSessionById(sessionId: String) {
        writableDatabase.delete("points", "sessionId = ?", arrayOf(sessionId))
        writableDatabase.delete("sessions", "id = ?", arrayOf(sessionId))
        refreshSessions()
        refreshPoints(sessionId)
    }

    suspend fun insertPoint(point: TrackingPoint) {
        writableDatabase.insert("points", null, point.toValues())
        refreshPoints(point.sessionId)
    }

    suspend fun getSessionWithPoints(sessionId: String): SessionWithPoints? {
        val session = getSessionById(sessionId) ?: return null
        return SessionWithPoints(session, getPointsForSession(sessionId))
    }

    private fun refreshSessions() {
        sessionsFlow.value = queryAllSessions()
    }

    private fun refreshPoints(sessionId: String) {
        pointFlows[sessionId]?.value = queryPointsForSession(sessionId)
    }

    private fun queryAllSessions(): List<Session> {
        readableDatabase.query(
            "sessions",
            null,
            null,
            null,
            null,
            null,
            "date DESC"
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toSession())
            }
        }
    }

    private fun queryPointsForSession(sessionId: String): List<TrackingPoint> {
        readableDatabase.query(
            "points",
            null,
            "sessionId = ?",
            arrayOf(sessionId),
            null,
            null,
            "timestamp ASC"
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toTrackingPoint())
            }
        }
    }

    private fun Session.toValues() = ContentValues().apply {
        put("id", id)
        put("date", date)
        put("durationSeconds", durationSeconds)
        put("activeSeconds", activeSeconds)
        put("distanceMeters", distanceMeters)
        put("avgSpeed", avgSpeed)
        put("avgPace", avgPace)
        put("status", status)
    }

    private fun TrackingPoint.toValues() = ContentValues().apply {
        put("sessionId", sessionId)
        put("latitude", latitude)
        put("longitude", longitude)
        put("accuracy", accuracy)
        put("speed", speed)
        put("timestamp", timestamp)
    }

    private fun Cursor.toSession() = Session(
        id = getString(getColumnIndexOrThrow("id")),
        date = getString(getColumnIndexOrThrow("date")),
        durationSeconds = getLong(getColumnIndexOrThrow("durationSeconds")),
        activeSeconds = getLong(getColumnIndexOrThrow("activeSeconds")),
        distanceMeters = getDouble(getColumnIndexOrThrow("distanceMeters")),
        avgSpeed = getDouble(getColumnIndexOrThrow("avgSpeed")),
        avgPace = getDouble(getColumnIndexOrThrow("avgPace")),
        status = getString(getColumnIndexOrThrow("status"))
    )

    private fun Cursor.toTrackingPoint() = TrackingPoint(
        id = getInt(getColumnIndexOrThrow("id")),
        sessionId = getString(getColumnIndexOrThrow("sessionId")),
        latitude = getDouble(getColumnIndexOrThrow("latitude")),
        longitude = getDouble(getColumnIndexOrThrow("longitude")),
        accuracy = getDouble(getColumnIndexOrThrow("accuracy")),
        speed = getDouble(getColumnIndexOrThrow("speed")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp"))
    )
}

class AppDatabase private constructor(context: Context) {
    private val dao = TrackingDao(context)

    fun trackingDao(): TrackingDao = dao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = AppDatabase(context)
                INSTANCE = instance
                instance
            }
        }
    }
}
