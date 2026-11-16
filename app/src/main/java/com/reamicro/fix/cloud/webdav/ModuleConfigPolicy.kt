package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.settings.ModuleSettings

internal enum class ModuleConfigScope(val title: String) {
    HIGHLIGHT_STYLES("高亮样式及图片"),
    HIGHLIGHT_SETTINGS("高亮设置及规则"),
    SOURCES("书源配置及书源本体"),
    SOURCE_CREDENTIALS("书源账号、密码、Cookie 及变量"),
    API("API 配置及密钥"),
    OTHER("其他模块设置及关联源"),
}

/** 同步行为和本机运行状态不随恢复覆盖；每个设置只归属一个范围。 */
internal object ModuleConfigPolicy {
    const val PREFS = "reamicro_module_config_sync"
    fun preferenceScope(store: String, key: String): ModuleConfigScope? {
        if (store == "reamicro_api_server") return if (key == "cached_meta") null else ModuleConfigScope.API
        if (store != ModuleSettings.PREFS_NAME) return if (store in extraPrefs) ModuleConfigScope.OTHER else null
        if (key == ModuleSettings.KEY_MODULE_ENABLED || key.startsWith("sync_") ||
            key in setOf(ModuleSettings.KEY_CLOUD_SYNC_EXTENSION_ENABLED, ModuleSettings.KEY_CLOUD_GITHUB_SYNC_ENABLED) ||
            key.endsWith("_unlocked") || key.startsWith("search_origin_")) return null
        if (key.startsWith("online_login_") || key.startsWith("online_source_variable_") ||
            key.startsWith("online_cookie_domain_"))
            return ModuleConfigScope.SOURCE_CREDENTIALS
        if (key in setOf(ModuleSettings.KEY_READER_HIGHLIGHT_STYLES,
                ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_LIGHT_STYLE_ID,
                ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_DARK_STYLE_ID,
                ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_COLOR,
                ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_FONT)) return ModuleConfigScope.HIGHLIGHT_STYLES
        if (key.startsWith("reader_highlight_") || key in setOf(ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_ENABLED,
                ModuleSettings.KEY_READER_SELECTION_HIGHLIGHT_ENABLED)) return ModuleConfigScope.HIGHLIGHT_SETTINGS
        if (key.startsWith("online_download_daily_date_") || key.startsWith("online_download_daily_used_")) return null
        if (key.startsWith("online_source_") || key.startsWith("online_download_") ||
            key.startsWith("online_paragraph_comments_")) return ModuleConfigScope.SOURCES
        return ModuleConfigScope.OTHER
    }
    val extraPrefs = setOf("reamicro_reader_auto_page", "reamicro_reader_page_speed", "reamicro_discover")
    val preferenceStores = extraPrefs + ModuleSettings.PREFS_NAME + "reamicro_api_server"
    fun fileScope(path: String): ModuleConfigScope? {
        if (!safePath(path)) return null
        return when {
            path in setOf("reamicro_ai_apis.json", "reamicro_dictionary_settings.json", "reamicro_image_settings.json") -> ModuleConfigScope.API
            path.startsWith("reamicro_online_sources/") && path.count { it == '/' } == 1 &&
                path.substringAfterLast('.').lowercase() in setOf("rmonline", "json", "txt") -> ModuleConfigScope.SOURCES
            path.startsWith("reamicro_sources/") && path.count { it == '/' } == 1 &&
                path.endsWith(".rmsource") -> ModuleConfigScope.OTHER
            else -> null
        }
    }
    fun safePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
        '\\' !in path && ':' !in path && path.none(Char::isISOControl) &&
        path.split('/').all { it.isNotBlank() && it !in setOf(".", "..") }
}
