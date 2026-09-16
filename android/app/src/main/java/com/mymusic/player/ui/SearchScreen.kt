package com.mymusic.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mymusic.player.domain.MusicGenres
import com.mymusic.player.domain.MusicMoods
import com.mymusic.player.network.SearchItem
import com.mymusic.player.network.toTrack
import com.mymusic.player.ui.theme.AppGradients
import com.mymusic.player.ui.theme.AuroraSpectrumDeep
import com.mymusic.player.ui.theme.AuroraText
import com.mymusic.player.ui.theme.LocalAuroraPhase
import com.mymusic.player.ui.theme.TAU
import com.mymusic.player.ui.theme.auroraFill
import com.mymusic.player.ui.theme.auroraGlow
import kotlin.math.sin

/**
 * Search page: a single scroll container so the hero banner scrolls away while
 * the search bar sticks to the top. Below it sits either search results
 * (pull-to-refresh re-searches) or the recommendation feed (pull-to-refresh
 * reloads, scrolling to the bottom loads the next batch automatically).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    vm: MainViewModel,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()
    val error by vm.searchError.collectAsState()
    val recommendations by vm.recommendations.collectAsState()
    val loadingRecommendations by vm.loadingRecommendations.collectAsState()
    val loadingMoreRecommendations by vm.loadingMoreRecommendations.collectAsState()
    val hasMoreRecommendations by vm.hasMoreRecommendations.collectAsState()
    val recommendError by vm.recommendError.collectAsState()
    val loadingMoreSearch by vm.loadingMoreSearch.collectAsState()
    val hasMoreSearch by vm.hasMoreSearch.collectAsState()
    val favoriteGenres by vm.favoriteGenres.collectAsState()
    val favoriteMoods by vm.favoriteMoods.collectAsState()
    val recommendGeneration by vm.recommendGeneration.collectAsState()
    val resolvingUid by vm.resolvingUid.collectAsState()
    val searchHistory by vm.searchHistory.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Scroll the recommendation feed back to its header whenever the pool is
    // reloaded (pull-to-refresh, or a music-preference change re-personalizing
    // the feed). Generation 1 is the initial load — nothing to scroll back to.
    LaunchedEffect(recommendGeneration) {
        if (recommendGeneration > 1 && recommendations.isNotEmpty()) {
            listState.scrollToItem(2) // banner(0) + search(1) → feed header
        }
    }

    // Pull-to-refresh indicator shows only when there is already content —
    // derived so the (cheap) comparison re-runs only when one of its inputs
    // actually flips, not on every keystroke recomposition.
    val isRefreshing by remember(query) {
        derivedStateOf {
            if (query.isBlank()) {
                loadingRecommendations && recommendations.isNotEmpty()
            } else {
                searching && results.isNotEmpty()
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (query.isBlank()) vm.refreshRecommendations() else vm.searchNow(query)
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
            // ---- Hero banner (scrolls away with the list) ----
            item(key = "banner") {
                HeroBanner()
            }

            // ---- Search bar (sticks at the top after the banner scrolls off) ----
            stickyHeader(key = "search") {
                StickySearchBar(
                    query = query,
                    onQueryChange = vm::onQueryChange,
                    onClear = vm::clearSearch,
                    onSearch = {
                        keyboard?.hide()
                        vm.searchNow(query)
                    },
                )
            }

            // ---- Content ----
            when {
                // Initial search in progress.
                searching && results.isEmpty() -> {
                    item(key = "spinner") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }

                // Search failed with nothing to show.
                error != null && results.isEmpty() -> {
                    item(key = "error") {
                        Column(
                            Modifier.fillParentMaxSize(),
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
                }

                // Search results (pull down at the top to re-search, scroll to
                // the bottom to load the next page).
                results.isNotEmpty() -> {
                    item(key = "results-header") {
                        ListHeaderRow(
                            title = "搜索结果",
                            onPlayAll = {
                                vm.requestPlay(results.map { it.toTrack() }) { onOpenPlayer() }
                            },
                        )
                    }
                    itemsIndexed(results, key = { _, it -> it.bvid }) { index, item ->
                        SearchRow(
                            item = item,
                            resolving = resolvingUid == item.bvid,
                            onClick = {
                                if (resolvingUid == item.bvid) {
                                    vm.cancelPlay()
                                } else {
                                    vm.requestPlay(results.map { it.toTrack() }, index) {
                                        onOpenPlayer()
                                    }
                                }
                            },
                        )
                    }
                    item(key = "search-load-more") {
                        LoadMoreRow(
                            hasMore = hasMoreSearch,
                            loading = loadingMoreSearch,
                            onLoadMore = vm::loadMoreSearch,
                        )
                    }
                }

                // Empty query → recommendation feed (loads more at the bottom), with
                // the search history section shown above it once any exists.
                query.isBlank() -> {
                    if (searchHistory.isNotEmpty()) {
                        item(key = "search-history") {
                            SearchHistorySection(
                                history = searchHistory,
                                onPick = { vm.searchNow(it) },
                                onRemove = vm::removeSearchHistory,
                                onClear = vm::clearSearchHistory,
                            )
                        }
                    }
                    when {
                        loadingRecommendations && recommendations.isEmpty() -> {
                            item(key = "rec-loading") {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }

                        recommendError != null && recommendations.isEmpty() -> {
                            item(key = "rec-error") {
                                Column(
                                    Modifier.fillParentMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(recommendError!!, color = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.height(12.dp))
                                    TextButton(onClick = vm::loadRecommendations) {
                                        Text("重试")
                                    }
                                }
                            }
                        }

                        recommendations.isEmpty() -> {
                            item(key = "rec-empty") {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "暂时没有推荐，试试输入关键词搜索",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        else -> {
                            item(key = "rec-header") {
                                RecommendHeader(
                                    favoriteGenreIds = favoriteGenres,
                                    favoriteMoodIds = favoriteMoods,
                                    onOpenSettings = onOpenSettings,
                                    onPlayAll = {
                                        vm.requestPlay(recommendations.map { it.toTrack() }) {
                                            onOpenPlayer()
                                        }
                                    },
                                )
                            }
                            itemsIndexed(recommendations, key = { _, it -> it.bvid }) { index, item ->
                                SearchRow(
                                    item = item,
                                    resolving = resolvingUid == item.bvid,
                                    onClick = {
                                        if (resolvingUid == item.bvid) {
                                            vm.cancelPlay()
                                        } else {
                                            vm.requestPlay(
                                                recommendations.map { it.toTrack() },
                                                index,
                                            ) {
                                                onOpenPlayer()
                                            }
                                        }
                                    },
                                )
                            }
                            // Load-more sentinel: composing near the bottom triggers
                            // the next batch to be appended automatically.
                            item(key = "load-more") {
                                LoadMoreRow(
                                    hasMore = hasMoreRecommendations,
                                    loading = loadingMoreRecommendations,
                                    onLoadMore = vm::loadMoreRecommendations,
                                )
                            }
                        }
                    }
                }

                // Non-blank query, no matches.
                else -> {
                    item(key = "no-results") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "没有找到相关结果",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Top aurora banner; as the first list item it scrolls away with content. */
