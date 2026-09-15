package com.mymusic.player.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Static surface colors shared across the app.
 *
 * All *gradients* are now living auroras driven by [AuroraFlow] — see
 * `Modifier.auroraFill`, `Modifier.auroraGlow`, [AuroraSky] and
 * [auroraAccent]. This file only keeps the fixed glass / scrim tones that
 * should NOT shift with the aurora (they carry text and keep the UI calm).
 */
object AppGradients {

    /**
     * Top color of the page background, used by sticky headers to blend into
     * the (static) top of [AuroraSky]'s base gradient — the aurora curtains
     * are biased below the header band so the blend stays seamless.
     */
    fun backgroundTop(dark: Boolean): Color =
        if (dark) Color(0xFF081020) else Color(0xFFF5FBF9)

    /** Deep translucent night-glass surface used by floating bars / mini player. */
    val BarGlass = Color(0xF20B1622)

    /** Bottom fade of the bottom navigation bar. */
    val BarBottom = Color(0xFF070D18)

    /** Dark translucent scrim used over blurred album art. */
    val ScrimDark = Color(0xCC05131A)
}
