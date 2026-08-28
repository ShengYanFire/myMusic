package com.mymusic.player.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mymusic.player.domain.Track
import com.mymusic.player.player.PlayerController

object Routes {
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(Routes.SEARCH, "搜索", Icons.Filled.Search),
    NavItem(Routes.NOW_PLAYING, "播放", Icons.Filled.MusicNote),
    NavItem(Routes.LIBRARY, "我的", Icons.Filled.LibraryMusic),
    NavItem(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.message.collect { msg ->
            if (msg != null) {
                snackbarHostState.showSnackbar(msg)
                vm.consumeMessage()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy
                            ?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SEARCH,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.SEARCH) {
                SearchScreen(
                    vm = vm,
                    onPlayTrack = { track ->
                        PlayerController.playTrack(track)
                        navController.navigate(Routes.NOW_PLAYING)
                    },
                    onPlayAll = { tracks ->
                        PlayerController.playQueue(tracks)
                        navController.navigate(Routes.NOW_PLAYING)
                    },
                )
            }
            composable(Routes.NOW_PLAYING) {
                NowPlayingScreen(vm = vm)
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    vm = vm,
                    onPlayTrack = { track ->
                        PlayerController.playTrack(track)
                        navController.navigate(Routes.NOW_PLAYING)
                    },
                    onPlayQueue = { tracks, index ->
                        PlayerController.playQueue(tracks, index)
                        navController.navigate(Routes.NOW_PLAYING)
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                )
            }
            composable(Routes.LOGIN) {
                LoginScreen(vm = vm, onClose = { navController.popBackStack() })
            }
        }
    }
}

/** Simple centered hint text. Callers pass fillMaxSize() to center it. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier,
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
