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

internal fun WebDavDriveHook.existingOnlineChapterImageHrefs(
    bookDir: File,
    chapter: OnlineDownloadedChapter,
): Map<String, String> {
    val imagesDir = File(bookDir, "OEBPS/Images")
    if (!imagesDir.isDirectory) return emptyMap()
    return OnlineChapterImageMarkup.imageUrls(chapter.content).mapNotNull { url ->
        val stem = stableOnlineImageFileStem(url)
        val file = imagesDir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == stem }
        file?.let { url to it.name }
    }.toMap()
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

internal fun WebDavDriveHook.onlineCompletionExistingCoverExt(bookDir: File): String? =
    File(bookDir, "OEBPS/Images").listFiles()
        ?.firstOrNull { file ->
            file.isFile && file.nameWithoutExtension.equals("cover", ignoreCase = true)
        }
        ?.extension
        ?.takeIf { it.isNotBlank() }

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

internal fun WebDavDriveHook.writeStoredTextZipEntry(zip: ZipOutputStream, path: String, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val crc = CRC32().apply { update(bytes) }
    val entry = ZipEntry(path).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        this.crc = crc.value
    }
    zip.putNextEntry(entry)
    zip.write(bytes)
    zip.closeEntry()
}

internal fun WebDavDriveHook.writeTextZipEntry(zip: ZipOutputStream, path: String, text: String) =
    writeBytesZipEntry(zip, path, text.toByteArray(Charsets.UTF_8))

internal fun WebDavDriveHook.writeBytesZipEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
    zip.putNextEntry(ZipEntry(path))
    zip.write(bytes)
    zip.closeEntry()
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

internal fun WebDavDriveHook.onlineCompletionFontFaces(
    settings: OnlineEpubStyleSettings,
): Map<String, Pair<OnlineEpubFontFace, File>> {
    val result = LinkedHashMap<String, Pair<OnlineEpubFontFace, File>>()
    OnlineEpubStyleCss.appliedKinds(settings).forEach { kind ->
        val style = settings.selected(kind) ?: return@forEach
        if (!style.supportsFont || !style.embedFont) return@forEach
        val path = style.fontFamily.trim()
        if (path.isBlank() || !path.contains(File.separatorChar) && !path.contains('/')) return@forEach
        val face = OnlineEpubFontEmbedder.faceFor(path) ?: return@forEach
        result[style.id] = face to File(path)
    }
    return result
}

/** 分割样式选中的装饰图，成书时固定写成 Images/divider.<ext>。 */
internal fun WebDavDriveHook.onlineCompletionDividerImage(settings: OnlineEpubStyleSettings): Pair<File, String>? {
    val style = settings.selected(OnlineEpubStyleKind.Transition) ?: return null
    if (!style.needsAsset) return null
    val file = File(style.assetPath.trim()).takeIf { it.isFile } ?: return null
    val extension = file.extension.lowercase(Locale.ROOT).ifBlank { "png" }
    return file to "divider.$extension"
}

/**
 * 头图：把用户选的原图按样式蒙版合成后，固定写成 Images/header.png（全书一份）。
 *
 * 蒙版从模块 assets 读取，与高亮图片走同一套 asset:// 机制。
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

/** 装饰图在 manifest 里的登记项，与正文插图共用 Images 目录。 */
internal fun WebDavDriveHook.onlineCompletionDecorManifestItems(decor: OnlineEpubDecor): List<OnlineEpubImageManifestItem> =
    listOfNotNull(decor.dividerImageHref, decor.headerImageHref)
        .map { it.substringAfterLast('/') }
        .mapNotNull(::onlineEpubImageManifestItem)

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

