package com.mymusic.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mymusic.player.data.AppSettings
import com.mymusic.player.data.LibraryStore
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.player.PlayerController
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application entry point: initializes singletons (settings, API client,
 * library store) and a Coil ImageLoader that adds Bilibili-required headers
 * (Referer / User-Agent) to cover-image requests.
 */
class MyMusicApp : Application(), ImageLoaderFactory {

    lateinit var settings: AppSettings
        private set
    lateinit var api: BiliDirectClient
        private set
    lateinit var library: LibraryStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        api = BiliDirectClient(settings)
        library = LibraryStore(this)
        PlayerController.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Referer", "https://www.bilibili.com/")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) MyMusic/1.0",
                    )
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
