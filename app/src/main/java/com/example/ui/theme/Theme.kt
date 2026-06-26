package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RunTrackerColorScheme = darkColorScheme(
    primary = ElectricGreen,
    onPrimary = DarkBackground,
    primaryContainer = ElectricGreenMuted,
    onPrimaryContainer = ElectricGreen,
    secondary = MutedGray,
    onSecondary = PureWhite,
    background = DarkBackground,
    onBackground = PureWhite,
    surface = DarkSurface,
    onSurface = PureWhite,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = LightGray,
    error = AlertRed,
    onError = PureWhite
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme as default for the running tracker vibe
    dynamicColor: Boolean = false, // Disable dynamic colors to keep our premium Electric Green theme branding
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RunTrackerColorScheme,
        typography = Typography,
        content = content
    )
}
