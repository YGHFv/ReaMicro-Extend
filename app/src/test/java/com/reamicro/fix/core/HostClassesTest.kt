package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宿主/框架类名的取值锁定。
 *
 * 这些字符串是模块与宿主之间的唯一契约，写错一个字符对应的功能就静默消失，
 * 而编译器完全看不出来。这里把每个取值钉死，重构挪动常量时能立刻发现改错。
 *
 * 宿主升级需要改类名时，同步改这里的期望值——这一步是刻意的摩擦，用来确保
 * 类名变更是有意为之而不是手误。
 */
class HostClassesTest {

    @Test
    fun `Kotlin 类名取值锁定`() {
        assertEquals("kotlin.jvm.internal.DefaultConstructorMarker", HostClasses.Kotlin.DEFAULT_CONSTRUCTOR_MARKER)
        assertEquals("kotlin.jvm.functions.Function0", HostClasses.Kotlin.FUNCTION0)
        assertEquals("kotlin.jvm.functions.Function1", HostClasses.Kotlin.FUNCTION1)
        assertEquals("kotlin.jvm.functions.Function2", HostClasses.Kotlin.FUNCTION2)
        assertEquals("kotlin.jvm.functions.Function3", HostClasses.Kotlin.FUNCTION3)
        assertEquals("kotlin.coroutines.Continuation", HostClasses.Kotlin.KOTLIN_CONTINUATION)
        assertEquals("kotlin.coroutines.intrinsics.CoroutineSingletons", HostClasses.Kotlin.KOTLIN_COROUTINE_SINGLETONS)
        assertEquals("kotlin.coroutines.EmptyCoroutineContext", HostClasses.Kotlin.KOTLIN_EMPTY_COROUTINE_CONTEXT)
        assertEquals("kotlin.coroutines.intrinsics.IntrinsicsKt", HostClasses.Kotlin.KOTLIN_INTRINSICS)
        assertEquals("kotlin.Pair", HostClasses.Kotlin.KOTLIN_PAIR)
        assertEquals("kotlin.jvm.internal.Reflection", HostClasses.Kotlin.KOTLIN_REFLECTION)
        assertEquals("kotlin.Result", HostClasses.Kotlin.KOTLIN_RESULT)
        assertEquals("kotlin.ResultKt", HostClasses.Kotlin.KOTLIN_RESULT_KT)
        assertEquals("kotlin.Unit", HostClasses.Kotlin.KOTLIN_UNIT)
    }

