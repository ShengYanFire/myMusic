package com.mymusic.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.mymusic.player.data.Playlist
import com.mymusic.player.domain.Track
import com.mymusic.player.player.PlayerController
import com.mymusic.player.ui.theme.Accent
import com.mymusic.player.ui.theme.AccentIcon
import com.mymusic.player.ui.theme.AccentText
import com.mymusic.player.ui.theme.accentColor
import com.mymusic.player.ui.theme.accentFill
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
    // The queue sheet's scroll position outlives the sheet itself: it is hoisted
    // to this screen (and remembered as saveable) so closing/reopening the sheet
    // — or navigating away and back — restores the previous position instead of
    // the top. queueAutoScroll brings the playing song into view only on the
    // very first open, then stays off so the user's own scroll wins afterwards.
    val queueListState = rememberSaveableLazyListState()
    var queueAutoScroll by rememberSaveable { mutableStateOf(true) }

    if (current == null) {
        EmptyHint(
            "暂无播放，去搜索吧",
            Modifier.fillMaxSize(),
        )
        return
    }

    // ---- Whole-page swipe up/down to switch tracks ----
    // The user drags anywhere on the page vertically; past the threshold the
    // current song is skipped (up = next, down = previous) and the page
    // content springs back.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 90.dp.toPx() }
    val hintThresholdPx = with(density) { 24.dp.toPx() }
    var swipeDrag by remember { mutableStateOf(0f) }
    var trackSwitching by remember { mutableStateOf(false) }
    // Follows the finger while dragging; springs back to 0 on release/switch.
    val contentOffset by animateFloatAsState(
        targetValue = swipeDrag,
        animationSpec = tween(160),
        label = "swipeOffset",
    )

    // Skip to the next/previous song, with a snackbar when the queue ends.
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
        // alone decides.
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

    // The vertical drag detector lives on the root Box so the gesture works
    // anywhere on the play page. It only claims VERTICAL drags, so the seek
    // bar's horizontal drag, all taps/buttons and the album-art tap keep
    // working untouched (child gesture detectors win for their own gestures).
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { swipeDrag = 0f },
                    onDragCancel = { swipeDrag = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    if (!trackSwitching) {
                        swipeDrag = (swipeDrag + dragAmount)
                            .coerceIn(-swipeThresholdPx * 1.4f, swipeThresholdPx * 1.4f)
                        if (abs(swipeDrag) >= swipeThresholdPx) {
                            val goNext = swipeDrag < 0f
                            trackSwitching = true
                            swipeDrag = 0f
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
                        translationY = contentOffset * 0.35f
                        alpha = (1f - abs(contentOffset) / swipeThresholdPx * 0.4f)
                            .coerceIn(0.6f, 1f)
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // ---- Eyebrow marker ----
                EyebrowLabel()

                Spacer(Modifier.height(12.dp))

                // ---- Album artwork (tap the cover to open lyrics) ----
                CoverArtwork(
                    cover = current.cover,
                    onClick = onOpenLyrics,
                )

                Spacer(Modifier.height(16.dp))

                // ---- Title / artist ----
                Text(
                    current.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    current.author ?: "未知作者",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                // their own cadence — isolating them here lets the surrounding
                // screen skip while only this small block recomposes.
                PlayerStatusLine(
                    retrying = state.retrying,
                    loadingNextUid = state.loadingNextUid,
                    buffering = state.buffering,
                )

                Spacer(Modifier.height(16.dp))

                // ---- Seek bar ----
                PlayerSeekBar(durationMs = state.durationMs)

                Spacer(Modifier.height(12.dp))

                // ---- 播放模式 (grouped mode toggles): shuffle · repeat ----
                ModeToggleCluster(
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                )

                Spacer(Modifier.height(10.dp))

                // ---- 主控制 (primary transport): previous · play · next ----
                TransportControls(isPlaying = state.isPlaying)

                Spacer(Modifier.height(10.dp))

                // ---- 更多操作 (secondary actions): favorite · playlist · queue · sleep ----
                // uid (not bvid): a specific 分P is favorited, not the video.
                val isFavorite = favorites.any { it.uid == current.uid }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LabeledIconButton(
                        onClick = { scope.launch { vm.toggleFavorite(current) } },
                        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        label = "收藏",
                        accent = isFavorite,
                    )
                    LabeledIconButton(
                        onClick = { showPlaylistDialog = true },
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        label = "歌单",
                    )
                    LabeledIconButton(
                        onClick = { showQueueSheet = true },
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        label = "队列",
                    )
                    LabeledIconButton(
                        onClick = { showSleepDialog = true },
                        icon = Icons.Filled.Timer,
                        label = sleepRemaining?.let { formatMs(it) } ?: "定时",
                        accent = sleepRemaining != null,
                        contentDescription = "定时停止播放",
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // Swipe direction hint — page-level overlay so it stays centered while
        // the content block slides. Visibility is derived (recomposes only
        // when the threshold is CROSSED, not every drag frame), and the label
        // itself is an isolated leaf: while dragging, only this hint redraws.
        val showHint by remember {
            derivedStateOf { abs(contentOffset) > hintThresholdPx && !trackSwitching }
        }
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (showHint) {
                SwipeDirectionHint(contentOffset)
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
            listState = queueListState,
            autoScrollToCurrent = queueAutoScroll,
            onAutoScrollConsumed = { queueAutoScroll = false },
            onDismiss = { showQueueSheet = false },
            onPlay = { index, track ->
                showQueueSheet = false
                if (!PlayerController.skipTo(track.uid)) {
                    vm.requestPlay(state.queue, index, useListAsQueue = true) {}
                }
            },
        )
    }
}

/** Static rounded-square album artwork (tap to open lyrics), like 网易云. */
@Composable
private fun CoverArtwork(
    cover: String?,
    onClick: () -> Unit,
) {
    AsyncImage(
        model = cover,
        contentDescription = "查看歌词",
        modifier = Modifier
            .size(236.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        contentScale = ContentScale.Crop,
    )
}

/**
 * Grouped playback-mode toggles (随机 / 循环) in one neutral capsule. Each
 * toggle is tinted with the flat brand red when active.
 */
@Composable
private fun ModeToggleCluster(shuffleEnabled: Boolean, repeatMode: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeToggleItem(
            icon = Icons.Filled.Shuffle,
            label = if (shuffleEnabled) "随机开" else "随机",
            active = shuffleEnabled,
            contentDescription = if (shuffleEnabled) "关闭随机播放" else "随机播放",
            onClick = { PlayerController.toggleShuffle() },
        )
        Box(
            Modifier
                .width(1.dp)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        )
        ModeToggleItem(
            icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                Icons.Filled.RepeatOne
            } else {
                Icons.Filled.Repeat
            },
            label = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "单曲循环"
                Player.REPEAT_MODE_ALL -> "列表循环"
                else -> "顺序"
            },
            active = repeatMode > 0,
            contentDescription = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "单曲循环"
                Player.REPEAT_MODE_ALL -> "列表循环"
                else -> "顺序播放"
            },
            onClick = { PlayerController.cycleRepeatMode() },
        )
    }
}