internal fun WebDavDriveHook.migrateOnlineCompletionChapterStyle(
    original: String,
    decor: OnlineEpubDecor = OnlineEpubDecor(),
): String {
    var result = original.replace(
        ONLINE_COMPLETION_INLINE_STYLE_REGEX,
        "<link rel=\"stylesheet\" type=\"text/css\" href=\"../Styles/default.css\"/>",
    )
    result = ONLINE_COMPLETION_ESCAPED_ENTITY_REGEX.replace(result) { match ->
        match.value.decodeOnlineHtmlEntities().xmlEscape()
    }
    if (!result.contains("../Styles/default.css")) {
        result = result.replaceFirst(
            "</head>",
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"../Styles/default.css\"/></head>",
        )
    }
    // 先整体改写旧的 div.te-chapter-heading 双层结构：其序号在 h1 之外，只看 h1 内容拆不出来。
    result = OnlineChapterHeadingMarkup.migrateLegacyHeadingBlock(result)
    result = ONLINE_COMPLETION_CHAPTER_HEADING_HTML_REGEX.replace(result) { match ->
        val attributes = match.groupValues[1]
        val content = match.groupValues[2]
        if (content.contains("te-chapter-name") || content.contains("te-volume-name")) return@replace match.value
        OnlineChapterHeadingMarkup.migrateSplit(content)?.let { return@replace it }
        if (attributes.contains("te-volume-title", ignoreCase = true)) {
            return@replace "<h1$attributes><span class=\"te-volume-name\">$content</span></h1>"
        }
        val nextAttributes = if (attributes.contains("class=", ignoreCase = true)) {
            attributes
        } else {
            "$attributes class=\"te-chapter-title\""
        }
        "<h1$nextAttributes><span class=\"te-chapter-name\">$content</span></h1>"
    }
    result = OnlineBodyMarkup.migrateLegacyBody(result)
    // 早期下载的章节里省略号还是普通 <p>，历史分割线的结构也未必对得上当前样式，这里一并改写。
    result = OnlineBodyMarkup.migrateTransitions(
        html = result,
        isDivider = { text -> ONLINE_DIVIDER_LINE_REGEX.matches(text) },
        markup = decor.transitionMarkup,
        imageHref = decor.dividerImageHref,
    )
    return result
}

