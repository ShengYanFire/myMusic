package com.mymusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.mymusic.player.player.PlayerController
import com.mymusic.player.player.PlayerUiState
import com.mymusic.player.ui.theme.AppGradients
import com.mymusic.player.ui.theme.Mint

object Routes {
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val LYRICS = "lyrics"
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(Routes.SEARCH, "搜索", Icons.Filled.Search),
    NavItem(Routes.NOW_PLAYING, "播放", Icons.Filled.MusicNote),
    NavItem(Routes.LIBRARY, "我的", Icons.Filled.LibraryMusic),
    NavItem(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

/**
 * Navigate to a bottom-bar tab destination (bottom bar taps, mini player opens,
 * and "jump to player" actions from search/library results).
 *
 * EVERY navigation to a tab route must use these options, identical to the
 * bottom bar's own navigation. Navigation 2.8.2 maps the saved state of a
 * non-inclusive `popUpTo(target) { saveState = true }` onto the target
 * destination itself (see NavController.executePopOperations), so if a tab
 * destination is ever pushed above the start destination with a bare
 * `navigate(route)`, the later popUpTo(startDestination){saveState} maps
 * backStackMap[startDestination] to that saved state and `restoreState = true`
 * brings the pushed screen straight back — the tab then appears completely
 * unresponsive (e.g. tapping 搜索 while the player sits above it on the stack).
 * Routing all tab jumps through these options keeps the back stack in the
 * shape the bottom-bar pattern expects and keeps tab state restorable.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    val playerState by PlayerController.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.message.collect { msg ->
            if (msg != null) {
                snackbarHostState.showSnackbar(msg)
                vm.consumeMessage()
            }
        }
    }

    val showMiniPlayer = currentRoute != Routes.NOW_PLAYING &&
        currentRoute != Routes.LYRICS &&
        currentRoute != Routes.LOGIN

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (showMiniPlayer) {
                    MiniPlayer(
                        state = playerState,
                        onOpen = { navController.navigateToTab(Routes.NOW_PLAYING) },
                        onToggle = { PlayerController.togglePlayPause() },
                        onNext = { PlayerController.playNext() },
                    )
                }
                AuroraBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { route -> navController.navigateToTab(route) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(AppGradients.backgroundBrush(isSystemInDarkTheme())),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.SEARCH,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.SEARCH) {
                    SearchScreen(
                        vm = vm,
                        onOpenPlayer = { navController.navigateToTab(Routes.NOW_PLAYING) },
                        onOpenSettings = { navController.navigateToTab(Routes.SETTINGS) },
                    )
                }
                composable(Routes.NOW_PLAYING) {
                    NowPlayingScreen(
                        vm = vm,
                        onOpenLyrics = { navController.navigate(Routes.LYRICS) },
                    )
                }
                composable(Routes.LYRICS) {
                    LyricsScreen(
                        vm = vm,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        vm = vm,
                        onOpenPlayer = { navController.navigateToTab(Routes.NOW_PLAYING) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        vm = vm,
                        onOpenLogin = { navController.navigate(Routes.LOGIN) },
                    )
                }
                composable(Routes.LOGIN) {
                    LoginScreen(
                        vm = vm,
                        onClose = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/** Floating glassy mini player shown above the bottom bar while a track is loaded. */
@Composable
private fun MiniPlayer(
    state: PlayerUiState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val current = state.current ?: return
    val fraction = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0x52000000),
                spotColor = Color(0x52000000),
            )
            .clip(RoundedCornerShape(20.dp))
            .background(AppGradients.BarGlass),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 10.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = current.cover,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    current.author ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play/pause — a small aurora bead.
                Box(
                    Modifier
                        .size(38.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = CircleShape,
                            ambientColor = Mint.copy(alpha = 0.45f),
                            spotColor = Mint.copy(alpha = 0.45f),
                        )
                        .clip(CircleShape)
                        .background(AppGradients.primaryBrush())
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onNext),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        GradientProgressBar(
            progress = fraction,
            animated = state.isPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
        )
    }
}

/** Custom aurora bottom navigation bar with a glass background + gradient pill indicator. */
@Composable
private fun AuroraBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(AppGradients.BarGlass, AppGradients.BarBottom),
                ),
            ),
    ) {
        // Aurora hairline on top of the bar.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppGradients.primaryBrush()),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            navItems.forEach { item ->
                val selected = currentRoute == item.route
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onNavigate(item.route) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .shadow(
                                    elevation = if (selected) 10.dp else 0.dp,
                                    shape = CircleShape,
                                    ambientColor = Mint.copy(alpha = 0.5f),
                                    spotColor = Mint.copy(alpha = 0.5f),
                                )
                                .clip(CircleShape)
                                .background(
                                    if (selected) AppGradients.primaryBrush() else SolidColor(Color.Transparent),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
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
