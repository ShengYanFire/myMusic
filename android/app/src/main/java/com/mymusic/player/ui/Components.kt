package com.mymusic.player.ui

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mymusic.player.domain.Track
import com.mymusic.player.player.PlayerController
import com.mymusic.player.ui.theme.AppGradients
import com.mymusic.player.ui.theme.AuroraSky
import com.mymusic.player.ui.theme.LocalAuroraColorPhase
import com.mymusic.player.ui.theme.TAU
import com.mymusic.player.ui.theme.White70
import com.mymusic.player.ui.theme.auroraAccent
import com.mymusic.player.ui.theme.auroraBrushAt
import com.mymusic.player.ui.theme.auroraColorAt
import com.mymusic.player.ui.theme.auroraFill
import com.mymusic.player.ui.theme.auroraWindow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Duration of one full aurora flow cycle through a progress bar. */
private const val AuroraFlowDurationMs = 2600

/** Duration of one drift cycle of the silk ribbons around the seek bar. */
private const val RibbonDriftDurationMs = 3800

/**
 * Builds the flowing-aurora brush for a bar [widthPx] wide at slide phase
 * [phase] in 0f..1f, tinted by the global aurora spectrum position [hue].
 * The gradient spans two color periods and slides one period per cycle, so
 * the colors stream continuously through the fill while their hues drift
 * around the shared spectrum — the bar never repeats the same view twice.
 */
private fun flowingAuroraBrush(widthPx: Float, phase: Float, heightPx: Float, hue: Float): Brush {
    val period = widthPx * 2f
    val start = -period + phase * period
    // Two full windows of the current hue (plus the wrapped first color) so
    // the fill can slide by exactly one period per loop and stay seamless at
    // every hue.
    val window = auroraWindow(hue, count = 3)
    val flowColors = window + window + window.first()
    return Brush.linearGradient(
        colors = flowColors,
        start = Offset(start, 0f),
        end = Offset(start + period * 2f, heightPx),
    )
}

/** Organic silk wave: a main sine plus a weaker harmonic ripple, in [-1, 1]. */
private fun waveOffset(x: Float, wavelength: Float, phase: Float): Float {
    val a = x / wavelength * TAU + phase * TAU
    return 0.7f * sin(a) + 0.3f * sin(2f * a + 1.3f)
}

/**
 * A comet-like silk tail streaming from the thumb bead: a tapered aurora band
 * anchored at [headX] (the bead) and trailing leftward for [tailLength],
 * waving around [baseY]. The head is thick and bright; toward the tail end the
 * band narrows and fades, with two loose threads peeling off beyond it. A soft
 * layered aura dissipates around the whole band. The wave pattern is anchored
 * to the head, so the tail follows the bead as one coherent shape while
 * [wavePhase] streams the silk backward like a wind-blown scarf.
 */
