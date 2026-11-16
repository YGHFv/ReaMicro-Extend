package com.reamicro.fix.core

/** Feature entries for the host's embedded completion-plan page. */
object ModuleFeatureCatalog {
    const val ACTION_OPEN_SETTINGS = "com.reamicro.fix.action.OPEN_COMPLETION_SETTINGS"
    const val EXTRA_OPEN_SETTINGS = "com.reamicro.fix.extra.OPEN_COMPLETION_SETTINGS"
    data class Feature(val id: String, val title: String, val description: String)
    val features = listOf(
        Feature("association", "关联补全", "关联搜索源、取消关联、封面修复"),
        Feature("bookshelf", "书架补全", "同步显示、书架样式、封面渐显、批量与自动更新"),
        Feature("online", "在线补全", "书源、登录、下载、章节更新和 EPUB 样式"),
        Feature("cloud", "云盘补全", "WebDAV、同步扩展、本地书库、下载取消"),
        Feature("font", "字体补全", "字体库、宿主字体、导入与阅读字体"),
        Feature("reader", "阅读补全", "选中菜单、编辑覆盖、词典、高亮、翻页和搜索"),
        Feature("background", "主页补全", "背景图片、裁切、模糊、透明度和卡片样式"),
        Feature("account", "账号补全", "账号数据导出、偏好同步与切换入口"),
        Feature("api", "API 配置", "AI API、词典、生图与预设"),
        Feature("about", "关于补全", "日志、DexKit、自动任务与旋转设置"),
    )
}
