package com.mymusic.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mymusic.player.domain.LyricLine
import com.mymusic.player.player.PlayerController
import com.mymusic.player.player.PlayerUiState
import com.mymusic.player.ui.theme.White40
import com.mymusic.player.ui.theme.White70
import com.mymusic.player.ui.theme.auroraAccent
import com.mymusic.player.ui.theme.auroraFill

/**
 * Immersive full-screen lyrics page, opened by tapping the album cover on
 * the Now Playing screen.
 *
 * Lyrics come from Bilibili subtitles of the video (perfectly synced) or, as
 * a fallback, from LRCLIB. The active line is highlighted and kept roughly a
 * third from the top of the list; tapping a synced line seeks there. While
 * the user's finger is on the list, auto-scrolling pauses so it never fights
 * a manual scroll. A compact seek bar + transport controls at the bottom
 * keep full playback control without leaving the lyrics.
 */
@Composable
fun LyricsScreen(
    vm: MainViewModel,
    onClose: () -> Unit,
) {
    val playerState by PlayerController.state.collectAsState()
    val current = playerState.current
    val lyricsState by vm.lyrics.collectAsState()

    // (Re)load lyrics whenever the played track changes (uid: multi-P pages
    // of one video each have their own subtitles).
    LaunchedEffect(current?.uid) {
        current?.let { vm.loadLyrics(it) }
    }

    Box(Modifier.fillMaxSize()) {
        // ---- Immersive blurred cover backdrop (same language as NowPlaying) ----
        BlurredCoverBackdrop(current?.cover)

        if (current == null) {
            EmptyHint("暂无播放，去搜索吧", Modifier.fillMaxSize())
            return@Box
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 20.dp, end = 20.dp),
        ) {
            // ---- Header: back / song info / refresh ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(
                    onClick = onClose,
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "返回",
                    size = 44.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        current.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.author ?: "未知作者",
                        style = MaterialTheme.typography.bodySmall,
                        color = White70,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(14.dp))
                // A refresh in flight (old lines still shown) lights the
                // button up with the aurora accent instead of blanking the list.
                GlassIconButton(
                    onClick = { vm.loadLyrics(current, force = true) },
                    icon = Icons.Filled.Refresh,
                    contentDescription = "重新获取歌词",
                    size = 44.dp,
                    accent = lyricsState.loading,
                )
            }

            // ---- Source badge ----
            lyricsState.source?.let { source ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "歌词来源 · $source",
                    style = MaterialTheme.typography.labelSmall,
                    color = White40,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(6.dp))

            // ---- Lyrics list area ----
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Generous top/bottom padding so the first and last lines can
                // also be centered in the viewport.
                val centerPadding = maxHeight * 0.34f
                val scrollTargetPx = with(LocalDensity.current) {
                    (maxHeight * 0.32f).toPx()
                }.toInt()

                when {
                    lyricsState.loading && lyricsState.lines.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AuroraSpinner()
                        }
                    }

                    lyricsState.lines.isEmpty() -> {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = White40,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                lyricsState.error ?: "暂无歌词",
                                style = MaterialTheme.typography.bodyMedium,
                                color = White70,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "该视频没有可用字幕，也没有匹配到网络歌词",
                                style = MaterialTheme.typography.bodySmall,
                                color = White40,
                                textAlign = TextAlign.Center,
                            )
                            TextButton(onClick = { vm.loadLyrics(current, force = true) }) {
                                Text("重试")
                            }
                        }
                    }

                    else -> LyricsList(
                        lines = lyricsState.lines,
                        synced = lyricsState.synced,
                        centerPadding = centerPadding,
                        scrollTargetPx = scrollTargetPx,
                    )
                }
            }

            // ---- Footer: seek bar + transport controls ----
            FooterControls(playerState = playerState)
        }
    }
}

/**
 * Scrollable lyrics with the active line highlighted (white, slightly
 * larger), neighbours dimmed, everything else faint. Tapping a synced line
 * seeks the player to it.
 *
 * Self-collects the 2 Hz position flow: the tick recomposes nothing above
 * this component, and [activeIndex] is DERIVED — between line changes the
 * tick re-evaluates one comparison instead of recomposing every row.
 */
@Composable
private fun LyricsList(
    lines: List<LyricLine>,
    synced: Boolean,
    centerPadding: Dp,
    scrollTargetPx: Int,
) {
    val listState = rememberLazyListState()
    var userTouching by remember { mutableStateOf(false) }
    val positionMs by PlayerController.positionMs.collectAsState()

    // The active line: the last line whose start has been reached. Derived so
    // the 500 ms position ticks only invalidate the list when the ACTIVE LINE
    // actually changes (not per tick, and not for the untimed plain lyrics).
    val activeIndex by remember(synced, lines) {
        derivedStateOf {
            if (!synced) -1 else lines.indexOfLast { it.startMs <= positionMs }
        }
    }

    // Keep the active line about a third from the top. Paused while the user
    // is touching the list so a manual scroll is never fought; resumes on the
    // next line change.
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0 || userTouching || !synced) return@LaunchedEffect
        listState.animateScrollToItem(activeIndex, scrollOffset = -scrollTargetPx)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    userTouching = true
                    waitForUpOrCancellation()
                    userTouching = false
                }
            },
        contentPadding = PaddingValues(vertical = centerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Keyed by start time: a lyrics refresh swaps the list wholesale, and
        // stable keys let rows (and their color/scale animations) survive
        // instead of all being treated as brand-new items.
        itemsIndexed(lines, key = { _, line -> line.startMs }) { index, line ->
            val active = index == activeIndex
            val near = !active && (index == activeIndex - 1 || index == activeIndex + 1)
            val color by animateColorAsState(
                targetValue = when {
                    active -> Color.White
                    near -> White70
                    else -> White40
                },
                animationSpec = tween(300),
                label = "lyricColor",
            )
            val scale by animateFloatAsState(
                targetValue = if (active) 1f else 0.92f,
                animationSpec = tween(300),
                label = "lyricScale",
            )
            Text(
                line.text,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clickable(enabled = synced) { PlayerController.seekTo(line.startMs) }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}

/** Loading spinner tinted with the living aurora accent (scoped leaf). */
@Composable
private fun AuroraSpinner() {
    CircularProgressIndicator(color = auroraAccent())
}

/** Seek bar + compact transport controls pinned to the bottom of the lyrics page. */
@Composable
private fun FooterControls(playerState: PlayerUiState) {
    Column(Modifier.fillMaxWidth()) {
        PlayerSeekBar(isPlaying = playerState.isPlaying, durationMs = playerState.durationMs)

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                onClick = { PlayerController.playPrevious() },
                icon = Icons.Filled.SkipPrevious,
                contentDescription = "上一首",
                size = 44.dp,
            )
            Spacer(Modifier.width(30.dp))
            Box(
                Modifier
                    .size(72.dp)
                    .shadow(12.dp, CircleShape, ambientColor = Color(0x4D000000))
                    .clip(CircleShape)
                    .auroraFill(CircleShape)
                    .clickable { PlayerController.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playerState.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.width(30.dp))
            GlassIconButton(
                onClick = { PlayerController.playNext() },
                icon = Icons.Filled.SkipNext,
                contentDescription = "下一首",
                size = 44.dp,
            )
        }

        Spacer(Modifier.height(6.dp))
    }
}
