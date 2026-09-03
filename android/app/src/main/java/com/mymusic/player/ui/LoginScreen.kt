package com.mymusic.player.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mymusic.player.ui.theme.AppGradients
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bilibili web login embedded in a WebView. The official passport page supports
 * account+password (with slider captcha), SMS code and QR login — the user
 * completes it themselves, and the app harvests the resulting session cookies
 * (SESSDATA etc.) into settings, replacing the old "copy cookie by hand" flow.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    vm: MainViewModel,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }

    fun harvestAndFinish() {
        if (finished) return
        val cookie = CookieManager.getInstance().getCookie(COOKIE_DOMAIN)
        if (!cookie.isNullOrEmpty() && cookie.contains("SESSDATA=")) {
            finished = true
            scope.launch {
                vm.saveCookie(cookie)
                vm.showMessage("B 站登录成功，已保存登录态")
                onClose()
            }
        }
    }

    // Poll cookies until the login flow completes (it redirects to bilibili.com).
    LaunchedEffect(Unit) {
        while (!finished) {
            delay(1000)
            harvestAndFinish()
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (webView?.canGoBack() == true) webView?.goBack() else onClose()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column {
                Text("B 站登录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "支持账号密码 / 短信验证码 / 扫码，登录成功自动保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.28f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AppGradients.primaryBrush()),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // A normal Chrome UA avoids Bilibili risk-control flags.
                        settings.userAgentString = CHROME_UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                harvestAndFinish()
                            }

                            // Keep every navigation (including the post-login
                            // redirect) inside this WebView.
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = false
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
                update = { webView = it },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

private const val LOGIN_URL = "https://passport.bilibili.com/login"
private const val COOKIE_DOMAIN = "https://www.bilibili.com"
private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