private fun DrawScope.drawSilkTail(
    headX: Float,
    baseY: Float,
    amplitude: Float,
    headThickness: Float,
    tailLength: Float,
    wavePhase: Float,
    wavelength: Float,
    hue: Float,
) {
    val tailX = headX - tailLength
    val step = 8f

    // Wave anchored to the head so the whole tail travels with the bead.
    fun waveAt(x: Float) = amplitude * waveOffset(x - headX, wavelength, wavePhase)

    // Taper: full thickness at the head, thin at the tail end.
    fun thickAt(x: Float): Float {
        val u = ((headX - x) / tailLength).coerceIn(0f, 1f)
        return headThickness * (1f - 0.85f * u)
    }

    fun topAt(x: Float) = baseY - thickAt(x) / 2f + waveAt(x)
    fun botAt(x: Float) = baseY + thickAt(x) / 2f + waveAt(x)

    // ---- Band: top edge head → tail, bottom edge back. ----
    val bandPath = Path()
    var x = headX
    bandPath.moveTo(x, topAt(x))
    while (x > tailX) {
        x = (x - step).coerceAtLeast(tailX)
        bandPath.lineTo(x, topAt(x))
    }
    var bx = tailX
    while (bx < headX) {
        bandPath.lineTo(bx, botAt(bx))
        bx = (bx + step).coerceAtMost(headX)
    }
    bandPath.lineTo(headX, botAt(headX))
    bandPath.close()

    // ---- Spine: the bright core line from head to tail. ----
    val spinePath = Path()
    var sx = headX
    spinePath.moveTo(sx, baseY + waveAt(sx))
    while (sx > tailX) {
        sx = (sx - step).coerceAtLeast(tailX)
        spinePath.lineTo(sx, baseY + waveAt(sx))
    }

    val shimmer = 0.85f + 0.15f * sin(wavePhase * 4f * TAU + 2f)
    // The silk shimmers through the shared spectrum: the global hue drifts
    // slowly while the ribbon's own drift phase streams it backward.
    val silkHue = hue + wavePhase * 0.4f
    val hueHead = auroraColorAt(silkHue)
    val hueMid = auroraColorAt(silkHue + 0.22f)
    val hueTail = auroraColorAt(silkHue + 0.4f)

    // ---- 逸散阴影: layered aura dissipating around the whole band. ----
    val auraBrush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to hueHead.copy(alpha = 0.10f * shimmer),
            0.55f to hueMid.copy(alpha = 0.05f),
            1f to hueTail.copy(alpha = 0f),
        ),
        startX = headX,
        endX = tailX,
    )
    drawPath(bandPath, auraBrush, style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round))
    drawPath(bandPath, auraBrush, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))

    // ---- Band fill, fading toward the tail. ----
    val fillBrush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to hueHead.copy(alpha = 0.5f * shimmer),
            0.4f to hueMid.copy(alpha = 0.32f),
            1f to hueTail.copy(alpha = 0f),
        ),
        startX = headX,
        endX = tailX,
    )
    drawPath(bandPath, fillBrush)

    // ---- Spine glow + crisp core. ----
    val spineBrush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to hueHead.copy(alpha = 0.95f),
            0.35f to hueMid.copy(alpha = 0.6f),
            0.7f to hueTail.copy(alpha = 0.3f),
            1f to hueTail.copy(alpha = 0f),
        ),
        startX = headX,
        endX = tailX,
    )
    drawPath(spinePath, spineBrush, alpha = 0.12f, style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
    drawPath(spinePath, spineBrush, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

    // ---- 拖尾: two loose threads peeling off past the tail end. ----
    val wispLen = tailLength * 0.38f
    val wispStart = tailX + tailLength * 0.18f
    val wispBrush = Brush.horizontalGradient(
        colors = listOf(hueTail.copy(alpha = 0.3f), Color.Transparent),
        startX = wispStart,
        endX = tailX - wispLen,
    )
    val wispOffsets = listOf(0.35f to 1.5.dp.toPx(), 0.6f to -1.5.dp.toPx())
    for ((phaseOff, vOff) in wispOffsets) {
        val wisp = Path()
        var wx = wispStart
        wisp.moveTo(wx, baseY + vOff + amplitude * 0.8f * waveOffset(wx - headX, wavelength, wavePhase + phaseOff))
        while (wx > tailX - wispLen) {
            wx = (wx - step).coerceAtLeast(tailX - wispLen)
            wisp.lineTo(wx, baseY + vOff + amplitude * 0.8f * waveOffset(wx - headX, wavelength, wavePhase + phaseOff))
        }
        drawPath(wisp, wispBrush, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * Rounded pill button filled with the *living* aurora gradient — the fill's
 * hues drift around the spectrum and its tilt sways like a hanging curtain.
 * Used for primary actions ("全部播放", "网页登录", ...).
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    phaseOffset: Float = 0f,
) {
    val shape = RoundedCornerShape(20.dp)
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.45f)
    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = Color(0x40000000),
                spotColor = Color(0x40000000),
            )
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.auroraFill(shape, phaseOffset = phaseOffset)
                } else {
                    Modifier.background(
                        SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                    )
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Thin rounded progress bar whose fill is the living aurora gradient. The
 * fill's hues drift around the shared spectrum at all times; while [animated]
 * is true (music playing) the colors additionally flow through the fill and
 * the progress crawls smoothly between the player's 500 ms position ticks
 * instead of stepping.
 */
@Composable
fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 3.dp,
    animated: Boolean = true,
) {
    val fraction = progress.coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // Color-only consumer → quantized aurora clock (~8 redraws/s, not 60+).
    val aurora = LocalAuroraColorPhase.current

    // Snap on big backward jumps (track change / notification seek) so the
    // bar never glides the WRONG way across the whole track; otherwise tween
    // to erase the 500 ms position ticks. Same shape as GradientSeekBar.
    var lastTarget by remember { mutableFloatStateOf(fraction) }
    val snapNow = fraction < lastTarget - 0.02f
    LaunchedEffect(fraction) { lastTarget = fraction }

    // Buttery crawl: interpolate between the discrete position ticks.
    // Remembered so the animation isn't restarted by recompositions.
    val glideSpec = remember { tween<Float>(durationMillis = 500, easing = LinearEasing) }
    val snapSpec = remember { snap<Float>() }
    val display by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (snapNow) snapSpec else glideSpec,
        label = "miniProgress",
    )

    val phase: Float = if (animated) {
        val infinite = rememberInfiniteTransition(label = "miniFlow")
        val p by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(AuroraFlowDurationMs, easing = LinearEasing),
            ),
            label = "miniPhase",
        )
        p
    } else {
        0f
    }

    Canvas(modifier) {
        val corner = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        drawRoundRect(
            color = trackColor,
            cornerRadius = corner,
        )
        val fillWidth = size.width * display
        if (fillWidth > 0f) {
            val hue = aurora.value
            val brush = if (animated) {
                flowingAuroraBrush(size.width, phase, size.height, hue)
            } else {
                // Paused: the hues still drift slowly with the global clock.
                auroraBrushAt(hue, size)
            }
            drawRoundRect(
                brush = brush,
                size = Size(fillWidth, size.height),
                cornerRadius = corner,
            )
        }
    }
}