@Composable
private fun HeroBanner() {
    Box(
        Modifier
            .fillMaxWidth()
            .auroraFill(spectrum = AuroraSpectrumDeep),
    ) {
        // Decorative glow circles — an aurora haze.
        Box(
            Modifier
                .size(170.dp)
                .align(Alignment.TopEnd)
                .offset(x = 44.dp, y = (-60).dp)
                .background(Color.White.copy(alpha = 0.10f), CircleShape),
        )
        Box(
            Modifier
                .size(240.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 96.dp, y = 60.dp)
                .background(Color.Black.copy(alpha = 0.16f), CircleShape),
        )
        // A living aurora halo echoing the drifting accent.
        Box(
            Modifier
                .size(90.dp)
                .align(Alignment.TopEnd)
                .offset(x = (-70).dp, y = 34.dp)
                .auroraGlow(alpha = 0.30f),
        )
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 30.dp)) {
            Text(
                "MyMusic",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.78f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "发现好音乐",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "搜索 B 站音乐 / UP 主，点击即播",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(14.dp))
            EqualizerBars()
        }
    }
}

/** Five rounded bars bobbing quietly on the shared aurora clock. */
@Composable
private fun EqualizerBars() {
    val aurora = LocalAuroraPhase.current
    Canvas(Modifier.size(width = 60.dp, height = 26.dp)) {
        val t = aurora.value
        val barWidth = 6.dp.toPx()
        val gap = 5.dp.toPx()
        val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        listOf(10, 20, 14, 26, 16).forEachIndexed { i, base ->
            // Integer clock multiplier → the dance loops seamlessly.
            val wave = 0.55f + 0.45f * (0.5f + 0.5f * sin(t * TAU * 2f + i * 1.9f))
            val h = base.dp.toPx() * wave
            drawRoundRect(
                color = Color.White.copy(alpha = 0.65f),
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = corner,
            )
        }
    }
}

