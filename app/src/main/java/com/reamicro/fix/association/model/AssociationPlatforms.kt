package com.reamicro.fix.association.model

object AssociationPlatforms {
    val sources: List<BookSource> = listOf(
        BookSource.QiDian,
        BookSource.ZongHeng,
        BookSource.XiRang,
        BookSource.YouDu,
        BookSource.CDDaoYue,
        BookSource.FaLoo,
        BookSource.JinJiang,
        BookSource.Ciweimao,
        BookSource.FanQie,
        BookSource.Ciyuanji,
        BookSource.GongZiCp,
        BookSource.QQReader,
        BookSource.ShaoNianDream,
        BookSource.SFAcg,
        BookSource.HanWuJiNian,
        BookSource.BuKeNeng,
    )

    private val sourcesById: Map<String, BookSource> = sources.associateBy { it.id }
    private val sourcesByDisplayName: Map<String, BookSource> = sources.associateBy { it.displayName }

    fun isAllowed(source: BookSource): Boolean = source.id in sourcesById

    fun normalizeDisplayName(raw: String): String =
        normalizeSource(raw)?.displayName.orEmpty()

    fun normalizeSource(raw: String): BookSource? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        sourcesByDisplayName[clean]?.let { return it }
        sourcesById[clean.lowercase()]?.let { return it }

        val lower = clean.lowercase()
        val normalized = lower.replace(Regex("[\\s_.\\-/]+"), "")
        return when {
            normalized in setOf("qidian", "qd", "qdian") ||
                lower.contains("qidian") ||
                clean.contains("\u8d77\u70b9") -> BookSource.QiDian

            normalized in setOf("zongheng", "zh") ||
                lower.contains("zongheng") ||
                clean.contains("\u7eb5\u6a2a") -> BookSource.ZongHeng

            normalized in setOf("xirang", "xrzww") ||
                lower.contains("xirang") ||
                lower.contains("xrzww") ||
                clean.contains("\u606f\u58e4") -> BookSource.XiRang

            normalized in setOf("youdu", "yodu", "yd") ||
                lower.contains("yodu") ||
                lower.contains("youdu") ||
                clean.contains("\u6709\u6bd2") -> BookSource.YouDu

            normalized in setOf("cddaoyue", "daoyue", "dy") ||
                lower.contains("cddaoyue") ||
                clean.contains("\u72ec\u9605\u8bfb") -> BookSource.CDDaoYue

            normalized in setOf("faloo", "fl") ||
                lower.contains("faloo") ||
                clean.contains("\u98de\u5362") -> BookSource.FaLoo

            normalized in setOf("jinjiang", "jjwxc", "jj") ||
                lower.contains("jinjiang") ||
                lower.contains("jjwxc") ||
                clean.contains("\u664b\u6c5f") -> BookSource.JinJiang

            normalized in setOf("ciweimao", "cwm", "hbooker") ||
                lower.contains("ciweimao") ||
                lower.contains("hbooker") ||
                clean.contains("\u523a\u732c\u732b") -> BookSource.Ciweimao

            normalized in setOf("fanqie", "fq", "tomato", "fanqienovel") ||
                lower.contains("fanqie") ||
                lower.contains("fanqienovel") ||
                clean.contains("\u756a\u8304") -> BookSource.FanQie

            normalized in setOf("ciyuanji", "cyj") ||
                lower.contains("ciyuanji") ||
                clean.contains("\u6b21\u5143\u59ec") -> BookSource.Ciyuanji

            normalized in setOf("gongzicp", "cp", "changpei") ||
                lower.contains("gongzicp") ||
                lower.contains("changpei") ||
                clean.contains("\u957f\u4f69") -> BookSource.GongZiCp

            normalized in setOf("qqreader", "qqread", "qq") ||
                lower.contains("qqreader") ||
                clean.contains("QQ\u9605\u8bfb") -> BookSource.QQReader

            normalized in setOf("shaoniandream", "sndream", "snm", "sn", "shaonianmeng") ||
                lower.contains("shaoniandream") ||
                lower.contains("shaonianmeng") ||
                clean.contains("\u5c11\u5e74\u68a6") -> BookSource.ShaoNianDream

            normalized in setOf("sfacg", "sf", "boluobao") ||
                lower.contains("sfacg") ||
                lower.contains("boluobao") ||
                clean.contains("SF\u8f7b\u5c0f\u8bf4") ||
                clean.contains("\u83e0\u841d\u5305") -> BookSource.SFAcg

            normalized in setOf("hanwujinian", "hwj", "hanwu") ||
                lower.contains("hanwujinian") ||
                clean.contains("\u5bd2\u6b66\u7eaa\u5e74") -> BookSource.HanWuJiNian

            normalized in setOf("bkneng", "bukeneng", "bukneng", "impossibleworld", "impossible") ||
                lower.contains("bkneng") ||
                lower.contains("bukeneng") ||
                lower.contains("bukneng") ||
                clean.contains("\u4e0d\u53ef\u80fd\u7684\u4e16\u754c") -> BookSource.BuKeNeng

            else -> null
        }
    }
}

fun BookSearchResult.withAllowedAssociationPlatform(): BookSearchResult? {
    val platformName = AssociationPlatforms.normalizeDisplayName(displaySourceName)
    if (platformName.isBlank()) return null
    return if (platformName == displaySourceName) this else copy(displaySourceName = platformName)
}
