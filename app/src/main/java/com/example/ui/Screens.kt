package com.example.ui

import android.Manifest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.AppDatabase
import com.example.data.Session
import com.example.data.TrackingPoint
import com.example.ui.theme.*
import com.example.util.TrackingUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

// --- COMPOSABLE VIEWS & NAVIGATION CONTAINER ---

sealed class Screen {
    object Onboarding : Screen()
    object Dashboard : Screen()
    object History : Screen()
    data class SessionDetail(val sessionId: String) : Screen()
}

@Composable
fun AppNavigationContainer(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    val blockedResumeSession by viewModel.blockedResumeSession.collectAsState()

    // Check permissions initially. If we don't have location, go to onboarding.
    val hasFineLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarseLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    
    val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    LaunchedEffect(hasFineLocation, hasCoarseLocation) {
        if (!hasFineLocation && !hasCoarseLocation) {
            currentScreen = Screen.Onboarding
        }
    }

    val scaffoldState = remember { SnackbarHostState() }

    LaunchedEffect(blockedResumeSession?.id) {
        if (blockedResumeSession != null) {
            currentScreen = Screen.Onboarding
            scaffoldState.showSnackbar(
                message = "Active session cannot resume because precise location was revoked.",
                duration = SnackbarDuration.Long
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = scaffoldState) },
        bottomBar = {
            if (currentScreen != Screen.Onboarding) {
                NavigationBar(
                    containerColor = DarkSurface,
                    contentColor = PureWhite
                ) {
                    NavigationBarItem(
                        selected = currentScreen is Screen.Dashboard,
                        onClick = { currentScreen = Screen.Dashboard },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Tracker") },
                        label = { Text("Tracker") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DarkBackground,
                            selectedTextColor = ElectricGreen,
                            indicatorColor = ElectricGreen,
                            unselectedIconColor = MutedGray,
                            unselectedTextColor = MutedGray
                        )
                    )
                    NavigationBarItem(
                        selected = currentScreen is Screen.History,
                        onClick = { currentScreen = Screen.History },
                        icon = { Icon(Icons.Default.History, contentDescription = "History") },
                        label = { Text("Runs") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DarkBackground,
                            selectedTextColor = ElectricGreen,
                            indicatorColor = ElectricGreen,
                            unselectedIconColor = MutedGray,
                            unselectedTextColor = MutedGray
                        )
                    )
                }
            }
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val screen = currentScreen) {
                is Screen.Onboarding -> {
                    PermissionOnboardingScreen(
                        onPermissionsGranted = {
                            viewModel.clearBlockedResumeSession()
                            viewModel.checkForActiveSessionToResume()
                            currentScreen = Screen.Dashboard
                        }
                    )
                }
                is Screen.Dashboard -> {
                    DashboardScreen(
                        viewModel = viewModel,
                        scaffoldState = scaffoldState
                    )
                }
                is Screen.History -> {
                    HistoryScreen(
                        viewModel = viewModel,
                        scaffoldState = scaffoldState
                    )
                }
                is Screen.SessionDetail -> {
                    SessionDetailScreen(
                        sessionId = screen.sessionId,
                        viewModel = viewModel,
                        onBack = {
                            currentScreen = Screen.History
                        }
                    )
                }
            }
        }
    }
}

// --- SCREEN 1: ONBOARDING & DYNAMIC RUNTIME PERMISSIONS ---

