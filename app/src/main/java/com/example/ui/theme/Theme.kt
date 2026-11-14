package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val MeridianDarkColorScheme = darkColorScheme(
    primary = MeridianTeal,
    onPrimary = ObsidianBg,
    primaryContainer = MeridianTealTint,
    onPrimaryContainer = MeridianTeal,
    secondary = InfoBlue,
    onSecondary = ObsidianBg,
    secondaryContainer = InfoBlueTint,
    onSecondaryContainer = InfoBlue,
    tertiary = WarnAmber,
    onTertiary = ObsidianBg,
    tertiaryContainer = WarnAmberTint,
    onTertiaryContainer = WarnAmber,
    background = ObsidianBg,
    onBackground = InkPrimary,
    surface = GraphiteSurface,
    onSurface = InkPrimary,
    surfaceVariant = SlateCard,
    onSurfaceVariant = InkSecondary,
    outline = HairlineBorder,
    outlineVariant = SlateElevated,
    error = BearCrimson,
    onError = InkPrimary,
    errorContainer = BearCrimsonTint,
    onErrorContainer = BearCrimson
)

val MeridianShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun MeridianTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MeridianDarkColorScheme,
        typography = MeridianTypography,
        shapes = MeridianShapes,
        content = content
    )
}
