package com.mymusic.player.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymusic.player.util.AppLog

/** Tail of the collected log shown in-app (rendering megabytes of text at once
 *  would stall composition); the newest entries always sit at the END. */
private const val MAX_DISPLAY_CHARS = 200_000

/**
 * 错误日志 viewer: shows the file [AppLog] has been collecting during daily
 * use — hard crashes (via the uncaught-exception handler), player errors,
 * network/API failures and 下一首/解析 failures — with copy / share / clear.
 * Entered from 设置.
 */
@Composable
fun LogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(AppLog.read()) }
    var justCleared by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Jump to the newest entries once the first frame has laid the text out.
    LaunchedEffect(Unit) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(Modifier.fillMaxSize()) {
        // ---- Top bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("错误日志", style = MaterialTheme.typography.titleLarge)
                Text(
                    "仅保存在本机，未上传",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
        }

        // ---- Actions ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                justCleared = false
                logText = AppLog.read()
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
            TextButton(onClick = { copyToClipboard(context, logText) }) {
                Text("复制")
            }
            GradientButton(text = "分享", icon = Icons.Filled.Share, onClick = { shareLog(context, logText) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                AppLog.clear()
                logText = ""
                justCleared = true
            }) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("清空", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(4.dp))

        // ---- Log body ----
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp),
            ) {
                val shown = logText.takeLast(MAX_DISPLAY_CHARS)
                if (shown.isBlank()) {
                    Text(
                        if (justCleared) "已清空，暂无日志" else "暂无日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (logText.length > MAX_DISPLAY_CHARS) {
                        Text(
                            "（仅显示末尾 $MAX_DISPLAY_CHARS 字符，底部为最新日志）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        shown,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("MyMusic 错误日志", text))
}

private fun shareLog(context: Context, text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "MyMusic 错误日志")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "分享错误日志"))
}