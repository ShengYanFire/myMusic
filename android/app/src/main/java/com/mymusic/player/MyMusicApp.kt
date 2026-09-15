package com.mymusic.player

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mymusic.player.data.AppSettings
import com.mymusic.player.data.AudioResolutionCache
import com.mymusic.player.data.LibraryStore
import com.mymusic.player.data.LyricsRepository
import com.mymusic.player.data.PlaybackProgressStore
import com.mymusic.player.data.TrackRepository
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.network.BiliHeaders
import com.mymusic.player.network.LyricsClient
import com.mymusic.player.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val imageLoader by lazy { buildImageLoader() }

    lateinit var settings: AppSettings
        private set
    lateinit var api: BiliDirectClient
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var playbackProgress: PlaybackProgressStore
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
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        settings = AppSettings(this)
        api = BiliDirectClient(settings, httpClient)
        library = LibraryStore(this)
        playbackProgress = PlaybackProgressStore(this)
        trackRepo = TrackRepository(api, settings)
        lyricsRepo = LyricsRepository(api, LyricsClient(settings, httpClient))
        audioCache = AudioResolutionCache(
            resolveFast = trackRepo::resolveAudioFast,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
        PlayerController.init(this)
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
            .build()
    }

    companion object {
        lateinit var instance: MyMusicApp
            private set
    }
}
