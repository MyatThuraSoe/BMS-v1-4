package com.lumipos.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = GrocersGreen,
    onPrimary = Color.White,
    primaryContainer = GrocersGreenContainer,
    onPrimaryContainer = OnGrocersGreenContainer,
    secondary = BrassGold,
    onSecondary = Color.White,
    secondaryContainer = BrassContainer,
    onSecondaryContainer = OnBrassContainer,
    tertiary = GrocersGreenLight,
    onTertiary = Color.White,
    error = BrickRed,
    onError = OnBrickRed,
    errorContainer = BrickRedLight,
    onErrorContainer = DarkRed,
    background = PaperBackground,
    onBackground = ForestInk,
    surface = PaperSurface,
    onSurface = ForestInk,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = ForestInkSecondary,
    outline = SoftOutline,
    outlineVariant = PaleDivider,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperSurfaceVariant,
    surfaceContainerHighest = PaperSurfaceVariant,
    surfaceContainerLow = PaperSurface,
    surfaceContainerLowest = Color.White,
    surfaceTint = GrocersGreen
)

private val DarkColorScheme = darkColorScheme(
    primary = NightGreen,
    onPrimary = OnGrocersGreenContainer,
    primaryContainer = NightGreenContainer,
    onPrimaryContainer = OnNightGreenContainer,
    secondary = NightGold,
    onSecondary = OnBrassContainer,
    secondaryContainer = NightGoldContainer,
    onSecondaryContainer = OnNightGoldContainer,
    tertiary = GrocersGreenLight,
    onTertiary = Color(0xFF0E2E1F),
    error = NightRed,
    onError = DarkRed,
    errorContainer = NightRedContainer,
    onErrorContainer = BrickRedLight,
    background = NightBackground,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightInkSecondary,
    outline = SoftOutline,
    outlineVariant = NightSurfaceVariant,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceVariant,
    surfaceContainerHighest = NightSurfaceVariant,
    surfaceContainerLow = NightBackground,
    surfaceContainerLowest = Color(0xFF0A110D),
    surfaceTint = NightGreen
)

val LumiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

@Composable
fun LumiPOSTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
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
        shapes = LumiShapes,
        content = content
    )
}