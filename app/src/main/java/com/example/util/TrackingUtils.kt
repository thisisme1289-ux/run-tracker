package com.example.util

import android.location.Location
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

object TrackingUtils {

    const val MAX_AUTO_RESUME_STALENESS_MILLIS = 6L * 60L * 60L * 1000L
    const val MIN_INCOMPLETE_CLASSIFICATION_AGE_MILLIS = 10_000L

    /**
     * Calculates distance between two coordinates in meters using the Haversine formula.
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Determines whether a new point should be accepted or rejected based on the noise filtering rules.
     */
    fun shouldAcceptPoint(
        newLat: Double,
        newLon: Double,
        newAccuracy: Double,
        newTimestamp: Long,
        lastLat: Double?,
        lastLon: Double?,
        lastTimestamp: Long?
    ): Boolean {
        // Rule 1: Reject if accuracy is worse than 20 meters
        if (newAccuracy > 20.0) {
            return false
        }

        // Rule 2: Reject if duplicate or out-of-order callback
        if (lastTimestamp != null && lastLat != null && lastLon != null) {
            val deltaSeconds = (newTimestamp - lastTimestamp) / 1000.0
            if (deltaSeconds <= 0) {
                return false
            }

            // Rule 3: Reject if implied speed > 15 m/s (54 km/h) -> catches GPS jumps
            val distance = calculateDistance(lastLat, lastLon, newLat, newLon)
            val impliedSpeed = distance / deltaSeconds
            if (impliedSpeed > 15.0) {
                return false
            }
        }

        return true
    }

    /**
     * Formats distance in meters to a standard km string (e.g. "3.42 km")
     */
    fun formatDistance(meters: Double): String {
        val km = meters / 1000.0
        return String.format(Locale.US, "%.2f km", km)
    }

    /**
     * Formats time in seconds to a monospaced timer string (e.g., "HH:MM:SS" or "MM:SS")
     */
    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /**
     * Converts speed in m/s to pace in seconds per km.
     */
    fun speedToPace(speedMps: Double): Double {
        if (speedMps < 0.2) return 0.0 // stationary or extremely slow
        return 1000.0 / speedMps
    }

    /**
     * Formats pace in seconds per km to a standard string (e.g. "5:30 /km" or "--:--")
     */
    fun formatPace(paceSecondsPerKm: Double): String {
        if (paceSecondsPerKm <= 0.0 || paceSecondsPerKm.isInfinite() || paceSecondsPerKm.isNaN()) {
            return "--:--"
        }
        val min = (paceSecondsPerKm / 60).toInt()
        val sec = (paceSecondsPerKm % 60).toInt()
        return String.format(Locale.US, "%d:%02d /km", min, sec)
    }

    /**
     * Formats speed in m/s to km/h.
     */
    fun formatSpeed(speedMps: Double): String {
        val kmh = speedMps * 3.6
        return String.format(Locale.US, "%.1f km/h", kmh)
    }

    /**
     * Formats date string from ISO timestamp or parses to readable form.
     */
    fun formatISOToReadable(isoDateStr: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val formatter = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
            val date = parser.parse(isoDateStr) ?: Date()
            formatter.format(date)
        } catch (e: Exception) {
            isoDateStr
        }
    }

    fun isActiveSessionFreshForResume(
        date: String,
        durationSeconds: Long,
        nowMillis: Long = System.currentTimeMillis(),
        maxStalenessMillis: Long = MAX_AUTO_RESUME_STALENESS_MILLIS
    ): Boolean {
        val startMillis = parseSessionStartMillis(date) ?: return false
        val lastRecordedActivityMillis = startMillis + durationSeconds.coerceAtLeast(0L) * 1000L
        val ageMillis = nowMillis - lastRecordedActivityMillis
        return ageMillis in 0L..maxStalenessMillis
    }

    fun sessionLastActivityAgeMillis(
        date: String,
        durationSeconds: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        val startMillis = parseSessionStartMillis(date) ?: return null
        val lastRecordedActivityMillis = startMillis + durationSeconds.coerceAtLeast(0L) * 1000L
        return nowMillis - lastRecordedActivityMillis
    }

    fun shouldMarkActiveSessionIncompleteWhenServiceStopped(
        date: String,
        durationSeconds: Long,
        nowMillis: Long = System.currentTimeMillis(),
        minAgeMillis: Long = MIN_INCOMPLETE_CLASSIFICATION_AGE_MILLIS
    ): Boolean {
        val ageMillis = sessionLastActivityAgeMillis(date, durationSeconds, nowMillis) ?: return true
        return ageMillis > minAgeMillis
    }

    private fun parseSessionStartMillis(date: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).parse(date)?.time
            } catch (e: Exception) {
                null
            }
        }
    }
}
