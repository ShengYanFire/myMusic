package com.mymusic.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.mymusic.player.data.Playlist
import com.mymusic.player.domain.Track
import com.mymusic.player.player.PlayerController
import com.mymusic.player.ui.theme.AppGradients
import com.mymusic.player.ui.theme.Mint
import com.mymusic.player.ui.theme.Rose
import com.mymusic.player.ui.theme.Sky
import kotlinx.coroutines.launch

private val White70 = Color(0xB3FFFFFF)
private val White40 = Color(0x66FFFFFF)
private val ActiveMint = Color(0xFF5EEAD4)

@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    onOpenLyrics: () -> Unit = {},
) {
    val state by PlayerController.state.collectAsState()
    val current = state.current
    val repeatMode by PlayerController.repeatMode.collectAsState()
    val sleepRemaining by PlayerController.sleepRemainingMs.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val scope = rememberCoroutineScope()

    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }

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

    // While the user drags, show the finger position locally instead of
    // sending a seek per drag event; commit the actual seek when finished.
    // The committed position itself is held optimistically by
    // PlayerController until the player confirms the seek, so the bar never
    // rubber-bands back to the pre-seek position.
    var sliderPos by remember { mutableStateOf<Float?>(null) }

    val maxMs = state.durationMs.coerceAtLeast(1L)
    val displayPosMs = sliderPos?.let { (it * maxMs).toLong() }
        ?: state.positionMs.coerceIn(0, state.durationMs)
    val progress = (displayPosMs / maxMs.toFloat()).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        // ---- Immersive blurred cover backdrop ----
        AsyncImage(
            model = current.cover,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp)
                .alpha(0.4f),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x33000000), AppGradients.ScrimDark),
                    ),
                ),
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(6.dp))

                // ---- Rotating vinyl disc (tap to open the lyrics page) ----
                Box(
                    Modifier
                        .size(270.dp)
                        .clickable(onClick = onOpenLyrics),
                    contentAlignment = Alignment.Center,
                ) {
                    // Soft aurora glow behind the disc.
                    Box(
                        Modifier
                            .size(320.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Mint.copy(alpha = 0.35f), Color.Transparent),
                                ),
                            ),
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
                        AsyncImage(
                            model = current.cover,
                            contentDescription = "查看歌词",
                            modifier = Modifier
                                .size(196.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
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
                            .background(AppGradients.primaryBrush()),
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
                if (state.retrying) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "正在切换音源…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFC96B),
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(22.dp))

                // ---- Repeat / sleep row ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                        tint = if (repeatMode > 0) ActiveMint else White40,
                    )
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
                        tint = if (sleepRemaining != null) ActiveMint else White40,
                    )
                }

                Spacer(Modifier.height(18.dp))

                // ---- Gradient seek bar (flowing aurora while playing) ----
                GradientSeekBar(
                    progress = progress,
                    onValueChange = { sliderPos = it },
                    onSeekFinished = {
                        sliderPos?.let { PlayerController.seekTo((it * maxMs).toLong()) }
                        sliderPos = null
                    },
                    onSeekCancelled = { sliderPos = null },
                    active = state.isPlaying,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(formatMs(displayPosMs), style = MaterialTheme.typography.labelSmall, color = White70)
                    Spacer(Modifier.weight(1f))
                    Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall, color = White70)
                }

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
                                ambientColor = Sky.copy(alpha = 0.55f),
                                spotColor = Mint.copy(alpha = 0.55f),
                            )
                            .clip(CircleShape)
                            .background(AppGradients.primaryBrush())
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

                // ---- Favorite / playlist ----
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    val isFavorite = favorites.any { it.bvid == current.bvid }
                    GlassIconButton(
                        onClick = { scope.launch { vm.toggleFavorite(current) } },
                        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isFavorite) Rose else White70,
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
                    vm.addToPlaylist(id, current)
                    vm.showMessage("已加入歌单")
                    showPlaylistDialog = false
                }
            },
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                scope.launch {
                    vm.createPlaylist(name.ifBlank { "新歌单" })
                    showCreateDialog = false
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
}

/** Circular glassy icon button used on the (dark) now-playing screen. */
@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    tint: Color = White70,
    size: Dp = 48.dp,
) {
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
            tint = tint,
            modifier = Modifier.size(size * 0.55f),
        )
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

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("歌单名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Track row used by library screens (favorites / playlist detail), styled as a card. */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
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
        trailing()
    }
}
