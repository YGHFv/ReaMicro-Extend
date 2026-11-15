package com.reamicro.fix.online

internal data class EnhancedSourceControl(
    val key: String,
    val title: String,
    val type: String,
    val options: List<String> = emptyList(),
    val default: String = "",
    val value: String = "",
    val action: String = "",
    val styleJson: String = "",
    val url: String = "",
    val upstreamJson: String = "",
)

internal data class EnhancedSourceForm(
    val rows: List<EnhancedSourceControl>,
    val values: Map<String, String>,
    val webUrl: String? = null,
)
