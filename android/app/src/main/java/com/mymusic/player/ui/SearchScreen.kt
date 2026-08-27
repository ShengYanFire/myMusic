package com.mymusic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mymusic.player.domain.Track
import com.mymusic.player.network.SearchItem
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    vm: MainViewModel,
    onPlayTrack: (Track) -> Unit,
    onPlayAll: (List<Track>) -> Unit,
) {
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()
    val error by vm.searchError.collectAsState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("搜索 B 站音乐 / UP 主") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = vm::clearSearch) {
                        Icon(Icons.Filled.Close, contentDescription = "清除")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                vm.searchNow(query)
            }),
        )

        when {
            searching && results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            error != null && results.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { vm.searchNow(query) }) {
                        Text("重试")
                    }
                }
            }

            results.isEmpty() -> {
                EmptyHint(
                    if (query.isBlank()) "输入关键词搜索 B 站音乐" else "没有找到相关结果",
                    Modifier.fillMaxSize(),
                )
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        TextButton(
                            onClick = {
                                scope.launch { playAll(vm, results, onPlayAll) }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("全部播放")
                        }
                    }
                    items(results, key = { it.bvid }) { item ->
                        SearchRow(
                            item = item,
                            onClick = {
                                scope.launch { playOne(vm, item, onPlayTrack) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(item: SearchItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.pic,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${item.author} · ${formatDuration(item.duration)} · ${formatPlayCount(item.play)} 播放",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.PlayCircleOutline,
            contentDescription = "播放",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

private suspend fun playOne(
    vm: MainViewModel,
    item: SearchItem,
    onPlayTrack: (Track) -> Unit,
) {
    vm.showMessage("解析音源中…")
    val track = item.toTrack()
    try {
        val resolved = vm.resolveAudio(track)
        onPlayTrack(resolved)
    } catch (e: Exception) {
        vm.showMessage("播放失败：${e.message ?: "无法获取音源"}")
    }
}

private suspend fun playAll(
    vm: MainViewModel,
    items: List<SearchItem>,
    onPlayAll: (List<Track>) -> Unit,
) {
    vm.showMessage("解析音源中…")
    val resolved = items.mapNotNull { item ->
        runCatching { vm.resolveAudio(item.toTrack()) }.getOrNull()
    }
    if (resolved.isEmpty()) {
        vm.showMessage("全部无法解析音源")
        return
    }
    onPlayAll(resolved)
}

private fun SearchItem.toTrack(): Track = Track(
    bvid = bvid,
    title = title,
    cover = pic,
    author = author,
    duration = duration,
)
