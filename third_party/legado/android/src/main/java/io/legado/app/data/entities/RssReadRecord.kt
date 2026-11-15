package io.legado.app.data.entities


data class RssReadRecord(
    val record: String,
    val title: String? = null,
    val readTime: Long? = null,
    val read: Boolean = true,
    val origin: String = "",
    var sort: String = "",
    var image: String? = null,
    /**类型 0网页，1图片，2视频**/
    var type: Int = 0,
    /**阅读进度**/
    var durPos: Int = 0,
    var pubDate: String? = null
) {
    fun toRssArticle(): RssArticle {
        return RssArticle(
            title = title ?: "",
            origin = origin,
            link = record,
            sort = sort,
            image = image,
            type = type,
            durPos = durPos,
            pubDate = pubDate
        )
    }

    fun toStar(): RssStar {
        return RssStar(
            title = title ?: "",
            origin = origin,
            link = record,
            sort = sort,
            image = image,
            type = type,
            durPos = durPos,
            pubDate = pubDate
        )
    }
}