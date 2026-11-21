package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NothingDarkColorScheme =
  darkColorScheme(
    primary = NothingWhite,
    onPrimary = NothingObsidian,
    primaryContainer = NothingSurfaceHighlight,
    onPrimaryContainer = NothingWhite,
    secondary = NothingGrey,
    onSecondary = NothingObsidian,
    secondaryContainer = NothingSurfaceElevated,
    onSecondaryContainer = NothingOffWhite,
    tertiary = NothingRed,
    onTertiary = NothingWhite,
    tertiaryContainer = NothingRedContainer,
    onTertiaryContainer = NothingRedText,
    background = NothingObsidian,
    onBackground = NothingWhite,
    surface = NothingSurface,
    onSurface = NothingWhite,
    surfaceVariant = NothingSurfaceElevated,
    onSurfaceVariant = NothingGrey,
    outline = NothingBorder,
    outlineVariant = NothingSurfaceHighlight,
    error = NothingRed,
    onError = NothingWhite,
    errorContainer = NothingRedContainer,
    onErrorContainer = NothingRedText,
  )

@Composable
fun NothingCameraTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = NothingDarkColorScheme,
    typography = NothingTypography,
    content = content,
  )
}
