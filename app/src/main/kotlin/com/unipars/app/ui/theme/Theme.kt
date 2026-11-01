package com.unipars.app.ui.theme

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unipars.app.R

enum class ThemeMode(@StringRes val title: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
}

/** Gradient palettes; each defines key colors for light and dark schemes plus the header gradient. */
enum class Palette(
    @StringRes val title: Int,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val primaryDark: Color,
    val gradientLight: List<Color>,
    val gradientDark: List<Color>,
) {
    AURORA(
        R.string.palette_aurora, Color(0xFF5B4CF5), Color(0xFF0891B2), Color(0xFFC026D3), Color(0xFFBDB4FF),
        listOf(Color(0xFF6D5DFB), Color(0xFF8B5CF6), Color(0xFF22B8CF)),
        listOf(Color(0xFF2B2170), Color(0xFF3B1E6E), Color(0xFF0B4A5C)),
    ),
    OCEAN(
        R.string.palette_ocean, Color(0xFF0061A4), Color(0xFF00897B), Color(0xFF5C6BC0), Color(0xFF9CCAFF),
        listOf(Color(0xFF0EA5E9), Color(0xFF2563EB), Color(0xFF14B8A6)),
        listOf(Color(0xFF0B3A5C), Color(0xFF172E6B), Color(0xFF0F4C47)),
    ),
    SUNSET(
        R.string.palette_sunset, Color(0xFFC2410C), Color(0xFFDB2777), Color(0xFF7C3AED), Color(0xFFFFB59A),
        listOf(Color(0xFFF97316), Color(0xFFEF4444), Color(0xFFEC4899)),
        listOf(Color(0xFF5C2410), Color(0xFF5B1A1A), Color(0xFF5C1A3F)),
    ),
    FOREST(
        R.string.palette_forest, Color(0xFF2E7D32), Color(0xFF00897B), Color(0xFF827717), Color(0xFF94D99A),
        listOf(Color(0xFF16A34A), Color(0xFF059669), Color(0xFF0D9488)),
        listOf(Color(0xFF123B1F), Color(0xFF0F3A2C), Color(0xFF0B3B37)),
    ),
    ;

    fun gradient(dark: Boolean): List<Color> = if (dark) gradientDark else gradientLight
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.AURORA,
    val dynamicColor: Boolean = false,
)

/** Header/hero gradient of the current theme. */
val LocalAppGradient = staticCompositionLocalOf { Palette.AURORA.gradientLight }

fun Palette.brush(dark: Boolean): Brush = Brush.linearGradient(gradient(dark))

private fun lightScheme(p: Palette): ColorScheme {
    val tint = { amount: Float -> lerp(p.primary, Color.White, amount) }
    return lightColorScheme(
        primary = p.primary,
        onPrimary = Color.White,
        primaryContainer = tint(0.82f),
        onPrimaryContainer = lerp(p.primary, Color.Black, 0.55f),
        secondary = p.secondary,
        onSecondary = Color.White,
        secondaryContainer = lerp(p.secondary, Color.White, 0.84f),
        onSecondaryContainer = lerp(p.secondary, Color.Black, 0.6f),
        tertiary = p.tertiary,
        onTertiary = Color.White,
        tertiaryContainer = lerp(p.tertiary, Color.White, 0.84f),
        onTertiaryContainer = lerp(p.tertiary, Color.Black, 0.6f),
        background = tint(0.965f),
        surface = tint(0.975f),
        surfaceVariant = tint(0.9f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = tint(0.955f),
        surfaceContainer = tint(0.94f),
        surfaceContainerHigh = tint(0.92f),
        surfaceContainerHighest = tint(0.9f),
        outline = lerp(p.primary, Color.Gray, 0.7f),
        outlineVariant = tint(0.8f),
    )
}

private fun darkScheme(p: Palette): ColorScheme {
    val base = Color(0xFF111118)
    val tint = { amount: Float -> lerp(base, p.primaryDark, amount) }
    return darkColorScheme(
        primary = p.primaryDark,
        onPrimary = lerp(p.primary, Color.Black, 0.6f),
        primaryContainer = lerp(p.primary, Color.Black, 0.45f),
        onPrimaryContainer = lerp(p.primaryDark, Color.White, 0.6f),
        secondary = lerp(p.secondary, Color.White, 0.45f),
        onSecondary = lerp(p.secondary, Color.Black, 0.65f),
        secondaryContainer = lerp(p.secondary, Color.Black, 0.55f),
        onSecondaryContainer = lerp(p.secondary, Color.White, 0.75f),
        tertiary = lerp(p.tertiary, Color.White, 0.45f),
        onTertiary = lerp(p.tertiary, Color.Black, 0.65f),
        tertiaryContainer = lerp(p.tertiary, Color.Black, 0.55f),
        onTertiaryContainer = lerp(p.tertiary, Color.White, 0.75f),
        background = base,
        surface = tint(0.03f),
        surfaceVariant = tint(0.14f),
        surfaceContainerLowest = Color(0xFF0B0B10),
        surfaceContainerLow = tint(0.05f),
        surfaceContainer = tint(0.08f),
        surfaceContainerHigh = tint(0.11f),
        surfaceContainerHighest = tint(0.14f),
        outline = tint(0.45f),
        outlineVariant = tint(0.22f),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun isDark(settings: ThemeSettings): Boolean = when (settings.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun UniParsTheme(settings: ThemeSettings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val ctx = LocalContext.current
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkScheme(settings.palette)
        else -> lightScheme(settings.palette)
    }
    CompositionLocalProvider(LocalAppGradient provides settings.palette.gradient(dark)) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
    }
}
