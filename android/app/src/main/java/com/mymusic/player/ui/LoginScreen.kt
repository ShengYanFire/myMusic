package com.mymusic.player.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mymusic.player.network.BiliIdentity
import com.mymusic.player.ui.theme.AppGradients
import com.mymusic.player.ui.theme.LocalAuroraColorPhase
import com.mymusic.player.ui.theme.White70
import com.mymusic.player.ui.theme.auroraBrushAt
import com.mymusic.player.ui.theme.auroraFill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bilibili web login embedded in a WebView, styled to match the app's aurora
 * design language:
 *  - a glassy header (back / refresh) floating over the living sky, sealed by
 *    a full-width aurora hairline;
 *  - the passport page in a rounded floating card, with a themed loading
 *    overlay (aurora arc spinner) and a friendly error state with retry;
 *  - a privacy hint pill at the bottom;
 *  - a brief aurora "登录成功" overlay before the session is saved and the
 *    screen closes itself.
 *
 * The official passport page supports account+password (with slider captcha),
 * SMS code and QR login — the user completes it themselves, and the app
 * harvests the resulting session cookies (SESSDATA etc.) into settings.
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
    var loadFailed by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    // Turns true once OUR buvid3 is seeded into the WebView cookie jar; the
    // WebView stays un-created until then so the login carries the app's
    // device fingerprint instead of a throwaway per-WebView one.
    var seeded by remember { mutableStateOf(false) }
    // Bumped to recreate the WebView when the user retries after a failure.
    var retryCount by remember { mutableIntStateOf(0) }

    fun harvestAndFinish() {
        if (finished) return
        val cookie = CookieManager.getInstance().getCookie(COOKIE_DOMAIN)
        if (!cookie.isNullOrEmpty() && cookie.contains("SESSDATA=")) {
            finished = true
            success = true
            // Persist the COMPLETE login identity (SESSDATA + DedeUserID +
            // DedeUserID__ckMd5 + bili_jct). A consistent device+login pair is
            // what B站 risk control reads as a normal browser. Volatile
            // WebView-session cookies are dropped by the whitelist, and the
            // device fingerprint (buvid3) is maintained separately in settings.
            val identity = BiliIdentity.loginIdentityCookies(cookie)
            scope.launch {
                if (identity.isNotBlank()) vm.saveCookie(identity)
                // Drop the WebView cookie jar: the login identity is safely in
                // settings, the web login page is done.
                runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }
                vm.showMessage("B 站登录成功，已保存登录态")
                // Let the success overlay breathe for a moment before closing.
                delay(SUCCESS_DISMISS_MS)
                onClose()
            }
        }
    }

    // Seed OUR buvid3 into the WebView cookie jar FIRST, then poll cookies until
    // the login flow completes (it redirects to bilibili.com). Seeding here —
    // on every login attempt — covers both the first login and re-logins, and
    // makes B站 mint SESSDATA against the same device fingerprint our API
    // requests send, instead of the WebView's throwaway per-session one.
    LaunchedEffect(Unit) {
        try {
            val buvid3 = vm.ensureBuvid3()
            if (buvid3.isNotBlank()) {
                CookieManager.getInstance().setCookie(
                    COOKIE_DOMAIN, "${BiliIdentity.BUVID3}=$buvid3",
                )
                CookieManager.getInstance().flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort: the login still works without a pre-seeded buvid3,
            // and our API requests (re)generate it lazily on first use.
        }
        seeded = true
        while (!finished) {
            delay(800)
            harvestAndFinish()
        }
    }

    // Pause the WebView's JS timers / media when the app goes to the
    // background with this screen open (saves battery, avoids background
    // audio from the login page).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- Glassy header over the living sky ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoginHeaderButton(
                    onClick = {
                        if (webView?.canGoBack() == true) webView?.goBack() else onClose()
                    },
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("B 站登录", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "账号密码 / 短信验证码 / 扫码，登录成功自动保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LoginHeaderButton(
                    onClick = { webView?.reload() },
                    icon = Icons.Filled.Refresh,
                    contentDescription = "刷新",
                )
            }
            // Full-width aurora hairline sealing the header.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .auroraFill(),
            )

            // ---- The passport page, floating in a rounded card ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = Color(0x4D000000),
                            spotColor = Color(0x4D000000),
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(
                            1.dp,
                            Color.Black.copy(alpha = 0.08f),
                            RoundedCornerShape(24.dp),
                        ),
                ) {
                    // Recreate the WebView once OUR buvid3 is seeded (and on
                    // retry): the passport page must load with the app's device
                    // fingerprint already in its cookie jar.
                    key(seeded, retryCount) {
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

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?,
                                        ) {
                                            if (request?.isForMainFrame == true) {
                                                loadFailed = true
                                                loading = false
                                            }
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            errorResponse: WebResourceResponse?,
                                        ) {
                                            if (request?.isForMainFrame == true) {
                                                loadFailed = true
                                                loading = false
                                            }
                                        }
                                    }
                                    if (seeded) loadUrl(LOGIN_URL)
                                }
                            },
                            update = { webView = it },
                            // onRelease is bound to the EXACT instance leaving
                            // composition. (The old DisposableEffect + shared
                            // `webView` var destroyed the NEWLY created WebView
                            // on retry — Compose dispatches node changes before
                            // the old effect's onDispose — and leaked the old
                            // one's native resources.)
                            onRelease = { it.destroy() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // ---- Themed loading overlay ----
                    if (loading && !success) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0x66000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AuroraSpinner(Modifier.size(46.dp), strokeWidth = 4.dp)
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    "正在加载 B 站登录页…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "首次加载可能较慢，请稍候",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }

                    // ---- Friendly error state with retry ----
                    if (loadFailed && !loading && !success) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            ) {
                                Text(
                                    "登录页加载失败",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF1F2937),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "请检查网络连接后重试",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280),
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(18.dp))
                                GradientButton(
                                    text = "重新加载",
                                    icon = Icons.Filled.Refresh,
                                    onClick = {
                                        retryCount++
                                        loading = true
                                        loadFailed = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ---- Privacy hint ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                LoginPrivacyHint()
            }
        }

        // ---- Success overlay (brief, then the screen closes itself) ----
        if (success) {
            LoginSuccessOverlay()
        }
    }
}

