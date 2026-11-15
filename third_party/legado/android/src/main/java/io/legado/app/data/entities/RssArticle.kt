package io.legado.app.data.entities

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

data class RssArticle(
    override var origin: String = "",
    var sort: String = "",
    var title: String = "",
    var order: Long = 0,
    override var link: String = "",
    var pubDate: String? = null,
    var description: String? = null,
    var content: String? = null,
    var image: String? = null,
    var group: String = "默认分组",
    var read: Boolean = false,
    override var variable: String? = null,
    /**类型 0网页，1图片，2视频**/
    var type: Int = 0,
    /**阅读进度**/
    var durPos: Int = 0
) : BaseRssArticle {

    override fun hashCode() = link.hashCode()

    override fun equals(other: Any?): Boolean {
        other ?: return false
        return if (other is RssArticle) origin == other.origin && link == other.link && sort == other.sort else false
    }

    @delegate:Transient
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()
    }

    fun toStar() = RssStar(
        origin = origin,
        sort = sort,
        title = title,
        starTime = System.currentTimeMillis(),
        link = link,
        pubDate = pubDate,
        description = description,
        content = content,
        image = image,
        group = group,
        variable = variable,
        type = type,
        durPos = durPos
    )

    fun toRecord() = RssReadRecord(
        origin = origin,
        sort = sort,
        title = title,
        readTime = System.currentTimeMillis(),
        record = link,
        image = image,
        type = type,
        durPos = durPos,
        pubDate = pubDate
    )
}
