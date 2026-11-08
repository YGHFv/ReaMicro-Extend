package com.reamicro.fix.hook

import com.reamicro.fix.settings.ModuleSettingsSnapshot

/** Cache key grammar read from the host's cover and banner ImageRequest builders. */
internal object CoverFadePolicy {
    // The bookshelf list still uses the legacy builder, without a modified/variant suffix.
    // Both builders also replace remote data with file:// when a cover is cached locally.
    private val coverKey = Regex("^#.+/.+\\[\\d+,\\d+](?:@(?:null|-?\\d+)#-?\\d+)?$")
    private val bannerKey = Regex("^#.+/.+~banner\\[\\d+,\\d+]@-?\\d+$")

    fun enabled(settings: ModuleSettingsSnapshot): Boolean =
        settings.moduleEnabled && settings.shelfDisableCoverFade

    fun isCoverKey(value: String?): Boolean =
        value != null && (coverKey.matches(value) || bannerKey.matches(value))
}
