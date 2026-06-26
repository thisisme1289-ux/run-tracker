package com.example.data

import kotlinx.coroutines.flow.Flow

class SessionRepository(private val dao: TrackingDao) {

    val allSessions: Flow<List<Session>> = dao.getAllSessionsFlow()

    suspend fun getActiveSession(): Session? {
        return dao.getActiveSession()
    }

    suspend fun getSessionById(sessionId: String): Session? {
        return dao.getSessionById(sessionId)
    }

    fun getPointsForSessionFlow(sessionId: String): Flow<List<TrackingPoint>> {
        return dao.getPointsForSessionFlow(sessionId)
    }

    suspend fun getPointsForSession(sessionId: String): List<TrackingPoint> {
        return dao.getPointsForSession(sessionId)
    }

    suspend fun insertSession(session: Session) {
        dao.insertSession(session)
    }

    suspend fun updateSession(session: Session) {
        dao.updateSession(session)
    }

    suspend fun deleteSessionById(sessionId: String) {
        dao.deleteSessionById(sessionId)
    }

    suspend fun insertPoint(point: TrackingPoint) {
        dao.insertPoint(point)
    }

    suspend fun getSessionWithPoints(sessionId: String): SessionWithPoints? {
        return dao.getSessionWithPoints(sessionId)
    }
}
