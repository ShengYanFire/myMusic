package com.mymusic.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.mymusic.player.data.Playlist
import com.mymusic.player.domain.Track
import com.mymusic.player.player.PlayerController
import com.mymusic.player.ui.theme.AuroraIcon
import com.mymusic.player.ui.theme.AuroraText
import com.mymusic.player.ui.theme.Rose
import com.mymusic.player.ui.theme.White40
import com.mymusic.player.ui.theme.White70
import com.mymusic.player.ui.theme.auroraFill
import com.mymusic.player.ui.theme.auroraGlow
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    onOpenLyrics: () -> Unit = {},
) {
    val state by PlayerController.state.collectAsState()
    val current = state.current
    val repeatMode by PlayerController.repeatMode.collectAsState()
    val shuffleEnabled by PlayerController.shuffleEnabled.collectAsState()
    val sleepRemaining by PlayerController.sleepRemainingMs.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val scope = rememberCoroutineScope()

    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    if (current == null) {
        EmptyHint(
            "暂无播放，去搜索吧",
            Modifier.fillMaxSize(),
        )
        return
    }

    // Slow vinyl rotation — advances only while playing.
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            while (true) {
                val from = rotation.value % 360f
                rotation.snapTo(from)
                rotation.animateTo(from + 360f, animationSpec = tween(20000, easing = LinearEasing))
            }
        }
    }

    // ---- Whole-page swipe up/down to switch tracks ----
    // The user drags anywhere on the page vertically; past the threshold the
    // current song is skipped (up = next, down = previous) and the page
    // content springs back.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 90.dp.toPx() }
    val hintThresholdPx = with(density) { 24.dp.toPx() }
    var discDrag by remember { mutableStateOf(0f) }
    var trackSwitching by remember { mutableStateOf(false) }
    var swipeUp by remember { mutableStateOf(true) }
    // Follows the finger while dragging; springs back to 0 on release/switch.
    val discOffset by animateFloatAsState(
        targetValue = discDrag,
        animationSpec = tween(160),
        label = "discOffset",
    )

    // Skip to the next/previous song, with a snackbar when the queue ends.
    // The bounds check runs SYNCHRONOUSLY against the visible queue (the
    // old delay-then-inspect race mis-toasted "已经是最后一首了" while the
    // switch was merely still resolving), with the async check kept only as
    // a no-op fallback.
    fun switchTrack(goNext: Boolean) {
        // uid (not bvid): switching between 分P of one video IS a track switch.
        val beforeUid = state.current?.uid
        val beforePosMs = PlayerController.positionMs.value
        if (goNext) PlayerController.playNext() else PlayerController.playPrevious()
        var announced = false
        fun announce(msg: String) {
            if (!announced) {
                announced = true
                vm.showMessage(msg)
            }
        }
        // Synchronous queue-bounds check: list cycling wraps around, so only
        // a sequential run at the last/first entry is really "the end"; an
        // on-demand 下一首 resolution in progress means the switch IS coming.
        // Under shuffle the logical index says nothing about the end of the
        // random round, so this fast path is skipped and the async fallback
        // (which observes whether the track actually changed) alone decides.
        val q = state.queue
        val idx = state.queueIndex
        val wraps = repeatMode == Player.REPEAT_MODE_ALL
        val resolvingNext = state.loadingNextUid != null
        if (!shuffleEnabled) {
            if (goNext) {
                if (!wraps && !resolvingNext && q.isNotEmpty() && idx >= q.lastIndex) {
                    announce("已经是最后一首了")
                }
            } else {
                // playPrevious restarts the current song once it is a few seconds
                // in — that is not "no previous".
                if (idx <= 0 && beforePosMs <= 3_000) {
                    announce("已经是第一首了")
                }
            }
        }
        scope.launch {
            delay(320)
            val now = PlayerController.state.value
            val stayed = now.current?.uid == beforeUid
            val stillResolving = now.loadingNextUid != null
            if (stayed && !stillResolving && !(!goNext && beforePosMs > 3_000)) {
                announce(
                    if (goNext) {
                        if (shuffleEnabled) "这一轮随机播放已播完" else "已经是最后一首了"
                    } else {
                        if (shuffleEnabled) "已经回到随机播放的起点" else "已经是第一首了"
                    },
                )
            }
        }
    }

    // ---- Whole-page swipe up/down to switch tracks ----
    // The vertical drag detector lives on the root Box so the gesture works
    // anywhere on the play page. It only claims VERTICAL drags, so the seek
    // bar's horizontal drag, all taps/buttons and the album-art tap keep
    // working untouched (child gesture detectors win for their own gestures).
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { discDrag = 0f },
                    onDragCancel = { discDrag = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    if (!trackSwitching) {
                        discDrag = (discDrag + dragAmount)
                            .coerceIn(-swipeThresholdPx * 1.4f, swipeThresholdPx * 1.4f)
                        if (abs(discDrag) >= swipeThresholdPx) {
                            val goNext = discDrag < 0f
                            swipeUp = goNext
                            trackSwitching = true
                            discDrag = 0f
                            switchTrack(goNext)
                            scope.launch {
                                delay(420)
                                trackSwitching = false
                            }
                        }
                    }
                }
            },
    ) {
        // ---- Immersive blurred cover backdrop ----
        BlurredCoverBackdrop(current.cover)

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Whole page follows the finger while swiping; the whole
                    // content block slides up/down and fades slightly, then
                    // springs back (or the track changes).
                    .graphicsLayer {
                        translationY = discOffset * 0.35f
                        alpha = (1f - abs(discOffset) / swipeThresholdPx * 0.4f)
                            .coerceIn(0.6f, 1f)
                    }
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(6.dp))

                // ---- Rotating vinyl disc (tap to open the lyrics page) ----
                // Swiping anywhere on the page (including the album art) is
                // handled by the root Box's vertical drag detector.
                Box(
                    Modifier
                        .size(270.dp)
                        .clickable(onClick = onOpenLyrics),
                    contentAlignment = Alignment.Center,
                ) {
                    // Soft living aurora glow behind the disc — the hue
                    // drifts around the spectrum with the whole app.
                    Box(
                        Modifier
                            .size(320.dp)
                            .auroraGlow(alpha = 0.35f),
                    )
                    // Disc (outer ring + rotating cover).
                    Box(
                        Modifier
                            .size(250.dp)
                            .graphicsLayer { rotationZ = rotation.value }
                            .clip(CircleShape)
                            .background(Color(0xFF0A1A20)),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Cover slides in from the direction the disc was
                        // swiped when the track changes.
                        AnimatedContent(
                            targetState = current.uid,
                            transitionSpec = {
                                val enter = slideInVertically(tween(280)) {
                                    if (swipeUp) it / 5 else -it / 5
                                } + fadeIn(tween(280))
                                enter.togetherWith(fadeOut(tween(160)))
                            },
                            label = "cover",
                        ) {
                            AsyncImage(
                                model = current.cover,
                                contentDescription = "查看歌词",
                                modifier = Modifier
                                    .size(196.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    // Static ring over the cover edge.
                    Box(
                        Modifier
                            .size(196.dp)
                            .border(1.dp, Color(0x22FFFFFF), CircleShape),
                    )
                    // Center spindle.
                    Box(
                        Modifier
                            .size(64.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .auroraFill(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0x55000000)),
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    current.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    current.author ?: "未知作者",
                    style = MaterialTheme.typography.bodyMedium,
                    color = White70,
                )

                state.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                // Status line leaf: the retry/resolve/buffering hints change on
                // their own cadence — isolating them here (instead of four
                // inline ifs) lets the surrounding screen skip while only this
                // small block recomposes.
                PlayerStatusLine(
                    retrying = state.retrying,
                    loadingNextUid = state.loadingNextUid,
                    buffering = state.buffering,
                )

                Spacer(Modifier.height(22.dp))

                // ---- Shuffle / repeat (left) · sleep (right) row ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassIconButton(
                            onClick = { PlayerController.toggleShuffle() },
                            icon = Icons.Filled.Shuffle,
                            contentDescription = if (shuffleEnabled) "关闭随机播放" else "随机播放",
                            tint = White40,
                            accent = shuffleEnabled,
                        )
                        GlassIconButton(
                            onClick = { PlayerController.cycleRepeatMode() },
                            icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Filled.RepeatOne
                            } else {
                                Icons.Filled.Repeat
                            },
                            contentDescription = when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> "单曲循环"
                                Player.REPEAT_MODE_ALL -> "列表循环"
                                else -> "顺序播放"
                            },
                            tint = White40,
                            accent = repeatMode > 0,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sleepRemaining?.let {
                            Text(
                                "定时 ${formatMs(it)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = White70,
                            )
                        }
                        GlassIconButton(
                            onClick = { showSleepDialog = true },
                            icon = Icons.Filled.Timer,
                            contentDescription = "定时停止播放",
                            tint = White40,
                            accent = sleepRemaining != null,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ---- Gradient seek bar (flowing aurora while playing) ----
                PlayerSeekBar(isPlaying = state.isPlaying, durationMs = state.durationMs)

                Spacer(Modifier.height(20.dp))

                // ---- Transport controls ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(
                        onClick = { PlayerController.playPrevious() },
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                        size = 56.dp,
                    )
                    Spacer(Modifier.width(28.dp))
                    Box(
                        Modifier
                            .size(84.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = Color(0x4D000000),
                                spotColor = Color(0x4D000000),
                            )
                            .clip(CircleShape)
                            .auroraFill(CircleShape)
                            .clickable { PlayerController.togglePlayPause() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(46.dp),
                        )
                    }
                    Spacer(Modifier.width(28.dp))
                    GlassIconButton(
                        onClick = { PlayerController.playNext() },
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        size = 56.dp,
                    )
                }

                Spacer(Modifier.height(26.dp))

                // ---- Favorite / queue / playlist ----
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    // uid (not bvid): a specific 分P is favorited, not the video.
                    val isFavorite = favorites.any { it.uid == current.uid }
                    GlassIconButton(
                        onClick = { scope.launch { vm.toggleFavorite(current) } },
                        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isFavorite) Rose else White70,
                    )
                    GlassIconButton(
                        onClick = { showQueueSheet = true },
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "正在播放列表",
                        tint = White70,
                    )
                    GlassIconButton(
                        onClick = { showPlaylistDialog = true },
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "加入歌单",
                        tint = White70,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // Swipe direction hint — page-level overlay so it stays centered while
        // the content block slides. Visibility is derived (recomposes only
        // when the threshold is CROSSED, not every drag frame), and the label
        // itself is an isolated leaf: while dragging, only this hint redraws.
        val showHint by remember {
            derivedStateOf { abs(discOffset) > hintThresholdPx && !trackSwitching }
        }
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (showHint) {
                SwipeDirectionHint(discOffset)
            }
        }
    }

    if (showPlaylistDialog) {
        PlaylistPickerDialog(
            playlists = playlists,
            onDismiss = { showPlaylistDialog = false },
            onCreate = {
                showPlaylistDialog = false
                showCreateDialog = true
            },
            onPick = { id ->
                scope.launch {
                    // Boolean result: false means the song was ALREADY in the
                    // list — keep the picker open so the user picks another.
                    val added = vm.addToPlaylist(id, current)
                    if (added) {
                        vm.showMessage("已加入歌单")
                        showPlaylistDialog = false
                    } else {
                        vm.showMessage("这首歌已在歌单中")
                    }
                }
            },
        )
    }

    if (showCreateDialog) {
        var createBusy by remember { mutableStateOf(false) }
        CreatePlaylistDialog(
            onDismiss = { if (!createBusy) showCreateDialog = false },
            busy = createBusy,
            onConfirm = { name ->
                scope.launch {
                    createBusy = true
                    try {
                        vm.createPlaylist(name.ifBlank { "新歌单" })
                        showCreateDialog = false
                    } finally {
                        createBusy = false
                    }
                }
            },
        )
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            onDismiss = { showSleepDialog = false },
            onPick = { minutes ->
                PlayerController.setSleepTimer(minutes)
                showSleepDialog = false
            },
        )
    }

    if (showQueueSheet) {
        QueueSheet(
            onDismiss = { showQueueSheet = false },
            onPlay = { index, track ->
                showQueueSheet = false
                // Entries already loaded in the player jump instantly (a pure
                // seek); ones still resolving go through resolve-and-play —
                // through requestPlay, so the resolution is cancellable and
                // the row spinner reflects it.
                if (!PlayerController.skipTo(track.uid)) {
                    // Keep the queue as displayed — never re-expand the tapped
                    // entry into its 合集/分P here.
                    vm.requestPlay(state.queue, index, useListAsQueue = true) {}
                }
            },
        )
    }
}