@Composable
fun PermissionOnboardingScreen(
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(1) }
    
    val checkPermissions = {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        fine || coarse
    }

    val launcherFine = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                currentStep = 2 // Move to Background Location request
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentStep = 3 // Move to Notifications
            } else {
                currentStep = 4 // Move to battery optimization completion
            }
        }
    }

    val launcherBg = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentStep = 3
            } else {
                currentStep = 4
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentStep = 3
            } else {
                currentStep = 4
            }
        }
    }

    val launcherNotifications = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        currentStep = 4
    }

    LaunchedEffect(Unit) {
        if (checkPermissions()) {
            onPermissionsGranted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // App Logo & Heading
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ElectricGreenMuted)
                    .border(2.dp, ElectricGreen, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsRun,
                    contentDescription = "Run Tracker Logo",
                    tint = ElectricGreen,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "SETUP TRACKER",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = ElectricGreen
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "We require location and notifications access to track your workouts in the background while your phone is locked.",
                color = MutedGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Active Step Content
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (currentStep) {
                    1 -> {
                        OnboardingStepView(
                            icon = Icons.Default.LocationOn,
                            title = "1. Active GPS Access",
                            description = "Allows us to acquire location coordinates while you are actively viewing the tracking dashboards."
                        )
                    }
                    2 -> {
                        OnboardingStepView(
                            icon = Icons.Default.DirectionsWalk,
                            title = "2. Background Location",
                            description = "CRITICAL: Set location access to 'Allow all the time' in the system settings dialog. This is what guarantees your route is recorded when your phone screen is locked."
                        )
                    }
                    3 -> {
                        OnboardingStepView(
                            icon = Icons.Default.Notifications,
                            title = "3. Push Notifications",
                            description = "Required to keep the Android Foreground Service alive and show live pace/timer summaries on your lockscreen."
                        )
                    }
                    4 -> {
                        OnboardingStepView(
                            icon = Icons.Default.BatteryChargingFull,
                            title = "4. Power Exemption (Recommended)",
                            description = "Some manufacturers aggressively terminate GPS listeners to save battery. Ensure battery optimization is disabled for optimal tracking performance."
                        )
                    }
                }
            }
        }

        // Navigation Action Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    when (currentStep) {
                        1 -> {
                            launcherFine.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                        2 -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                launcherBg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            } else {
                                currentStep = 3
                            }
                        }
                        3 -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                launcherNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                currentStep = 4
                            }
                        }
                        4 -> {
                            // Prompt battery optimizer settings (non-blocking)
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_SETTINGS)
                                context.startActivity(intent)
                            }
                            onPermissionsGranted()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("onboarding_action_button"),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (currentStep == 4) "Disable Battery Optimization & Finish" else "Grant Permission",
                    color = DarkBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (currentStep == 4) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = { onPermissionsGranted() },
                    modifier = Modifier.testTag("skip_onboarding_button")
                ) {
                    Text("Skip for now", color = MutedGray, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun OnboardingStepView(
    icon: ImageVector,
    title: String,
    description: String
) {
    Icon(
        imageVector = icon,
        contentDescription = title,
        tint = ElectricGreen,
        modifier = Modifier.size(48.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = title,
        color = PureWhite,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = description,
        color = LightGray,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        lineHeight = 20.sp
    )
}

// --- SCREEN 2: MAIN WORKOUT TRACKER / DASHBOARD ---

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    scaffoldState: SnackbarHostState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    val isTracking by viewModel.isTracking.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val isAutoPaused by viewModel.isAutoPaused.collectAsState()
    
    val currentSession by viewModel.currentSession.collectAsState()
    val points by viewModel.currentPoints.collectAsState()
    val preloadedLocation by viewModel.preloadedLocation.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    
    val postSummary by viewModel.postSessionSummary.collectAsState()

    data class PermissionSnapshot(
        val hasFineLocation: Boolean,
        val hasCoarseLocation: Boolean,
        val hasBackgroundLocation: Boolean,
        val hasNotifications: Boolean
    ) {
        val hasLocation: Boolean
            get() = hasFineLocation || hasCoarseLocation
    }

    fun readPermissionSnapshot(): PermissionSnapshot {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return PermissionSnapshot(fine, coarse, background, notifications)
    }

    var permissionSnapshot by remember { mutableStateOf(readPermissionSnapshot()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionSnapshot = readPermissionSnapshot()
                if (permissionSnapshot.hasLocation) {
                    viewModel.preloadCurrentLocation(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionSnapshot.hasLocation) {
        if (permissionSnapshot.hasLocation) {
            viewModel.preloadCurrentLocation(context)
        }
    }

    // Map logic: auto-centering lock configuration
    var mapInteractedTime by remember { mutableStateOf(0L) }
    var autoCenterMap by remember { mutableStateOf(true) }

    LaunchedEffect(mapInteractedTime) {
        if (mapInteractedTime > 0) {
            autoCenterMap = false
            delay(5000L) // Wait 5 seconds after manual zoom/drag
            autoCenterMap = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Map segment (45% height in Idle state, 60% height in Active state)
            val isSessionUiActive = isTracking || isStarting
            val mapHeightFraction = if (isSessionUiActive) 0.6f else 0.45f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(mapHeightFraction)
            ) {
                val lastPoint = points.lastOrNull()
                val mapLocation = lastPoint ?: if (!isTracking) preloadedLocation else null
                TrackingMapView(
                    points = points,
                    currentLocation = mapLocation,
                    isInteractive = true,
                    autoCenter = autoCenterMap,
                    onMapMovedByUser = {
                        mapInteractedTime = System.currentTimeMillis()
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // GPS Tracking Status Ribbon overlay & Header (Artistic Flair)
                if (isSessionUiActive) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .background(
                                color = DarkBackground.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BlinkingDot(
                                color = if (isPaused || isAutoPaused) WarningOrange else ElectricGreen,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isStarting && !isTracking) "STARTING GPS" else if (isPaused) "PAUSED" else if (isAutoPaused) "PAUSED (STATIONARY)" else "RECORDING",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPaused || isAutoPaused) WarningOrange else ElectricGreen,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Active Session",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PureWhite
                                )
                            }
                        }

                        // GPS High badge
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.05f), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "GPS HIGH",
                                color = ElectricGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                if (!permissionSnapshot.hasBackgroundLocation || !permissionSnapshot.hasNotifications) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .background(DarkBackground.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
                            .border(1.dp, WarningOrange.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "LIMITED BACKGROUND TRACKING",
                            color = WarningOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = when {
                                !permissionSnapshot.hasBackgroundLocation && !permissionSnapshot.hasNotifications ->
                                    "Background location and notifications are off. Tracking can start, but route recording may stop when the phone is locked or the app is backgrounded."
                                !permissionSnapshot.hasBackgroundLocation ->
                                    "Background location is off. Tracking can start, but route recording may stop when the phone is locked or the app is backgrounded."
                                else ->
                                    "Notifications are off. Tracking can continue, but Android may hide the live service notification and make background tracking less reliable."
                            },
                            color = LightGray,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Stats & Control Panel below Map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(DarkSurface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(20.dp)
            ) {
                if (!isSessionUiActive) {
                    // IDLE STATE - Large circular Start Button
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "START ACTIVE TRACKING",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricGreen,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(if (permissionSnapshot.hasLocation) ElectricGreen else DarkSurfaceVariant)
                                .clickable {
                                    Log.d("DashboardScreen", "Start button clicked")
                                    permissionSnapshot = readPermissionSnapshot()
                                    if (permissionSnapshot.hasLocation) {
                                        when (viewModel.preloadCurrentLocation(context)) {
                                            DashboardViewModel.LocationPreloadResult.LOCATION_DISABLED -> {
                                                scope.launch {
                                                    scaffoldState.showSnackbar(
                                                        message = "Turn on device Location before starting.",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                            }
                                            DashboardViewModel.LocationPreloadResult.PERMISSION_MISSING -> {
                                                scope.launch {
                                                    scaffoldState.showSnackbar(
                                                        message = "Grant location access before starting.",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                            }
                                            DashboardViewModel.LocationPreloadResult.REQUESTED -> {
                                                viewModel.startTracking(context)
                                            }
                                        }
                                    } else {
                                        scope.launch {
                                            scaffoldState.showSnackbar(
                                                message = "Location permission was revoked. Grant location access before starting.",
                                                duration = SnackbarDuration.Short
                                            )
                                        }
                                    }
                                }
                                .testTag("start_workout_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start Running Tracker",
                                tint = if (permissionSnapshot.hasLocation) DarkBackground else MutedGray,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                permissionSnapshot = readPermissionSnapshot()
                                if (permissionSnapshot.hasLocation) {
                                    when (viewModel.preloadCurrentLocation(context)) {
                                        DashboardViewModel.LocationPreloadResult.LOCATION_DISABLED -> {
                                            scope.launch {
                                                scaffoldState.showSnackbar(
                                                    message = "Turn on device Location to load your position.",
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                        }
                                        DashboardViewModel.LocationPreloadResult.PERMISSION_MISSING -> {
                                            scope.launch {
                                                scaffoldState.showSnackbar(
                                                    message = "Grant location access before loading your position.",
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        }
                                        DashboardViewModel.LocationPreloadResult.REQUESTED -> Unit
                                    }
                                } else {
                                    scope.launch {
                                        scaffoldState.showSnackbar(
                                            message = "Grant location access before loading your position.",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 54.dp)
                                .height(44.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricGreen),
                            border = BorderStroke(1.dp, ElectricGreen.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = "Get location",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (preloadedLocation != null) "Location Ready" else "Get Location",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (permissionSnapshot.hasLocation) {
                                if (preloadedLocation != null) {
                                    "Location is ready. Tap Start to begin recording your route."
                                } else {
                                    "Load your location first, then tap Start to begin tracking."
                                }
                            } else {
                                "Location permission is required before tracking can start."
                            },
                            color = MutedGray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    // ACTIVE STATE - Live Dashboard statistics & thumb action buttons
                    val session = currentSession ?: Session("", "", 0, 0, 0.0, 0.0, 0.0, "active")
                    val formattedDistance = TrackingUtils.formatDistance(session.distanceMeters)
                    val formattedTime = TrackingUtils.formatDuration(session.durationSeconds)
                    val formattedPace = TrackingUtils.formatPace(session.avgPace)
                    val formattedSpeed = TrackingUtils.formatSpeed(session.avgSpeed)

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Large primary statistic: Distance (Artistic Flair Style)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "DISTANCE",
                                fontSize = 11.sp,
                                color = MutedGray,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                val kmVal = session.distanceMeters / 1000.0
                                val distanceValueStr = String.format(java.util.Locale.US, "%.2f", kmVal)
                                Text(
                                    text = distanceValueStr,
                                    fontSize = 64.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ElectricGreen,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = (-2).sp,
                                    modifier = Modifier.testTag("live_distance")
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "km",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MutedGray,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // Progress bar
                            val progressFraction = (session.distanceMeters / 5000.0).coerceIn(0.0, 1.0).toFloat()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.05f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(progressFraction)
                                        .background(ElectricGreen)
                                )
                            }
                        }

                        // Secondary Stats Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            SecondaryStatItem(label = "TIME", value = formattedTime, isMonospace = true)
                            SecondaryStatItem(label = "PACE", value = formattedPace, isMonospace = true)
                            SecondaryStatItem(label = "SPEED", value = formattedSpeed, isMonospace = true)
                        }

                        // Bottom Control Actions
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pause/Resume Button
                            IconButton(
                                onClick = { viewModel.togglePause(context) },
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(DarkSurfaceVariant)
                                    .border(1.dp, ElectricGreen, CircleShape)
                                    .testTag("pause_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Resume" else "Pause",
                                    tint = ElectricGreen,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Swipe/Hold-to-Stop Slide Confirm Control
                            HoldToStopButton(
                                onTriggered = {
                                    viewModel.stopTracking(context)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 16.dp)
                                    .testTag("stop_workout_holder")
                            )
                        }
                    }
                }
            }
        }

        // POST-STOP SUMMARY SLIDE UP CARD OVERLAY
        AnimatedVisibility(
            visible = postSummary != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val summary = postSummary
            if (summary != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground.copy(alpha = 0.8f))
                        .clickable(enabled = false) {} // block background interactions
                ) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(ElectricGreenMuted, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Completed",
                                    tint = ElectricGreen
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "WORKOUT FINISHED!",
                                color = ElectricGreen,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Route thumbnail canvas render
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkBackground)
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                RouteThumbnail(
                                    points = summary.points,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                SecondaryStatItem(
                                    label = "TOTAL DISTANCE",
                                    value = TrackingUtils.formatDistance(summary.session.distanceMeters)
                                )
                                SecondaryStatItem(
                                    label = "DURATION",
                                    value = TrackingUtils.formatDuration(summary.session.durationSeconds)
                                )
                                SecondaryStatItem(
                                    label = "AVG PACE",
                                    value = TrackingUtils.formatPace(summary.session.avgPace)
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = { viewModel.clearPostSessionSummary() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("save_workout_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricGreen),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Save Workout", color = DarkBackground, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Button(
                                    onClick = { viewModel.deleteJustRecordedSession() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("delete_workout_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Delete Session", color = PureWhite, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecondaryStatItem(
    label: String,
    value: String,
    isMonospace: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = MutedGray,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 17.sp,
            color = PureWhite,
            fontWeight = FontWeight.Bold,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

/**
 * Custom hold-to-stop action button to prevent accidental stops mid-workout.
 * Requires pressing and holding for 1.5 seconds.
 */
@Composable
fun HoldToStopButton(
    onTriggered: () -> Unit,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val startTime = System.currentTimeMillis()
            val holdDuration = 1500L
            while (isHolding && progress < 1f) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / holdDuration).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    onTriggered()
                    isHolding = false
                }
                delay(30L)
            }
        } else {
            // Recoil animation if user releases early
            while (progress > 0f) {
                progress = (progress - 0.15f).coerceAtLeast(0f)
                delay(30L)
            }
        }
    }

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(CircleShape)
            .background(Color(0xFF39FF88))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isHolding = true },
                    onDragEnd = { isHolding = false },
                    onDragCancel = { isHolding = false },
                    onDrag = { _, _ -> }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Progress bar background filler - using a dark contrast green
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(Color(0xFF1B5E20))
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded aspect-square indicator circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0B0E11)),
                contentAlignment = Alignment.Center
            ) {
                // White square stop symbol
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color.White)
                )
            }

            Text(
                text = if (isHolding) "RELEASE TO CANCEL" else "HOLD TO FINISH",
                color = if (progress > 0.4f) Color.White else Color(0xFF0B0E11),
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 44.dp) // Balance visual centering offsetting the left circle
            )
        }
    }
}

/**
 * Pulsing red blinking dot to indicate recording status
 */
@Composable
fun BlinkingDot(
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// --- SCREEN 3: WORKOUT HISTORY LIST ---

@Composable
fun HistoryScreen(
    viewModel: DashboardViewModel,
    scaffoldState: SnackbarHostState
) {
    val sessions by viewModel.historicalSessions.collectAsState()
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsWalk,
                    contentDescription = "No workouts yet",
                    tint = MutedGray,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "NO RUNS YET",
                    color = PureWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Start and log your first route inside the tracking dashboard.",
                    color = MutedGray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "HISTORICAL RUNS",
                    color = ElectricGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sessions, key = { it.id }) { session ->
                        var sessionPoints by remember { mutableStateOf<List<TrackingPoint>>(emptyList()) }
                        val db = AppDatabase.getDatabase(LocalContext.current)
                        
                        LaunchedEffect(session.id) {
                            sessionPoints = db.trackingDao().getPointsForSession(session.id)
                        }

                        HistorySessionCard(
                            session = session,
                            points = sessionPoints,
                            onDelete = {
                                viewModel.deleteSession(session.id)
                                scope.launch {
                                    scaffoldState.showSnackbar(
                                        message = "Workout deleted successfully.",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistorySessionCard(
    session: Session,
    points: List<TrackingPoint>,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_card_${session.id}"),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left segment details
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsRun,
                        contentDescription = "Workout type",
                        tint = ElectricGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = TrackingUtils.formatISOToReadable(session.date),
                        color = PureWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MiniStatColumn(label = "DIST", value = TrackingUtils.formatDistance(session.distanceMeters))
                    MiniStatColumn(label = "TIME", value = TrackingUtils.formatDuration(session.durationSeconds))
                    MiniStatColumn(label = "PACE", value = TrackingUtils.formatPace(session.avgPace))
                }
            }

            // Right segment: Route Thumbnail Preview and Delete option
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Route Canvas Shape
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkBackground)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    RouteThumbnail(
                        points = points,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_history_${session.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete run",
                        tint = AlertRed.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun MiniStatColumn(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 8.sp, color = MutedGray, fontWeight = FontWeight.SemiBold)
        Text(text = value, fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
    }
}

/**
 * Route Thumbnail Custom Canvas Drawing Component.
 * Fits coordinates onto a bounded Canvas screen by finding bounds and scaling points.
 */
@Composable
fun RouteThumbnail(
    points: List<TrackingPoint>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas

        val lats = points.map { it.latitude }
        val lons = points.map { it.longitude }
        val minLat = lats.minOrNull() ?: 0.0
        val maxLat = lats.maxOrNull() ?: 0.0
        val minLon = lons.minOrNull() ?: 0.0
        val maxLon = lons.maxOrNull() ?: 0.0

        val latRange = maxLat - minLat
        val lonRange = maxLon - minLon

        val padding = 12f
        val w = size.width - (2 * padding)
        val h = size.height - (2 * padding)

        val path = Path()
        points.forEachIndexed { idx, pt ->
            val x = padding + if (lonRange > 0.0) (((pt.longitude - minLon) / lonRange) * w).toFloat() else w / 2f
            val y = padding + if (latRange > 0.0) (((1.0 - (pt.latitude - minLat) / latRange)) * h).toFloat() else h / 2f

            if (idx == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = ElectricGreen,
            style = Stroke(
                width = 5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

// --- SCREEN 4: DETAILED SESSION SUMMARY DETAIL SCREEN ---

@Composable
fun SessionDetailScreen(
    sessionId: String,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    val sessionWithPts by viewModel.selectedSessionWithPoints.collectAsState()

    LaunchedEffect(sessionId) {
        viewModel.loadSessionDetail(sessionId)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearSessionDetail()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        val data = sessionWithPts
        if (data == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ElectricGreen)
            }
        } else {
            val session = data.session
            val points = data.points

            Column(modifier = Modifier.fillMaxSize()) {
                
                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "Back to History",
                            tint = PureWhite
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "WORKOUT DETAILS",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricGreen
                    )
                }

                // Map taking up top ~40% of screen height
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.38f)
                ) {
                    TrackingMapView(
                        points = points,
                        currentLocation = null,
                        isInteractive = true,
                        autoCenter = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Detailed Statistics Scrollable view
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(DarkBackground)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Date & Title
                    item {
                        Column {
                            Text(
                                text = "WORKOUT ACCOMPLISHED",
                                color = ElectricGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = TrackingUtils.formatISOToReadable(session.date),
                                color = PureWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // Main Stats Cards Grid
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    DetailedStatBlock(
                                        label = "TOTAL DISTANCE",
                                        value = TrackingUtils.formatDistance(session.distanceMeters)
                                    )
                                    DetailedStatBlock(
                                        label = "TOTAL TIME",
                                        value = TrackingUtils.formatDuration(session.durationSeconds)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = DarkSurfaceVariant)
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    DetailedStatBlock(
                                        label = "AVG PACE",
                                        value = TrackingUtils.formatPace(session.avgPace)
                                    )
                                    DetailedStatBlock(
                                        label = "AVG SPEED",
                                        value = TrackingUtils.formatSpeed(session.avgSpeed)
                                    )
                                }
                                
                                if (session.activeSeconds < session.durationSeconds) {
                                    val pausedSeconds = session.durationSeconds - session.activeSeconds
                                    Spacer(modifier = Modifier.height(16.dp))
                                    HorizontalDivider(color = DarkSurfaceVariant)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        DetailedStatBlock(
                                            label = "ACTIVE MOVING TIME",
                                            value = TrackingUtils.formatDuration(session.activeSeconds)
                                        )
                                        DetailedStatBlock(
                                            label = "PAUSED STATIONARY TIME",
                                            value = TrackingUtils.formatDuration(pausedSeconds)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Pace/Speed Sparkline Chart
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "SPEED SPARKLINE CHART",
                                    color = MutedGray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                SparklineChart(
                                    points = points,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .background(DarkBackground, RoundedCornerShape(10.dp))
                                        .padding(8.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Start", fontSize = 9.sp, color = MutedGray)
                                    Text("Max Speed: ${getMaxSpeedFormatted(points)}", fontSize = 9.sp, color = ElectricGreen)
                                    Text("End", fontSize = 9.sp, color = MutedGray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailedStatBlock(label: String, value: String) {
    Column(modifier = Modifier.width(130.dp)) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = MutedGray,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 20.sp,
            color = PureWhite,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Renders a visual Sparkline Chart plotted against accepted location speed updates.
 */
@Composable
fun SparklineChart(
    points: List<TrackingPoint>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas

        val speeds = points.map { it.speed.toFloat() }
        val maxSpeed = speeds.maxOrNull() ?: 1.0f
        val minSpeed = speeds.minOrNull() ?: 0.0f
        val speedRange = if (maxSpeed > minSpeed) maxSpeed - minSpeed else 1.0f

        val w = size.width
        val h = size.height
        val stepX = w / (points.size - 1)

        val path = Path()
        val fillPath = Path()

        points.forEachIndexed { idx, pt ->
            val x = idx * stepX
            val normalizedSpeed = (pt.speed.toFloat() - minSpeed) / speedRange
            val y = h - (normalizedSpeed * h)

            if (idx == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (idx == points.size - 1) {
                fillPath.lineTo(x, h)
                fillPath.close()
            }
        }

        // Draw soft gradient background fill beneath the line
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(ElectricGreen.copy(alpha = 0.25f), Color.Transparent)
            )
        )

        // Draw primary spark line
        drawPath(
            path = path,
            color = ElectricGreen,
            style = Stroke(
                width = 4f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

fun getMaxSpeedFormatted(points: List<TrackingPoint>): String {
    val mps = points.map { it.speed }.maxOrNull() ?: 0.0
    return TrackingUtils.formatSpeed(mps)
}

// --- SCREEN EXTRA: OPENSTREETMAP ROUTE MAP ---

/**
 * No-key OpenStreetMap renderer. The tracking service remains the source of truth;
 * this view only displays persisted GPS points and the current accepted location.
 */
@Composable
fun TrackingMapView(
    points: List<TrackingPoint>,
    currentLocation: TrackingPoint?,
    isInteractive: Boolean,
    autoCenter: Boolean,
    modifier: Modifier = Modifier,
    onMapMovedByUser: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isOnline by rememberNetworkAvailable(context)
    var mapRenderFailed by remember { mutableStateOf(false) }

    if (mapRenderFailed || !isOnline || (points.isEmpty() && currentLocation == null)) {
        Box(modifier = modifier.background(DarkBackground)) {
            RouteMapCanvas(
                points = points,
                currentLocation = currentLocation,
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = if (isOnline) {
                    if (mapRenderFailed) {
                        "Map view unavailable - showing route"
                    } else {
                        "Waiting for GPS fix - route will draw here"
                    }
                } else {
                    "Offline - map tiles unavailable, route still recording"
                },
                color = LightGray,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .background(DarkBackground.copy(alpha = 0.86f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
        return
    }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(isInteractive)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(17.0)
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.overlays.clear()
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_MOVE && isInteractive) {
                        onMapMovedByUser?.invoke()
                    }
                    false
                }
            }
        },
        modifier = modifier,
        update = { map ->
            runCatching {
                val allPoints = points.map { GeoPoint(it.latitude, it.longitude) }
                val currentPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                map.overlays.clear()

                buildRouteSegments(points).forEach { segment ->
                    if (segment.size >= 2) {
                        map.overlays.add(
                            Polyline().apply {
                                setPoints(segment.map { GeoPoint(it.latitude, it.longitude) })
                                outlinePaint.color = ElectricGreen.toArgb()
                                outlinePaint.strokeWidth = 10f
                            }
                        )
                    }
                }

                allPoints.firstOrNull()?.let { start ->
                    map.overlays.add(
                        Marker(map).apply {
                            position = start
                            title = "Start"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = ContextCompat.getDrawable(context, android.R.drawable.presence_online)
                        }
                    )
                }

                val markerPoint = currentPoint ?: allPoints.lastOrNull()
                markerPoint?.let { point ->
                    map.overlays.add(
                        Marker(map).apply {
                            position = point
                            title = if (currentPoint != null) "Current position" else "Finish"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = ContextCompat.getDrawable(
                                context,
                                if (currentPoint != null) android.R.drawable.presence_invisible else android.R.drawable.presence_busy
                            )
                        }
                    )
                }

                if (autoCenter) {
                    when {
                        currentPoint != null -> {
                            map.controller.animateTo(currentPoint)
                            if (map.zoomLevelDouble < 16.0) {
                                map.controller.setZoom(17.0)
                            }
                        }
                        allPoints.size >= 2 && allPoints.distinctBy { it.latitude to it.longitude }.size >= 2 -> {
                            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(allPoints), true, 96)
                        }
                        allPoints.isNotEmpty() -> {
                            map.controller.setCenter(allPoints.first())
                            map.controller.setZoom(17.0)
                        }
                    }
                }

                map.invalidate()
            }.onFailure { error ->
                Log.e("TrackingMapView", "Map render failed; falling back to route canvas", error)
                mapRenderFailed = true
            }
        }
    )
}

@Composable
private fun rememberNetworkAvailable(context: Context): State<Boolean> {
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val isAvailable = remember {
        mutableStateOf(connectivityManager.isNetworkAvailable())
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(connectivityManager) {
        fun updateAvailability() {
            mainHandler.post {
                isAvailable.value = connectivityManager.isNetworkAvailable()
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateAvailability()
            }

            override fun onLost(network: Network) {
                updateAvailability()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateAvailability()
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    return isAvailable
}

private fun ConnectivityManager.isNetworkAvailable(): Boolean {
    val network = activeNetwork ?: return false
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun buildRouteSegments(points: List<TrackingPoint>): List<List<TrackingPoint>> {
    if (points.isEmpty()) return emptyList()
    val segments = mutableListOf<MutableList<TrackingPoint>>()
    var active = mutableListOf(points.first())

    for (point in points.drop(1)) {
        val previous = active.last()
        if (point.timestamp - previous.timestamp > 15_000L) {
            segments.add(active)
            active = mutableListOf(point)
        } else {
            active.add(point)
        }
    }

    segments.add(active)
    return segments
}

@Composable
fun RouteMapCanvas(
    points: List<TrackingPoint>,
    currentLocation: TrackingPoint?,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .background(DarkBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // 1. Draw grid background lines
            val gridSpacing = 50.dp.toPx()
            val gridColor = Color(0xFF8A919E).copy(alpha = 0.08f)

            // Vertical grid lines
            var xLoc = 0f
            while (xLoc < canvasWidth) {
                drawLine(
                    color = gridColor,
                    start = Offset(xLoc, 0f),
                    end = Offset(xLoc, canvasHeight),
                    strokeWidth = 1.5f
                )
                xLoc += gridSpacing
            }

            // Horizontal grid lines
            var yLoc = 0f
            while (yLoc < canvasHeight) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, yLoc),
                    end = Offset(canvasWidth, yLoc),
                    strokeWidth = 1.5f
                )
                yLoc += gridSpacing
            }

            // 2. Draw compass rose / satellite ring circles decoration in center-right
            val centerRingX = canvasWidth * 0.8f
            val centerRingY = canvasHeight * 0.25f
            drawCircle(
                color = ElectricGreen.copy(alpha = 0.04f),
                radius = 80.dp.toPx(),
                center = Offset(centerRingX, centerRingY),
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = ElectricGreen.copy(alpha = 0.02f),
                radius = 120.dp.toPx(),
                center = Offset(centerRingX, centerRingY),
                style = Stroke(width = 1.dp.toPx())
            )
            drawLine(
                color = ElectricGreen.copy(alpha = 0.03f),
                start = Offset(centerRingX - 140.dp.toPx(), centerRingY),
                end = Offset(centerRingX + 140.dp.toPx(), centerRingY),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = ElectricGreen.copy(alpha = 0.03f),
                start = Offset(centerRingX, centerRingY - 140.dp.toPx()),
                end = Offset(centerRingX, centerRingY + 140.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )

            // 3. Render tracking route points if present
            if (points.isNotEmpty()) {
                val minLat = points.minOfOrNull { it.latitude } ?: 0.0
                val maxLat = points.maxOfOrNull { it.latitude } ?: 1.0
                val minLon = points.minOfOrNull { it.longitude } ?: 0.0
                val maxLon = points.maxOfOrNull { it.longitude } ?: 1.0

                val latRange = (maxLat - minLat).coerceAtLeast(0.0001)
                val lonRange = (maxLon - minLon).coerceAtLeast(0.0001)

                val padding = 40.dp.toPx()
                val drawWidth = canvasWidth - 2 * padding
                val drawHeight = canvasHeight - 2 * padding

                // Helper lambda to project lat/lon coordinates to canvas pixels
                val project = { lat: Double, lon: Double ->
                    val x = padding + ((lon - minLon) / lonRange * drawWidth).toFloat()
                    // Invert y coordinate for standard screen coords
                    val y = canvasHeight - padding - ((lat - minLat) / latRange * drawHeight).toFloat()
                    Offset(x, y)
                }

                // Collect offsets for route points
                val offsets = points.map { project(it.latitude, it.longitude) }

                val routePaths = mutableListOf<Path>()
                var activePath = Path()
                offsets.forEachIndexed { index, offset ->
                    val shouldBreak = index > 0 &&
                            points[index].timestamp - points[index - 1].timestamp > 15_000L

                    if (index == 0 || shouldBreak) {
                        if (index > 0) {
                            routePaths.add(activePath)
                            activePath = Path()
                        }
                        activePath.moveTo(offset.x, offset.y)
                    } else {
                        activePath.lineTo(offset.x, offset.y)
                    }
                }
                routePaths.add(activePath)

                routePaths.forEach { routePath ->
                    drawPath(
                        path = routePath,
                        color = ElectricGreen.copy(alpha = 0.15f),
                        style = Stroke(width = 24f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = routePath,
                        color = ElectricGreen,
                        style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // Draw Start Location pin/marker (Green circle with center)
                val startOffset = offsets.first()
                drawCircle(
                    color = DarkBackground,
                    radius = 8.dp.toPx(),
                    center = startOffset
                )
                drawCircle(
                    color = ElectricGreen,
                    radius = 6.dp.toPx(),
                    center = startOffset
                )
                drawCircle(
                    color = PureWhite,
                    radius = 2.dp.toPx(),
                    center = startOffset
                )

                // Draw Current Position or Destination End marker
                val activeLoc = currentLocation ?: points.last()
                val currentOffset = project(activeLoc.latitude, activeLoc.longitude)

                // Pulse outer ring
                drawCircle(
                    color = (if (currentLocation != null) Color(0xFF00E5FF) else AlertRed).copy(alpha = 1f - pulseProgress),
                    radius = 18.dp.toPx() * pulseProgress,
                    center = currentOffset,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Marker dot
                drawCircle(
                    color = DarkBackground,
                    radius = 8.dp.toPx(),
                    center = currentOffset
                )
                drawCircle(
                    color = if (currentLocation != null) Color(0xFF00E5FF) else AlertRed,
                    radius = 6.dp.toPx(),
                    center = currentOffset
                )
                drawCircle(
                    color = PureWhite,
                    radius = 2.dp.toPx(),
                    center = currentOffset
                )

            } else if (currentLocation != null) {
                // Only current location is available (starting point)
                val centerOffset = Offset(canvasWidth / 2f, canvasHeight / 2f)

                // Pulse outer ring
                drawCircle(
                    color = ElectricGreen.copy(alpha = 1f - pulseProgress),
                    radius = 24.dp.toPx() * pulseProgress,
                    center = centerOffset,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Inner core dot
                drawCircle(
                    color = DarkBackground,
                    radius = 10.dp.toPx(),
                    center = centerOffset
                )
                drawCircle(
                    color = ElectricGreen,
                    radius = 6.dp.toPx(),
                    center = centerOffset
                )
            } else {
                // Empty state map design
                val centerOffset = Offset(canvasWidth / 2f, canvasHeight / 2f)
                drawCircle(
                    color = ElectricGreen.copy(alpha = 0.05f),
                    radius = 40.dp.toPx(),
                    center = centerOffset
                )
            }
        }

    }
}
