# Keep Gson model classes
-keep class com.mymusic.player.network.** { *; }
-keep class com.mymusic.player.domain.** { *; }
-keep class com.mymusic.player.data.** { *; }

# OkHttp / Gson
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
