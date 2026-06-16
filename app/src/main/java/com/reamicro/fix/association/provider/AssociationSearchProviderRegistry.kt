package com.reamicro.fix.association.provider

import android.content.Context
import com.reamicro.fix.settings.SearchSourceGroup

object AssociationSearchProviderRegistry {
    fun providers(context: Context? = null): List<BookAssociationSearchProvider> =
        builtInProviders() + ExternalSourceLoader.load(context)

    fun searchSourceGroups(context: Context? = null): List<SearchSourceGroup> =
        providers(context)
            .distinctBy { it.source.id }
            .map { provider ->
                SearchSourceGroup(
                    id = provider.source.id,
                    title = provider.source.displayName,
                    sources = setOf(provider.source),
                )
            }

    private fun builtInProviders(): List<BookAssociationSearchProvider> = emptyList()
}
