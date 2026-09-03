package com.mymusic.player.domain

/**
 * A music genre the user can mark as a favorite (音乐偏好).
 *
 * [id] is persisted in AppSettings (DataStore); [label] is what the UI shows;
 * [keywords] are Bilibili search terms used to source personalized
 * recommendations — one is randomly picked per genre on every recommendation
 * reload so the feed stays fresh.
 */
data class MusicGenre(
    val id: String,
    val label: String,
    val keywords: List<String>,
)

/**
 * Catalog of selectable music genres (music-pure, B站-friendly searches),
 * grouped by family: 大众风格 → 语言/地区 → 细分风格 → 场景/来源.
 * [ALL] order is the display order of the settings chips.
 */
object MusicGenres {

    val ALL: List<MusicGenre> = listOf(
        // ---- 大众风格 ----
        MusicGenre("pop", "流行", listOf("流行音乐", "热门歌曲", "华语流行")),
        MusicGenre("folk", "民谣", listOf("民谣", "吉他弹唱", "原创民谣")),
        MusicGenre("rock", "摇滚", listOf("摇滚现场", "摇滚乐", "乐队演出")),
        MusicGenre("rap", "说唱", listOf("说唱", "中文说唱", "嘻哈")),
        MusicGenre("electronic", "电子", listOf("电子音乐", "电音", "DJ混音")),
        MusicGenre("ancient", "古风", listOf("古风音乐", "国风音乐", "古风歌曲")),
        MusicGenre("rnb", "R&B", listOf("R&B", "节奏布鲁斯", "慢摇")),
        MusicGenre("jazz", "爵士", listOf("爵士乐", "爵士现场", "萨克斯")),
        MusicGenre("classical", "古典", listOf("古典音乐", "交响乐", "钢琴曲")),
        MusicGenre("instrumental", "纯音乐", listOf("纯音乐", "轻音乐", "器乐演奏")),
        MusicGenre("healing", "治愈", listOf("治愈音乐", "放松音乐", "助眠纯音乐")),
        MusicGenre("oldies", "经典老歌", listOf("经典老歌", "怀旧金曲", "华语经典")),
        MusicGenre("cover", "翻唱", listOf("翻唱", "神级翻唱", "女生翻唱")),
        MusicGenre("live", "现场", listOf("音乐现场", "演唱会", "livehouse")),

        // ---- 语言 / 地区 ----
        MusicGenre("western", "欧美", listOf("欧美音乐", "欧美流行", "英文歌")),
        MusicGenre("jpop", "日语", listOf("日语歌曲", "日文歌", "J-POP")),
        MusicGenre("kpop", "韩语", listOf("韩语歌曲", "KPOP", "韩语流行")),
        MusicGenre("cantonese", "粤语", listOf("粤语歌", "粤语经典", "粤语流行")),

        // ---- 细分风格 ----
        MusicGenre("metal", "金属", listOf("重金属", "金属乐", "金属乐队")),
        MusicGenre("punk", "朋克", listOf("朋克摇滚", "朋克音乐", "朋克乐队")),
        MusicGenre("postrock", "后摇", listOf("后摇", "后摇滚", "器乐后摇")),
        MusicGenre("blues", "蓝调", listOf("蓝调", "布鲁斯", "蓝调口琴")),
        MusicGenre("country", "乡村", listOf("乡村音乐", "乡村歌曲", "乡村民谣")),
        MusicGenre("indie", "独立音乐", listOf("独立音乐", "indie音乐", "独立乐队")),
        MusicGenre("trad", "民乐", listOf("民乐", "古筝", "二胡演奏")),
        MusicGenre("opera", "戏腔", listOf("戏腔", "戏腔翻唱", "国风戏腔")),
        MusicGenre("vocaloid", "虚拟歌手", listOf("VOCALOID", "洛天依", "初音未来")),

        // ---- 场景 / 来源 ----
        MusicGenre("acg", "动漫", listOf("动漫音乐", "ACG音乐", "动漫歌曲")),
        MusicGenre("game", "游戏原声", listOf("游戏音乐", "游戏原声", "游戏BGM")),
        MusicGenre("ost", "影视原声", listOf("影视原声", "电影配乐", "影视配乐")),
        MusicGenre("bgm", "热门BGM", listOf("热门BGM", "洗脑BGM", "BGM合集")),
        MusicGenre("lofi", "Lo-fi", listOf("lofi音乐", "lofi", "学习BGM")),
        MusicGenre("acapella", "阿卡贝拉", listOf("阿卡贝拉", "无伴奏合唱", "人声合唱")),
    )

    private val catalogById = ALL.associateBy { it.id }

    /** Genre for [id], or null when the id is unknown/stale. */
    fun byId(id: String): MusicGenre? = catalogById[id]

    /** Ordered labels for the given ids (unknown ids silently dropped). */
    fun labelsOf(ids: Collection<String>): List<String> =
        ids.mapNotNull { byId(it)?.label }
}
