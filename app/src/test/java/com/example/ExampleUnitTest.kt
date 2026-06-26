package com.example

import com.example.util.TrackingUtils
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun shouldAcceptPoint_rejectsAccuracyWorseThanTwentyMeters() {
    val accepted = TrackingUtils.shouldAcceptPoint(
      newLat = 37.4219999,
      newLon = -122.0840575,
      newAccuracy = 21.0,
      newTimestamp = 1_000L,
      lastLat = null,
      lastLon = null,
      lastTimestamp = null
    )

    assertFalse(accepted)
  }

  @Test
  fun shouldAcceptPoint_rejectsImplausibleSpeedJump() {
    val accepted = TrackingUtils.shouldAcceptPoint(
      newLat = 37.4319999,
      newLon = -122.0840575,
      newAccuracy = 5.0,
      newTimestamp = 2_000L,
      lastLat = 37.4219999,
      lastLon = -122.0840575,
      lastTimestamp = 1_000L
    )

    assertFalse(accepted)
  }

  @Test
  fun shouldAcceptPoint_acceptsPlausibleOutdoorWalkingPoint() {
    val accepted = TrackingUtils.shouldAcceptPoint(
      newLat = 37.4220358,
      newLon = -122.0840575,
      newAccuracy = 6.0,
      newTimestamp = 6_000L,
      lastLat = 37.4219999,
      lastLon = -122.0840575,
      lastTimestamp = 1_000L
    )

    assertTrue(accepted)
  }

  @Test
  fun paceFormatting_handlesStationaryWithoutInfinityOrNan() {
    val pace = TrackingUtils.speedToPace(0.0)

    assertEquals(0.0, pace, 0.0)
    assertEquals("--:--", TrackingUtils.formatPace(pace))
  }

  @Test
  fun calculateDistance_returnsReasonableMetersForKnownOffset() {
    val meters = TrackingUtils.calculateDistance(
      lat1 = 37.4219999,
      lon1 = -122.0840575,
      lat2 = 37.4228999,
      lon2 = -122.0840575
    )

    assertEquals(100.0, meters, 1.0)
  }

  @Test
  fun isActiveSessionFreshForResume_usesLastRecordedActivityTime() {
    val start = millis("2026-06-26T10:00:00Z")
    val now = start + (2 * 60 * 60 * 1000L) + (5 * 60 * 1000L)

    val fresh = TrackingUtils.isActiveSessionFreshForResume(
      date = "2026-06-26T10:00:00Z",
      durationSeconds = 2 * 60 * 60,
      nowMillis = now
    )

    assertTrue(fresh)
  }

  @Test
  fun isActiveSessionFreshForResume_rejectsAbandonedOldActiveRows() {
    val start = millis("2026-06-20T10:00:00Z")
    val now = millis("2026-06-26T10:00:00Z")

    val fresh = TrackingUtils.isActiveSessionFreshForResume(
      date = "2026-06-20T10:00:00Z",
      durationSeconds = 30 * 60,
      nowMillis = now
    )

    assertFalse(fresh)
  }

  @Test
  fun isActiveSessionFreshForResume_rejectsRowsBeyondSixHourWindow() {
    val start = millis("2026-06-26T10:00:00Z")
    val now = start + TrackingUtils.MAX_AUTO_RESUME_STALENESS_MILLIS + 1L

    val fresh = TrackingUtils.isActiveSessionFreshForResume(
      date = "2026-06-26T10:00:00Z",
      durationSeconds = 0,
      nowMillis = now
    )

    assertFalse(fresh)
  }

  @Test
  fun shouldMarkActiveSessionIncompleteWhenServiceStopped_allowsFreshStartGracePeriod() {
    val start = millis("2026-06-26T10:00:00Z")

    val shouldMarkIncomplete = TrackingUtils.shouldMarkActiveSessionIncompleteWhenServiceStopped(
      date = "2026-06-26T10:00:00Z",
      durationSeconds = 0,
      nowMillis = start + 1_000L
    )

    assertFalse(shouldMarkIncomplete)
  }

  @Test
  fun shouldMarkActiveSessionIncompleteWhenServiceStopped_marksOldStoppedSessionIncomplete() {
    val start = millis("2026-06-26T10:00:00Z")

    val shouldMarkIncomplete = TrackingUtils.shouldMarkActiveSessionIncompleteWhenServiceStopped(
      date = "2026-06-26T10:00:00Z",
      durationSeconds = 0,
      nowMillis = start + (7 * 60 * 60 * 1000L)
    )

    assertTrue(shouldMarkIncomplete)
  }

  private fun millis(value: String): Long {
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(value)!!.time
  }
}
