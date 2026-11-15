package com.reamicro.fix.association.provider

import android.app.Activity
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.settings.FontSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettingsSnapshot

class ExternalFeatureApi(
    val classLoader: ClassLoader,
    val activityProvider: () -> Activity?,
    val settingsProvider: () -> ModuleSettingsSnapshot,
    val fontSettingsProvider: () -> FontSettingsSnapshot,
    val onSearchSourceDisabled: (BookSource, String) -> Unit,
) {
    /** Optional local integrations bind logical symbols through the same installed query results. */
    val hostSymbols: ExternalHostSymbols by lazy { ExternalHostSymbols(classLoader) }
    val manualEditPublicEntryVisible: Boolean get() = settingsProvider().associationManualEditUnlocked
}
