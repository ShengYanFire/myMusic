package com.mymusic.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.mymusic.player.ui.theme.accentColor
import com.mymusic.player.ui.theme.accentFill
import com.mymusic.player.ui.theme.accentHorizontalGradientBrush
import kotlin.math.roundToInt

/**
 * Rounded pill button filled with the FLAT brand red (网易云红). Used for
 * primary actions ("全部播放", "网页登录", ...).
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(18.dp)
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.55f)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.accentFill()
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
 * Red "全部播放" pill, shared by search results, recommendations and playlist
 * headers so the icon/text stay consistent everywhere.
 */
@Composable
fun PlayAllButton(onClick: () -> Unit, enabled: Boolean = true) {
    GradientButton(
        text = "全部播放",
        icon = Icons.Filled.PlayArrow,
        onClick = onClick,
        enabled = enabled,
    )
}

/**
 * Thin rounded progress bar whose fill is the flat brand red. The progress
 * crawls smoothly between the player's 500 ms position ticks; while [animated]
 * is false (music paused) the fill reads a touch dimmer.
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

    // Snap on big backward jumps (track change / notification seek) so the
    // bar never glides the WRONG way across the whole track; otherwise tween
    // to erase the 500 ms position ticks. Same shape as GradientSeekBar.
    var lastTarget by remember { mutableFloatStateOf(fraction) }
    val snapNow = fraction < lastTarget - 0.02f
    LaunchedEffect(fraction) { lastTarget = fraction }

    val glideSpec = remember { tween<Float>(durationMillis = 500, easing = LinearEasing) }
    val snapSpec = remember { snap<Float>() }
    val display by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (snapNow) snapSpec else glideSpec,
        label = "miniProgress",
    )

    Canvas(modifier) {
        val corner = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        drawRoundRect(
            color = trackColor,
            cornerRadius = corner,
        )
        val fillWidth = size.width * display
        if (fillWidth > 0f) {
            drawRoundRect(
                brush = accentHorizontalGradientBrush(fillWidth, if (animated) 1f else 0.7f),
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
            elevation = 2.dp,
            shape = shape,
            ambientColor = Color(0x14000000),
            spotColor = Color(0x14000000),
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
 * Tappable + draggable seek bar with a flat red fill (no glow).
 *
 * Progress glides smoothly between the player's 500 ms position ticks; large
 * backward jumps (track change, notification seek) snap instantly instead of
 * gliding backwards. Touching/dragging pops the thumb up with a springy scale.
 *
 * [progress] is 0f..1f. A tap or drag calls [onValueChange] with the touched
 * fraction so the parent can preview it; the actual seek is committed once via
 * [onSeekFinished]. If the gesture is cancelled, [onSeekCancelled] is called
 * instead so the parent can drop its preview without seeking.
 */
@Composable
fun GradientSeekBar(
    progress: Float,
    onValueChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSeekCancelled: () -> Unit = {},
    modifier: Modifier = Modifier,
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

    // The bead pops up a little while being touched/dragged.
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.6f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "seekThumbScale",
    )

    val trackColor = MaterialTheme.colorScheme.surfaceVariant

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

        // Track + fill, drawn on a canvas (no per-frame glow).
        Canvas(
            Modifier.matchParentSize(),
        ) {
            val barHeight = 4.dp.toPx()
            val corner = CornerRadius(barHeight / 2f, barHeight / 2f)
            val barTop = (size.height - barHeight) / 2f
            val fillWidth = size.width * display

            // ---- Track ----
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, barTop),
                size = Size(size.width, barHeight),
                cornerRadius = corner,
            )

            // ---- Red fill (same-hue gradient: deep → bright toward playhead) ----
            if (fillWidth > 0f) {
                drawRoundRect(
                    brush = accentHorizontalGradientBrush(fillWidth),
                    topLeft = Offset(0f, barTop),
                    size = Size(fillWidth, barHeight),
                    cornerRadius = corner,
                )
            }
        }

        // Thumb — a flat red bead ringed in the surface color so it reads over
        // both track and fill; pops up while dragged. The offset lambda reads
        // `display` in the PLACEMENT phase: the 500 ms glide re-places the bead
        // without recomposing this content at all.
        Box(
            Modifier
                .offset { IntOffset(((maxW.toPx() * display) - 5.dp.toPx()).roundToInt(), 0) }
                .size(10.dp)
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .clip(CircleShape)
                .accentFill(),
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
 * component only — the parent screen passes just [durationMs] (which changes
 * rarely), so no whole-screen recomposition per tick.
 */
@Composable
fun PlayerSeekBar(
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
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                formatMs(displayPosMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatMs(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Clean circular icon button used on the light player / lyric screens. When
 * [accent] is true the icon is tinted with the flat brand red.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 48.dp,
    accent: Boolean = false,
) {
    val accentTint = if (accent) accentColor() else tint
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (accent) accentTint else tint,
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

/**
 * A [LazyListState] whose scroll position survives the list leaving
 * composition: navigating to the player, switching tabs, or closing and
 * reopening the queue sheet then restores where the user was instead of
 * snapping back to the top. [LazyListState.Saver] persists the first visible
 * item index + pixel offset through the surrounding saved-state registry
 * (the NavBackStackEntry for a destination) across recomposition and process
 * death.
 */
@Composable
fun rememberSaveableLazyListState(): LazyListState =
    rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

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
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
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