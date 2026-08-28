package com.mymusic.player.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mymusic.player.data.Playlist
import com.mymusic.player.domain.Track
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    vm: MainViewModel,
    onPlayTrack: (Track) -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
) {
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Playlist?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    if (selected != null) {
        val playlist = selected!!
        PlaylistDetailScreen(
            playlist = playlist,
            onBack = { selected = null },
            onPlayAll = {
                scope.launch {
                    vm.showMessage("解析音源中…")
                    onPlayQueue(resolveAll(vm, playlist.tracks), 0)
                }
            },
            onPlay = { list, index ->
                scope.launch {
                    vm.showMessage("解析音源中…")
                    onPlayQueue(resolveAll(vm, list), index)
                }
            },
            onRemove = { bvid ->
                scope.launch { vm.removeFromPlaylist(playlist.id, bvid) }
            },
            onRename = { name ->
                scope.launch { vm.renamePlaylist(playlist.id, name) }
            },
            onDelete = {
                scope.launch {
                    vm.deletePlaylist(playlist.id)
                    selected = null
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("收藏") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("歌单") },
            )
        }

        when (tab) {
            0 -> FavoritesContent(
                favorites = favorites,
                onPlay = { track ->
                    scope.launch {
                        vm.showMessage("解析音源中…")
                        onPlayTrack(resolveOne(vm, track))
                    }
                },
                onRemove = { track -> scope.launch { vm.toggleFavorite(track) } },
            )
            else -> PlaylistsContent(
                playlists = playlists,
                onOpen = { selected = it },
                onCreate = { showCreateDialog = true },
            )
        }
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
}

@Composable
private fun FavoritesContent(
    favorites: List<Track>,
    onPlay: (Track) -> Unit,
    onRemove: (Track) -> Unit,
) {
    if (favorites.isEmpty()) {
        EmptyHint("还没有收藏，播放时点 ♥ 收藏", Modifier.fillMaxSize())
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(favorites, key = { it.bvid }) { track ->
            TrackRow(
                track = track,
                onClick = { onPlay(track) },
                trailing = {
                    IconButton(onClick = { onRemove(track) }) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "取消收藏",
                            tint = MaterialTheme.colorScheme.primary,
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
        TextButton(
            onClick = onCreate,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("新建歌单")
        }
        if (playlists.isEmpty()) {
            EmptyHint("暂无歌单", Modifier.fillMaxSize())
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(playlist) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
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
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: Playlist,
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
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                Icon(Icons.Filled.PlayArrow, contentDescription = "全部播放")
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
            itemsIndexed(playlist.tracks, key = { _, track -> track.bvid }) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { onPlay(playlist.tracks, index) },
                    trailing = {
                        IconButton(onClick = { onRemove(track.bvid) }) {
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

/** Re-resolve a track's audio URL (stored library URLs expire over time). */
private suspend fun resolveOne(vm: MainViewModel, track: Track): Track =
    runCatching { vm.resolveAudio(track) }.getOrElse { track }

private suspend fun resolveAll(vm: MainViewModel, tracks: List<Track>): List<Track> =
    tracks.map { t -> runCatching { vm.resolveAudio(t) }.getOrElse { t } }
