package com.mymusic.player

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mymusic.player.data.AppSettings
import com.mymusic.player.data.AudioResolutionCache
import com.mymusic.player.data.LibraryStore
import com.mymusic.player.data.LyricsRepository
import com.mymusic.player.data.PlaybackProgressStore
import com.mymusic.player.data.SearchHistoryStore
import com.mymusic.player.data.TrackRepository
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.network.BiliHeaders
import com.mymusic.player.network.LyricsClient
import com.mymusic.player.player.PlayerController
import com.mymusic.player.ui.PlaybackQueueCoordinator
import com.mymusic.player.util.AppLog
import com.mymusic.player.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application entry point: initializes singletons (settings, API client,
 * library store) and a Coil ImageLoader that adds Bilibili-required headers
 * (Referer / User-Agent) to cover-image requests.
 */
class MyMusicApp : Application(), ImageLoaderFactory {

    // Built once and reused: newImageLoader() may be called more than once,
    // and each call would otherwise create a fresh OkHttpClient + memory cache.
    // The explicit Lazy lets onTrimMemory skip the first-time build instead of
    // allocating a whole ImageLoader (client + caches) just to clear it while
    // the system is already under memory pressure.
    private val imageLoaderDelegate = lazy { buildImageLoader() }
    private val imageLoader: ImageLoader by imageLoaderDelegate

    lateinit var settings: AppSettings
        private set
    lateinit var api: BiliDirectClient
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var playbackProgress: PlaybackProgressStore
        private set
    lateinit var searchHistory: SearchHistoryStore
        private set

    /** Single shared repository instances (stateless) for the whole app. */
    lateinit var trackRepo: TrackRepository
        private set
    lateinit var lyricsRepo: LyricsRepository
        private set

    /**
     * One app-wide cache for freshly-resolved audio URLs (TTL + in-flight
     * dedup), shared by the play-queue coordinator and the player's
     * error-recovery path so a fresh direct URL is reused everywhere.
     */
    lateinit var audioCache: AudioResolutionCache
        private set

    /**
     * Process-wide CoroutineScope for the playback pipeline (background queue
     * fill + 下一首/续播 subscriptions) — it must outlive any single Activity so
     * lock-screen / background playback keeps resolving while the UI is gone (a
     * ViewModel-scoped scope died with the Activity and stalled it).
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Single shared playback-queue coordinator (created once in [onCreate]). */
    lateinit var queue: PlaybackQueueCoordinator
        private set

    /** Snackbar messages from the process-wide queue pipeline, bridged into the
     *  ViewModel's Channel by whichever UI screen is alive. */
    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    /**
     * One shared OkHttpClient for the API + lyrics paths (one connection
     * pool, one dispatcher), from which specialized clients are derived.
     */
    lateinit var httpClient: OkHttpClient
        private set

    // Publish [instance] as early as possible (before onCreate) so a
    // ContentProvider or any eager initializer can reach the singletons…
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }

    // …but build the singletons in onCreate, NOT attachBaseContext:
    // AppSettings' DataStore-backed flows read `context.applicationContext`,
    // and during attachBaseContext the Application's applicationContext is
    // still null — constructing AppSettings there throws
    // "applicationContext must not be null" and the app crashes before launch.
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        installCrashHandler()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        settings = AppSettings(this)
        api = BiliDirectClient(settings, httpClient)
        library = LibraryStore(this)
        playbackProgress = PlaybackProgressStore(this)
        searchHistory = SearchHistoryStore(this)
        trackRepo = TrackRepository(api, settings)
        lyricsRepo = LyricsRepository(api, LyricsClient(settings, httpClient))
        audioCache = AudioResolutionCache(
            resolveFast = trackRepo::resolveAudioFast,
            scope = appScope,
        )
        queue = PlaybackQueueCoordinator(
            repo = trackRepo,
            scope = appScope,
            onMessage = { _uiMessages.tryEmit(it) },
            shared = audioCache,
        )
        PlayerController.init(this)

        // 播单记忆上次进度: restore the last session's queue / song / position
        // (shown paused). Process-wide, not tied to any Activity.
        appScope.launch {
            val saved = runSuspendCatching { playbackProgress.state.first() }.getOrNull()
            if (saved != null) PlayerController.restoreProgress(saved)
        }
        // 下一首 on-demand: resolve-and-insert entries the player asks for but
        // cannot play yet. Subscribed on the PROCESS scope (not a ViewModel), so
        // lock-screen / background 下一首 keeps working after the Activity is gone.
        PlayerController.needResolveNext
            .onEach { queue.onNeedResolveNext(it) }
            .launchIn(appScope)
        // 续播 a restored session whose timeline is still empty (a play tap on a
        // not-yet-re-resolved restored queue) — also process-scoped.
        PlayerController.needResume
            .onEach {
                val s = PlayerController.state.value
                val tracks = s.queue
                if (tracks.isEmpty()) return@onEach
                queue.requestPlay(
                    tracks = tracks,
                    index = s.queueIndex.coerceAtLeast(0).coerceAtMost(tracks.lastIndex),
                    useListAsQueue = true,
                    onOpened = {},
                    startPositionMs = PlayerController.positionMs.value.coerceAtLeast(0L),
                )
            }
            .launchIn(appScope)
    }

    /**
     * Capture uncaught exceptions to the daily-use log file before letting the
     * previous handler run (system crash dialog / Play reporting). This is how
     * hard crashes — like a Compose layout crash — land in 错误日志 even though
     * they never reach an explicit `Log.e`.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("UncaughtException", "崩溃：${throwable}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Respond to system memory pressure by dropping the in-memory image cache.
     * The covers are also cached on disk, so they re-load quickly once pressure
     * passes — this frees the largest app-controllable memory consumer (besides
     * the login WebView) exactly when the OS needs it most. Skips the first-time
     * build: there is no point allocating a client + caches just to clear them.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            imageLoaderDelegate.isInitialized()
        ) {
            imageLoader.memoryCache?.clear()
        }
    }

    override fun newImageLoader(): ImageLoader = imageLoader

    private fun buildImageLoader(): ImageLoader {
        // Coil needs its own client (it configures caching itself), but the
        // Bilibili request headers come from the one shared constant.
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Referer", BiliHeaders.REFERER)
                    .header("User-Agent", BiliHeaders.COVER_UA)
                    .build()
                chain.proceed(request)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            // Covers are small thumbnails (44–236 dp), so Coil's default
            // ~25%-of-heap in-memory cache is far more than they can ever use
            // and eats headroom on low-RAM devices (especially while the login
            // WebView is alive). Cap it at 10% and bound the disk cache too —
            // re-decoding a cover from disk is cheap, so a smaller cache costs
            // almost nothing.
            .memoryCache(
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10)
                    .build(),
            )
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build(),
            )
            .build()
    }

    companion object {
        lateinit var instance: MyMusicApp
            private set
    }
}
