package com.mymusic.player.domain

/**
 * A listening mood the user can mark as a favorite (心情偏好), alongside
 * music genres. [id] is persisted in AppSettings (DataStore); [label] is what
 * the UI shows; [keywords] are Bilibili search terms used to source
 * personalized recommendations — one is randomly picked per mood on every
 * recommendation reload so the feed stays fresh.
 */
data class MusicMood(
    val id: String,
    val label: String,
    val keywords: List<String>,
)

/**
 * Catalog of selectable listening moods (B站-friendly searches), ordered
 * upbeat → soft → melancholy. [ALL] order is the display order of the
 * settings chips.
 */
object MusicMoods {

    val ALL: List<MusicMood> = listOf(
        // ---- 元气满满 ----
        MusicMood("happy", "开心", listOf("欢快音乐", "开心歌曲", "快乐的音乐")),
        MusicMood("exciting", "嗨", listOf("劲爆音乐", "嗨曲", "卡点音乐")),
        MusicMood("energetic", "燃", listOf("热血BGM", "燃向音乐", "超燃歌曲")),
        MusicMood("motivational", "励志", listOf("励志歌曲", "正能量音乐", "逆袭BGM")),

        // ---- 轻柔舒缓 ----
        MusicMood("romantic", "浪漫", listOf("浪漫情歌", "情歌对唱", "婚礼音乐")),
        MusicMood("gentle", "温柔", listOf("温柔女声", "温柔歌曲", "治愈女声")),
        MusicMood("relax", "放松", listOf("放松音乐", "解压音乐", "舒缓音乐")),
        MusicMood("calm", "安静", listOf("安静的音乐", "深夜歌单", "静心音乐")),
        MusicMood("sleepy", "助眠", listOf("助眠音乐", "哄睡轻音乐", "睡前音乐")),

        // ---- 情绪沉淀 ----
        MusicMood("sad", "伤感", listOf("伤感音乐", "伤感歌曲", "催泪歌曲")),
        MusicMood("lonely", "孤独", listOf("孤独感音乐", "一个人听的歌", "emo歌单")),
        MusicMood("nostalgic", "怀旧", listOf("怀旧金曲", "青春回忆杀", "老歌回忆")),
    )

    private val catalogById = ALL.associateBy { it.id }

    /** Mood for [id], or null when the id is unknown/stale. */
    fun byId(id: String): MusicMood? = catalogById[id]

    /** Ordered labels for the given ids (unknown ids silently dropped). */
    fun labelsOf(ids: Collection<String>): List<String> =
        ids.mapNotNull { byId(it)?.label }
}
