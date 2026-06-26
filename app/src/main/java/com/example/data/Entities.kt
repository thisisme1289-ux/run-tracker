package com.example.data

data class Session(
    val id: String,
    val date: String,              // ISO timestamp
    val durationSeconds: Long,
    val activeSeconds: Long,       // duration minus auto-paused time
    val distanceMeters: Double,
    val avgSpeed: Double,          // m/s
    val avgPace: Double,           // seconds per km
    val status: String             // 'active' | 'completed'
)

data class TrackingPoint(
    val id: Int = 0,
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val speed: Double,
    val timestamp: Long
)

data class SessionWithPoints(
    val session: Session,
    val points: List<TrackingPoint>
)
