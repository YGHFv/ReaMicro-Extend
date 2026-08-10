package com.reamicro.fix.hook

import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.ManualAssociationCandidate
import com.reamicro.fix.core.HostClasses

class ReaMicroThirdPartyBookFactory(
    private val classLoader: ClassLoader,
) {
    fun create(candidate: ManualAssociationCandidate, coverFallback: String = ""): Any {
        val cover = candidate.coverUrl.ifBlank { coverFallback }
        return createThirdPartyBook(
            title = candidate.title,
            author = candidate.author,
            alias = candidate.title,
            intro = candidate.intro,
            cover = cover,
            publisher = candidate.source.displayName,
            words = candidate.words,
            detail = "",
            rating = 0.0,
            status = candidate.status,
        )
    }

    fun create(result: BookSearchResult): Any {
        return createThirdPartyBook(
            title = result.title,
            author = result.author,
            alias = result.title,
            intro = result.intro,
            cover = result.coverUrl,
            publisher = result.displaySourceName,
            words = result.words,
            detail = "",
            rating = 0.0,
            status = result.status.ifBlank { result.tags.joinToString(" / ") },
        )
    }

    fun createThirdPartyGroup(publisher: String, books: List<Any>): Any {
        val thirdPartyClass = classLoader.loadClass(THIRD_PARTY_CLASS)
        val constructor = thirdPartyClass.getConstructor(String::class.java, List::class.java)
        return constructor.newInstance(publisher, books)
    }

    private fun createThirdPartyBook(
        title: String,
        author: String,
        alias: String,
        intro: String,
        cover: String,
        publisher: String,
        words: String,
        detail: String,
        rating: Double,
        status: String,
    ): Any {
        val thirdPartyBookClass = classLoader.loadClass(THIRD_PARTY_BOOK_CLASS)
        val stringClass = String::class.java
        val commonArgs = arrayOf(
            title,
            author,
            alias,
            "",
            "",
            intro,
            cover,
            "",
            publisher,
            "",
            "",
            "",
            words,
        )
        return runCatching {
            thirdPartyBookClass
                .getConstructor(
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    java.lang.Double.TYPE,
                    stringClass,
                )
                .newInstance(*commonArgs, detail, rating, status)
        }.getOrElse {
            thirdPartyBookClass
                .getConstructor(
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                    stringClass,
                )
                .newInstance(*commonArgs, status)
        }
    }

    private companion object {
        const val THIRD_PARTY_BOOK_CLASS = HostClasses.Host.THIRD_PARTY_BOOK
        const val THIRD_PARTY_CLASS = HostClasses.Host.THIRD_PARTY
    }
}