/** One toggle inside [ModeToggleCluster]: icon + label, red-colored when on. */
@Composable
private fun ModeToggleItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val color = if (active) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * Primary transport cluster: previous · play/pause · next, centered with the
 * big red play button as the single focal point.
 */
@Composable
private fun TransportControls(isPlaying: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            onClick = { PlayerController.playPrevious() },
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "上一首",
            size = 52.dp,
        )
        Spacer(Modifier.width(28.dp))
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .accentFill()
                .clickable { PlayerController.togglePlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.width(28.dp))
        GlassIconButton(
            onClick = { PlayerController.playNext() },
            icon = Icons.Filled.SkipNext,
            contentDescription = "下一首",
            size = 52.dp,
        )
    }
}

/** Small "正在播放" eyebrow label above the cover: a red dot + letter-spaced gray. */
@Composable
private fun EyebrowLabel() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "正在播放",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 3.sp,
        )
    }
}

/**
 * Icon button with a caption underneath — the clean, labeled secondary action
 * cluster (收藏 / 歌单 / 队列 / 定时). When [accent] is set the icon and caption
 * both use the flat brand red.
 */
@Composable
private fun LabeledIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    accent: Boolean = false,
    contentDescription: String? = null,
) {
    val color = if (accent) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription ?: label,
                tint = color,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

/** "上滑 · 下一首 / 下滑 · 上一首" pill — isolated so per-frame drag updates
 *  only recompose this leaf. */
@Composable
private fun SwipeDirectionHint(swipeDrag: Float) {
    Text(
        if (swipeDrag < 0f) "上滑 · 下一首" else "下滑 · 上一首",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * Transient playback status hints (切源 / 解析下一首 / 缓冲) as an isolated
 * leaf: the whole surrounding screen stays skippable while only these few
 * lines react to their own fields.
 */
@Composable
private fun PlayerStatusLine(
    retrying: Boolean,
    loadingNextUid: String?,
    buffering: Boolean,
) {
    val message: String? = when {
        retrying -> "正在切换音源…"
        loadingNextUid != null -> "正在解析下一首…"
        buffering -> "缓冲中…"
        else -> null
    }
    message?.let { text ->
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 正在播放列表: the current play queue in a bottom sheet — the whole list
 * behind the playing song, including entries whose audio is still being
 * resolved in the background. The playing entry is accent-highlighted and
 * the sheet opens with it in view; tapping an entry jumps to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    listState: LazyListState,
    autoScrollToCurrent: Boolean,
    onAutoScrollConsumed: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: (Int, Track) -> Unit,
) {
    val state by PlayerController.state.collectAsState()
    val queue = state.queue
    val queueIndex = state.queueIndex
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // First open: bring the playing song into view (3 songs of context above).
    // Later opens reuse the hoisted listState, so the user's own scroll position
    // is preserved instead of being overridden back to the playing song.
    LaunchedEffect(Unit) {
        if (autoScrollToCurrent && queueIndex > 2) {
            listState.scrollToItem(queueIndex - 3)
        }
        onAutoScrollConsumed()
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

/** One entry of the 正在播放列表 sheet; the playing one is accent-highlighted. */
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
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (playing) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
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
                AccentText(
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
            AccentIcon(
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