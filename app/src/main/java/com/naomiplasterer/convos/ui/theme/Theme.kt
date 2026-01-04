package com.naomiplasterer.convos.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = ColorStandard,
    onPrimary = ColorTextPrimaryInverted,
    primaryContainer = ColorFillSecondary,
    onPrimaryContainer = ColorTextPrimary,
    secondary = ColorFillSecondary,
    onSecondary = ColorTextPrimary,
    secondaryContainer = ColorFillTertiary,
    onSecondaryContainer = ColorTextSecondary,
    tertiary = ColorFillTertiary,
    onTertiary = ColorTextSecondary,
    error = ColorCaution,
    onError = ColorTextPrimaryInverted,
    errorContainer = ColorCaution.copy(alpha = 0.1f),
    onErrorContainer = ColorCaution,
    background = ColorBackgroundPrimary,
    onBackground = ColorTextPrimary,
    surface = ColorBackgroundPrimary,
    onSurface = ColorTextPrimary,
    surfaceVariant = ColorFillSecondary,
    onSurfaceVariant = ColorTextSecondary,
    outline = ColorBorderSubtle,
    outlineVariant = ColorBorderSubtle2,
    inverseSurface = ColorBackgroundInverted,
    inverseOnSurface = ColorTextPrimaryInverted
)

private val DarkColorScheme = darkColorScheme(
    primary = ColorStandardDark,
    onPrimary = ColorTextPrimaryInvertedDark,
    primaryContainer = ColorFillSecondaryDark,
    onPrimaryContainer = ColorTextPrimaryDark,
    secondary = ColorFillSecondaryDark,
    onSecondary = ColorTextPrimaryDark,
    secondaryContainer = ColorFillTertiaryDark,
    onSecondaryContainer = ColorTextSecondaryDark,
    tertiary = ColorFillTertiaryDark,
    onTertiary = ColorTextSecondaryDark,
    error = ColorCautionDark,
    onError = ColorTextPrimaryInvertedDark,
    errorContainer = ColorCautionDark.copy(alpha = 0.1f),
    onErrorContainer = ColorCautionDark,
    background = ColorBackgroundPrimaryDark,
    onBackground = ColorTextPrimaryDark,
    surface = ColorBackgroundPrimaryDark,
    onSurface = ColorTextPrimaryDark,
    surfaceVariant = ColorFillSecondaryDark,
    onSurfaceVariant = ColorTextSecondaryDark,
    outline = ColorBorderSubtleDark,
    outlineVariant = ColorBorderSubtle2Dark,
    inverseSurface = ColorBackgroundInvertedDark,
    inverseOnSurface = ColorTextPrimaryInvertedDark
)

@Composable
fun ConvosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
