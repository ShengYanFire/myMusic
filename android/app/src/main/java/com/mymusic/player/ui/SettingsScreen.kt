package com.mymusic.player.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymusic.player.data.AppSettings
import com.mymusic.player.domain.MusicGenres
import com.mymusic.player.domain.MusicMoods
import com.mymusic.player.ui.theme.AccentText
import com.mymusic.player.ui.theme.accentFill
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onOpenLogin: () -> Unit = {},
) {
    val quality by vm.quality.collectAsState()
    val favoriteGenres by vm.favoriteGenres.collectAsState()
    val favoriteMoods by vm.favoriteMoods.collectAsState()
    val isLoggedIn by vm.isLoggedIn.collectAsState()
    val scope = rememberCoroutineScope()

    // Both preference panels start collapsed (their summaries are always
    // visible); saved so tab switches / recreations keep the state.
    var genresExpanded by rememberSaveable { mutableStateOf(false) }
    var moodsExpanded by rememberSaveable { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    // Typed result (Boolean?: null = not tested yet) — the old String? +
    // contains("正常") check broke the moment a message wording changed.
    var testOk by remember { mutableStateOf<Boolean?>(null) }

    // Static option tables — remembered instead of rebuilt per recomposition.
    val genreOptions = remember { MusicGenres.ALL.map { it.id to it.label } }
    val moodOptions = remember { MusicMoods.ALL.map { it.id to it.label } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
        )

        // ---- Account ----
        SettingCard(title = "账号", icon = Icons.AutoMirrored.Filled.Login) {
            if (isLoggedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF16A34A)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "已登录",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "已保存登录态，用于获取更高音质音源、更少被风控拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    SecondaryButton(
                        text = "退出登录",
                        onClick = { scope.launch { vm.clearCookie() } },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    GradientButton(
                        text = "重新登录",
                        icon = Icons.AutoMirrored.Filled.Login,
                        onClick = onOpenLogin,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    "在 App 内完成网页登录（账号密码 / 短信验证码 / 扫码均可），登录成功自动保存，音质更高、更少被风控拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                GradientButton(
                    text = "网页登录 B 站账号",
                    icon = Icons.AutoMirrored.Filled.Login,
                    onClick = onOpenLogin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Music preference (drives the recommendation feed) ----
        CollapsiblePreferenceCard(
            title = "音乐偏好",
            icon = Icons.Filled.Tune,
            description = "选择喜欢的音乐类型（可多选），与心情偏好一起决定推荐内容；不选则随机推荐热门音乐。",
            options = genreOptions,
            selectedIds = favoriteGenres,
            onToggleOption = vm::toggleGenre,
            expanded = genresExpanded,
            onToggleExpanded = { genresExpanded = !genresExpanded },
            unitNoun = "个类型",
        )

        Spacer(Modifier.height(16.dp))

        // ---- Mood preference (drives the recommendation feed too) ----
        CollapsiblePreferenceCard(
            title = "心情偏好",
            icon = Icons.Filled.Mood,
            description = "选择想听的心情（可多选），与音乐类型一起决定推荐内容。",
            options = moodOptions,
            selectedIds = favoriteMoods,
            onToggleOption = vm::toggleMood,
            expanded = moodsExpanded,
            onToggleExpanded = { moodsExpanded = !moodsExpanded },
            unitNoun = "种心情",
        )

        Spacer(Modifier.height(16.dp))

        // ---- Quality ----
        SettingCard(title = "音质", icon = Icons.Filled.GraphicEq) {
            Row(Modifier.fillMaxWidth()) {
                QualityOption(
                    label = "高清（默认）",
                    selected = quality != AppSettings.QUALITY_LOW,
                    onClick = { scope.launch { vm.saveQuality(AppSettings.QUALITY_HIGH) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(12.dp))
                QualityOption(
                    label = "流畅（省流量）",
                    selected = quality == AppSettings.QUALITY_LOW,
                    onClick = { scope.launch { vm.saveQuality(AppSettings.QUALITY_LOW) } },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "影响音源码率：高清码率更高，流畅更省流量。改动即时生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- Network ----
        SettingCard(title = "网络", icon = Icons.Filled.NetworkCheck) {
            Text(
                "测试能否连通 B 站接口，排查风控 / 网络问题。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            GradientButton(
                text = if (testing) "测试中…" else "测试连接",
                icon = Icons.Filled.NetworkCheck,
                onClick = {
                    scope.launch {
                        testing = true
                        testOk = null
                        testOk = vm.testConnection()
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            )
            if (testing) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "正在测试…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            testOk?.let { ok ->
                Spacer(Modifier.height(12.dp))
                Text(
                    if (ok) "B 站连接正常" else "无法连接 B 站",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- About ----
        SettingCard(title = "说明", icon = Icons.Filled.Info) {
            Text(
                "• 本 App 完全自包含，直接连接 B 站官方接口，无需任何服务器。\n" +
                    "• 音源来自 B 站视频，仅供个人学习使用，请遵守平台规则与版权规定。\n" +
                    "• 后台播放：播放后切到后台或锁屏仍会继续，通知栏与锁屏可控制播放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .accentFill(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun QualityOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (selected) {
                    Modifier.accentFill()
                } else {
                    Modifier.background(
                        SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Collapsible preference card (音乐偏好 / 心情偏好): the header row — icon,
 * title and a rotating chevron — is always visible and toggles the body with
 * a smooth size animation. Collapsed, a one-line summary of the current
 * selection is shown instead of the chip grid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsiblePreferenceCard(
    title: String,
    icon: ImageVector,
    description: String,
    options: List<Pair<String, String>>,
    selectedIds: Set<String>,
    onToggleOption: (String) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    unitNoun: String,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize()
            .padding(16.dp),
    ) {
        // ---- Header: always visible, tap to expand/collapse ----
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .accentFill(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
            )
        }

        if (expanded) {
            // ---- Expanded body: description + option chips + hint ----
            Spacer(Modifier.height(12.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { (id, label) ->
                    PreferenceChip(
                        label = label,
                        selected = id in selectedIds,
                        onClick = { onToggleOption(id) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            AccentText(
                if (selectedIds.isEmpty()) {
                    "尚未设置 · 更改后推荐列表会自动刷新"
                } else {
                    "已选 ${selectedIds.size} $unitNoun · 更改后推荐列表会自动刷新"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            // ---- Collapsed: one-line summary of the current selection ----
            Spacer(Modifier.height(10.dp))
            val labels = options.filter { it.first in selectedIds }.map { it.second }
            val summaryText = if (labels.isEmpty()) {
                "未设置 · 点此展开选择"
            } else {
                "已选 ${labels.size} $unitNoun：${summarizeLabels(labels)}"
            }
            val summaryModifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleExpanded)
            if (labels.isEmpty()) {
                Text(
                    summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = summaryModifier,
                )
            } else {
                // The summary is tinted with the fixed accent.
                AccentText(
                    summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = summaryModifier,
                )
            }
        }
    }
}

/** Tappable preference pill: fixed electric gradient + check mark when selected. */
@Composable
private fun PreferenceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (selected) {
                    Modifier.accentFill()
                } else {
                    Modifier.background(
                        SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text(
                "✓ ",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Secondary (non-gradient) action button — used for the destructive 退出登录. */
@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val contentColor = MaterialTheme.colorScheme.error
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, contentColor.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
    }
}
