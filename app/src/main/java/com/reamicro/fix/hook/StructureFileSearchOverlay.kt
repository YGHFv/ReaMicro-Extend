package com.reamicro.fix.hook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reamicro.fix.R

@Composable
internal fun StructureFileSearchOverlay(
    visible: Boolean,
    paths: List<String>,
    labels: Map<String, List<String>> = emptyMap(),
    palette: StructureHome130Style.Palette,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.epub_structure_search_title)
    val hint = stringResource(R.string.epub_structure_search_hint)
    val confirm = stringResource(R.string.epub_structure_search_confirm)
    val noMatch = stringResource(R.string.epub_structure_search_empty)
    val ambiguous = stringResource(R.string.epub_structure_search_ambiguous)
    val model = remember(visible, title, hint, confirm) {
        if (visible) StructureEditorModel(Any(), hint, "", title = title,
            confirmLabel = confirm, singleLine = true) else null
    }
    val matches = remember(paths, labels, query) { StructureFileSearch.matches(paths, query, labels) }
    StructureEditorOverlay(
        model, palette, busy, error,
        onDismiss = onDismiss,
        onEdited = { error = null },
        onValueChanged = { query = it },
        onSave = { value ->
            val target = StructureFileSearch.resolve(paths, value, labels)
            if (target != null) onSelect(target)
            else error = if (StructureFileSearch.matches(paths, value, labels).isEmpty()) noMatch else ambiguous
        },
        supportingContent = {
            if (query.isNotBlank()) Column(Modifier.fillMaxWidth()
                .padding(horizontal = StructureSearchChromeMetrics.fieldTextInset.dp)) {
                Text(if (matches.isEmpty()) noMatch
                    else stringResource(R.string.epub_structure_search_count, matches.size),
                    style = MaterialTheme.typography.bodySmall, color = palette.caption)
                if (matches.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                    items(matches, key = { it }) { path ->
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            onSelect(path)
                        }.padding(vertical = 10.dp)) {
                            StructureTruncatedText(path, MaterialTheme.typography.bodyLarge, palette.text, Modifier.fillMaxWidth())
                            val description = labels[path].orEmpty().joinToString(" ")
                            if (description.isNotBlank()) StructureTruncatedText(description,
                                MaterialTheme.typography.bodySmall, palette.caption, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
    )
}
