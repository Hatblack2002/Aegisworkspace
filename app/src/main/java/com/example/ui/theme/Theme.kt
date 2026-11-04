package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AegisLightColorScheme = lightColorScheme(
    primary = AegisPrimary,
    onPrimary = AegisOnPrimary,
    secondary = AegisPrimaryText,
    onSecondary = AegisOnPrimary,
    background = AegisBackground,
    onBackground = AegisOnSurface,
    surface = AegisSurface,
    onSurface = AegisOnSurface,
    surfaceVariant = AegisCodeBg,
    onSurfaceVariant = AegisOnSurfaceVariant,
    error = AegisError,
    onError = AegisOnPrimary,
    outline = AegisOutline
)

val AegisShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AegisTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AegisLightColorScheme,
        typography = AegisTypography,
        shapes = AegisShapes,
        content = content
    )
}