internal fun WebDavDriveHook.chapterXhtml(
    title: String,
    content: String,
    imageHrefs: Map<String, String> = emptyMap(),
    decor: OnlineEpubDecor = OnlineEpubDecor(),
    isVolumeFirstChapter: Boolean = false,
): String {
    val bodyLines = stripDuplicatedChapterTitle(title, content.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList())
    // 转场只认前后都有正文的省略号段，连续多段合并成一条；首尾的孤立省略号保持原样。
    val plan = OnlineBodyMarkup.planTransitions(
        bodyLines.map { line ->
            OnlineChapterImageMarkup.markerUrl(line) == null && ONLINE_DIVIDER_LINE_REGEX.matches(line)
        },
    )
    val paragraphs = bodyLines.mapIndexedNotNull { index, line ->
        when (index) {
            in plan.drop -> null
            in plan.replace -> OnlineBodyMarkup.transition(
                markup = decor.transitionMarkup,
                textHtml = line.xmlEscape(),
                imageHref = decor.dividerImageHref,
            )
            else -> chapterParagraphHtml(line, imageHrefs, decor)
        }
    }.joinToString("\n")
    val heading = chapterHeadingHtml(title)
    val header = decor.headerHtml(isVolumePage = false, isVolumeFirstChapter = isVolumeFirstChapter)
    return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${title.xmlEscape()}</title><link rel="stylesheet" type="text/css" href="../Styles/default.css"/></head>
<body>$header$heading
$paragraphs
</body>
</html>"""
}

internal fun WebDavDriveHook.chapterParagraphHtml(
    line: String,
    imageHrefs: Map<String, String> = emptyMap(),
    decor: OnlineEpubDecor = OnlineEpubDecor(),
): String {
    OnlineChapterImageMarkup.markerUrl(line)?.let { url ->
        val fileName = imageHrefs[url]
        val src = if (fileName != null) "../Images/$fileName" else url
        return OnlineBodyMarkup.illustration(src.xmlEscape())
    }
    return OnlineBodyMarkup.paragraph(line.xmlEscape())
}

internal fun WebDavDriveHook.stripDuplicatedChapterTitle(title: String, lines: List<String>): List<String> {
    if (lines.isEmpty()) return emptyList()
    val titleKey = title.normalizedChapterTitleKey()
    if (titleKey.isBlank()) return lines
    val parts = splitChapterHeading(title)
    val mutable = lines.toMutableList()
    stripChapterTitlePrefix(mutable.firstOrNull().orEmpty(), title)?.let { remainder ->
        if (remainder.isBlank()) mutable.removeAt(0) else mutable[0] = remainder
        return mutable
    }
    if (mutable.size >= 2 && (mutable[0] + mutable[1]).normalizedChapterTitleKey() == titleKey) {
        mutable.removeAt(0)
        mutable.removeAt(0)
        return mutable
    }
    if (parts.size >= 2 && mutable.size >= 2) {
        stripChapterTitlePrefix(mutable[0], parts.first())?.takeIf { it.isBlank() }?.let {
            mutable.removeAt(0)
            stripChapterTitlePrefix(mutable.firstOrNull().orEmpty(), parts.drop(1).joinToString(""))?.let { remainder ->
                if (remainder.isBlank()) mutable.removeAt(0) else mutable[0] = remainder
            }
            return mutable
        }
    }
    val scanCount = mutable.size.coerceAtMost(ONLINE_CHAPTER_TITLE_SCAN_LINES)
    for (index in 0 until (scanCount - 1).coerceAtLeast(0)) {
        if ((mutable[index] + mutable[index + 1]).normalizedChapterTitleKey() == titleKey) {
            mutable.removeAt(index + 1)
            mutable.removeAt(index)
            return mutable
        }
    }
    if (parts.size >= 2) {
        val titlePrefix = parts.first()
        val titleSuffix = parts.drop(1).joinToString("")
        for (index in 0 until (scanCount - 1).coerceAtLeast(0)) {
            val firstRemainder = stripChapterTitlePrefix(mutable[index], titlePrefix)
            if (firstRemainder != null && firstRemainder.isBlank()) {
                val secondRemainder = stripChapterTitlePrefix(mutable[index + 1], titleSuffix)
                if (secondRemainder != null) {
                    if (secondRemainder.isBlank()) {
                        mutable.removeAt(index + 1)
                    } else {
                        mutable[index + 1] = secondRemainder
                    }
                    mutable.removeAt(index)
                    return mutable
                }
            }
        }
    }
    for (index in 0 until scanCount) {
        stripChapterTitlePrefix(mutable.getOrNull(index).orEmpty(), title)?.let { remainder ->
            if (remainder.isBlank()) {
                mutable.removeAt(index)
            } else {
                mutable[index] = remainder
            }
            return mutable
        }
    }
    return mutable
}

internal fun WebDavDriveHook.stripChapterTitlePrefix(line: String, title: String): String? {
    val target = title.normalizedChapterTitleKey()
    if (line.isBlank() || target.isBlank()) return null
    var targetIndex = 0
    var removeEnd = -1
    for (index in line.indices) {
        val key = line[index].toString().normalizedChapterTitleKey()
        if (key.isBlank()) {
            if (targetIndex == 0) continue
            continue
        }
        if (targetIndex >= target.length) break
        if (key != target[targetIndex].toString()) return null
        targetIndex += 1
        removeEnd = index + 1
        if (targetIndex == target.length) break
    }
    if (targetIndex != target.length || removeEnd < 0) return null
    return line.substring(removeEnd).trimChapterHeadingSeparator()
}

internal fun WebDavDriveHook.chapterHeadingHtml(title: String): String {
    val parts = splitChapterHeading(title)
    if (parts.size < 2) return OnlineChapterHeadingMarkup.single(parts.firstOrNull().orEmpty().xmlEscape())
    val number = parts.first().xmlEscape()
    val subtitle = parts.drop(1).joinToString("<br/>") { it.xmlEscape() }
    return OnlineChapterHeadingMarkup.split(number, subtitle)
}

internal fun WebDavDriveHook.splitChapterHeading(title: String): List<String> {
    val clean = title.trim()
    if (clean.isBlank()) return listOf("")
    ONLINE_CHAPTER_HEADING_SPLIT_REGEX.matchEntire(clean)?.let { match ->
        val prefix = match.groupValues[1].trim()
        val suffix = match.groupValues[2].trimChapterHeadingSeparator()
        if (prefix.isNotBlank() && suffix.isNotBlank()) return listOf(prefix, suffix)
    }
    ONLINE_SPECIAL_HEADING_SPLIT_REGEX.matchEntire(clean)?.let { match ->
        val prefix = match.groupValues[1].trim()
        val suffix = match.groupValues[2].trimChapterHeadingSeparator()
        if (prefix.isNotBlank() && suffix.isNotBlank()) return listOf(prefix, suffix)
    }
    return listOf(clean)
}

internal fun WebDavDriveHook.defaultOnlineChapterHrefs(count: Int): List<String> =
    (1..count).map { order -> "Text/chapter_${order.toString().padStart(4, '0')}.xhtml" }

/** 目录里连续同卷名的章节归为一卷，用于生成卷首页。 */
internal fun WebDavDriveHook.onlineVolumeSegments(chapters: List<OnlineDownloadedChapter>): List<OnlineVolumeSegment> {
    val segments = ArrayList<OnlineVolumeSegment>()
    var index = 0
    while (index < chapters.size) {
        val volumeTitle = chapters[index].volumeTitle.trim()
        if (volumeTitle.isBlank()) {
            index += 1
            continue
        }
        segments += OnlineVolumeSegment(
            order = segments.size + 1,
            title = volumeTitle,
            startIndex = index,
        )
        while (index < chapters.size && chapters[index].volumeTitle.trim() == volumeTitle) {
            index += 1
        }
    }
    return segments
}

internal fun WebDavDriveHook.onlineVolumeHref(order: Int): String =
    "Text/volume_${order.toString().padStart(4, '0')}.xhtml"

/** 卷首页文档：仿起点单独成页，序号与卷名自动分行。 */
internal fun WebDavDriveHook.volumeXhtml(volumeTitle: String, decor: OnlineEpubDecor = OnlineEpubDecor()): String {
    val heading = OnlineVolumeHeadingMarkup.parse(volumeTitle)
    val body = if (heading.number.isNotBlank() && heading.title.isNotBlank()) {
        OnlineVolumeHeadingMarkup.split(heading.number.xmlEscape(), heading.title.xmlEscape())
    } else {
        OnlineVolumeHeadingMarkup.single(heading.title.ifBlank { heading.number }.xmlEscape())
    }
    val documentTitle = volumeTitle.trim().ifBlank { "卷首页" }
    val header = decor.headerHtml(isVolumePage = true, isVolumeFirstChapter = false)
    return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${documentTitle.xmlEscape()}</title><link rel="stylesheet" type="text/css" href="../Styles/default.css"/></head>
<body>$header$body
</body>
</html>"""
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

internal fun WebDavDriveHook.onlineTocNcx(
    target: OnlineDownloadTarget,
    chapters: List<OnlineDownloadedChapter>,
    chapterHrefs: List<String> = defaultOnlineChapterHrefs(chapters.size),
): String {
    var playOrder = 1
    fun chapterPoint(index: Int, chapter: OnlineDownloadedChapter, indent: String): String {
        val order = index + 1
        val pointOrder = playOrder++
        val childIndent = "$indent  "
        val title = chapter.title.xmlEscape()
        val href = chapterHrefs.getOrNull(index)
            ?: "Text/chapter_${order.toString().padStart(4, '0')}.xhtml"
        return buildString {
            appendLine("""${indent}<navPoint id="chapter$order" playOrder="$pointOrder">""")
            appendLine("${childIndent}<navLabel>")
            appendLine("${childIndent}  <text>$title</text>")
            appendLine("${childIndent}</navLabel>")
            appendLine("""${childIndent}<content src="$href"/>""")
            appendLine("${indent}</navPoint>")
        }
    }
    val points = StringBuilder()
    var index = 0
    var volumeSequence = 0
    while (index < chapters.size) {
        val volumeTitle = chapters[index].volumeTitle.trim()
        if (volumeTitle.isBlank()) {
            points.append(chapterPoint(index, chapters[index], "    "))
            index += 1
            continue
        }
        val volumeOrder = playOrder++
        volumeSequence += 1
        val children = StringBuilder()
        while (index < chapters.size && chapters[index].volumeTitle.trim() == volumeTitle) {
            children.append(chapterPoint(index, chapters[index], "      "))
            index += 1
        }
        val href = onlineVolumeHref(volumeSequence)
        points.appendLine("""    <navPoint id="volume$volumeOrder" playOrder="$volumeOrder">""")
        points.appendLine("      <navLabel>")
        points.appendLine("        <text>${volumeTitle.xmlEscape()}</text>")
        points.appendLine("      </navLabel>")
        points.appendLine("""      <content src="$href"/>""")
        points.append(children)
        points.appendLine("    </navPoint>")
    }
    return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="${onlineBookUuid(target).xmlEscape()}"/>
  </head>
  <docTitle>
    <text>${target.result.name.xmlEscape()}</text>
  </docTitle>
  <navMap>
$points  </navMap>
</ncx>"""
}

internal fun WebDavDriveHook.onlineContentOpf(
    target: OnlineDownloadTarget,
    chapters: List<OnlineDownloadedChapter>,
    coverExt: String,
    hasCover: Boolean,
    chapterHrefs: List<String> = defaultOnlineChapterHrefs(chapters.size),
    contentImages: List<OnlineContentImage> = emptyList(),
    fontFaces: Collection<OnlineEpubFontFace> = emptyList(),
    decorImages: List<OnlineEpubImageManifestItem> = emptyList(),
): String {
    val manifestChapters = chapters.indices.joinToString("\n") { index ->
        val order = index + 1
        val href = chapterHrefs.getOrNull(index)
            ?: "Text/chapter_${order.toString().padStart(4, '0')}.xhtml"
        """    <item id="chapter$order" href="${href.xmlEscape()}" media-type="application/xhtml+xml"/>"""
    }
    val volumeSegments = onlineVolumeSegments(chapters)
    val volumeStarts = volumeSegments.associateBy { it.startIndex }
    val manifestVolumes = volumeSegments.joinToString("\n") { segment ->
        """    <item id="volume${segment.order}" href="${onlineVolumeHref(segment.order).xmlEscape()}" media-type="application/xhtml+xml"/>"""
    }
    val spine = chapters.indices.flatMap { index ->
        val chapterRef = """    <itemref idref="chapter${index + 1}"/>"""
        volumeStarts[index]
            ?.let { listOf("""    <itemref idref="volume${it.order}"/>""", chapterRef) }
            ?: listOf(chapterRef)
    }.joinToString("\n")
    val imageManifest = contentImages.mapIndexed { index, image ->
        """    <item id="online-img-${index + 1}" href="Images/${image.fileName.xmlEscape()}" media-type="${image.mimeType}"/>"""
    }.joinToString("\n")
    val fontManifest = fontFaces.distinctBy { it.family }.joinToString("\n") { face ->
        """    <item id="${face.family}" href="${OnlineEpubFontEmbedder.manifestHref(face)}" media-type="${OnlineEpubFontEmbedder.mediaType(face)}"/>"""
    }
    val decorManifest = decorImages.distinctBy { it.fileName }.joinToString("\n") { image ->
        """    <item id="online-decor-${image.fileName.substringBeforeLast('.')}" href="Images/${image.fileName.xmlEscape()}" media-type="${image.mimeType}"/>"""
    }
    val coverManifest = if (hasCover) {
        listOf(
            """    <item id="cover-image" href="Images/cover.$coverExt" media-type="${coverMimeType(coverExt)}" properties="cover-image"/>""",
            """    <item id="cover-page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>""",
        ).joinToString("\n")
    } else {
        ""
    }
    val manifestItems = listOf(
        """    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""",
        """    <item id="default-style" href="Styles/default.css" media-type="text/css"/>""",
        """    <item id="source-chapter-index" href="$ONLINE_COMPLETION_CHAPTER_INDEX" media-type="application/json"/>""",
        coverManifest,
        imageManifest,
        decorManifest,
        fontManifest,
        manifestVolumes,
        manifestChapters,
    ).filter { it.isNotBlank() }.joinToString("\n")
    val coverMeta = if (hasCover) """    <meta name="cover" content="cover-image"/>""" else ""
    val coverGuide = if (hasCover) {
        """
<guide>
  <reference type="cover" title="Cover" href="Text/cover.xhtml"/>
</guide>""".trimIndent()
    } else {
        ""
    }
    val onlineMeta = onlineSourceMetadataOpf(target).prependIndent("    ")
    return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:identifier id="BookId">${onlineBookUuid(target).xmlEscape()}</dc:identifier>
    <dc:title>${target.result.name.xmlEscape()}</dc:title>
    <dc:creator>${target.result.author.xmlEscape()}</dc:creator>
    <dc:language>zh-CN</dc:language>
