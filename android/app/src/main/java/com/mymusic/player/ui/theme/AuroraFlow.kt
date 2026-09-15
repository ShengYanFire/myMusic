package com.mymusic.player.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * ============================================================================
 *  AuroraFlow — the app-wide "living aurora" engine (极光动态变色)
 * ============================================================================
 *
 * One shared clock ([LocalAuroraPhase], a 0f..1f loop hosted by [ProvideAurora]
 * inside [MyMusicTheme]) drives every animated color in the app:
 *
 *  - [auroraColorAt] samples a *circular* spectrum — emerald → mint → sky →
 *    iris → violet → rose → (wrap) — so every accent continuously morphs
 *    through the whole aurora range instead of sitting on a fixed brand hue;
 *  - [Modifier.auroraFill] paints any shape with a gradient whose stops drift
 *    around the spectrum and whose tilt slowly sways, all in the *draw phase*
 *    (no per-frame recomposition);
 *  - [Modifier.auroraGlow] paints a soft radial glow of the current hue;
 *  - [AuroraSky] is the full-screen living background: a deep night (or pale
 *    morning) base, drifting aurora curtains and — at night — a twinkling
 *    starfield;
 *  - [auroraAccent] / [AuroraText] / [AuroraIcon] give small text & icon
 *    accents the same breathing color (scoped to the leaf, so only that leaf
 *    recomposes).
 *
 * Everything shares the same 16-second lap, so the whole UI breathes as one
 * coherent aurora.
 */

/** Full circle in radians. */
val TAU = (2.0 * PI).toFloat()

/** One full lap around the aurora spectrum, in milliseconds. */
const val AURORA_CYCLE_MS = 16_000

/** Quantization steps of [LocalAuroraColorPhase] per lap (~8 color changes/s). */
private const val AURORA_COLOR_STEPS = 128f

/** Default spacing between gradient stops, as a fraction of the spectrum. */
const val AuroraSpacing = 1f / 6f

/** The circular aurora spectrum the whole app breathes through. */
val AuroraSpectrum = listOf(
    Color(0xFF34D399), // aurora green
    Color(0xFF2DD4BF), // mint teal
    Color(0xFF38BDF8), // sky cyan
    Color(0xFF818CF8), // iris blue
    Color(0xFFC084FC), // violet
    Color(0xFFF472B6), // rose pink
)

/** Deeper, saturated variant for hero surfaces that carry white text. */
val AuroraSpectrumDeep = listOf(
    Color(0xFF047857), // emerald night
    Color(0xFF0F766E), // deep teal
    Color(0xFF0369A1), // deep sky
    Color(0xFF4F46E5), // indigo
    Color(0xFF7C3AED), // violet
    Color(0xFFA21CAF), // fuchsia dusk
)

/**
 * Samples the circular spectrum at [t] (any float; it wraps). Adjacent stops
 * are cross-faded, so the returned color glides smoothly around the wheel.
 */
fun auroraColorAt(t: Float, spectrum: List<Color> = AuroraSpectrum): Color {
    val n = spectrum.size
    // Wrap into [0, 1) — Kotlin's % keeps the sign of negative inputs, which
    // would otherwise index the palette out of bounds.
    val wrapped = ((t % 1f) + 1f) % 1f
    val scaled = wrapped * n
    val i = scaled.toInt().coerceIn(0, n - 1)
    val f = scaled - i
    return lerp(spectrum[i], spectrum[(i + 1) % n], f)
}

/**
 * A window of [count] colors sampled around the spectrum starting at [t],
 * each [spacing] apart — the sliding gradient-stop set used everywhere.
 */
fun auroraWindow(
    t: Float,
    count: Int = 3,
    spacing: Float = AuroraSpacing,
    spectrum: List<Color> = AuroraSpectrum,
): List<Color> = List(count) { i -> auroraColorAt(t + i * spacing, spectrum) }

/**
 * A linear aurora brush for a surface [size] at spectrum position [t]: three
 * stops drifting around the wheel, tilted at an angle that slowly sways like
 * a hanging curtain. [alpha] scales the stops' opacity (for glows/overlays).
 */
