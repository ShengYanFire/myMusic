package com.mymusic.player.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Signature gradient brushes shared across the whole app.
 *
 * The two gradient families give every screen one consistent "aurora"
 * identity — fresh enough to feel airy (清新淡雅), yet luminous enough to
 * feel cool (炫酷):
 *  - [Primary]  mint → sky → iris (hero buttons, play control, accents)
 *  - [Accent]   sky → iris (secondary progress, tags, rings)
 */
object AppGradients {

    val Primary = listOf(Mint, Sky, Iris)
    val Accent = listOf(Sky, Iris)

    fun primaryBrush(): Brush = Brush.linearGradient(Primary)

    fun accentBrush(): Brush = Brush.linearGradient(Accent)

    /** Hero banner behind the search header — a deep aurora night. */
    fun bannerBrush(): Brush = Brush.linearGradient(
        colors = listOf(Color(0xFF0F766E), Color(0xFF0369A1), Color(0xFF4F46E5)),
    )

    /** Top color of the page background, used by sticky headers to blend in. */
    fun backgroundTop(dark: Boolean): Color =
        if (dark) Color(0xFF07161C) else Color(0xFFF4FBF9)

    /** Full-screen ambient background (subtle). */
    fun backgroundBrush(dark: Boolean): Brush =
        if (dark) {
            Brush.verticalGradient(listOf(Color(0xFF07161C), DarkBgDeep))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFF4FBF9), Color(0xFFE7F3EF)))
        }

    /** Deep translucent teal-glass surface used by floating bars / mini player. */
    val BarGlass = Color(0xF20B1E24)

    /** Bottom fade of the bottom navigation bar. */
    val BarBottom = Color(0xFF050E12)

    /** Dark translucent scrim used over blurred album art. */
    val ScrimDark = Color(0xCC05131A)
}