/** Rounded-square album artwork with a soft shadow. */
@Composable
fun AlbumCover(
    model: Any?,
    size: Dp,
    corner: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(corner)
    val base = Modifier
        .size(size)
        .shadow(
            elevation = 6.dp,
            shape = shape,
            ambientColor = Color(0x2E000000),
            spotColor = Color(0x2E000000),
        )
        .clip(shape)
    val modifier = if (onClick != null) base.clickable(onClick = onClick) else base
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

/**
 * Tappable + draggable seek bar threaded by a single aurora silk ribbon.
 *
 * Decorations (always alive):
 *  - a comet-like silk tail streams from the thumb bead: a tapered aurora
 *    band anchored to the bead and stretching back over exactly the played
 *    portion of the track — its length mirrors the progress — waving above
 *    and below the bar as the bead travels; it fades toward its far end
 *    (the bar's start) with loose trailing threads and a soft aura;
 *  - the thumb is a small aurora bead the same thickness as the bar itself;
 *    touching/dragging it pops it up with a springy scale.
 *
 * While [active] (music playing):
 *  - the aurora gradient flows continuously through the filled part,
 *  - the thumb carries a breathing aurora halo,
 *  - progress glides smoothly between the player's 500 ms position ticks.
 *
 * Large backward jumps (track change, seek from the notification) snap
 * instantly instead of gliding backwards.
 *
 * [progress] is 0f..1f. A tap or drag calls [onValueChange] with the touched
 * fraction so the parent can preview it; the actual seek is committed once via
 * [onSeekFinished] (the parent applies the real seek there). If the gesture is
 * cancelled (stolen by a parent scroll, system gesture, ...) [onSeekCancelled]
 * is called instead so the parent can drop its preview without seeking.
 */
@Composable
fun GradientSeekBar(
    progress: Float,
    onValueChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSeekCancelled: () -> Unit = {},
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(progress) }
    val target = (if (dragging) dragValue else progress).coerceIn(0f, 1f)

    // The gesture handlers are installed once (pointerInput(Unit)) but the
    // callbacks they must invoke can change identity on every recomposition
    // (they capture parent state like sliderPos). Remembered latest values
    // keep the long-lived gesture coroutines calling the CURRENT callbacks —
    // otherwise a stale closure silently seeks through an outdated lambda.
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)
    val currentOnSeekCancelled by rememberUpdatedState(onSeekCancelled)

    // Snap on big backward jumps (track change / notification seek) so the
    // bar never glides the wrong way; otherwise tween to erase the 500 ms
    // position ticks. The remembered value lags one composition, which is
    // exactly what the comparison needs. Remembered specs keep the running
    // animation stable across recompositions.
    var lastTarget by remember { mutableFloatStateOf(progress) }
    val snapNow = dragging || target < lastTarget - 0.02f
    LaunchedEffect(target) { lastTarget = target }

    val glideSpec = remember { tween<Float>(durationMillis = 500, easing = LinearEasing) }
    val snapSpec = remember { snap<Float>() }
    val display by animateFloatAsState(
        targetValue = target,
        animationSpec = if (snapNow) snapSpec else glideSpec,
        label = "seekProgress",
    )

    // The global aurora clock — read in the draw phase below so the whole
    // bar breathes with the app without recomposing. Quantized twin: the
    // bar's hue reads only need ~8 changes a second, not display rate.
    val aurora = LocalAuroraColorPhase.current

    // Ambient ribbon drift — the silk field floats whether or not music plays.
    val ribbonDrift = rememberInfiniteTransition(label = "seekRibbons")
    val ribbonPhase by ribbonDrift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(RibbonDriftDurationMs, easing = LinearEasing),
        ),
        label = "seekRibbonPhase",
    )

    // Flowing aurora + breathing halo, only while music is playing.
    val phase: Float
    val breath: Float
    if (active) {
        val infinite = rememberInfiniteTransition(label = "seekGlow")
        val p by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(AuroraFlowDurationMs, easing = LinearEasing),
            ),
            label = "seekPhase",
        )
        val b by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "seekBreath",
        )
        phase = p
        breath = b
    } else {
        phase = 0f
        breath = 0f
    }

    // The bead pops up a little while being touched/dragged.
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.6f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "seekThumbScale",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            // Tap anywhere on the bar to seek directly to that position.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        currentOnValueChange(fraction)
                        currentOnSeekFinished()
                    },
                )
            }
            // Drag the thumb to scrub; commit the seek when the finger lifts.
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        dragValue = fraction
                        currentOnValueChange(fraction)
                    },
                    onDrag = { change, _ ->
                        dragValue = (change.position.x / size.width).coerceIn(0f, 1f)
                        currentOnValueChange(dragValue)
                    },
                    onDragEnd = {
                        dragging = false
                        currentOnSeekFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        // Gesture stolen/cancelled: abort the drag preview
                        // without committing a seek.
                        currentOnSeekCancelled()
                    },
                )
            },
    ) {
        val maxW = maxWidth

        // Ribbons + track + fill, drawn on a canvas so everything animates
        // every frame without recomposing.
        Canvas(
            Modifier.matchParentSize(),
        ) {
            val barHeight = 6.dp.toPx()
            val corner = CornerRadius(barHeight / 2f, barHeight / 2f)
            val barTop = (size.height - barHeight) / 2f
            val fillWidth = size.width * display
            val hue = aurora.value

            // ---- The silk tail: streams from the thumb bead ----
            // Anchored at the bead and stretching back over EXACTLY the
            // played portion of the track: the ribbon's head rides on the
            // bead and its length equals the played progress, growing with
            // playback (bead → bar start). Clipped to the bar bounds so the
            // loose threads never spill past the track's start. Drawn before
            // the bar so the bead and fill thread over it.
            if (fillWidth > 1f) {
                clipRect {
                    drawSilkTail(
                        headX = fillWidth,
                        baseY = barTop + barHeight / 2f,
                        amplitude = 13.dp.toPx(),
                        headThickness = 4.5.dp.toPx(),
                        tailLength = fillWidth,
                        wavePhase = ribbonPhase,
                        wavelength = size.width / 2.4f,
                        hue = hue,
                    )
                }
            }

            // ---- Track + flowing aurora fill ----
            drawRoundRect(
                color = Color(0x26FFFFFF),
                topLeft = Offset(0f, barTop),
                size = Size(size.width, barHeight),
                cornerRadius = corner,
            )
            if (fillWidth > 0f) {
                val brush = if (active) {
                    flowingAuroraBrush(size.width, phase, size.height, hue)
                } else {
                    // Paused: the hues still drift slowly with the clock.
                    auroraBrushAt(hue, size)
                }
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(0f, barTop),
                    size = Size(fillWidth, barHeight),
                    cornerRadius = corner,
                )
            }

            // ---- Breathing halo — an expanding, fading aurora ring ----
            // behind the bead (drawn here so it animates in the draw phase).
            if (active) {
                val haloScale = thumbScale * (1f + breath * 1.2f)
                drawCircle(
                    color = auroraColorAt(hue).copy(alpha = 0.3f * (1f - breath)),
                    radius = 6.dp.toPx() * haloScale,
                    center = Offset(fillWidth, size.height / 2f),
                )
            }
        }

        // Thumb — an aurora bead as thick as the bar, ringed in white so it
        // reads over both track and fill; pops up while dragged. The offset
        // lambda reads `display` in the PLACEMENT phase: the 500 ms glide
        // re-places the bead without recomposing this content at all.
        Box(
            Modifier
                .offset { IntOffset(((maxW.toPx() * display) - 3.dp.toPx()).roundToInt(), 0) }
                .size(6.dp)
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x40000000),
                    spotColor = Color(0x40000000),
                )
                .clip(CircleShape)
                .auroraFill(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        )
    }
}

