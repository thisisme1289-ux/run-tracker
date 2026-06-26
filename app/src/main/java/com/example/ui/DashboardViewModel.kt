package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Session
import com.example.data.SessionRepository
import com.example.data.SessionWithPoints
import com.example.data.TrackingPoint
import com.example.service.TrackingService
import com.example.util.TrackingUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    enum class LocationPreloadResult {
        REQUESTED,
        PERMISSION_MISSING,
        LOCATION_DISABLED
    }

    private val repository: SessionRepository
    
    // Live tracking states collected directly from the Foreground Service
    val isTracking: StateFlow<Boolean> = TrackingService.isTrackingFlow.asStateFlow()
    val isPaused: StateFlow<Boolean> = TrackingService.isPausedFlow.asStateFlow()
    val isAutoPaused: StateFlow<Boolean> = TrackingService.isAutoPausedFlow.asStateFlow()
    val currentSession: StateFlow<Session?> = TrackingService.currentSessionFlow.asStateFlow()
    val currentPoints: StateFlow<List<TrackingPoint>> = TrackingService.currentPointsFlow.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _preloadedLocation = MutableStateFlow<TrackingPoint?>(null)
    val preloadedLocation: StateFlow<TrackingPoint?> = _preloadedLocation.asStateFlow()

    // Database-persisted historical sessions
    val historicalSessions: StateFlow<List<Session>>

    // Selected session detail state
    private val _selectedSessionWithPoints = MutableStateFlow<SessionWithPoints?>(null)
    val selectedSessionWithPoints: StateFlow<SessionWithPoints?> = _selectedSessionWithPoints.asStateFlow()

    // Temporary session result after stop is pressed
    private val _postSessionSummary = MutableStateFlow<SessionWithPoints?>(null)
    val postSessionSummary: StateFlow<SessionWithPoints?> = _postSessionSummary.asStateFlow()

    private val _blockedResumeSession = MutableStateFlow<Session?>(null)
    val blockedResumeSession: StateFlow<Session?> = _blockedResumeSession.asStateFlow()

    init {
        val dao = AppDatabase.getDatabase(application).trackingDao()
        repository = SessionRepository(dao)

        // Gather all completed sessions reactively from database
        historicalSessions = repository.allSessions
            .map { list -> list.filter { it.status == "completed" } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        viewModelScope.launch {
            isTracking.collect { tracking ->
                if (tracking) _isStarting.value = false
            }
        }

        checkForActiveSessionToResume()
    }

    /**
     * Checks if there's an ongoing active session in SQLite (e.g., if app was killed).
     * If found, automatically starts/resumes the Foreground Service.
     */
    fun checkForActiveSessionToResume() {
        viewModelScope.launch {
            val activeSession = repository.getActiveSession()
            if (activeSession != null && !isTracking.value) {
                val ageMillis = TrackingUtils.sessionLastActivityAgeMillis(
                    activeSession.date,
                    activeSession.durationSeconds
                )
                if (!TrackingUtils.shouldMarkActiveSessionIncompleteWhenServiceStopped(
                        activeSession.date,
                        activeSession.durationSeconds
                    )
                ) {
                    Log.d(
                        TAG,
                        "Active session ${activeSession.id} is not tracking yet; ageMillis=$ageMillis. Grace period active."
                    )
                    return@launch
                }

                Log.d(
                    TAG,
                    "Marking active session ${activeSession.id} incomplete because service is not tracking; ageMillis=$ageMillis"
                )
                repository.updateSession(activeSession.copy(status = "incomplete"))
                _blockedResumeSession.value = null
                return@launch
            }

            if (activeSession != null) {
                // A service-backed active session exists. Keep permission state honest if access was revoked.
                val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
                    getApplication(),
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
                    getApplication(),
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                if (hasFine || hasCoarse) {
                    _blockedResumeSession.value = null
                } else {
                    _blockedResumeSession.value = activeSession
                }
            }
        }
    }

    fun clearBlockedResumeSession() {
        _blockedResumeSession.value = null
    }

    fun startTracking(context: Context, resumeSessionId: String? = null) {
        Log.d(TAG, "startTracking requested resumeSessionId=$resumeSessionId")
        _isStarting.value = true
        preloadCurrentLocation(context)
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            if (resumeSessionId != null) {
                putExtra(TrackingService.EXTRA_SESSION_ID, resumeSessionId)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun preloadCurrentLocation(context: Context): LocationPreloadResult {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.d(TAG, "Skipping location preload; location permission missing")
            return LocationPreloadResult.PERMISSION_MISSING
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        providers
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.let { location ->
                _preloadedLocation.value = location.toPreloadedPoint()
                Log.d(TAG, "Preloaded last known location provider=${location.provider}")
            }

        val provider = when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            providers.isNotEmpty() -> providers.first()
            else -> null
        }

        if (provider == null) {
            Log.d(TAG, "No enabled provider available for location preload")
            return LocationPreloadResult.LOCATION_DISABLED
        }

        val listener = LocationListener { location ->
            _preloadedLocation.value = location.toPreloadedPoint()
            Log.d(TAG, "Preloaded fresh location provider=${location.provider}")
        }

        runCatching {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure { error ->
            Log.e(TAG, "Failed to request one-shot location preload", error)
        }
        return LocationPreloadResult.REQUESTED
    }

    private fun Location.toPreloadedPoint() = TrackingPoint(
        sessionId = "preloaded",
        latitude = latitude,
        longitude = longitude,
        accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        speed = if (hasSpeed()) speed.toDouble() else 0.0,
        timestamp = if (time > 0L) time else System.currentTimeMillis()
    )

    fun togglePause(context: Context) {
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_PAUSE_TOGGLE
        }
        context.startService(intent)
    }

    /**
     * Stops the tracking session, prepares the completed session detail for the post-stop
     * summary card view, and terminates the Foreground Service.
     */
    fun stopTracking(context: Context) {
        Log.d(TAG, "stopTracking requested from ViewModel")
        viewModelScope.launch {
            val session = currentSession.value
            val points = currentPoints.value
            
            if (session != null && points.isNotEmpty() && session.distanceMeters > 0.0 && session.durationSeconds > 0L) {
                // Capture the summary so the UI can show the post-workout slide up summary card immediately.
                _postSessionSummary.value = SessionWithPoints(
                    session.copy(status = "completed"),
                    points
                )
            } else {
                _postSessionSummary.value = null
            }

            val intent = Intent(context, TrackingService::class.java).apply {
                action = TrackingService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    fun clearPostSessionSummary() {
        _postSessionSummary.value = null
    }

    /**
     * Deletes a completed session.
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSessionById(sessionId)
        }
    }

    /**
     * Deletes the post-session summary we just recorded (discard session action).
     */
    fun deleteJustRecordedSession() {
        val summary = _postSessionSummary.value
        if (summary != null) {
            viewModelScope.launch {
                repository.deleteSessionById(summary.session.id)
                _postSessionSummary.value = null
            }
        }
    }

    /**
     * Loads points and data for a specific historical session.
     */
    fun loadSessionDetail(sessionId: String) {
        viewModelScope.launch {
            val sessionWithPts = repository.getSessionWithPoints(sessionId)
            _selectedSessionWithPoints.value = sessionWithPts
        }
    }

    fun clearSessionDetail() {
        _selectedSessionWithPoints.value = null
    }
}