fun auroraBrushAt(
    t: Float,
    size: Size,
    alpha: Float = 1f,
    spectrum: List<Color> = AuroraSpectrum,
    spacing: Float = AuroraSpacing,
): Brush {
    val colors = auroraWindow(t, count = 3, spacing = spacing, spectrum = spectrum)
        .let { cs -> if (alpha >= 1f) cs else cs.map { it.copy(alpha = it.alpha * alpha) } }
    val w = size.width.coerceAtLeast(1f)
    val h = size.height.coerceAtLeast(1f)
    // Tilt sways ±22° around 28° over each lap — the light sweeps gently.
    val angle = (28f + 22f * sin(t * TAU)) * PI.toFloat() / 180f
    val len = (w + h) / 2f
    val cx = w / 2f
    val cy = h / 2f
    val dx = cos(angle) * len
    val dy = sin(angle) * len
    return Brush.linearGradient(
        colors = colors,
        start = Offset(cx - dx / 2f, cy - dy / 2f),
        end = Offset(cx + dx / 2f, cy + dy / 2f),
    )
}

/** Fills [shape] with the aurora gradient at spectrum position [t]. */
fun DrawScope.drawAuroraShape(
    shape: Shape,
    t: Float,
    alpha: Float = 1f,
    spectrum: List<Color> = AuroraSpectrum,
    spacing: Float = AuroraSpacing,
) {
    val brush = auroraBrushAt(t, size, alpha, spectrum, spacing)
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> drawRect(brush)
        // Circle- and rounded-corner shapes all land here; our shapes always
        // use a uniform corner radius.
        is Outline.Rounded -> {
            val rr = outline.roundRect
            drawRoundRect(
                brush = brush,
                topLeft = Offset(rr.left, rr.top),
                size = Size(rr.width, rr.height),
                cornerRadius = rr.topLeftCornerRadius,
            )
        }
        is Outline.Generic -> drawPath(outline.path, brush)
    }
}

// ---------------------------------------------------------------------------
// The shared clock
// ---------------------------------------------------------------------------

/**
 * The app-wide aurora phase: a 0f..1f loop, one lap per [AURORA_CYCLE_MS].
 * Read `.value` in a draw lambda (cheap redraw) or via [auroraAccent]
 * (scoped recomposition) — never at a large composable's root.
 *
 * Readers that only use the phase for COLOR should read
 * [LocalAuroraColorPhase] instead — the 128-step quantized twin that ticks
 * ~8 times a second instead of at display frame rate.
 */
val LocalAuroraPhase = compositionLocalOf<State<Float>> { mutableStateOf(0f) }

/**
 * The color-only aurora clock: [LocalAuroraPhase] quantized to 128 steps per
 * lap (≈8 changes/second at [AURORA_CYCLE_MS]). The inter-step color delta is
 * a ~1.8° hue turn — invisible — but it cuts the per-frame recomposition of
 * every [AuroraText]/[AuroraIcon]/[auroraAccent] leaf and the per-frame
 * redraw of every [Modifier.auroraFill]/[auroraGlow] surface from display
 * rate (60–120 Hz) down to 8 Hz. Spatially-moving visuals ([AuroraSky]'s
 * drifting curtains, the seek bar's silk tail) keep the smooth phase.
 */
val LocalAuroraColorPhase = compositionLocalOf<State<Float>> { mutableStateOf(0f) }

/** Hosts the single infinite transition that ticks the whole aurora. */
@Composable
fun ProvideAurora(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(AURORA_CYCLE_MS, easing = LinearEasing),
        ),
        label = "auroraPhase",
    )
    val colorPhase = remember {
        derivedStateOf { (phase.value * AURORA_COLOR_STEPS).toInt() / AURORA_COLOR_STEPS }
    }
    CompositionLocalProvider(
        LocalAuroraPhase provides phase,
        LocalAuroraColorPhase provides colorPhase,
    ) { content() }
}

/** The current animated accent color (for small text / icon tints). */
@Composable
fun auroraAccent(offset: Float = 0f): Color {
    val phase = LocalAuroraColorPhase.current
    return auroraColorAt(phase.value + offset)
}

