package com.example.blindproject1.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 산뜻하고 신뢰감을 주는 블루 계열 컬러 팔레트
val PrimaryBlue = Color(0xFF0056D2)
val SecondaryBlue = Color(0xFF4285F4)
val LightBlue = Color(0xFFE8F0FE)

// Accent & Warning
val AccentOrange = Color(0xFFFF9800)
val DangerRed = Color(0xFFE53935)
val DangerRedLight = Color(0xFFFFCDD2)

// Background & Surface
val BackgroundLight = Color(0xFFF8F9FA)
val SurfaceWhite = Color(0xFFFFFFFF)

// Text Colors
val TextPrimary = Color(0xFF202124)
val TextSecondary = Color(0xFF5F6368)

// 고대비 대체 컬러
val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)

// 공모전용: 밝고 깔끔한 Light Theme
private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    secondary = SecondaryBlue,
    tertiary = AccentOrange,
    background = BackgroundLight,
    surface = SurfaceWhite,
    onPrimary = White,
    onSecondary = White,
    onTertiary = White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = DangerRed,
    onError = White
)

// 시각장애인용 고대비(Dark) 테마
private val DarkHighContrastColorScheme = darkColorScheme(
    primary = SecondaryBlue,
    secondary = AccentOrange,
    tertiary = White,
    background = Black,
    surface = Color(0xFF1E1E1E),
    onPrimary = White,
    onSecondary = Black,
    onBackground = White,
    onSurface = White,
    error = DangerRed,
    onError = White
)

val LocalThemeMode = staticCompositionLocalOf<Boolean> { false } // false for Light, true for Dark

@Composable
fun BlindProject1Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isHighContrastMode: Boolean = false, // App settings override
    content: @Composable () -> Unit
) {
    val useDark = isHighContrastMode || darkTheme
    val colorScheme = if (useDark) DarkHighContrastColorScheme else LightColorScheme

    CompositionLocalProvider(LocalThemeMode provides useDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}