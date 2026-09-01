package com.ownapps.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Nord is fundamentally a dark, arctic-bluish palette; dynamic color is deliberately disabled so
// the app keeps a fixed Nord identity regardless of the device wallpaper.

private val NordDarkColorScheme = darkColorScheme(
    primary = nord8,
    onPrimary = nord0,
    primaryContainer = nord10,
    onPrimaryContainer = nord6,
    secondary = nord7,
    onSecondary = nord0,
    tertiary = nord9,
    onTertiary = nord0,
    background = nord0,
    onBackground = nord6,
    surface = nord1,
    onSurface = nord4,
    surfaceVariant = nord2,
    onSurfaceVariant = nord4,
    error = nord11,
    onError = nord6,
    outline = nord3
)

private val NordLightColorScheme = lightColorScheme(
    primary = nord10,
    onPrimary = nord6,
    primaryContainer = nord8,
    onPrimaryContainer = nord0,
    secondary = nord7,
    onSecondary = nord0,
    tertiary = nord9,
    onTertiary = nord6,
    background = nord6,
    onBackground = nord0,
    surface = nord5,
    onSurface = nord1,
    surfaceVariant = nord4,
    onSurfaceVariant = nord1,
    error = nord11,
    onError = nord6,
    outline = nord3
)

@Composable
fun OwnAppsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NordDarkColorScheme else NordLightColorScheme,
        typography = OwnAppsTypography,
        content = content
    )
}