/** Round glassy icon button for the login header (adaptive to both themes). */
@Composable
private fun LoginHeaderButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A rotating arc spinner filled with the living aurora gradient — the
 * loading state breathes with the rest of the app instead of a stock ring.
 */
@Composable
private fun AuroraSpinner(modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    val infinite = rememberInfiniteTransition(label = "loginSpinner")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "spin",
    )
    val sweep by infinite.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    // Color-only consumer → quantized aurora clock (~8 hue updates/s).
    val aurora = LocalAuroraColorPhase.current
    Canvas(modifier) {
        drawArc(
            brush = auroraBrushAt(aurora.value, size),
            startAngle = rotation,
            sweepAngle = 360f * sweep,
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Small glass pill reassuring the user about where the login state lives. */
@Composable
private fun LoginPrivacyHint() {
    val dark = isSystemInDarkTheme()
    val background = if (dark) AppGradients.BarGlass else Color.White.copy(alpha = 0.85f)
    Row(
        Modifier
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Color(0x33000000),
                spotColor = Color(0x33000000),
            )
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "登录态仅保存在本机，用于获取更高音质音源",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Full-screen aurora confirmation shown briefly right before closing. */
@Composable
private fun LoginSuccessOverlay() {
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val scale by animateFloatAsState(
        targetValue = if (appear) 1f else 0.6f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "successScale",
    )
    val fade by animateFloatAsState(
        targetValue = if (appear) 1f else 0f,
        animationSpec = tween(250),
        label = "successFade",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(AppGradients.ScrimDark)
            .alpha(fade),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        ) {
            Box(
                Modifier
                    .size(92.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x55000000),
                        spotColor = Color(0x55000000),
                    )
                    .clip(CircleShape)
                    .auroraFill(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(46.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "登录成功",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "已保存登录态，音质更高、更少被风控拦截",
                style = MaterialTheme.typography.bodySmall,
                color = White70,
            )
        }
    }
}

private const val LOGIN_URL = "https://passport.bilibili.com/login"
private const val COOKIE_DOMAIN = "https://www.bilibili.com"
private const val SUCCESS_DISMISS_MS = 1000L
private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
