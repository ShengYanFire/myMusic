# Gson reflects over the persisted/transferred model classes — every field
# name and no-arg construction must survive R8. The old blanket
# "-keep class ...{ *; }" rules worked but kept entire packages; these keep
# exactly the Gson-touched types.
-keep class com.mymusic.player.domain.Track { *; }
-keep class com.mymusic.player.domain.LyricLine { *; }
-keep class com.mymusic.player.domain.Lyrics { *; }
-keep class com.mymusic.player.data.ResolvedTrack { *; }
-keep class com.mymusic.player.data.Playlist { *; }
-keep class com.mymusic.player.data.SavedPlaybackState { *; }
-keep class com.mymusic.player.network.SearchItem { *; }
-keep class com.mymusic.player.network.AudioInfo { *; }
-keep class com.mymusic.player.network.BiliPage { *; }
-keep class com.mymusic.player.network.VideoInfo { *; }
-keep class com.mymusic.player.network.BiliSubtitle { *; }
# AppSettings persists raw strings only (no reflected types) — nothing to keep.

# OkHttp / Gson plumbing
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# Media3 / Compose ship their own consumer rules; nothing extra needed.
