package com.reamicro.fix.hook

import android.content.Context
import com.reamicro.fix.webdav.OnlineChapterImageMarkup
import com.reamicro.fix.webdav.decodeOnlineHtmlEntities
import com.reamicro.fix.webdav.onlineEpubImageManifestItem
import com.reamicro.fix.webdav.mergeOnlineEpubImageManifest
import com.reamicro.fix.webdav.OnlineEpubImageManifestItem
import com.reamicro.fix.webdav.stableOnlineImageFileStem
import com.reamicro.fix.webdav.OnlineDownloadedChapter
import com.reamicro.fix.webdav.OnlineOnDemandMetadata
import com.reamicro.fix.webdav.OnlineOnDemandMetadataCodec
import com.reamicro.fix.webdav.OnlineChapterHeadingMarkup
import com.reamicro.fix.webdav.OnlineBodyMarkup
import com.reamicro.fix.webdav.OnlineEpubFontEmbedder
import com.reamicro.fix.webdav.OnlineEpubFontFace
import com.reamicro.fix.webdav.OnlineEpubStyleCss
import com.reamicro.fix.webdav.OnlineHeaderImageComposer
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleSettings
import com.reamicro.fix.settings.OnlineEpubStyleStore
import com.reamicro.fix.webdav.OnlineVolumeHeadingMarkup
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.epub.onlineCoverExtFromMime
import com.reamicro.fix.online.epub.onlineCoverExtFromBytes
import com.reamicro.fix.online.epub.onlineCoverExtFromUrl
import com.reamicro.fix.online.epub.writeStoredTextZipEntry
import com.reamicro.fix.online.epub.writeTextZipEntry
import com.reamicro.fix.online.epub.onlineCoverExt
import com.reamicro.fix.online.epub.writeBytesZipEntry
import com.reamicro.fix.online.epub.onlineCoverXhtml
import com.reamicro.fix.online.epub.onlineVolumeSegments
import com.reamicro.fix.online.epub.chapterXhtml
import com.reamicro.fix.online.epub.onlineVolumeHref
import com.reamicro.fix.online.epub.volumeXhtml
import com.reamicro.fix.online.epub.defaultOnlineChapterHrefs
import com.reamicro.fix.online.epub.onlineTocNcx
import com.reamicro.fix.online.epub.onlineContentOpf
import com.reamicro.fix.online.epub.onlineCompletionDecorManifestItems
import com.reamicro.fix.online.epub.onlineCompletionFontFaces
import com.reamicro.fix.online.epub.onlineCompletionDividerImage
import com.reamicro.fix.online.epub.migrateOnlineCompletionChapterStyle
import com.reamicro.fix.online.epub.coverMimeType
import com.reamicro.fix.online.download.writeOnlineCompletionBytesAtomically
import com.reamicro.fix.online.download.writeOnlineCompletionTextAtomically
import com.reamicro.fix.online.download.onlineCompletionChapterIndexJson
import com.reamicro.fix.online.download.onlineCompletionFailedChaptersJson
import com.reamicro.fix.online.download.onlineCompletionChapterFile
import com.reamicro.fix.logging.logWebDav

// WebDavDriveHook 的在线补全 EPUB 生成簇。
//
// 把下载好的章节写成标准 EPUB：章节 xhtml、分卷页、toc.ncx、content.opf、封面、
// 默认样式与字体嵌入、正文图片本地化。
//
// 从 WebDavDriveHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun WebDavDriveHook.localizeOnlineChapterImages(
    bookDir: File,
    target: OnlineDownloadTarget,
    chapter: OnlineDownloadedChapter,
): Map<String, String> {
    val urls = OnlineChapterImageMarkup.imageUrls(chapter.content)
    if (urls.isEmpty()) return emptyMap()
    val imagesDir = File(bookDir, "OEBPS/Images").apply { mkdirs() }
    val imageHrefs = linkedMapOf<String, String>()
    urls.forEach { url ->
        val stem = stableOnlineImageFileStem(url)
        val existing = imagesDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.nameWithoutExtension == stem
        }
        if (existing != null) {
            imageHrefs[url] = existing.name
            return@forEach
        }
        val payload = runCatching { downloadOnlineBytes(target.source, url) }.getOrElse { error ->
            logWebDav("online imported illustration download failed url=${url.take(120)} error=${error.message.orEmpty()}")
            null
        }
        if (payload == null || payload.bytes.isEmpty()) return@forEach
        val ext = onlineCoverExtFromMime(payload.mimeType)
            ?: onlineCoverExtFromBytes(payload.bytes)
            ?: onlineCoverExtFromUrl(url)
        val file = File(imagesDir, "$stem.$ext")
        writeOnlineCompletionBytesAtomically(file, payload.bytes)
        imageHrefs[url] = file.name
    }
    synchronizeOnlineImageManifest(bookDir)
    return imageHrefs
}