    @Test
    fun `Compose 类名取值锁定`() {
        assertEquals("androidx.compose.ui.Alignment", HostClasses.Compose.ALIGNMENT)
        assertEquals("androidx.compose.ui.draw.AlphaKt", HostClasses.Compose.ALPHA_KT)
        assertEquals("androidx.compose.ui.graphics.AndroidCanvas_androidKt", HostClasses.Compose.ANDROID_CANVAS_KT)
        assertEquals("androidx.compose.ui.viewinterop.AndroidView_androidKt", HostClasses.Compose.ANDROID_VIEW_KT)
        assertEquals("androidx.compose.animation.AnimatedVisibilityKt", HostClasses.Compose.ANIMATED_VISIBILITY_KT)
        assertEquals("androidx.compose.ui.text.AnnotatedString", HostClasses.Compose.ANNOTATED_STRING)
        assertEquals("androidx.compose.ui.text.AnnotatedString\$Builder", HostClasses.Compose.ANNOTATED_STRING_BUILDER)
        assertEquals("androidx.compose.ui.text.AnnotatedString\$Range", HostClasses.Compose.ANNOTATED_STRING_RANGE)
        assertEquals("androidx.compose.foundation.layout.Arrangement", HostClasses.Compose.ARRANGEMENT)
        assertEquals("androidx.compose.material.icons.automirrored.outlined.ArrowBackKt", HostClasses.Compose.ARROW_BACK_ICON)
        assertEquals("androidx.compose.foundation.BackgroundKt", HostClasses.Compose.BACKGROUND_KT)
        assertEquals("androidx.compose.foundation.text.BasicTextKt", HostClasses.Compose.BASIC_TEXT)
        assertEquals("androidx.compose.foundation.BorderKt", HostClasses.Compose.BORDER_KT)
        assertEquals("androidx.compose.foundation.layout.BoxKt", HostClasses.Compose.BOX_KT)
        assertEquals("androidx.compose.ui.graphics.Brush", HostClasses.Compose.BRUSH_COMPANION)
        assertEquals("androidx.compose.ui.graphics.BrushKt", HostClasses.Compose.BRUSH_KT)
        assertEquals("androidx.compose.foundation.ClickableKt", HostClasses.Compose.CLICKABLE_KT)
        assertEquals("androidx.compose.ui.draw.ClipKt", HostClasses.Compose.CLIP_KT)
        assertEquals("androidx.compose.ui.graphics.Color", HostClasses.Compose.COLOR)
        assertEquals("androidx.compose.ui.graphics.ColorKt", HostClasses.Compose.COLOR_KT)
        assertEquals("androidx.compose.material3.ColorScheme", HostClasses.Compose.COLOR_SCHEME)
        assertEquals("androidx.compose.foundation.layout.ColumnKt", HostClasses.Compose.COLUMN_KT)
        assertEquals("androidx.compose.runtime.internal.ComposableLambdaKt", HostClasses.Compose.COMPOSABLE_LAMBDA_KT)
        assertEquals("androidx.compose.runtime.Composer", HostClasses.Compose.COMPOSER)
        assertEquals("androidx.compose.ui.text.Placeholder", HostClasses.Compose.COMPOSE_PLACEHOLDER)
        assertEquals("androidx.compose.runtime.State", HostClasses.Compose.COMPOSE_STATE)
        assertEquals("androidx.compose.material.icons.outlined.ContentCopyKt", HostClasses.Compose.CONTENT_COPY_ICON)
        assertEquals("androidx.compose.material.icons.outlined.DarkModeKt", HostClasses.Compose.DARK_MODE_ICON)
        assertEquals("androidx.compose.material.icons.outlined.AutoStoriesKt", HostClasses.Compose.DICTIONARY_ICON_AUTO_STORIES)
        assertEquals("androidx.compose.material.icons.outlined.BookKt", HostClasses.Compose.DICTIONARY_ICON_BOOK)
        assertEquals("androidx.compose.material.icons.outlined.MenuBookKt", HostClasses.Compose.DICTIONARY_ICON_MENU_BOOK)
        assertEquals("androidx.compose.material.icons.outlined.TranslateKt", HostClasses.Compose.DICTIONARY_ICON_TRANSLATE)
        assertEquals("androidx.compose.ui.draw.DrawModifierKt", HostClasses.Compose.DRAW_MODIFIER_KT)
        assertEquals("androidx.compose.material.icons.outlined.EditKt", HostClasses.Compose.EDIT_ICON)
        assertEquals("androidx.compose.foundation.layout.FlowLayoutKt", HostClasses.Compose.FLOW_LAYOUT_KT)
        assertEquals("androidx.compose.ui.text.font.FontFamily", HostClasses.Compose.FONT_FAMILY)
        assertEquals("androidx.compose.ui.text.font.FontFamilyKt", HostClasses.Compose.FONT_FAMILY_KT)
        assertEquals("androidx.compose.ui.text.font.FontWeight", HostClasses.Compose.FONT_WEIGHT)
        assertEquals("androidx.compose.foundation.shape.RoundedCornerShapeKt", HostClasses.Compose.FOUNDATION_SHAPE_KT)
        assertEquals("androidx.compose.material.icons.outlined.BorderColorKt", HostClasses.Compose.HIGHLIGHT_ICON_BORDER_COLOR)
        assertEquals("androidx.compose.material.icons.outlined.FormatColorFillKt", HostClasses.Compose.HIGHLIGHT_ICON_FORMAT_COLOR_FILL)
        assertEquals("androidx.compose.material.icons.outlined.ModeEditKt", HostClasses.Compose.HIGHLIGHT_ICON_MODE_EDIT)
        assertEquals("androidx.compose.material3.IconKt", HostClasses.Compose.ICON_KT)
        assertEquals("androidx.compose.ui.graphics.vector.ImageVector", HostClasses.Compose.IMAGE_VECTOR)
        assertEquals("androidx.compose.foundation.text.inlineContent", HostClasses.Compose.INLINE_CONTENT_ANNOTATION_TAG)
        assertEquals("androidx.compose.foundation.lazy.LazyDslKt", HostClasses.Compose.LAZY_DSL_KT)
        assertEquals("androidx.compose.foundation.lazy.LazyListScope", HostClasses.Compose.LAZY_LIST_SCOPE)
        assertEquals("androidx.compose.material.icons.outlined.LightModeKt", HostClasses.Compose.LIGHT_MODE_ICON)
        assertEquals("androidx.compose.material3.ListItemDefaults", HostClasses.Compose.LIST_ITEM_DEFAULTS)
        assertEquals("androidx.compose.material3.ListItemKt", HostClasses.Compose.LIST_ITEM_KT)
        assertEquals("androidx.compose.material3.MaterialTheme", HostClasses.Compose.MATERIAL_THEME)
        assertEquals("androidx.compose.ui.Modifier", HostClasses.Compose.MODIFIER)
        assertEquals("androidx.compose.runtime.MutableState", HostClasses.Compose.MUTABLE_STATE)
        assertEquals("androidx.compose.material.icons.automirrored.filled.NavigateNextKt", HostClasses.Compose.NAVIGATE_NEXT_ICON)
        assertEquals("androidx.compose.foundation.layout.PaddingKt", HostClasses.Compose.PADDING_KT)
        assertEquals("androidx.compose.foundation.layout.PaddingValues", HostClasses.Compose.PADDING_VALUES)
        assertEquals("androidx.compose.ui.text.PlaceholderVerticalAlign", HostClasses.Compose.PLACEHOLDER_VERTICAL_ALIGN)
        assertEquals("androidx.compose.material.icons.outlined.RecordVoiceOverKt", HostClasses.Compose.READ_ALOUD_ICON_RECORD_VOICE_OVER)
        assertEquals("androidx.compose.material.icons.outlined.VolumeUpKt", HostClasses.Compose.READ_ALOUD_ICON_VOLUME_UP)
        assertEquals("androidx.compose.ui.graphics.RectangleShapeKt", HostClasses.Compose.RECTANGLE_SHAPE_KT)
        assertEquals("androidx.compose.foundation.layout.RowKt", HostClasses.Compose.ROW_KT)
        assertEquals("androidx.compose.foundation.layout.RowScope", HostClasses.Compose.ROW_SCOPE)
        assertEquals("androidx.compose.material3.ScaffoldKt", HostClasses.Compose.SCAFFOLD_KT)
        assertEquals("androidx.compose.ui.draw.ScaleKt", HostClasses.Compose.SCALE_KT)
        assertEquals("androidx.compose.foundation.ScrollKt", HostClasses.Compose.SCROLL_KT)
        assertEquals("androidx.compose.material.icons.outlined.SearchKt", HostClasses.Compose.SEARCH_ICON)
        assertEquals("androidx.compose.foundation.layout.SizeKt", HostClasses.Compose.SIZE_KT)
        assertEquals("androidx.compose.runtime.SnapshotStateKt__SnapshotStateKt", HostClasses.Compose.SNAPSHOT_STATE_KT)
        assertEquals("androidx.compose.ui.graphics.SolidColor", HostClasses.Compose.SOLID_COLOR)
        assertEquals("androidx.compose.foundation.layout.SpacerKt", HostClasses.Compose.SPACER_KT)
        assertEquals("androidx.compose.ui.text.SpanStyle", HostClasses.Compose.SPAN_STYLE)
        assertEquals("androidx.compose.ui.text.StringAnnotation", HostClasses.Compose.STRING_ANNOTATION)
        assertEquals("org.jetbrains.compose.resources.StringResourcesKt", HostClasses.Compose.STRING_RESOURCES)
        assertEquals("androidx.compose.material3.SwitchDefaults", HostClasses.Compose.SWITCH_DEFAULTS)
        assertEquals("androidx.compose.material3.SwitchKt", HostClasses.Compose.SWITCH_KT)
        assertEquals("androidx.compose.material3.TextKt", HostClasses.Compose.TEXT_KT)
        assertEquals("androidx.compose.ui.text.style.TextOverflow", HostClasses.Compose.TEXT_OVERFLOW)
        assertEquals("androidx.compose.ui.text.TextStyle", HostClasses.Compose.TEXT_STYLE)
        assertEquals("androidx.compose.ui.unit.TextUnitKt", HostClasses.Compose.TEXT_UNIT_KT)
        assertEquals("androidx.compose.material3.TopAppBarDefaults", HostClasses.Compose.TOP_APP_BAR_DEFAULTS)
        assertEquals("androidx.compose.ui.graphics.vector.VectorKt", HostClasses.Compose.VECTOR_KT)
        assertEquals("androidx.compose.foundation.layout.WindowInsets", HostClasses.Compose.WINDOW_INSETS)
        assertEquals("androidx.compose.foundation.layout.WindowInsetsKt", HostClasses.Compose.WINDOW_INSETS_KT)
    }

