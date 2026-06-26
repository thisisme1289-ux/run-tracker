package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.Session
import com.example.data.SessionRepository
import com.example.data.TrackingPoint
import com.example.util.TrackingUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: SessionRepository
    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null

    private var timerJob: Job? = null
    
    // Live tracking states updated on tick
    private var sessionId: String = ""
    private var startTimeIso: String = ""
    private var elapsedSeconds = 0L
    private var activeSeconds = 0L
    private var totalDistanceMeters = 0.0
    private var currentSpeedMps = 0.0
    private var lastLocation: Location? = null
    
    // Auto-pause calculation
    private var stationarySeconds = 0
    private var isAutoPaused = false

    companion object {
        const val TAG = "TrackingService"
        const val CHANNEL_ID = "tracking_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE_TOGGLE = "ACTION_PAUSE_TOGGLE"
        const val ACTION_STOP = "ACTION_STOP"
        
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"

        // Singleton-like streams for UI Viewers
        val isTrackingFlow = MutableStateFlow(false)
        val isPausedFlow = MutableStateFlow(false)
        val isAutoPausedFlow = MutableStateFlow(false)
        val currentSessionFlow = MutableStateFlow<Session?>(null)
        val currentPointsFlow = MutableStateFlow<List<TrackingPoint>>(emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        val dao = AppDatabase.getDatabase(this).trackingDao()
        repository = SessionRepository(dao)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")
        when (action) {
            ACTION_START -> {
                val existingId = intent.getStringExtra(EXTRA_SESSION_ID)
                startTracking(
                    resumeSessionId = existingId,
                    resumeMostRecentActiveSession = false
                )
            }
            ACTION_PAUSE_TOGGLE -> {
                togglePause()
            }
            ACTION_STOP -> {
                stopTracking()
            }
            null -> {
                startTracking(
                    resumeSessionId = null,
                    resumeMostRecentActiveSession = true
                )
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTracking(
        resumeSessionId: String?,
        resumeMostRecentActiveSession: Boolean
    ) {
        if (isTrackingFlow.value) {
            // Service already running, skip re-initialization
            return
        }

        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.e(TAG, "Foreground location permission not granted. Stopping tracking service.")
            stopSelf()
            return
        }

        // Start foreground service synchronously immediately to satisfy Android background/foreground constraints
        startServiceForeground(createNotification())

        serviceScope.launch {
            val sessionToResume = resumeSessionId
                ?: if (resumeMostRecentActiveSession) repository.getActiveSession()?.id else null
            if (sessionToResume != null) {
                // Resume existing session
                val session = repository.getSessionById(sessionToResume)
                if (session != null &&
                    session.status == "active" &&
                    TrackingUtils.isActiveSessionFreshForResume(session.date, session.durationSeconds)
                ) {
                    sessionId = session.id
                    startTimeIso = session.date
                    elapsedSeconds = session.durationSeconds
                    activeSeconds = session.activeSeconds
                    totalDistanceMeters = session.distanceMeters
                    
                    val points = repository.getPointsForSession(sessionId)
                    currentPointsFlow.value = points
                    if (points.isNotEmpty()) {
                        val lastPt = points.last()
                        lastLocation = Location("gps").apply {
                            latitude = lastPt.latitude
                            longitude = lastPt.longitude
                            accuracy = lastPt.accuracy.toFloat()
                            time = lastPt.timestamp
                        }
                    }
                } else {
                    if (session != null && session.status == "active") {
                        val ageMillis = TrackingUtils.sessionLastActivityAgeMillis(
                            session.date,
                            session.durationSeconds
                        )
                        Log.d(
                            TAG,
                            "Marking active session ${session.id} incomplete during service resume; ageMillis=$ageMillis"
                        )
                        repository.updateSession(session.copy(status = "incomplete"))
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
            } else {
                createNewSession()
            }

            // Update the notification with the loaded/created session stats
            updateNotificationImmediate()

            // Update States
            isTrackingFlow.value = true
            isPausedFlow.value = false
            isAutoPausedFlow.value = false

            locationListener = LocationListener { location ->
                try {
                    processLocationUpdate(location)
                } catch (e: Exception) {
                    Log.e(TAG, "Location update failed without stopping service", e)
                }
            }

            withContext(Dispatchers.Main) {
                startNativeLocationUpdates()
            }

            startStatsTimer()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNativeLocationUpdates() {
        val listener = locationListener ?: return
        val enabledProviders = locationManager.getProviders(true).toSet()
        val looper = Looper.getMainLooper()
        var requestedAnyProvider = false

        if (LocationManager.GPS_PROVIDER in enabledProviders) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L,
                    1.0f,
                    listener,
                    looper
                )
                requestedAnyProvider = true
                Log.d(TAG, "Native GPS provider enabled for tracking")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request GPS provider updates; keeping session active while waiting", e)
            }
        }

        if (LocationManager.NETWORK_PROVIDER in enabledProviders) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    5.0f,
                    listener,
                    looper
                )
                requestedAnyProvider = true
                Log.d(TAG, "Native network provider enabled as a fallback")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request network provider updates; keeping session active while waiting", e)
            }
        }

        if (!requestedAnyProvider) {
            Log.e(TAG, "No native location provider is currently available. Session remains active while waiting for location.")
        }
    }

    private suspend fun createNewSession() {
        sessionId = UUID.randomUUID().toString()
        startTimeIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        elapsedSeconds = 0L
        activeSeconds = 0L
        totalDistanceMeters = 0.0
        currentSpeedMps = 0.0
        lastLocation = null
        
        val newSession = Session(
            id = sessionId,
            date = startTimeIso,
            durationSeconds = 0,
            activeSeconds = 0,
            distanceMeters = 0.0,
            avgSpeed = 0.0,
            avgPace = 0.0,
            status = "active"
        )
        repository.insertSession(newSession)
        currentSessionFlow.value = newSession
        currentPointsFlow.value = emptyList()
    }

    private fun togglePause() {
        val nextPauseState = !isPausedFlow.value
        isPausedFlow.value = nextPauseState
        if (nextPauseState) {
            // Paused manually, stop any auto-pause flag so it doesn't conflict
            isAutoPaused = false
            isAutoPausedFlow.value = false
            stationarySeconds = 0
            currentSpeedMps = 0.0
        }
        updateNotificationImmediate()
    }

    private fun processLocationUpdate(location: Location) {
        if (isPausedFlow.value) {
            // Manual pause is active, completely ignore and skip everything
            return
        }

        val readingAccuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.MAX_VALUE
        val readingTimestamp = if (location.time > 0L) location.time else System.currentTimeMillis()
        val lastLoc = lastLocation
        val accepted = TrackingUtils.shouldAcceptPoint(
            newLat = location.latitude,
            newLon = location.longitude,
            newAccuracy = readingAccuracy,
            newTimestamp = readingTimestamp,
            lastLat = lastLoc?.latitude,
            lastLon = lastLoc?.longitude,
            lastTimestamp = lastLoc?.time
        )

        if (!accepted) {
            Log.d(TAG, "Point rejected due to noise filters: lat=${location.latitude} lon=${location.longitude} accuracy=$readingAccuracy")
            return
        }

        location.time = readingTimestamp

        // Point accepted! Now compute stats
        var stepDistance = 0.0
        if (lastLoc != null) {
            stepDistance = TrackingUtils.calculateDistance(
                lastLoc.latitude, lastLoc.longitude,
                location.latitude, location.longitude
            )
        }

        // Calculate speed
        val isReallyMoving = if (lastLoc != null) {
            val deltaSec = (location.time - lastLoc.time) / 1000.0
            val speed = if (deltaSec > 0) stepDistance / deltaSec else 0.0
            currentSpeedMps = speed
            speed >= 0.4
        } else {
            currentSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0
            currentSpeedMps >= 0.4
        }

        // If auto-paused, we don't accumulate distance or draw line segment.
        // But if we just resumed from auto-pause (moving again), reset the auto-pause state.
        if (isAutoPaused) {
            if (isReallyMoving) {
                // Auto-resume. Do not count the first jump out of a stationary gap.
                isAutoPaused = false
                isAutoPausedFlow.value = false
                stationarySeconds = 0
                stepDistance = 0.0
            } else {
                // Keep auto-paused, don't accumulate distance
                currentSpeedMps = 0.0
                lastLocation = location
                return
            }
        }

        if (!isAutoPaused) {
            totalDistanceMeters += stepDistance
        }

        lastLocation = location

        // Save point to local SQLite
        val trackingPoint = TrackingPoint(
            sessionId = sessionId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = readingAccuracy,
            speed = currentSpeedMps,
            timestamp = location.time
        )

        serviceScope.launch {
            repository.insertPoint(trackingPoint)
            
            // Append point to flow list safely
            val currentList = currentPointsFlow.value.toMutableList()
            currentList.add(trackingPoint)
            currentPointsFlow.value = currentList

            // Save running session details
            saveSessionToDatabase()
        }
    }

    private fun startStatsTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var saveCounter = 0
            while (isActive) {
                delay(1000L)
                if (!isPausedFlow.value) {
                    elapsedSeconds++
                    
                    // Check auto-pause conditions: speed is near 0
                    if (currentSpeedMps < 0.4) {
                        stationarySeconds++
                        if (stationarySeconds >= 30 && !isAutoPaused) {
                            isAutoPaused = true
                            isAutoPausedFlow.value = true
                            Log.d(TAG, "Entering Auto-Pause due to inactivity (30s stationary)")
                        }
                    } else {
                        stationarySeconds = 0
                        if (isAutoPaused) {
                            isAutoPaused = false
                            isAutoPausedFlow.value = false
                        }
                    }

                    if (!isAutoPaused) {
                        activeSeconds++
                    }

                    // Push live tick updates to subscribers
                    val avgSpeed = if (activeSeconds > 0) totalDistanceMeters / activeSeconds else 0.0
                    val avgPace = TrackingUtils.speedToPace(avgSpeed)

                    val updatedSession = Session(
                        id = sessionId,
                        date = startTimeIso,
                        durationSeconds = elapsedSeconds,
                        activeSeconds = activeSeconds,
                        distanceMeters = totalDistanceMeters,
                        avgSpeed = avgSpeed,
                        avgPace = avgPace,
                        status = "active"
                    )
                    currentSessionFlow.value = updatedSession

                    // Write to DB every 5s to guarantee recovery on crash
                    saveCounter++
                    if (saveCounter >= 5) {
                        saveSessionToDatabase()
                        saveCounter = 0
                    }

                    // Update persistent notification summary every 5s
                    if (elapsedSeconds % 5 == 0L) {
                        updateNotificationImmediate()
                    }
                }
            }
        }
    }

    private suspend fun saveSessionToDatabase() {
        val current = currentSessionFlow.value ?: return
        repository.insertSession(current)
    }

    private fun stopTracking() {
        timerJob?.cancel()
        timerJob = null

        locationListener?.let {
            locationManager.removeUpdates(it)
        }
        locationListener = null

        serviceScope.launch {
            val finalSession = currentSessionFlow.value
            if (finalSession != null) {
                val persistedPoints = repository.getPointsForSession(finalSession.id)
                val avgSpeed = if (activeSeconds > 0) totalDistanceMeters / activeSeconds else 0.0
                val avgPace = TrackingUtils.speedToPace(avgSpeed)
                Log.d(
                    TAG,
                    "Finalizing session ${finalSession.id}: pointCount=${persistedPoints.size}, " +
                            "distanceMeters=$totalDistanceMeters, durationSeconds=$elapsedSeconds, avgSpeed=$avgSpeed"
                )

                if (persistedPoints.isEmpty() || totalDistanceMeters <= 0.0 || elapsedSeconds <= 0L) {
                    Log.d(TAG, "Discarding empty session ${finalSession.id}; no persisted route data to save")
                    repository.deleteSessionById(finalSession.id)
                } else {
                
                    val completedSession = finalSession.copy(
                        durationSeconds = elapsedSeconds,
                        activeSeconds = activeSeconds,
                        distanceMeters = totalDistanceMeters,
                        avgSpeed = avgSpeed,
                        avgPace = avgPace,
                        status = "completed"
                    )
                    repository.insertSession(completedSession)
                }
            }

            // Reset Singleton Streams
            isTrackingFlow.value = false
            isPausedFlow.value = false
            isAutoPausedFlow.value = false
            currentSessionFlow.value = null
            currentPointsFlow.value = emptyList()

            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun togglePauseFromNotification() {
        togglePause()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Run Tracker Activity",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live running/walking tracker stats during an active session."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification Pause/Resume action
        val pauseIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_PAUSE_TOGGLE
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification Stop action
        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPaused = isPausedFlow.value
        val isAuto = isAutoPausedFlow.value
        
        val actionText = if (isPaused) "Resume" else "Pause"
        val statusLabel = if (isPaused) "Paused" else if (isAuto) "Paused (Stationary)" else "Recording"

        val formattedDistance = TrackingUtils.formatDistance(totalDistanceMeters)
        val formattedTime = TrackingUtils.formatDuration(elapsedSeconds)
        val avgSpeed = if (activeSeconds > 0) totalDistanceMeters / activeSeconds else 0.0
        val currentPace = if (isPaused || isAuto) 0.0 else TrackingUtils.speedToPace(avgSpeed)
        val formattedPace = TrackingUtils.formatPace(currentPace)

        val contentText = "$statusLabel: $formattedDistance | $formattedTime | $formattedPace"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Active Workout Tracker")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_play, actionText, pausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun startServiceForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationImmediate() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        timerJob = null
        locationListener?.let {
            locationManager.removeUpdates(it)
        }
        locationListener = null
        isTrackingFlow.value = false
        isPausedFlow.value = false
        isAutoPausedFlow.value = false
        currentSessionFlow.value = null
        currentPointsFlow.value = emptyList()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
