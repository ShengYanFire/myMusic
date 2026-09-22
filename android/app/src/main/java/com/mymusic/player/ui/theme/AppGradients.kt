package com.mymusic.player.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Static surface tones shared across the app (light-first).
 *
 * Accent surfaces (buttons, pills, progress fills) use the flat [accentFill] /
 * [accentHorizontalBrush] from Accent.kt — one solid brand red. This file only
 * keeps the neutral paper/glass tones that carry text and keep the UI calm.
 */
object AppGradients {

    /**
     * Top color of the page background, used by sticky headers to blend into
     * the top of the page base color.
     */
    fun backgroundTop(dark: Boolean): Color =
        if (dark) Color(0xFF0B0B0E) else Color(0xFFF6F6F8)

    /** Bottom navigation / mini-player surface — near-opaque white. */
    val BarSurface = Color(0xFFFFFFFF)

    /** Bottom fade of the bottom navigation bar (kept opaque white). */
    val BarBottom = Color(0xFFFFFFFF)

    /** Hairline divider above the bottom bar. */
    val BarDivider = Color(0xFFE9E9ED)

    /** Light translucent scrim used over blurred art / success overlays. */
    val ScrimLight = Color(0xE6FFFFFF)
}