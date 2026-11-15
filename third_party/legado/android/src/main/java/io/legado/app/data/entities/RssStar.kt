package io.legado.app.data.entities

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject


data class RssStar(
    override var origin: String = "",
    var sort: String = "",
    var title: String = "",
    var starTime: Long = 0,
    override var link: String = "",
    var pubDate: String? = null,
    var description: String? = null,
    var content: String? = null,
    var image: String? = null,
    var group: String = "默认分组",
    override var variable: String? = null,
    /**类型 0网页，1图片，2视频**/
    var type: Int = 0,
    /**阅读进度**/
    var durPos: Int = 0
) : BaseRssArticle {

    @delegate:Transient
    override val variableMap by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()
    }

    fun toRssArticle() = RssArticle(
        origin = origin,
        sort = sort,
        title = title,
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
