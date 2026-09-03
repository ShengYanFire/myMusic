package com.mymusic.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Palette — "Aurora Breeze": fresh mint, sky blue and soft iris.
//
// The identity is 清新 (fresh mint/sky) + 淡雅 (low-saturation surfaces)
// with a 炫酷 aurora gradient (mint → sky → iris) as the single accent
// family shared by every screen in the app.
// ---------------------------------------------------------------------------

// Signature gradient stops (shared by both themes so the accents always pop).
val Mint = Color(0xFF2DD4BF)   // 薄荷青 — fresh, airy primary
val Sky = Color(0xFF38BDF8)    // 天空蓝 — luminous middle stop
val Iris = Color(0xFF818CF8)   // 鸢尾蓝 — cool, elegant end stop
val Rose = Color(0xFFFB7185)   // 淡玫瑰 — used only for the favorite heart

// Dark theme — a deep "aurora night" ocean: near-black teal, never flat gray.
val DarkBgDeep = Color(0xFF050E12)
val DarkBgMid = Color(0xFF071318)
val DarkSurface = Color(0xFF0C1D24)
val DarkSurfaceVariant = Color(0xFF12262E)
val DarkOutline = Color(0xFF1E3A44)
val DarkOnSurface = Color(0xFFEAF6F4)
val DarkOnSurfaceVariant = Color(0xFF8FABA9)
val DarkError = Color(0xFFF87171)

// Light theme — fresh mint morning mist.
val LightPrimary = Color(0xFF0D9488)
val LightSecondary = Color(0xFF0284C7)
val LightBackground = Color(0xFFF2FAF7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE2F0EB)
val LightOnSurface = Color(0xFF0E2420)
val LightOnSurfaceVariant = Color(0xFF55716C)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00352E),
    primaryContainer = Color(0xFF0E3B36),
    onPrimaryContainer = Color(0xFFB5F2E7),
    secondary = Sky,
    onSecondary = Color(0xFF00293A),
    secondaryContainer = Color(0xFF0B324A),
    onSecondaryContainer = Color(0xFFCDEBFB),
    tertiary = Iris,
    onTertiary = Color(0xFF171B4D),
    tertiaryContainer = Color(0xFF2A2E63),
    onTertiaryContainer = Color(0xFFDFE0FF),
    background = DarkBgDeep,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F1E8),
    onPrimaryContainer = Color(0xFF04302A),
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EBFA),
    onSecondaryContainer = Color(0xFF053248),
    tertiary = Color(0xFF5558D9),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2E3FF),
    onTertiaryContainer = Color(0xFF131649),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = Color(0xFFBFD6CF),
    error = Color(0xFFDC2626),
)

// Generous, friendly rounding everywhere — kept uniform so every card,
// button and cover share one consistent silhouette (整齐).
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.4.sp),
)

@Composable
fun MyMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
