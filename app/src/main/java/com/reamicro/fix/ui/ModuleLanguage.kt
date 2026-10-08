package com.reamicro.fix.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.view.ContextThemeWrapper
import com.reamicro.fix.R
import java.util.Locale

internal fun moduleLanguageTag(index: Int): String = when (index) {
    1 -> "zh-Hans"
    2 -> "en"
    else -> ""
}

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

        val locales = if (tag.isEmpty()) {
            Resources.getSystem().configuration.locales
        } else {
            LocaleList.forLanguageTags(tag)
        }
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(if (locales.isEmpty) LocaleList(Locale.SIMPLIFIED_CHINESE) else locales)
        }

        return ContextThemeWrapper(context, R.style.ModuleTheme).apply {
            applyOverrideConfiguration(configuration)
        }
    }
}
