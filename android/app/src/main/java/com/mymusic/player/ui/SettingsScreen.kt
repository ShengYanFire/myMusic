package com.mymusic.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val cookie by vm.cookie.collectAsState()
    val quality by vm.quality.collectAsState()
    val scope = rememberCoroutineScope()

    var cookieInput by remember { mutableStateOf(cookie) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // Keep input in sync with persisted value when the screen (re)opens.
    LaunchedEffect(cookie) { cookieInput = cookie }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("B 站 Cookie（可选）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = cookieInput,
            onValueChange = { cookieInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("SESSDATA=xxxx; bili_jct=xxxx; …") },
            minLines = 3,
        )
        Text(
            "登录 www.bilibili.com 后从浏览器开发者工具复制完整 Cookie。填入后可解锁更高音质、减少风控拦截。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Text("音质", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("高清（默认）", style = MaterialTheme.typography.bodyMedium)
            RadioButton(
                selected = quality != "low",
                onClick = { scope.launch { vm.saveQuality("high") } },
            )
            Spacer(Modifier.size(16.dp))
            Text("流畅（省流量）", style = MaterialTheme.typography.bodyMedium)
            RadioButton(
                selected = quality == "low",
                onClick = { scope.launch { vm.saveQuality("low") } },
            )
        }
        Text(
            "影响音源码率：高清码率更高，流畅更省流量。改动即时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Row {
            Button(
                onClick = {
                    scope.launch {
                        vm.saveCookie(cookieInput)
                        vm.showMessage("已保存")
                    }
                },
            ) {
                Text("保存")
            }
            Spacer(Modifier.size(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        testing = true
                        testResult = null
                        testResult = if (vm.testConnection()) "B 站连接正常" else "无法连接 B 站"
                        testing = false
                    }
                },
                enabled = !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("测试连接")
                }
            }
        }
        testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.contains("正常")) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "说明：\n" +
                "• 本 App 完全自包含，直接连接 B 站官方接口，无需任何服务器。\n" +
                "• 音源来自 B 站视频，仅供个人学习使用，请遵守平台规则与版权规定。\n" +
                "• 后台播放：播放后切到后台或锁屏仍会继续，通知栏与锁屏可控制播放。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