    @Test
    fun `AndroidX 类名取值锁定`() {
        assertEquals("androidx.activity.compose.BackHandlerKt", HostClasses.AndroidX.BACK_HANDLER_KT)
        assertEquals("androidx.paging.LoadStates", HostClasses.AndroidX.LOAD_STATES)
        assertEquals("androidx.navigationevent.compose.NavigationEventHandlerKt", HostClasses.AndroidX.NAVIGATION_EVENT_HANDLER_KT)
        assertEquals("androidx.navigation.NavBackStackEntryKt", HostClasses.AndroidX.NAV_BACK_STACK_ENTRY_KT)
        assertEquals("androidx.navigation.NavController", HostClasses.AndroidX.NAV_CONTROLLER)
        assertEquals("androidx.paging.PagingData", HostClasses.AndroidX.PAGING_DATA)
        assertEquals("androidx.navigationevent.compose.RememberNavigationEventStateKt", HostClasses.AndroidX.REMEMBER_NAVIGATION_EVENT_STATE_KT)
    }

    @Test
    fun `Host 类名取值锁定`() {
        assertEquals("app.zhendong.reamicro.ui.setting.AboutScreenKt", HostClasses.Host.ABOUT_SCREEN)
        assertEquals("app.zhendong.reamicro.ui.setting.AccountSecurityScreenKt", HostClasses.Host.ACCOUNT_SECURITY_SCREEN)
        assertEquals("app.zhendong.reamicro.arch.icons.colored.AliyunKt", HostClasses.Host.ALIYUN_ICON)
        assertEquals("app.zhendong.reamicro.arch.icons.colored.AndroidOsKt", HostClasses.Host.ANDROID_OS_ICON)
        assertEquals("app.zhendong.reamicro.ui.setting.components.AppAboutKt", HostClasses.Host.APP_ABOUT)
        assertEquals("app.zhendong.reamicro.AppKt", HostClasses.Host.APP_KT)
        assertEquals("app.zhendong.reamicro.arch.components.AppTopBarKt", HostClasses.Host.APP_TOP_BAR)
        assertEquals("app.zhendong.reamicro.ui.backup.components.AuthCardKt", HostClasses.Host.AUTH_CARD)
        assertEquals("app.zhendong.reamicro.constants.BackupType", HostClasses.Host.BACKUP_TYPE)
        assertEquals("app.zhendong.reamicro.ui.storage.baidu.BaiduNetDiskAccountScreenKt", HostClasses.Host.BAIDU_ACCOUNT_SCREEN)
        assertEquals("app.zhendong.reamicro.arch.icons.colored.BaiduNetdiskKt", HostClasses.Host.BAIDU_ICON)
        assertEquals("app.zhendong.reamicro.ui.storage.baidu.BaiduNetDiskViewModel", HostClasses.Host.BAIDU_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro.data.db.entity.Book", HostClasses.Host.BOOK)
        assertEquals("app.zhendong.reamicro.data.reader.Bookmark", HostClasses.Host.BOOKMARK)
        assertEquals("app.zhendong.reamicro.repository.BookshelfRepository", HostClasses.Host.BOOKSHELF_REPOSITORY)
        assertEquals("app.zhendong.reamicro.ui.home.BookshelfScreenKt", HostClasses.Host.BOOKSHELF_SCREEN)
        assertEquals("app.zhendong.reamicro.ui.backup.BookBackupScreenKt", HostClasses.Host.BOOK_BACKUP_SCREEN)
        assertEquals("app.zhendong.reamicro.ui.backup.BookBackupViewModel", HostClasses.Host.BOOK_BACKUP_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro.ui.book.BookDetailsViewModel", HostClasses.Host.BOOK_DETAILS_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro.ui.home.components.BookLibrarySheetKt", HostClasses.Host.BOOK_LIBRARY_SHEET)
        assertEquals("app.zhendong.reamicro.ui.home.components.BookLocalSheetKt", HostClasses.Host.BOOK_LOCAL_SHEET)
        assertEquals("app.zhendong.reamicro.ui.home.components.BookOverviewItemsKt", HostClasses.Host.BOOK_OVERVIEW_ITEMS)
        assertEquals("app.zhendong.reamicro.ui.home.BookOverviewViewModel", HostClasses.Host.BOOK_OVERVIEW_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro.ui.book.BookPublishViewModel", HostClasses.Host.BOOK_PUBLISH_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro.ui.storage.components.BookRowInfoKt", HostClasses.Host.BOOK_ROW_INFO)
        assertEquals("app.zhendong.reamicro.repository.core.Cached", HostClasses.Host.CACHED)
        assertEquals("app.zhendong.reamicro.ui.reader.CatalogChapterItem", HostClasses.Host.CATALOG_CHAPTER_ITEM)
        assertEquals("app.zhendong.reamicro.data.storage.CloudBook", HostClasses.Host.CLOUD_BOOK)
        assertEquals("app.zhendong.reamicro.ui.storage.components.CloudBookListKt", HostClasses.Host.CLOUD_BOOK_LIST)
        assertEquals("app.zhendong.reamicro.data.storage.CloudFolder", HostClasses.Host.CLOUD_FOLDER)
        assertEquals("app.zhendong.reamicro.repository.storage.CloudStorageRepository", HostClasses.Host.CLOUD_STORAGE_REPOSITORY)
        assertEquals("app.zhendong.reamicro.ui.storage.CloudStorageScreenKt", HostClasses.Host.CLOUD_STORAGE_SCREEN)
        assertEquals("app.zhendong.reamicro.ui.storage.CloudStorageUIEvent", HostClasses.Host.CLOUD_STORAGE_UI_EVENT)
        assertEquals("app.zhendong.reamicro.ui.storage.CloudStorageUiState", HostClasses.Host.CLOUD_STORAGE_UI_STATE)
        assertEquals("app.zhendong.reamicro.ui.storage.CloudStorageViewModel", HostClasses.Host.CLOUD_STORAGE_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro.ui.storage.components.CloudTreeKt", HostClasses.Host.CLOUD_TREE)
        assertEquals("app.zhendong.reamicro.data.storage.CloudUserInfo", HostClasses.Host.CLOUD_USER_INFO)
        assertEquals("app.zhendong.reamicro.data.third.Dir", HostClasses.Host.DIR)
        assertEquals("app.zhendong.reamicro.arch.components.DividerKt", HostClasses.Host.DIVIDER_KT)
        assertEquals("app.zhendong.reamicro.ui.backup.components.DriveCardKt", HostClasses.Host.DRIVE_CARD)
        assertEquals("app.zhendong.reamicro.ui.reader.theme.DynamicThemeContentKt", HostClasses.Host.DYNAMIC_THEME_CONTENT_KT)
        assertEquals("app.zhendong.reamicro.data.res.EnvelopeKt", HostClasses.Host.ENVELOPE_KT)
        assertEquals("app.zhendong.reamicro.ui.reader.components.EpubContainerKt", HostClasses.Host.EPUB_CONTAINER_KT)
        assertEquals("app.zhendong.reamicro.arch.EpubFileManager", HostClasses.Host.EPUB_FILE_MANAGER)
        assertEquals("app.zhendong.reamicro.data.epub.EpubPage", HostClasses.Host.EPUB_PAGE)
        assertEquals("app.zhendong.reamicro.arch.icons.colored.FileFolderKt", HostClasses.Host.FILE_FOLDER_ICON)
        assertEquals("app.zhendong.reamicro.arch.FileSource", HostClasses.Host.FILE_SOURCE)
        assertEquals("app.zhendong.reamicro.arch.components.item.FooterKt", HostClasses.Host.FOOTER)
        assertEquals("app.zhendong.reamicro.ui.home.HomeScreenKt", HostClasses.Host.HOME_SCREEN)
        assertEquals("app.zhendong.reamicro.ui.home.components.HomeSearchBarKt", HostClasses.Host.HOME_SEARCH_BAR)
        assertEquals("app.zhendong.reamicro.ui.home.HomeViewModel", HostClasses.Host.HOME_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro", HostClasses.Host.HOST_PACKAGE_NAME)
        assertEquals("app.zhendong.reamicro.arch.theme.TextStyleKt", HostClasses.Host.HOST_TEXT_STYLE_KT)
        assertEquals("app.zhendong.reamicro.data.db.entity.User", HostClasses.Host.HOST_USER)
        assertEquals("app.zhendong.reamicro.data.db.dao.UserDao", HostClasses.Host.HOST_USER_DAO)
        assertEquals("app.zhendong.reamicro.arch.IntentReceiver", HostClasses.Host.INTENT_RECEIVER)
        assertEquals("app.zhendong.reamicro.arch.components.JustifyText_androidKt", HostClasses.Host.JUSTIFY_TEXT)
        assertEquals("app.zhendong.reamicro.ui.backup.components.LocalStorageCardKt", HostClasses.Host.LOCAL_STORAGE_CARD)
        assertEquals("app.zhendong.reamicro.MainActivity", HostClasses.Host.MAIN_ACTIVITY)
        assertEquals("app.zhendong.reamicro.data.db.entity.Mark", HostClasses.Host.MARK)
        assertEquals("app.zhendong.reamicro.arch.components.slider.MaterialSliderDefaults", HostClasses.Host.MATERIAL_SLIDER_DEFAULTS)
        assertEquals("app.zhendong.reamicro.arch.components.NavControllerHolder", HostClasses.Host.NAV_CONTROLLER_HOLDER)
        assertEquals("app.zhendong.reamicro.NavGraphScope", HostClasses.Host.NAV_GRAPH_SCOPE)
        assertEquals("app.zhendong.reamicro.ui.storage.components.NotAuthKt", HostClasses.Host.NOT_AUTH)
        assertEquals("app.zhendong.reamicro.data.res.book.PostUserBookReq", HostClasses.Host.POST_USER_BOOK_REQ)
        assertEquals("app.zhendong.reamicro.constants.PrefKeys", HostClasses.Host.PREF_KEYS)
        assertEquals("app.zhendong.reamicro.ui.profile.ProfileScreenKt", HostClasses.Host.PROFILE_SCREEN)
        assertEquals("app.zhendong.reamicro.ui.reader.components.ReaderBottomBarKt", HostClasses.Host.READER_BOTTOM_BAR)
        assertEquals("app.zhendong.reamicro.ui.reader.compose.ReaderCatalogKt", HostClasses.Host.READER_CATALOG)
        assertEquals("app.zhendong.reamicro.ui.reader.compose.ReaderFamilyBuildInKt", HostClasses.Host.READER_FAMILY_BUILD_IN)
        assertEquals("app.zhendong.reamicro.ui.reader.compose.ReaderFamilyEpubKt", HostClasses.Host.READER_FAMILY_EPUB)
        assertEquals("app.zhendong.reamicro.ui.reader.compose.ReaderFamilyUserKt", HostClasses.Host.READER_FAMILY_USER)
        assertEquals("app.zhendong.reamicro.ui.reader.compose.ReaderHighlightScreenKt", HostClasses.Host.READER_HIGHLIGHT_SCREEN)
        assertEquals("app.zhendong.reamicro.ui.reader.ReaderScreenKt", HostClasses.Host.READER_SCREEN_KT)
        assertEquals("app.zhendong.reamicro.ui.reader.compose.ReaderMoreSettingScreenKt", HostClasses.Host.READER_SETTINGS)
        assertEquals("app.zhendong.reamicro.ui.reader.components.ReaderSharedState", HostClasses.Host.READER_SHARED_STATE)
        assertEquals("app.zhendong.reamicro.ui.reader.compose.ReaderThemesKt", HostClasses.Host.READER_THEMES_KT)
        assertEquals("app.zhendong.reamicro.ui.reader.compose.ReaderTypeSettingKt", HostClasses.Host.READER_TYPE_SETTING)
        assertEquals("app.zhendong.reamicro.ui.reader.ReaderUiIntent", HostClasses.Host.READER_UI_INTENT)
        assertEquals("app.zhendong.reamicro.ui.reader.ReaderViewModel", HostClasses.Host.READER_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro.ui.reader.components.ScrollPagerKt", HostClasses.Host.SCROLL_PAGER_KT)
        assertEquals("app.zhendong.reamicro.repository.core.Session", HostClasses.Host.SESSION)
        assertEquals("app.zhendong.reamicro.ui.setting.SettingsScreenKt", HostClasses.Host.SETTINGS_SCREEN)
        assertEquals("app.zhendong.reamicro.arch.components.ShapeKt", HostClasses.Host.SHAPE_KT)
        assertEquals("app.zhendong.reamicro.arch.components.slider.SliderBrushColor", HostClasses.Host.SLIDER_BRUSH_COLOR)
        assertEquals("app.zhendong.reamicro.ui.reader.components.TapGesturesBoxKt", HostClasses.Host.TAP_GESTURES_BOX)
        assertEquals("app.zhendong.reamicro.arch.theme.ThemeKt", HostClasses.Host.THEME_KT)
        assertEquals("app.zhendong.reamicro.data.search.ThirdParty", HostClasses.Host.THIRD_PARTY)
        assertEquals("app.zhendong.reamicro.data.search.ThirdPartyBook", HostClasses.Host.THIRD_PARTY_BOOK)
        assertEquals("app.zhendong.reamicro.data.third.ThirdPartyKeys", HostClasses.Host.THIRD_PARTY_KEYS)
        assertEquals("app.zhendong.reamicro.arch.extensions.TimeExtKt", HostClasses.Host.TIME_EXT_KT)
        assertEquals("app.zhendong.reamicro.ui.reader.components.TypeSettingSliderKt", HostClasses.Host.TYPE_SETTING_SLIDER)
        assertEquals("app.zhendong.reamicro.ui.reader.UiSheetStatus", HostClasses.Host.UI_SHEET_STATUS)
        assertEquals("app.zhendong.reamicro.ui.reader.UiStatus", HostClasses.Host.UI_STATUS)
        assertEquals("app.zhendong.reamicro.arch.extensions.UnitExtKt", HostClasses.Host.UNIT_EXT_KT)
        assertEquals("app.zhendong.reamicro.repository.UserRepository", HostClasses.Host.USER_REPOSITORY)
        assertEquals("app.zhendong.reamicro.arch.fs.UserStorage", HostClasses.Host.USER_STORAGE)
        assertEquals("app.zhendong.reamicro.ui.insets.WindowInsetsExt_androidKt", HostClasses.Host.WINDOW_INSETS_EXT_ANDROID_KT)
        assertEquals("app.zhendong.reamicro.arch.WorkerManager", HostClasses.Host.WORKER_MANAGER)
        assertEquals("app.zhendong.reamicro.arch.WorkHandle", HostClasses.Host.WORK_HANDLE)
        assertEquals("app.zhendong.reamicro.arch.WorkState", HostClasses.Host.WORK_STATE)
        assertEquals("app.zhendong.reamicro.arch.WorkStatus", HostClasses.Host.WORK_STATUS)
        assertEquals("app.zhendong.reamicro.arch.WorkTracker", HostClasses.Host.WORK_TRACKER)
        assertEquals("app.zhendong.reamicro.ui.storage.c115.Y115NetDiskAccountScreenKt", HostClasses.Host.Y115_ACCOUNT_SCREEN)
        assertEquals("app.zhendong.reamicro.ui.storage.c115.Y115NetDiskViewModel", HostClasses.Host.Y115_VIEW_MODEL)
        assertEquals("app.zhendong.reamicro.arch.icons.colored.Yun115Kt", HostClasses.Host.YUN115_ICON)
    }

    @Test
    fun `Epub 类名取值锁定`() {
        assertEquals("org.epub.utils.AnnotatedStringExtKt", HostClasses.Epub.ANNOTATED_STRING_EXT)
        assertEquals("org.epub.ui.CommentAnnotation", HostClasses.Epub.COMMENT_ANNOTATION)
        assertEquals("org.epub.html.node.ContentDom", HostClasses.Epub.CONTENT_DOM)
        assertEquals("org.epub.html.EpubCFI", HostClasses.Epub.EPUB_CFI)
        assertEquals("org.epub.FontProvider", HostClasses.Epub.FONT_PROVIDER)
        assertEquals("org.epub.html.HtmlDocument", HostClasses.Epub.HTML_DOCUMENT)
        assertEquals("org.epub.html.ImagePlaceholder", HostClasses.Epub.IMAGE_PLACEHOLDER)
        assertEquals("org.epub.structure.opf.Opf", HostClasses.Epub.OPF)
        assertEquals("org.epub.UIEpubWindow", HostClasses.Epub.UI_EPUB_WINDOW)
    }

    @Test
    fun `ThirdParty 类名取值锁定`() {
        assertEquals("okhttp3.internal.platform.Android10Platform", HostClasses.ThirdParty.OKHTTP_ANDROID10_PLATFORM)
        assertEquals("okhttp3.internal.platform.AndroidPlatform", HostClasses.ThirdParty.OKHTTP_ANDROID_PLATFORM)
        assertEquals("okhttp3.OkHttpClient", HostClasses.ThirdParty.OKHTTP_CLIENT)
        assertEquals("okhttp3.MediaType", HostClasses.ThirdParty.OKHTTP_MEDIA_TYPE)
        assertEquals("okhttp3.internal.platform.Platform", HostClasses.ThirdParty.OKHTTP_PLATFORM)
        assertEquals("okhttp3.Request", HostClasses.ThirdParty.OKHTTP_REQUEST)
        assertEquals("okhttp3.RequestBody", HostClasses.ThirdParty.OKHTTP_REQUEST_BODY)
        assertEquals("okio.Path", HostClasses.ThirdParty.OKIO_PATH)
    }

    @Test
    fun `类名取值互不重复`() {
        val all = listOf(
            HostClasses.Kotlin.DEFAULT_CONSTRUCTOR_MARKER,
            HostClasses.Kotlin.FUNCTION0,
            HostClasses.Kotlin.FUNCTION1,
            HostClasses.Kotlin.FUNCTION2,
            HostClasses.Kotlin.FUNCTION3,
            HostClasses.Kotlin.KOTLIN_CONTINUATION,
            HostClasses.Kotlin.KOTLIN_COROUTINE_SINGLETONS,
            HostClasses.Kotlin.KOTLIN_EMPTY_COROUTINE_CONTEXT,
            HostClasses.Kotlin.KOTLIN_INTRINSICS,
            HostClasses.Kotlin.KOTLIN_PAIR,
            HostClasses.Kotlin.KOTLIN_REFLECTION,
            HostClasses.Kotlin.KOTLIN_RESULT,
            HostClasses.Kotlin.KOTLIN_RESULT_KT,
            HostClasses.Kotlin.KOTLIN_UNIT,
            HostClasses.Compose.ALIGNMENT,
            HostClasses.Compose.ALPHA_KT,
            HostClasses.Compose.ANDROID_CANVAS_KT,
            HostClasses.Compose.ANDROID_VIEW_KT,
            HostClasses.Compose.ANIMATED_VISIBILITY_KT,
            HostClasses.Compose.ANNOTATED_STRING,
            HostClasses.Compose.ANNOTATED_STRING_BUILDER,
            HostClasses.Compose.ANNOTATED_STRING_RANGE,
            HostClasses.Compose.ARRANGEMENT,
            HostClasses.Compose.ARROW_BACK_ICON,
            HostClasses.Compose.BACKGROUND_KT,
            HostClasses.Compose.BASIC_TEXT,
            HostClasses.Compose.BORDER_KT,
            HostClasses.Compose.BOX_KT,
            HostClasses.Compose.BRUSH_COMPANION,
            HostClasses.Compose.BRUSH_KT,
            HostClasses.Compose.CLICKABLE_KT,
            HostClasses.Compose.CLIP_KT,
            HostClasses.Compose.COLOR,
            HostClasses.Compose.COLOR_KT,
            HostClasses.Compose.COLOR_SCHEME,
            HostClasses.Compose.COLUMN_KT,
            HostClasses.Compose.COMPOSABLE_LAMBDA_KT,
            HostClasses.Compose.COMPOSER,
            HostClasses.Compose.COMPOSE_PLACEHOLDER,
            HostClasses.Compose.COMPOSE_STATE,
            HostClasses.Compose.CONTENT_COPY_ICON,
            HostClasses.Compose.DARK_MODE_ICON,
            HostClasses.Compose.DICTIONARY_ICON_AUTO_STORIES,
            HostClasses.Compose.DICTIONARY_ICON_BOOK,
            HostClasses.Compose.DICTIONARY_ICON_MENU_BOOK,
            HostClasses.Compose.DICTIONARY_ICON_TRANSLATE,
            HostClasses.Compose.DRAW_MODIFIER_KT,
            HostClasses.Compose.EDIT_ICON,
            HostClasses.Compose.FLOW_LAYOUT_KT,
            HostClasses.Compose.FONT_FAMILY,
            HostClasses.Compose.FONT_FAMILY_KT,
            HostClasses.Compose.FONT_WEIGHT,
            HostClasses.Compose.FOUNDATION_SHAPE_KT,
            HostClasses.Compose.HIGHLIGHT_ICON_BORDER_COLOR,
            HostClasses.Compose.HIGHLIGHT_ICON_FORMAT_COLOR_FILL,
            HostClasses.Compose.HIGHLIGHT_ICON_MODE_EDIT,
            HostClasses.Compose.ICON_KT,
            HostClasses.Compose.IMAGE_VECTOR,
            HostClasses.Compose.INLINE_CONTENT_ANNOTATION_TAG,
            HostClasses.Compose.LAZY_DSL_KT,
            HostClasses.Compose.LAZY_LIST_SCOPE,
            HostClasses.Compose.LIGHT_MODE_ICON,
            HostClasses.Compose.LIST_ITEM_DEFAULTS,
            HostClasses.Compose.LIST_ITEM_KT,
            HostClasses.Compose.MATERIAL_THEME,
            HostClasses.Compose.MODIFIER,
            HostClasses.Compose.MUTABLE_STATE,
            HostClasses.Compose.NAVIGATE_NEXT_ICON,
            HostClasses.Compose.PADDING_KT,
            HostClasses.Compose.PADDING_VALUES,
            HostClasses.Compose.PLACEHOLDER_VERTICAL_ALIGN,
            HostClasses.Compose.READ_ALOUD_ICON_RECORD_VOICE_OVER,
            HostClasses.Compose.READ_ALOUD_ICON_VOLUME_UP,
            HostClasses.Compose.RECTANGLE_SHAPE_KT,
            HostClasses.Compose.ROW_KT,
            HostClasses.Compose.ROW_SCOPE,
            HostClasses.Compose.SCAFFOLD_KT,
            HostClasses.Compose.SCALE_KT,
            HostClasses.Compose.SCROLL_KT,
            HostClasses.Compose.SEARCH_ICON,
            HostClasses.Compose.SIZE_KT,
            HostClasses.Compose.SNAPSHOT_STATE_KT,
            HostClasses.Compose.SOLID_COLOR,
            HostClasses.Compose.SPACER_KT,
            HostClasses.Compose.SPAN_STYLE,
            HostClasses.Compose.STRING_ANNOTATION,
            HostClasses.Compose.STRING_RESOURCES,
            HostClasses.Compose.SWITCH_DEFAULTS,
            HostClasses.Compose.SWITCH_KT,
            HostClasses.Compose.TEXT_KT,
            HostClasses.Compose.TEXT_OVERFLOW,
            HostClasses.Compose.TEXT_STYLE,
            HostClasses.Compose.TEXT_UNIT_KT,
            HostClasses.Compose.TOP_APP_BAR_DEFAULTS,
            HostClasses.Compose.VECTOR_KT,
            HostClasses.Compose.WINDOW_INSETS,
            HostClasses.Compose.WINDOW_INSETS_KT,
            HostClasses.AndroidX.BACK_HANDLER_KT,
            HostClasses.AndroidX.LOAD_STATES,
            HostClasses.AndroidX.NAVIGATION_EVENT_HANDLER_KT,
            HostClasses.AndroidX.NAV_BACK_STACK_ENTRY_KT,
            HostClasses.AndroidX.NAV_CONTROLLER,
            HostClasses.AndroidX.PAGING_DATA,
            HostClasses.AndroidX.REMEMBER_NAVIGATION_EVENT_STATE_KT,
            HostClasses.Host.ABOUT_SCREEN,
            HostClasses.Host.ACCOUNT_SECURITY_SCREEN,
            HostClasses.Host.ALIYUN_ICON,
            HostClasses.Host.ANDROID_OS_ICON,
            HostClasses.Host.APP_ABOUT,
            HostClasses.Host.APP_KT,
            HostClasses.Host.APP_TOP_BAR,
            HostClasses.Host.AUTH_CARD,
            HostClasses.Host.BACKUP_TYPE,
            HostClasses.Host.BAIDU_ACCOUNT_SCREEN,
            HostClasses.Host.BAIDU_ICON,
            HostClasses.Host.BAIDU_VIEW_MODEL,
            HostClasses.Host.BOOK,
            HostClasses.Host.BOOKMARK,
            HostClasses.Host.BOOKSHELF_REPOSITORY,
            HostClasses.Host.BOOKSHELF_SCREEN,
            HostClasses.Host.BOOK_BACKUP_SCREEN,
            HostClasses.Host.BOOK_BACKUP_VIEW_MODEL,
            HostClasses.Host.BOOK_DETAILS_VIEW_MODEL,
            HostClasses.Host.BOOK_LIBRARY_SHEET,
            HostClasses.Host.BOOK_LOCAL_SHEET,
            HostClasses.Host.BOOK_OVERVIEW_ITEMS,
            HostClasses.Host.BOOK_OVERVIEW_VIEW_MODEL,
            HostClasses.Host.BOOK_PUBLISH_VIEW_MODEL,
            HostClasses.Host.BOOK_ROW_INFO,
            HostClasses.Host.CACHED,
            HostClasses.Host.CATALOG_CHAPTER_ITEM,
            HostClasses.Host.CLOUD_BOOK,
            HostClasses.Host.CLOUD_BOOK_LIST,
            HostClasses.Host.CLOUD_FOLDER,
            HostClasses.Host.CLOUD_STORAGE_REPOSITORY,
            HostClasses.Host.CLOUD_STORAGE_SCREEN,
            HostClasses.Host.CLOUD_STORAGE_UI_EVENT,
            HostClasses.Host.CLOUD_STORAGE_UI_STATE,
            HostClasses.Host.CLOUD_STORAGE_VIEW_MODEL,
            HostClasses.Host.CLOUD_TREE,
            HostClasses.Host.CLOUD_USER_INFO,
            HostClasses.Host.DIR,
            HostClasses.Host.DIVIDER_KT,
            HostClasses.Host.DRIVE_CARD,
            HostClasses.Host.DYNAMIC_THEME_CONTENT_KT,
            HostClasses.Host.ENVELOPE_KT,
            HostClasses.Host.EPUB_CONTAINER_KT,
            HostClasses.Host.EPUB_FILE_MANAGER,
            HostClasses.Host.EPUB_PAGE,
            HostClasses.Host.FILE_FOLDER_ICON,
            HostClasses.Host.FILE_SOURCE,
            HostClasses.Host.FOOTER,
            HostClasses.Host.HOME_SCREEN,
            HostClasses.Host.HOME_SEARCH_BAR,
            HostClasses.Host.HOME_VIEW_MODEL,
            HostClasses.Host.HOST_PACKAGE_NAME,
            HostClasses.Host.HOST_TEXT_STYLE_KT,
            HostClasses.Host.HOST_USER,
            HostClasses.Host.HOST_USER_DAO,
            HostClasses.Host.INTENT_RECEIVER,
            HostClasses.Host.JUSTIFY_TEXT,
            HostClasses.Host.LOCAL_STORAGE_CARD,
            HostClasses.Host.MAIN_ACTIVITY,
            HostClasses.Host.MARK,
            HostClasses.Host.MATERIAL_SLIDER_DEFAULTS,
            HostClasses.Host.NAV_CONTROLLER_HOLDER,
            HostClasses.Host.NAV_GRAPH_SCOPE,
            HostClasses.Host.NOT_AUTH,
            HostClasses.Host.POST_USER_BOOK_REQ,
            HostClasses.Host.PREF_KEYS,
            HostClasses.Host.PROFILE_SCREEN,
            HostClasses.Host.READER_BOTTOM_BAR,
            HostClasses.Host.READER_CATALOG,
            HostClasses.Host.READER_FAMILY_BUILD_IN,
            HostClasses.Host.READER_FAMILY_EPUB,
            HostClasses.Host.READER_FAMILY_USER,
            HostClasses.Host.READER_HIGHLIGHT_SCREEN,
            HostClasses.Host.READER_SCREEN_KT,
            HostClasses.Host.READER_SETTINGS,
            HostClasses.Host.READER_SHARED_STATE,
            HostClasses.Host.READER_THEMES_KT,
            HostClasses.Host.READER_TYPE_SETTING,
            HostClasses.Host.READER_UI_INTENT,
            HostClasses.Host.READER_VIEW_MODEL,
            HostClasses.Host.SCROLL_PAGER_KT,
            HostClasses.Host.SESSION,
            HostClasses.Host.SETTINGS_SCREEN,
            HostClasses.Host.SHAPE_KT,
            HostClasses.Host.SLIDER_BRUSH_COLOR,
            HostClasses.Host.TAP_GESTURES_BOX,
            HostClasses.Host.THEME_KT,
            HostClasses.Host.THIRD_PARTY,
            HostClasses.Host.THIRD_PARTY_BOOK,
            HostClasses.Host.THIRD_PARTY_KEYS,
            HostClasses.Host.TIME_EXT_KT,
            HostClasses.Host.TYPE_SETTING_SLIDER,
            HostClasses.Host.UI_SHEET_STATUS,
            HostClasses.Host.UI_STATUS,
            HostClasses.Host.UNIT_EXT_KT,
            HostClasses.Host.USER_REPOSITORY,
            HostClasses.Host.USER_STORAGE,
            HostClasses.Host.WINDOW_INSETS_EXT_ANDROID_KT,
            HostClasses.Host.WORKER_MANAGER,
            HostClasses.Host.WORK_HANDLE,
            HostClasses.Host.WORK_STATE,
            HostClasses.Host.WORK_STATUS,
            HostClasses.Host.WORK_TRACKER,
            HostClasses.Host.Y115_ACCOUNT_SCREEN,
            HostClasses.Host.Y115_VIEW_MODEL,
            HostClasses.Host.YUN115_ICON,
            HostClasses.Epub.ANNOTATED_STRING_EXT,
            HostClasses.Epub.COMMENT_ANNOTATION,
            HostClasses.Epub.CONTENT_DOM,
            HostClasses.Epub.EPUB_CFI,
            HostClasses.Epub.FONT_PROVIDER,
            HostClasses.Epub.HTML_DOCUMENT,
            HostClasses.Epub.IMAGE_PLACEHOLDER,
            HostClasses.Epub.OPF,
            HostClasses.Epub.UI_EPUB_WINDOW,
            HostClasses.ThirdParty.OKHTTP_ANDROID10_PLATFORM,
            HostClasses.ThirdParty.OKHTTP_ANDROID_PLATFORM,
            HostClasses.ThirdParty.OKHTTP_CLIENT,
            HostClasses.ThirdParty.OKHTTP_MEDIA_TYPE,
            HostClasses.ThirdParty.OKHTTP_PLATFORM,
            HostClasses.ThirdParty.OKHTTP_REQUEST,
            HostClasses.ThirdParty.OKHTTP_REQUEST_BODY,
            HostClasses.ThirdParty.OKIO_PATH,
        )
        assertEquals(all.size, all.toSet().size)
    }
}
