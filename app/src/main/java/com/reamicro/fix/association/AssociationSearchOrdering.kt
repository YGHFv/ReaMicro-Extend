package com.reamicro.fix.association

import com.reamicro.fix.association.model.BookSearchResult
import java.util.Locale

fun List<BookSearchResult>.orderExactTitleMatchesFirst(keyword: String): List<BookSearchResult> {
    val normalizedKeyword = keyword.normalizedAssociationSearchKey()
    if (normalizedKeyword.isBlank() || size < 2) return this
    return mapIndexed { index, result -> index to result }
        .sortedWith(
            compareBy<Pair<Int, BookSearchResult>> { (_, result) ->
                if (result.title.normalizedAssociationSearchKey() == normalizedKeyword) 0 else 1
            }.thenBy { it.first },
        )
        .map { it.second }
}

fun String.normalizedAssociationSearchKey(): String =
    lowercase(Locale.ROOT)
        .replace(Regex("[\\s\\u3000《》<>【】\\[\\]（）()「」『』:：,，.。!！?？\"'“”‘’、/\\\\_-]+"), "")
