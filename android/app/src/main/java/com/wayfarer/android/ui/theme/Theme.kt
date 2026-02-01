package com.wayfarer.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = WfCyan,
    onPrimary = WfCyanOn,
    secondary = Color(0xFF2DD4BF),
    tertiary = Color(0xFFF472B6),
    background = WfBgDark,
    onBackground = WfOnDark,
    surface = WfSurfaceDark,
    onSurface = WfOnDark,
    surfaceVariant = WfSurface2Dark,
    onSurfaceVariant = WfMutedDark,
    error = WfDanger,
    onError = WfDangerOn,
    outline = WfOutlineDark,
    outlineVariant = WfOutlineStrongDark,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0F766E),
    tertiary = Color(0xFFBE185D),
    background = WfBgLight,
    onBackground = WfOnLight,
    surface = WfSurfaceLight,
    onSurface = WfOnLight,
    surfaceVariant = WfSurface2Light,
    onSurfaceVariant = WfMutedLight,
    error = Color(0xFFE11D48),
    onError = Color(0xFFFFFFFF),
    outline = WfOutlineLight,
    outlineVariant = WfOutlineStrongLight,
)

@Composable
fun WayfarerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