/** Text whose color breathes with the aurora (recomposition stays local). */
@Composable
fun AuroraText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val phase = LocalAuroraColorPhase.current
    Text(
        text = text,
        color = auroraColorAt(phase.value),
        style = style,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

/** Icon tinted with the living aurora accent (recomposition stays local). */
@Composable
fun AuroraIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val phase = LocalAuroraColorPhase.current
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = auroraColorAt(phase.value),
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Modifiers (draw-phase only — they never trigger recomposition)
// ---------------------------------------------------------------------------

/**
 * Fills the element with the living aurora gradient clipped to [shape].
 * [phaseOffset] staggers elements around the spectrum so the UI doesn't pulse
 * in lockstep; [alpha] softens the fill (glows, halos); [spectrum] switches
 * between the airy [AuroraSpectrum] and the deep [AuroraSpectrumDeep].
 */
fun Modifier.auroraFill(
    shape: Shape = RectangleShape,
    phaseOffset: Float = 0f,
    alpha: Float = 1f,
    spectrum: List<Color> = AuroraSpectrum,
    spacing: Float = AuroraSpacing,
): Modifier = composed {
    // Color-only consumer → quantized clock: the fill redraws ~8×/s instead
    // of at display rate, an invisible trade for a big battery/GPU win.
    val phase = LocalAuroraColorPhase.current
    this.drawBehind {
        drawAuroraShape(shape, phase.value + phaseOffset, alpha, spectrum, spacing)
    }
}

/**
 * A soft radial glow of the current aurora hue, fading to transparent at the
 * element's edge — used for halos and ambient auras.
 */
fun Modifier.auroraGlow(
    phaseOffset: Float = 0f,
    alpha: Float = 0.35f,
): Modifier = composed {
    // Color-only consumer → quantized clock (see LocalAuroraColorPhase).
    val phase = LocalAuroraColorPhase.current
    this.drawBehind {
        val color = auroraColorAt(phase.value + phaseOffset)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// The living sky
// ---------------------------------------------------------------------------

/** One deterministic twinkling star (normalized position, radius in dp). */
private class Star(val x: Float, val y: Float, val radiusDp: Float, val speed: Int, val seed: Float)

// Lissajous drivers for the three drifting curtains: integer speeds so the
// motion wraps seamlessly when the shared phase loops 0f..1f.
private val CurtainFx = floatArrayOf(1f, 2f, 3f)
private val CurtainFy = floatArrayOf(2f, 3f, 1f)
private val CurtainPx = floatArrayOf(0.05f, 0.42f, 0.78f)
private val CurtainPy = floatArrayOf(0.13f, 0.62f, 0.38f)

/**
 * The full-screen living aurora background:
 *  - a deep aurora-night base (or a pale morning base in light theme);
 *  - three aurora curtains — big soft radial glows drifting on Lissajous
 *    paths, each cycling through the spectrum at a different offset, gently
 *    breathing in and out;
 *  - at night, a deterministic starfield twinkling above the curtains.
 *
 * [intensity] scales the glow strength (e.g. the sky brightens while music
 * plays); [base] false skips the opaque base gradient (used to lay a faint
 * aurora mist over the blurred album art of the immersive player screens).
 */
@Composable
fun AuroraSky(
    dark: Boolean,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    base: Boolean = true,
    stars: Boolean = dark,
) {
    val phase = LocalAuroraPhase.current
    // The opaque base gradient is theme-dependent but frame-invariant —
    // remember it instead of re-allocating the Brush on every draw call.
    val baseBrush = remember(dark) {
        if (dark) {
            Brush.verticalGradient(listOf(Color(0xFF081020), Color(0xFF0A1428), Color(0xFF070D18)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFF5FBF9), Color(0xFFECF4F2), Color(0xFFE7F0F1)))
        }
    }
    val starField = remember {
        val rnd = Random(20_250_101)
        List(56) {
            Star(
                x = rnd.nextFloat(),
                // Keep stars below the sticky-header band so the solid
                // header never cuts one in half.
                y = 0.12f + rnd.nextFloat() * 0.84f,
                radiusDp = 0.8f + rnd.nextFloat() * 1.5f,
                speed = 1 + rnd.nextInt(2),
                seed = rnd.nextFloat(),
            )
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val t = phase.value

        // ---- Base gradient ----
        if (base) {
            drawRect(baseBrush)
        }

        // ---- Twinkling starfield (night only) ----
        if (stars) {
            starField.forEach { s ->
                val twinkle = 0.5f + 0.5f * sin(t * TAU * s.speed + s.seed * TAU)
                drawCircle(
                    color = Color.White.copy(alpha = (0.07f + 0.30f * twinkle) * intensity),
                    radius = s.radiusDp.dp.toPx() / 2f,
                    center = Offset(s.x * size.width, s.y * size.height),
                )
            }
        }

        // ---- Drifting aurora curtains ----
        val glowBase = (if (dark) 0.16f else 0.10f) * intensity
        for (i in 0 until 3) {
            val fx = CurtainFx[i]
            val fy = CurtainFy[i]
            val px = CurtainPx[i]
            val py = CurtainPy[i]
            val x = size.width * (0.5f + 0.36f * sin((t * fx + px) * TAU))
            val y = size.height * (0.46f + 0.25f * sin((t * fy + py) * TAU))
            val color = auroraColorAt(t + i * 0.19f)
            val a = glowBase * (0.72f + 0.28f * sin((t * 2f + i * 0.75f) * TAU))
            val r = size.width * (0.40f + 0.13f * i)
            val center = Offset(x, y)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = a),
                        color.copy(alpha = a * 0.45f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
            )
        }
    }
}
