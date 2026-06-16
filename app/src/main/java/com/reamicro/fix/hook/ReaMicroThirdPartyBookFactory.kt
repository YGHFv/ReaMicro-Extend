package com.reamicro.fix.hook

import com.reamicro.fix.association.model.BookSearchResult

class ReaMicroThirdPartyBookFactory(
    private val classLoader: ClassLoader,
) {
    fun create(result: BookSearchResult): Any {
        val thirdPartyBookClass = classLoader.loadClass(THIRD_PARTY_BOOK_CLASS)
        val constructor = thirdPartyBookClass.getConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        return constructor.newInstance(
            result.title,
            result.author,
            result.title,
            "",
            "",
            result.intro,
            result.coverUrl,
            "",
            result.displaySourceName,
            "",
            "",
            "",
            result.words,
            result.status.ifBlank { result.tags.joinToString(" / ") },
        )
    }

    fun createThirdPartyGroup(publisher: String, books: List<Any>): Any {
        val thirdPartyClass = classLoader.loadClass(THIRD_PARTY_CLASS)
        val constructor = thirdPartyClass.getConstructor(String::class.java, List::class.java)
        return constructor.newInstance(publisher, books)
    }

    private companion object {
        const val THIRD_PARTY_BOOK_CLASS = "app.zhendong.reamicro.data.search.ThirdPartyBook"
        const val THIRD_PARTY_CLASS = "app.zhendong.reamicro.data.search.ThirdParty"
    }
}
