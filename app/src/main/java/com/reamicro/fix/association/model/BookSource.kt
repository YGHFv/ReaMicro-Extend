package com.reamicro.fix.association.model

class BookSource(
    val id: String,
    val displayName: String,
    val reamicroQueryType: Int?,
) {
    val hasReaMicroQueryType: Boolean
        get() = reamicroQueryType != null

    override fun equals(other: Any?): Boolean =
        other is BookSource && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "BookSource(id=$id, displayName=$displayName, reamicroQueryType=$reamicroQueryType)"

    companion object {
        val DouBan = BookSource("douban", "\u5b9e\u4f53\u51fa\u7248", 1)
        val QiDian = BookSource("qidian", "\u8d77\u70b9\u4e2d\u6587\u7f51", 2)
        val JinJiang = BookSource("jinjiang", "\u664b\u6c5f\u6587\u5b66\u57ce", 3)
        val ZongHeng = BookSource("zongheng", "\u7eb5\u6a2a\u4e2d\u6587\u7f51", 4)
        val GongZiCp = BookSource("gongzicp", "\u957f\u4f69\u6587\u5b66", 7)
        val FaLoo = BookSource("faloo", "\u98de\u5362\u5c0f\u8bf4\u7f51", 8)
        val QQReader = BookSource("qqreader", "QQ\u9605\u8bfb", 9)
        val Ciweimao = BookSource("ciweimao", "\u523a\u732c\u732b\u9605\u8bfb", 10)
        val ShaoNianDream = BookSource("shaoniandream", "\u5c11\u5e74\u68a6", 11)
        val CDDaoYue = BookSource("cddaoyue", "\u72ec\u9605\u8bfb", 13)
        val SFAcg = BookSource("sfacg", "\u83e0\u841d\u5305\u8f7b\u5c0f\u8bf4", 14)
        val HanWuJiNian = BookSource("hanwujinian", "\u5bd2\u6b66\u7eaa\u5e74", null)
        val BuKeNeng = BookSource("bkneng", "\u4e0d\u53ef\u80fd\u7684\u4e16\u754c", null)
        val Ciyuanji = BookSource("ciyuanji", "\u6b21\u5143\u59ec", null)
        val XiRang = BookSource("xirang", "\u606f\u58e4\u4e2d\u6587\u7f51", null)
        val YouDu = BookSource("youdu", "\u6709\u6bd2\u5c0f\u8bf4\u7f51", null)
        val YouShu = BookSource("youshu", "\u4f18\u4e66\u7f51", null)
        val BaiduBaike = BookSource("baidu_baike", "\u767e\u5ea6\u767e\u79d1", null)
        val FanQie = BookSource("fanqie", "\u756a\u8304\u5c0f\u8bf4", null)
        val DaHuiLang = BookSource("dahuilang", "\u756a\u8304\u5c0f\u8bf4", null)
        val WanFengLi = BookSource("wanfengli", "\u665a\u98ce\u91cc", null)

        val entries: List<BookSource> = listOf(
            DouBan,
            QiDian,
            JinJiang,
            ZongHeng,
            GongZiCp,
            FaLoo,
            QQReader,
            Ciweimao,
            ShaoNianDream,
            CDDaoYue,
            SFAcg,
            HanWuJiNian,
            BuKeNeng,
            Ciyuanji,
            XiRang,
            YouDu,
            YouShu,
            BaiduBaike,
            FanQie,
            DaHuiLang,
            WanFengLi,
        )

        val manualSelectableSources: List<BookSource>
            get() = AssociationPlatforms.sources

        fun fromId(id: String): BookSource? = entries.firstOrNull { it.id == id }

        fun fromReaMicroQueryType(queryType: Int): BookSource? = entries.firstOrNull {
            it.reamicroQueryType == queryType
        }
    }
}
