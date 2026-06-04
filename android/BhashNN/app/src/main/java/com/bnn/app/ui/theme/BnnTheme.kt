package com.bnn.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── B#NN Terminal Colour Palette ──────────────────────────────────────────────
// Dark: Black bg + neon green (matches bitchat iOS/Android terminal aesthetic)

val NeonGreen    = Color(0xFF39FF14)   // Primary neon green – terminal classic
val DarkGreen    = Color(0xFF2ECB10)   // Secondary / darker variant
val AiPurple     = Color(0xFF9C27B0)   // AI/relay accent – purple
val RelayTeal    = Color(0xFF00BCD4)   // Relay messages – teal
val ErrorRed     = Color(0xFFFF5555)   // Error – soft red
val WarnOrange   = Color(0xFFFF9500)   // Warning / typing – iOS orange
val TermBlack    = Color(0xFF000000)   // True black background
val SurfaceDark  = Color(0xFF0D0D0D)   // Slightly lighter surface
val SurfaceDark2 = Color(0xFF1A1A1A)   // Cards / input bg
val OnBgGreen    = Color(0xFF39FF14)   // Text on black bg

val BnnDarkColorScheme = darkColorScheme(
    primary          = NeonGreen,
    onPrimary        = Color.Black,
    primaryContainer = Color(0xFF003D00),
    onPrimaryContainer = NeonGreen,
    secondary        = DarkGreen,
    onSecondary      = Color.Black,
    tertiary         = AiPurple,
    onTertiary       = Color.White,
    background       = TermBlack,
    onBackground     = OnBgGreen,
    surface          = SurfaceDark,
    onSurface        = NeonGreen,
    surfaceVariant   = SurfaceDark2,
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline          = Color(0xFF2A2A2A),
    error            = ErrorRed,
    onError          = Color.Black
)

val BnnLightColorScheme = lightColorScheme(
    primary          = Color(0xFF008000),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD4F5D4),
    onPrimaryContainer = Color(0xFF002200),
    secondary        = Color(0xFF006600),
    onSecondary      = Color.White,
    tertiary         = Color(0xFF7B1FA2),
    onTertiary       = Color.White,
    background       = Color(0xFFF5F5F5),
    onBackground     = Color(0xFF1A1A1A),
    surface          = Color.White,
    onSurface        = Color(0xFF1A1A1A),
    surfaceVariant   = Color(0xFFE8F5E9),
    onSurfaceVariant = Color(0xFF444444),
    outline          = Color(0xFFCCCCCC),
    error            = Color(0xFFCC0000),
    onError          = Color.White
)

@Composable
fun BnnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) BnnDarkColorScheme else BnnLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BnnTypography,
        content = content
    )
}
