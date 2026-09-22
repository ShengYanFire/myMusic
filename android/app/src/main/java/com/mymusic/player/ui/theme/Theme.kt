package com.mymusic.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Palette — "网易云" style: a light-first, clean music-app look with ONE flat
// brand accent (网易云红). No multi-stop gradient, no neon glow, no glass cloud.
//
// Accent rules:
//  - [Accent] is the single highlight color for text / icons / active states,
//    and fills every primary button, pill, selected chip and progress bar.
//  - White text/icons over [Accent]-filled surfaces stays readable (the red is
//    deep enough).
//  - Everything else is neutral light (近白底 + 白卡片 + 灰字), mirroring how
//    网易云 / QQ音乐 默认的浅色界面看起来克制、不花哨。
// ---------------------------------------------------------------------------

// Accent family — flat 网易云红, single hue. No purple, no cyan, no gradient.
val Accent = Color(0xFFE03E3E)        // 主红 —— 文本/图标强调、激活态、按钮/进度填充
val AccentDeep = Color(0xFFC52828)    // 深红 —— 压态 / 渐变末端（同一色相，不跨色）

// Light theme — "paper white" base. The app renders this by default.
val LightBackground = Color(0xFFF6F6F8)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFECECEF)
val LightOnSurface = Color(0xFF1C1C1E)
val LightOnSurfaceVariant = Color(0xFF8E8E93)
val LightOutline = Color(0xFFE3E3E8)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1E1),
    onPrimaryContainer = Color(0xFF4A0A0A),
    secondary = Color(0xFF6E6E73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F2),
    onSecondaryContainer = Color(0xFF2A2A2E),
    tertiary = AccentDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD6),
    onTertiaryContainer = Color(0xFF3B0906),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = Color(0xFFEFEFF2),
    surfaceTint = Accent,
    inverseSurface = Color(0xFF2C2C30),
    inverseOnSurface = Color(0xFFF4F4F6),
    inversePrimary = Color(0xFFFF6B6B),
    scrim = Color(0xFF000000),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFFE0E0),
    onErrorContainer = Color(0xFF5C0A0A),
    // Explicit surface-container ramp: cards sit slightly above the paper-white
    // page; low/high containers give touch feedback and inset panels.
    surfaceDim = Color(0xFFECECEF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFD),
    surfaceContainer = Color(0xFFF7F7F9),
    surfaceContainerHigh = Color(0xFFF1F1F4),
    surfaceContainerHighest = Color(0xFFECECEF),
)

// Dark scheme retained only for completeness; the app defaults to light.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF3B0906),
    primaryContainer = Color(0xFF6E0B0B),
    onPrimaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121214),
    onBackground = Color(0xFFF4F4F6),
    surface = Color(0xFF1B1B1E),
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF28282C),
    onSurfaceVariant = Color(0xFFC5C5CB),
    outline = Color(0xFF3A3A40),
    error = Color(0xFFFF6B6B),
)

// Clean, restrained rounding — uniform so cards, buttons and covers share one
// silhouette, but tighter than the old "glowy pill" look.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.4.sp),
)

/**
 * Whether the app is currently rendering its dark theme (currently always
 * false — the app ships light-first). Screens that branch on theme read this
 * local instead of calling isSystemInDarkTheme() (a configuration lookup that
 * breaks under explicit theme overrides, e.g. tests/previews).
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun MyMusicTheme(
    // Light-first by design: the app renders the 网易云-style paper palette.
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        // Every Text that omits an explicit color resolves to LocalContentColor
        // (defaults to Color.Black) — pin it to onBackground so default text is
        // the correct ink color (near-black in light, near-white in dark).
        LocalContentColor provides colors.onBackground,
    ) {
        MaterialTheme(
            colorScheme = colors,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}