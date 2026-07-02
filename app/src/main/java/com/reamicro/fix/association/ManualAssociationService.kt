package com.reamicro.fix.association

import com.reamicro.fix.association.model.AssociatedFileMetadata
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.model.ManualAssociationCandidate
import com.reamicro.fix.association.model.ManualAssociationDraft
import com.reamicro.fix.association.model.ManualAssociationValidation

class ManualAssociationService(
    private val selectableSourcesProvider: () -> List<BookSource> = { BookSource.manualSelectableSources },
) {
    val selectableSources: List<BookSource>
        get() = selectableSourcesProvider().distinctBy { it.id }

    fun createDraft(
        fileMetadata: AssociatedFileMetadata,
        defaultSource: BookSource = selectableSources.firstOrNull() ?: BookSource.QiDian,
    ): ManualAssociationDraft = ManualAssociationDraft.fromFile(fileMetadata, defaultSource)

    fun updateDraft(
        draft: ManualAssociationDraft,
        title: String = draft.title,
        author: String = draft.author,
        intro: String = draft.intro,
        source: BookSource = draft.source,
    ): ManualAssociationDraft = draft.copy(
        title = title,
        author = author,
        intro = intro,
        source = source,
    )

    fun validate(draft: ManualAssociationDraft): ManualAssociationValidation {
        val validation = draft.validate()
        if (!validation.isValid) return validation
        if (selectableSources.none { it == draft.source }) {
            return validation.copy(sourceError = "该来源不在平台白名单中")
        }
        return validation
    }

    fun buildCandidate(draft: ManualAssociationDraft): ManualAssociationCandidate {
        val validation = validate(draft)
        require(validation.isValid) { validation.firstError ?: "手动关联信息不完整" }
        return draft.toCandidate()
    }
}