internal fun WebDavDriveHook.synchronizeOnlineImageManifest(bookDir: File) {
    val opfFile = File(bookDir, "OEBPS/content.opf")
    val imagesDir = File(bookDir, "OEBPS/Images")
    if (!opfFile.isFile || !imagesDir.isDirectory) return
    val manifestImages = imagesDir.listFiles()
        ?.filter { it.isFile && it.nameWithoutExtension.startsWith("online_img_") }
        ?.mapNotNull { onlineEpubImageManifestItem(it.name) }
        .orEmpty()
    if (manifestImages.isEmpty()) return
    val original = opfFile.readText(Charsets.UTF_8)
    val merged = mergeOnlineEpubImageManifest(original, manifestImages)
    if (merged != original) {
        writeOnlineCompletionTextAtomically(opfFile, merged)
        logWebDav("online completion image manifest synchronized count=${manifestImages.size}")
    }
}

internal fun WebDavDriveHook.writeOnlineCompletionEpub(
    file: File,
    target: OnlineDownloadTarget,
    chapters: List<OnlineDownloadedChapter>,
    cover: OnlineBinaryPayload?,
    failedChapters: List<OnlineFailedChapter> = emptyList(),
    onDemandMetadata: OnlineOnDemandMetadata? = null,
) {
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        val styleSettings = OnlineEpubStyleStore.read(currentApplicationContext() ?: currentContext())
        // mimetype 必须是 EPUB 包里的第一个条目，字体等其它条目一律排在其后。
        writeStoredTextZipEntry(zip, "mimetype", "application/epub+zip")
        val fontFaces = writeOnlineCompletionFontEntries(zip, styleSettings)
        writeTextZipEntry(
            zip,
            "META-INF/container.xml",
            """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        )
        writeTextZipEntry(
            zip,
            ONLINE_COMPLETION_DEFAULT_STYLE_PATH,
            OnlineEpubStyleCss.build(styleSettings, fontFaces),
        )
        val coverExt = onlineCoverExt(cover)
        cover?.let {
            writeBytesZipEntry(zip, "OEBPS/Images/cover.$coverExt", it.bytes)
            writeTextZipEntry(zip, "OEBPS/Text/cover.xhtml", onlineCoverXhtml(target, coverExt))
        }
        val contentImages = collectOnlineContentImages(target, chapters)
        val imageHrefs = contentImages.associate { it.url to it.fileName }
        contentImages.forEach { image ->
            writeBytesZipEntry(zip, "OEBPS/Images/${image.fileName}", image.bytes)
        }
        val decor = resolveOnlineCompletionDecor(styleSettings) { fileName, bytes ->
            writeBytesZipEntry(zip, "OEBPS/Images/$fileName", bytes)
        }
        val volumeSegments = onlineVolumeSegments(chapters)
        val volumeFirstChapters = volumeSegments.mapTo(hashSetOf()) { it.startIndex }
        chapters.forEachIndexed { index, chapter ->
            writeTextZipEntry(
                zip,
                "OEBPS/Text/chapter_${(index + 1).toString().padStart(4, '0')}.xhtml",
                chapterXhtml(
                    title = chapter.title,
                    content = chapter.content,
                    imageHrefs = imageHrefs,
                    decor = decor,
                    isVolumeFirstChapter = index in volumeFirstChapters,
                ),
            )
        }
        volumeSegments.forEach { segment ->
            writeTextZipEntry(
                zip,
                "OEBPS/${onlineVolumeHref(segment.order)}",
                volumeXhtml(segment.title, decor),
            )
        }
        val chapterHrefs = defaultOnlineChapterHrefs(chapters.size)
        writeTextZipEntry(zip, "OEBPS/toc.ncx", onlineTocNcx(target, chapters, chapterHrefs))
        writeTextZipEntry(
            zip,
            "OEBPS/content.opf",
            onlineContentOpf(
                target = target,
                chapters = chapters,
                coverExt = coverExt,
                hasCover = cover != null,
                chapterHrefs = chapterHrefs,
                contentImages = contentImages,
                fontFaces = fontFaces.values,
                decorImages = onlineCompletionDecorManifestItems(decor),
            ),
        )
        writeTextZipEntry(
            zip,
            "OEBPS/$ONLINE_COMPLETION_CHAPTER_INDEX",
            onDemandMetadata?.let(OnlineOnDemandMetadataCodec::encode)
                ?: onlineCompletionChapterIndexJson(
                    target = target,
                    chapters = chapters.mapIndexed { index, chapter ->
                        OnlineChapter(
                            title = chapter.title,
                            url = chapter.sourceUrl.ifBlank { "generated:${index + 1}" },
                            volumeTitle = chapter.volumeTitle,
                            level = chapter.level,
                        )
                    },
                    chapterHrefs = chapterHrefs,
                ),
        )
        if (failedChapters.isNotEmpty()) {
            writeTextZipEntry(
                zip,
                "OEBPS/$ONLINE_COMPLETION_FAILED_CHAPTER_LOG",
                onlineCompletionFailedChaptersJson(target, failedChapters),
            )
        }
    }
}

internal fun WebDavDriveHook.writeOnlineCompletionDefaultStyle(bookDir: File) {
    val root = bookDir.canonicalFile
    val styleFile = File(root, ONLINE_COMPLETION_DEFAULT_STYLE_PATH).canonicalFile
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    if (!styleFile.path.startsWith(rootPrefix)) error("EPUB style path escapes book dir")
    styleFile.parentFile?.mkdirs()
    styleFile.writeText(onlineCompletionDefaultCss(root), Charsets.UTF_8)
}

/**
 * 按用户选中的成书样式拼装 default.css，并把样式选用的字体嵌入书目录。
 *
 * 读不到 Context 时回退到内置默认样式，保证下载流程不因设置不可用而中断。
 */

internal fun WebDavDriveHook.onlineCompletionDefaultCss(bookDir: File?): String {
    val settings = OnlineEpubStyleStore.read(currentApplicationContext() ?: currentContext())
    val fontFaces = bookDir?.let { embedOnlineCompletionFonts(it, settings) }.orEmpty()
    return OnlineEpubStyleCss.build(settings, fontFaces)
}

/** 收集参与成书的样式所选字体，去重后写入书目录并登记到 manifest。 */
internal fun WebDavDriveHook.embedOnlineCompletionFonts(
    bookDir: File,
    settings: OnlineEpubStyleSettings,
): Map<String, OnlineEpubFontFace> {
    val faces = onlineCompletionFontFaces(settings)
    if (faces.isEmpty()) return emptyMap()
    val root = bookDir.canonicalFile
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    val fontsDir = File(root, "OEBPS/Fonts").canonicalFile
    if (!fontsDir.path.startsWith(rootPrefix)) error("EPUB Fonts directory escapes book dir")
    val embedded = LinkedHashMap<String, OnlineEpubFontFace>()
    faces.forEach { (styleId, entry) ->
        val (face, sourceFile) = entry
        val target = File(root, "OEBPS/" + OnlineEpubFontEmbedder.manifestHref(face))
        val copied = runCatching {
            if (!target.isFile || target.length() != sourceFile.length()) {
                fontsDir.mkdirs()
                sourceFile.copyTo(target, overwrite = true)
            }
            true
        }.getOrElse { error ->
            logWebDav("online completion font embed failed font=${sourceFile.name} error=${error.message.orEmpty()}")
            false
        }
        if (copied) embedded[styleId] = face
    }
    if (embedded.isEmpty()) return emptyMap()
    val opfFile = File(root, "OEBPS/content.opf")
    if (opfFile.isFile) {
        val original = opfFile.readText(Charsets.UTF_8)
        val merged = OnlineEpubFontEmbedder.mergeManifest(original, embedded.values)
        if (merged != original) writeOnlineCompletionTextAtomically(opfFile, merged)
    }
    return embedded
}

/** 整本下载时把样式所选字体直接写进 EPUB 包，返回样式 id 到字体的映射。 */
internal fun WebDavDriveHook.writeOnlineCompletionFontEntries(
    zip: ZipOutputStream,
    settings: OnlineEpubStyleSettings,
): Map<String, OnlineEpubFontFace> {
    val embedded = LinkedHashMap<String, OnlineEpubFontFace>()
    val written = HashSet<String>()
    onlineCompletionFontFaces(settings).forEach { (styleId, entry) ->
        val (face, sourceFile) = entry
        val path = "OEBPS/" + OnlineEpubFontEmbedder.manifestHref(face)
        val ok = runCatching {
            if (written.add(path)) writeBytesZipEntry(zip, path, sourceFile.readBytes())
            true
        }.getOrElse { error ->
            logWebDav("online completion font pack failed font=${sourceFile.name} error=${error.message.orEmpty()}")
            false
        }
        if (ok) embedded[styleId] = face
    }
    return embedded
}

/**
 * 参与成书的样式里选中的字体文件，key 为样式 id。
 *
 * 「仅声明字体名」模式不复制文件，因此不出现在结果里，CSS 侧会退回写裸 family 名。
 */

internal fun WebDavDriveHook.onlineCompletionHeaderImage(settings: OnlineEpubStyleSettings): ByteArray? {
    if (!settings.headerEnabled) return null
    val style = settings.selected(OnlineEpubStyleKind.Header) ?: return null
    val source = File(style.assetPath.trim()).takeIf { it.isFile } ?: return null
    val mask = style.maskAsset.takeIf { it.isNotBlank() }?.let { asset ->
        ReaderHighlightImageAssets.decodeBitmap("asset://$asset", currentContext(), LOG_PREFIX)
    }
    return runCatching {
        OnlineHeaderImageComposer.compose(source, mask, style.sampleWidth, style.sampleHeight)
    }.onFailure { error ->
        logWebDav("online completion header compose failed: ${error.message.orEmpty()}")
    }.getOrNull().also { mask?.recycle() }
}

/**
 * 解析本次成书要用的装饰资源，并交给 [writeImage] 落地。
 *
 * 整本下载写 zip 条目、增量更新写书目录，落地方式不同但选图与合成逻辑一致。
 */

internal fun WebDavDriveHook.resolveOnlineCompletionDecor(
    settings: OnlineEpubStyleSettings,
    writeImage: (fileName: String, bytes: ByteArray) -> Unit,
): OnlineEpubDecor {
    var dividerHref: String? = null
    onlineCompletionDividerImage(settings)?.let { (file, fileName) ->
        runCatching {
            writeImage(fileName, file.readBytes())
            dividerHref = "../Images/$fileName"
        }.onFailure { error ->
            logWebDav("online completion divider image failed: ${error.message.orEmpty()}")
        }
    }
    var headerHref: String? = null
    onlineCompletionHeaderImage(settings)?.let { bytes ->
        runCatching {
            writeImage(ONLINE_COMPLETION_HEADER_IMAGE, bytes)
            headerHref = "../Images/$ONLINE_COMPLETION_HEADER_IMAGE"
        }.onFailure { error ->
            logWebDav("online completion header image failed: ${error.message.orEmpty()}")
        }
    }
    return OnlineEpubDecor(
        dividerImageHref = dividerHref,
        headerImageHref = headerHref,
        headerScope = settings.headerScope,
        transitionMarkup = settings.selected(OnlineEpubStyleKind.Transition)?.markup.orEmpty(),
    )
}

/** 书目录侧的装饰资源：图片直接落到 OEBPS/Images 并登记 manifest。 */
internal fun WebDavDriveHook.onlineCompletionBookDirDecor(bookDir: File): OnlineEpubDecor {
    val settings = OnlineEpubStyleStore.read(currentApplicationContext() ?: currentContext())
    val root = bookDir.canonicalFile
    val imagesDir = File(root, "OEBPS/Images")
    val decor = resolveOnlineCompletionDecor(settings) { fileName, bytes ->
        imagesDir.mkdirs()
        val target = File(imagesDir, fileName)
        if (!target.isFile || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
    }
    val manifestItems = onlineCompletionDecorManifestItems(decor)
    if (manifestItems.isNotEmpty()) {
        val opfFile = File(root, "OEBPS/content.opf")
        if (opfFile.isFile) {
            val original = opfFile.readText(Charsets.UTF_8)
            val merged = mergeOnlineEpubImageManifest(original, manifestItems)
            if (merged != original) writeOnlineCompletionTextAtomically(opfFile, merged)
        }
    }
    return decor
}

internal fun WebDavDriveHook.syncOnlineCompletionDefaultStyle(bookDir: File): Boolean {
    val root = bookDir.canonicalFile
    var changed = false
    val nextCss = onlineCompletionDefaultCss(root)
    val styleFile = File(root, ONLINE_COMPLETION_DEFAULT_STYLE_PATH)
    if (!styleFile.isFile || styleFile.readText(Charsets.UTF_8) != nextCss) {
        styleFile.parentFile?.mkdirs()
        styleFile.writeText(nextCss, Charsets.UTF_8)
        changed = true
    }
    val textDir = File(root, "OEBPS/Text")
    val decor = onlineCompletionBookDirDecor(root)
    textDir.listFiles()
        ?.filter {
            ONLINE_COMPLETION_CHAPTER_FILE_REGEX.matches(it.name) ||
                ONLINE_COMPLETION_VOLUME_FILE_REGEX.matches(it.name)
        }
        ?.forEach { chapterFile ->
            val original = chapterFile.readText(Charsets.UTF_8)
            val migrated = migrateOnlineCompletionChapterStyle(original, decor)
            if (migrated != original) {
                chapterFile.writeText(migrated, Charsets.UTF_8)
                changed = true
            }
        }
    val opfFile = File(root, "OEBPS/content.opf")
    if (opfFile.isFile) {
        val original = opfFile.readText(Charsets.UTF_8)
        val migrated = if (original.contains("id=\"default-style\"")) {
            original
        } else {
            original.replaceFirst(
                "<manifest>",
                "<manifest>\n    <item id=\"default-style\" href=\"Styles/default.css\" media-type=\"text/css\"/>",
            )
        }
        if (migrated != original) {
            opfFile.writeText(migrated, Charsets.UTF_8)
            changed = true
        }
    }
    if (changed) logWebDav("online completion default style synchronized dir=${root.absolutePath}")
    return changed
}

/** 把卷首页写入已导入的书目录，并清理卷数变少后残留的旧卷首页。 */
internal fun WebDavDriveHook.writeOnlineCompletionVolumePages(
    bookDir: File,
    chapters: List<OnlineDownloadedChapter>,
    decor: OnlineEpubDecor = OnlineEpubDecor(),
): Boolean {
    val root = bookDir.canonicalFile
    File(root, "OEBPS/Text").mkdirs()
    var changed = false
    val expectedNames = HashSet<String>()
    onlineVolumeSegments(chapters).forEach { segment ->
        val file = onlineCompletionChapterFile(root, onlineVolumeHref(segment.order))
        expectedNames += file.name
        val next = volumeXhtml(segment.title, decor)
        if (!file.isFile || file.readText(Charsets.UTF_8) != next) {
            writeOnlineCompletionTextAtomically(file, next)
            changed = true
        }
    }
    File(root, "OEBPS/Text").listFiles()
        ?.filter { file ->
            file.isFile &&
                ONLINE_COMPLETION_VOLUME_FILE_REGEX.matches(file.name) &&
                file.name !in expectedNames
        }
        ?.forEach { stale ->
            if (stale.delete()) {
                changed = true
                logWebDav("online completion stale volume page removed path=${stale.absolutePath}")
            }
        }
    return changed
}

internal fun WebDavDriveHook.collectOnlineContentImages(
    target: OnlineDownloadTarget,
    chapters: List<OnlineDownloadedChapter>,
): List<OnlineContentImage> {
    val urls = chapters.flatMap { OnlineChapterImageMarkup.imageUrls(it.content) }.distinct()
    if (urls.isEmpty()) return emptyList()
    val images = ArrayList<OnlineContentImage>(urls.size)
    urls.forEach { url ->
        val payload = runCatching { downloadOnlineBytes(target.source, url) }.getOrElse { error ->
            logWebDav("online illustration download failed url=${url.take(120)} error=${error.message.orEmpty()}")
            null
        }
        if (payload == null || payload.bytes.isEmpty()) return@forEach
        val ext = onlineCoverExtFromMime(payload.mimeType)
            ?: onlineCoverExtFromBytes(payload.bytes)
            ?: onlineCoverExtFromUrl(url)
        images.add(
            OnlineContentImage(
                url = url,
                fileName = "${stableOnlineImageFileStem(url)}.$ext",
                bytes = payload.bytes,
                mimeType = coverMimeType(ext),
            ),
        )
    }
    logWebDav("online illustrations embedded=${images.size}/${urls.size} book=${target.result.name}")
    return images
}