/**
 * Immersive backdrop shared by the Now Playing and Lyrics screens: the album
 * cover blurred and dimmed, over a vertical dark scrim, with a faint living
 * aurora mist drifting on top so even the immersive screens keep breathing
 * with the rest of the app.
 */
@Composable
fun BlurredCoverBackdrop(cover: String?) {
    Box(Modifier.fillMaxSize()) {
        // Modifier.blur is a no-op below API 31 (no RenderEffect): a sharp
        // full-bleed cover would fight every text on the screen, so on old
        // devices the backdrop degrades to scrim + aurora only.
        val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (cover != null && blurSupported) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .alpha(0.4f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x33000000), AppGradients.ScrimDark),
                    ),
                ),
        )
        // No base gradient here — only the soft drifting curtains and stars,
        // laid gently over the scrim.
        AuroraSky(
            dark = true,
            intensity = 0.55f,
            base = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Shared seek bar + current/total time row for the player screens. While the
 * user drags, the finger position is shown locally instead of sending a seek
 * per drag event; the actual seek is committed once when the gesture finishes
 * (and then held optimistically by [PlayerController] until the player
 * confirms it, so the bar never rubber-bands back).
 *
 * Self-collects [PlayerController.positionMs]: the 2 Hz tick recomposes THIS
 * component only — the parent screen passes just [isPlaying]/[durationMs]
 * (which change rarely), so no whole-screen recomposition per tick.
 */
@Composable
fun PlayerSeekBar(
    isPlaying: Boolean,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    var sliderPos by remember { mutableStateOf<Float?>(null) }
    val positionMs by PlayerController.positionMs.collectAsState()
    val maxMs = durationMs.coerceAtLeast(1L)
    val displayPosMs = sliderPos?.let { (it * maxMs).toLong() }
        ?: positionMs.coerceIn(0, durationMs)

    Column(modifier) {
        GradientSeekBar(
            progress = (displayPosMs / maxMs.toFloat()).coerceIn(0f, 1f),
            onValueChange = { sliderPos = it },
            onSeekFinished = {
                sliderPos?.let { PlayerController.seekTo((it * maxMs).toLong()) }
                sliderPos = null
            },
            onSeekCancelled = { sliderPos = null },
            active = isPlaying,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(formatMs(displayPosMs), style = MaterialTheme.typography.labelSmall, color = White70)
            Spacer(Modifier.weight(1f))
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = White70)
        }
    }
}

/**
 * Circular glassy icon button used on the immersive (dark) player screens.
 * When [accent] is true the icon is tinted with the living aurora accent —
 * resolved *inside* this button so the per-frame aurora read only ever
 * recomposes this small leaf, never the caller's screen.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    tint: Color = White70,
    size: Dp = 48.dp,
    accent: Boolean = false,
) {
    val accentColor = if (accent) auroraAccent() else tint
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x1FFFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (accent) accentColor else tint,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** Simple centered hint text. Callers pass fillMaxSize() to center it. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Track row used by library screens (favorites / playlist detail), styled as a card. */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
    resolving: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumCover(track.cover, 48.dp, 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.author != null) {
                Text(
                    track.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (resolving) {
            // 解析音源中: persistent per-row spinner; tap the row again to cancel.
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
        }
        trailing()
    }
}

/** "新建歌单" input dialog shared by the Now Playing and Library screens.
 *  Confirm is disabled while the name is blank or a creation is already in
 *  flight ([busy]) — double-taps could otherwise create "歌单"/"歌单 (1)". */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    busy: Boolean = false,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("歌单名称") },
                singleLine = true,
                enabled = !busy,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotBlank() && !busy,
            ) { Text(if (busy) "创建中…" else "创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
