package com.reamicro.fix.hook

import com.reamicro.fix.online.epub.OnlineChapterImageMarkup
import com.reamicro.fix.online.epub.onlineEpubImageManifestItem
import com.reamicro.fix.online.epub.mergeOnlineEpubImageManifest
import com.reamicro.fix.online.epub.stableOnlineImageFileStem
import com.reamicro.fix.cloud.webdav.OnlineDownloadedChapter
import com.reamicro.fix.online.download.OnlineOnDemandMetadata
import com.reamicro.fix.online.download.OnlineOnDemandMetadataCodec
import com.reamicro.fix.online.epub.OnlineEpubFontEmbedder
import com.reamicro.fix.online.epub.OnlineEpubFontFace
import com.reamicro.fix.online.epub.OnlineEpubStyleCss
import com.reamicro.fix.online.epub.OnlineHeaderImageComposer
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleSettings
import com.reamicro.fix.settings.OnlineEpubStyleStore
import java.io.File
import java.util.zip.ZipOutputStream
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

internal fun WebDavDriveHook.onlineCompletionDefaultCss(bookDir: File?): String {
    val settings = OnlineEpubStyleStore.read(currentApplicationContext() ?: currentContext())
    val fontFaces = bookDir?.let { embedOnlineCompletionFonts(it, settings) }.orEmpty()
    return OnlineEpubStyleCss.build(settings, fontFaces)
}

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
