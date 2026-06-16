package com.reamicro.fix.association.provider

interface ExternalFeatureProvider {
    val id: String
    val displayName: String
    val capabilities: Set<String>

    fun install(api: ExternalFeatureApi)
}