$coverMeta
$onlineMeta
  </metadata>
  <manifest>
$manifestItems
  </manifest>
  <spine toc="ncx">
$spine
  </spine>
$coverGuide
</package>"""
}

internal fun WebDavDriveHook.onlineCoverXhtml(target: OnlineDownloadTarget, coverExt: String): String =
    """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${target.result.name.xmlEscape()}</title><style type="text/css">
body{margin:0;padding:0;text-align:center;}
img{max-width:100%;max-height:100%;height:auto;}
</style></head>
<body><img alt="${target.result.name.xmlEscape()}" src="../Images/cover.$coverExt"/></body>
</html>"""

internal fun WebDavDriveHook.onlineBookUuid(target: OnlineDownloadTarget): String =
    "reamicro-online-${target.source.id}-${(target.result.detailUrl.ifBlank { target.result.name }).hashCode().toUInt()}"

internal fun WebDavDriveHook.onlineSourceMetadataOpf(target: OnlineDownloadTarget): String =
    listOf(
        "reamicro-online-source-id" to target.source.id,
        "reamicro-online-source-name" to target.source.name,
        "reamicro-online-detail-url" to target.result.detailUrl,
    ).joinToString("\n") { (name, value) ->
        """<meta name="${name.xmlEscape()}" content="${value.xmlEscape()}"/>"""
    }

internal fun WebDavDriveHook.onlineImportedBookBackupId(target: OnlineDownloadTarget): String =
    ONLINE_COMPLETION_BOOK_PREFIX +
        URLEncoder.encode(target.source.id, "UTF-8") +
        "?name=${URLEncoder.encode(target.source.name, "UTF-8")}" +
        "&detail=${URLEncoder.encode(target.result.detailUrl.ifBlank { target.source.sourceUrl }, "UTF-8")}"

internal fun WebDavDriveHook.onlineSourceIdFromUuid(uuid: String): String =
    uuid.removePrefix(ONLINE_COMPLETION_UUID_PREFIX)
        .substringBeforeLast('-', "")
        .ifBlank { "unknown" }

internal fun WebDavDriveHook.onlineCoverExt(cover: OnlineBinaryPayload?): String =
    cover?.let {
        onlineCoverExtFromMime(it.mimeType)
            ?: onlineCoverExtFromBytes(it.bytes)
            ?: onlineCoverExtFromUrl(it.url)
    } ?: "jpg"

internal fun WebDavDriveHook.onlineCoverExtFromMime(mimeType: String): String? =
    when (mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> null
    }

internal fun WebDavDriveHook.onlineCoverExtFromBytes(bytes: ByteArray): String? =
    when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() -> "gif"
        bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() &&
            bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() &&
            bytes[11] == 0x50.toByte() -> "webp"
        else -> null
    }

internal fun WebDavDriveHook.onlineCoverExtFromUrl(url: String): String =
    url.substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
        .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") }
        ?.let { if (it == "jpeg") "jpg" else it }
        ?: "jpg"

internal fun WebDavDriveHook.coverMimeType(ext: String): String =
    when (ext.lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

internal fun WebDavDriveHook.safeOnlineFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "_")
        .trim()
        .take(80)
        .ifBlank { "online_completion" }

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