/** Search field pinned as a sticky header; its band matches the page background. */
@Composable
private fun StickySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    Box(
        Modifier
            .fillMaxWidth()
            .background(AppGradients.backgroundTop(dark))
            .padding(top = 6.dp, bottom = 6.dp),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color(0x40000000),
                    spotColor = Color(0x40000000),
                )
                .clip(RoundedCornerShape(28.dp)),
            placeholder = {
                Text("搜索歌曲、UP 主、视频", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "清除")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
    }
}

/**
 * 搜索历史 section shown above the recommendation feed while the search box is
 * EMPTY (once any history exists): tap a chip to re-search, tap the × to drop
 * just that one entry, or 清空 to clear all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistorySection(
    history: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "搜索历史",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("清空") }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { term ->
                HistoryChip(
                    term = term,
                    onPick = { onPick(term) },
                    onRemove = { onRemove(term) },
                )
            }
        }
    }
}

/** History chip: tap the label to re-search, tap the × to drop just this entry. */
@Composable
private fun HistoryChip(term: String, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .clickable(onClick = onPick)
            .padding(start = 12.dp, top = 5.dp, bottom = 5.dp, end = 2.dp),
    ) {
        Text(
            term,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(26.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "删除这条记录",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Section title + "全部播放" action row (shared by results & recommendations). */
@Composable
private fun ListHeaderRow(title: String, onPlayAll: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        GradientButton(
            text = "全部播放",
            icon = Icons.Filled.PlayArrow,
            onClick = onPlayAll,
        )
    }
}

/**
 * Recommendation header: "为你推荐" when genre/mood preferences are set (with
 * a summary of the liked genres & moods), otherwise a plain "推荐" plus a
 * hint. The title block opens the settings page where 音乐/心情偏好 can be
 * edited.
 */
@Composable
private fun RecommendHeader(
    favoriteGenreIds: Set<String>,
    favoriteMoodIds: Set<String>,
    onOpenSettings: () -> Unit,
    onPlayAll: () -> Unit,
) {
    val labels = MusicGenres.labelsOf(favoriteGenreIds) + MusicMoods.labelsOf(favoriteMoodIds)
    val hasPreference = labels.isNotEmpty()
    // Show up to 4 genre/mood labels, then "等 N 项" for the rest.
    val summary = if (hasPreference) {
        val shown = labels.take(4).joinToString(" · ")
        if (labels.size > 4) "$shown 等 ${labels.size} 项" else shown
    } else {
        null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenSettings)
                .padding(vertical = 2.dp),
        ) {
            Text(
                if (hasPreference) "为你推荐" else "推荐",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            if (summary != null) {
                // Preference summary breathes with the aurora.
                AuroraText(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "点此设置喜欢的音乐类型，推荐更懂你",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        GradientButton(
            text = "全部播放",
            icon = Icons.Filled.PlayArrow,
            onClick = onPlayAll,
        )
    }
}

/**
 * Sentinel row at the bottom of the recommendation list. Once composed (i.e.
 * the user scrolls near the bottom) it asks the ViewModel to load the next
 * batch, showing a small spinner while that batch loads.
 */
@Composable
private fun LoadMoreRow(
    hasMore: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
) {
    if (!hasMore) return
    LaunchedEffect(Unit) { onLoadMore() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SearchRow(
    item: SearchItem,
    onClick: () -> Unit,
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
        AsyncImage(
            model = item.pic,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .shadow(6.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp)),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(38.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x40000000),
                    spotColor = Color(0x40000000),
                )
                .clip(CircleShape)
                .auroraFill(CircleShape, phaseOffset = 0.05f),
            contentAlignment = Alignment.Center,
        ) {
            if (resolving) {
                // 解析音源中: persistent per-row spinner (the 4s snackbar alone
                // used to leave the screen looking frozen); tap again to cancel.
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
