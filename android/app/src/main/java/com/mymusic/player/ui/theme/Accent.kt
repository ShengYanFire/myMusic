package com.mymusic.player.ui.theme

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * ============================================================================
 *  Accent — the app-wide flat red accent engine (网易云红, one hue).
 * ============================================================================
 *
 * The old engine colored every accent surface with an electric cyan→indigo→
 * violet sweep plus radial glows. This replacement is deliberately FLAT: a
 * single brand red ([Accent]) tints every accent surface, and nothing glows,
 * drifts or cycles. Contrast with the light paper background comes from the
 * one solid hue, exactly how 网易云 / QQ音乐 use their brand color.
 *
 *  - [accentColor]  — the single highlight color for text / icons;
 *  - [AccentText] / [AccentIcon]  — text & icon tinted with that color;
 *  - [Modifier.accentFill]  — fills a shape with the flat brand red;
 *  - [accentHorizontalBrush]  — a flat red brush for progress / seek bars.
 */

/** The single highlight color, resolved as a composable for call sites. */
@Composable
fun accentColor(): Color = Accent

/** Text tinted with the flat brand red. */
@Composable
fun AccentText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        color = Accent,
        style = style,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

/** Icon tinted with the flat brand red. */
@Composable
fun AccentIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Accent,
        modifier = modifier,
    )
}

/**
 * Fills the element's (already-clipped) bounds with the flat brand red.
 * The caller applies its own clip, so this simply paints the solid color
 * edge-to-edge. [alpha] softens it where a translucent fill is wanted.
 */
fun Modifier.accentFill(alpha: Float = 1f): Modifier = drawBehind {
    drawRect(color = Accent.copy(alpha = alpha))
}

/**
 * A flat red brush of the requested [width] — used for progress / seek bars
 * whose fill width changes with playback. [alpha] dims it (paused bars read a
 * touch quieter than playing ones).
 */
fun accentHorizontalBrush(width: Float, alpha: Float = 1f): Brush =
    SolidColor(Accent.copy(alpha = alpha))

/**
 * A same-hue gradient brush for progress / seek-bar fills: the played span runs
 * from the deeper [AccentDeep] at its start to the brighter brand [Accent] at
 * the playhead — a single hue, no cross-color — giving the bar a quiet sense of
 * forward momentum without breaking the flat aesthetic. [alpha] dims it (paused
 * bars read a touch quieter than playing ones).
 */
fun accentHorizontalGradientBrush(width: Float, alpha: Float = 1f): Brush =
    Brush.horizontalGradient(
        0f to AccentDeep.copy(alpha = alpha),
        1f to Accent.copy(alpha = alpha),
        startX = 0f,
        endX = width,
    )