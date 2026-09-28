package com.reamicro.fix.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.view.ContextThemeWrapper
import com.reamicro.fix.R
import java.util.Locale

/** Stable preference values; display names come from string resources. */
internal fun moduleLanguageTag(index: Int): String = when (index) {
    1 -> "zh-Hans"
    2 -> "en"
    else -> ""
}

/**
 * App language for the module's own Compose UI.
 *
 * Deliberately **not** using [android.app.LocaleManager] / per-app system locales: on Android 13+
 * the very first call to `setApplicationLocales` (unset -> set) forces one Activity recreate that
 * `android:configChanges` cannot suppress — that is the "screen flashes black once" the user saw.
 * Driving the language purely from our own preference plus a local resource-override [Context] means
 * every switch (including the first) is just a Compose recomposition: no recreate, no flash.
 *
 * "Follow system" (index 0) resolves the live system locale, so a Chinese device shows Chinese and
 * an English device shows English automatically. The trade-off is that this language no longer
 * appears in the system per-app language screen, which is acceptable for a Xposed module tool.
 * Never mutate Resources.getSystem(), Locale.setDefault(), or shared application resources.
 */
internal object ModuleLanguage {
    private const val PREFS = "reamicro_module_ui"
    private const val KEY_LANGUAGE = "appLanguage"

    fun selection(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LANGUAGE, 0).coerceIn(0, 2)

    fun setSelection(context: Context, index: Int) {
        require(index in 0..2)
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LANGUAGE, index).commit(),
        ) { "Unable to persist app language" }
    }

    fun localizedContext(context: Context, index: Int = selection(context)): Context {
        val tag = moduleLanguageTag(index)
        // Follow-system reads the live device locales; an explicit choice pins one tag.
        val locales = if (tag.isEmpty()) {
            Resources.getSystem().configuration.locales
        } else {
            LocaleList.forLanguageTags(tag)
        }
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(if (locales.isEmpty) LocaleList(Locale.SIMPLIFIED_CHINESE) else locales)
        }
        // Keep the Activity in the wrapper chain: miuix overlays and keyboard handling need it.
        return ContextThemeWrapper(context, R.style.ModuleTheme).apply {
            applyOverrideConfiguration(configuration)
        }
    }
}