/** "上滑 · 下一首 / 下滑 · 上一首" pill — isolated so per-frame drag updates
 *  only recompose this leaf. */
@Composable
private fun SwipeDirectionHint(discOffset: Float) {
    Text(
        if (discOffset < 0f) "上滑 · 下一首" else "下滑 · 上一首",
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
        modifier = Modifier
            .shadow(6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x73000000))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * Transient playback status hints (切源 / 解析下一首 / 缓冲) as an isolated
 * leaf: the whole surrounding screen stays skippable while only these few
 * lines react to their own fields.
 */
@Composable
private fun PlayerStatusLine(    retrying: Boolean,
    loadingNextUid: String?,
    buffering: Boolean,
) {
    val message: Pair<String, Color>? = when {
        retrying -> "正在切换音源…" to Color(0xFFFFC96B)
        loadingNextUid != null -> "正在解析下一首…" to Color(0xFF7EC8FF)
        // The seek already happened and the player wants to play, but the
        // audio stream is still loading from the CDN.
        buffering -> "缓冲中…" to Color(0xFF7EC8FF)
        else -> null
    }
    message?.let { (text, color) ->
        Spacer(Modifier.height(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 正在播放列表: the current play queue in a bottom sheet — the whole list
 * behind the playing song, including entries whose audio is still being
 * resolved in the background. The playing entry is aurora-highlighted and
 * the sheet opens with it in view; tapping an entry jumps to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    onDismiss: () -> Unit,
    onPlay: (Int, Track) -> Unit,
) {
    // The sheet collects the player state ITSELF: while the background queue
    // fill re-emits the queue, only this (currently open) sheet recomposes —
    // not the whole Now Playing screen behind it.
    val state by PlayerController.state.collectAsState()
    val queue = state.queue
    val queueIndex = state.queueIndex
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    // Open with the playing entry in view (a few rows of context above it).
    LaunchedEffect(Unit) {
        if (queueIndex > 2) listState.scrollToItem(queueIndex - 3)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("正在播放", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (queueIndex >= 0) "${queueIndex + 1} / ${queue.size}" else "${queue.size} 首",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (queue.isEmpty()) {
            EmptyHint("队列为空", Modifier.height(140.dp))
            Spacer(Modifier.height(16.dp))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                itemsIndexed(queue, key = { _, track -> track.uid }) { index, track ->
                    QueueRow(
                        track = track,
                        playing = index == queueIndex,
                        onClick = { onPlay(index, track) },
                    )
                }
            }
        }
    }
}

/** One entry of the 正在播放列表 sheet; the playing one is aurora-highlighted. */
@Composable
private fun QueueRow(
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (playing) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumCover(track.cover, 42.dp, 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (playing) {
                AuroraText(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!track.author.isNullOrBlank()) {
                Text(
                    track.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (playing) {
            AuroraIcon(
                Icons.Filled.GraphicEq,
                contentDescription = "正在播放",
                modifier = Modifier.size(20.dp),
            )
        } else if (track.duration > 0) {
            Text(
                formatDuration(track.duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SleepTimerDialog(
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val options = listOf(
        "关闭定时" to 0,
        "10 分钟" to 10,
        "30 分钟" to 30,
        "60 分钟" to 60,
        "90 分钟" to 90,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时停止播放") },
        text = {
            Column {
                options.forEach { (label, minutes) ->
                    TextButton(
                        onClick = { onPick(minutes) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入歌单") },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text("还没有歌单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = { onPick(playlist.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(playlist.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate) { Text("新建歌单") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
