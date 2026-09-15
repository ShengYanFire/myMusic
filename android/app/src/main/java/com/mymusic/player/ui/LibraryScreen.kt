package com.mymusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import com.mymusic.player.data.Playlist
import com.mymusic.player.domain.Track
import com.mymusic.player.ui.theme.AuroraIcon
import com.mymusic.player.ui.theme.Rose
import com.mymusic.player.ui.theme.auroraFill
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    vm: MainViewModel,
    onOpenPlayer: () -> Unit,
) {
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val resolvingUid by vm.resolvingUid.collectAsState()
    val scope = rememberCoroutineScope()

    // rememberSaveable: the chosen tab / open playlist detail survive
    // configuration changes (rotation) and process death instead of
    // silently dumping the user back onto the favorites tab.
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Derive the detail playlist from the live flow so removals / renames
    // inside the detail screen are reflected immediately (a snapshot would go stale).
    val selectedPlaylist = selectedId?.let { id -> playlists.firstOrNull { it.id == id } }

    if (selectedPlaylist != null) {
        val playlist = selectedPlaylist
        PlaylistDetailScreen(
            playlist = playlist,
            resolvingUid = resolvingUid,
            onBack = { selectedId = null },
            onPlayAll = {
                vm.requestPlay(playlist.tracks, useListAsQueue = true) { onOpenPlayer() }
            },
            onPlay = play@{ list, index ->
                val track = list.getOrNull(index) ?: return@play
                if (resolvingUid == track.uid) {
                    vm.cancelPlay()
                } else {
                    vm.requestPlay(list, index, useListAsQueue = true) { onOpenPlayer() }
                }
            },
            onRemove = { uid ->
                // uid (not bvid): removes THIS 分P, not every page of the video.
                scope.launch { vm.removeFromPlaylist(playlist.id, uid) }
            },
            onRename = { name ->
                scope.launch { vm.renamePlaylist(playlist.id, name) }
            },
            onDelete = {
                scope.launch {
                    vm.deletePlaylist(playlist.id)
                    selectedId = null
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "我的音乐",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 10.dp),
        )
        SegmentedTabs(
            selected = tab,
            onSelect = { tab = it },
        )

        when (tab) {
            0 -> FavoritesContent(
                favorites = favorites,
                resolvingUid = resolvingUid,
                onPlay = play@{ index ->
                    val track = favorites.getOrNull(index) ?: return@play
                    if (resolvingUid == track.uid) {
                        vm.cancelPlay()
                    } else {
                        vm.requestPlay(favorites, index, useListAsQueue = true) { onOpenPlayer() }
                    }
                },
                onRemove = { track -> scope.launch { vm.toggleFavorite(track) } },
            )
            else -> PlaylistsContent(
                playlists = playlists,
                onOpen = { selectedId = it.id },
                onCreate = { showCreateDialog = true },
            )
        }
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
                        // A blank name never reaches here (confirm disabled);
                        // the store still guards against empty names itself.
                        vm.createPlaylist(name)
                        showCreateDialog = false
                    } finally {
                        createBusy = false
                    }
                }
            },
        )
    }
}

/** Pill-shaped segmented control with a living gradient indicator. */
@Composable
private fun SegmentedTabs(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf("收藏", "歌单")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = selected == i
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (isSelected) Modifier.auroraFill(RoundedCornerShape(12.dp)) else Modifier,
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun FavoritesContent(
    favorites: List<Track>,
    resolvingUid: String?,
    onPlay: (Int) -> Unit,
    onRemove: (Track) -> Unit,
) {
    if (favorites.isEmpty()) {
        EmptyHint("还没有收藏，播放时点 ♥ 收藏", Modifier.fillMaxSize())
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "${favorites.size} 首收藏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(favorites, key = { _, track -> track.uid }) { index, track ->
            TrackRow(
                track = track,
                onClick = { onPlay(index) },
                resolving = resolvingUid == track.uid,
                trailing = {
                    IconButton(onClick = { onRemove(track) }) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "取消收藏",
                            tint = Rose,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun PlaylistsContent(
    playlists: List<Playlist>,
    onOpen: (Playlist) -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${playlists.size} 个歌单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            GradientButton(
                text = "新建歌单",
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                onClick = onCreate,
            )
        }
        if (playlists.isEmpty()) {
            EmptyHint("暂无歌单", Modifier.fillMaxSize())
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .clickable { onOpen(playlist) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .shadow(6.dp, RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .auroraFill(RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${playlist.tracks.size} 首",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AuroraIcon(Icons.Filled.PlayArrow, contentDescription = "播放")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: Playlist,
    resolvingUid: String?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onPlayAll,
                enabled = playlist.tracks.isNotEmpty(),
            ) {
                AuroraIcon(Icons.Filled.PlayArrow, contentDescription = "全部播放")
            }
            IconButton(onClick = { showRenameDialog = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "重命名歌单")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除歌单")
            }
        }
        if (playlist.tracks.isEmpty()) {
            EmptyHint("歌单为空", Modifier.fillMaxSize())
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${playlist.tracks.size} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    GradientButton(
                        text = "全部播放",
                        icon = Icons.Filled.PlayArrow,
                        onClick = onPlayAll,
                        enabled = playlist.tracks.isNotEmpty(),
                    )
                }
            }
            itemsIndexed(playlist.tracks, key = { _, track -> track.uid }) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { onPlay(playlist.tracks, index) },
                    resolving = resolvingUid == track.uid,
                    trailing = {
                        IconButton(onClick = { onRemove(track.uid) }) {
                            Icon(Icons.Filled.Close, contentDescription = "移除")
                        }
                    },
                )
            }
        }
    }

    if (showRenameDialog) {
        RenamePlaylistDialog(
            initial = playlist.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                onRename(name)
                showRenameDialog = false
            },
        )
    }
}

@Composable
private fun RenamePlaylistDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("歌单名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
