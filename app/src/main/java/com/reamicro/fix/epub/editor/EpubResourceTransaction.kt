package com.reamicro.fix.epub.editor

import java.io.File
import java.util.Properties
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object EpubResourceTransaction {
    data class Result(val path: String?, val changedPaths: Set<String>, val cover: String, val backup: File, val renamedPaths: Map<String,String>)
    private data class Change(val path: String,val before: VerifiedFileIO.Digest,val after: VerifiedFileIO.Digest?,val backup: File,val stage: File?)
    private val textTypes=setOf("opf","ncx","xml","xhtml","html","htm","css","svg","smil")
    private const val MAX_TEXT=8L*1024*1024

    fun execute(storage: EpubStorageFiles, path: String, newName: String?, backupBase: File, currentCover: String,
        commitBook: (String)->Unit = {}, rollbackBook: ()->Unit = {},
        checkpoint: (String)->Unit = {},
    ): Result {
        val root=storage.root
        require(!backupBase.canonicalFile.toPath().startsWith(root.toPath())) { "联动备份必须存放在图书目录外" }
        val source=storage.child(path);require(source.isFile){"源文件不存在"}
        val original=storage.relative(source)
        require(original !in setOf("META-INF/container.xml","mimetype")){"container.xml/mimetype 是 EPUB 固定入口，不能移除或改名"}
        val inventory=storage.visibleFiles()
        val container=storage.child("META-INF/container.xml")
        val primary=if(container.isFile) {
            val name=EpubOpfMetadata.packagePath(EpubTextFiles.load(container,MAX_TEXT).text)
            storage.child(name).takeIf { it.isFile }?.let(storage::relative)
                ?: EpubLinkRewriter.resolve(File(root,"_container.xml"),name,root).orEmpty()
        } else
            inventory.firstOrNull { it.extension.equals("opf",true) }?.let(storage::relative).orEmpty()
        require(primary.isNotBlank() && storage.child(primary).isFile){"没有有效的主 OPF，未执行联动"}
        require(!storage.child("META-INF/signatures.xml").exists() || (original=="META-INF/signatures.xml" && newName==null)) { "此 EPUB 带数字签名，无法在没有签名密钥时保持签名有效；请先移除签名认证" }
        require(newName!=null || original!=primary){"不能删除当前主 OPF；这会删除整本书的结构入口"}
        val moved=linkedMapOf<String,String>()
        if(newName!=null) {
            val target=File(source.parentFile,EpubStorageFiles.validName(newName))
            require(target.canonicalFile!=source && !target.exists()){"目标文件名未变化或已经存在"}
            moved[original]=storage.relative(target)
            if(original==currentCover) {
                val banner=EpubBannerVariants.existing(currentCover,inventory.map(storage::relative).toSet())
                if(banner!=null && banner!=original) {
                    val newBanner=EpubBannerVariants.primary(storage.relative(target))
                    if(newBanner!=banner){ require(!storage.child(newBanner).exists()){"新横幅路径已存在，未覆盖"};moved[banner]=newBanner }
                }
            }
        }
        val deleted=original.takeIf { newName==null }
        require(moved.values.toSet().size==moved.size){"联动目标文件名冲突"}
        val backup=File(backupBase,"resource-${UUID.randomUUID()}")
        require(backup.mkdirs()){"无法创建联动备份目录"}
        val changes=linkedMapOf<String,Change>()
        val scanned=linkedMapOf<String,VerifiedFileIO.Digest>()
        val journal=Properties().apply { setProperty("root",root.path);setProperty("state","planning") }
        fun writeJournal(state: String) {
            journal.setProperty("state",state)
            changes.values.forEachIndexed { i,c ->
                journal.setProperty("file.$i",c.path);journal.setProperty("before.$i",c.before.sha256)
                journal.setProperty("after.$i",c.after?.sha256.orEmpty());journal.setProperty("target.$i",moved[c.path]?:c.path)
                journal.setProperty("backup.$i",c.backup.name)
            }
            journal.setProperty("count",changes.size.toString())
            val pending=File(backup,"journal.pending")
            pending.outputStream().use { journal.store(it,"EPUB resource transaction");it.fd.sync() }
            java.nio.file.Files.move(pending.toPath(),File(backup,"journal.properties").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        fun record(file: File,next: String?,remove: Boolean=false,expected: VerifiedFileIO.Digest?=null) {
            val rel=storage.relative(file)
            val before=VerifiedFileIO.digest(file)
            require(expected==null || expected==before){"文件在规划时被改变"}
            val saved=File(backup,"${changes.size}.before")
            VerifiedFileIO.copyAtomic(file,saved)
            VerifiedFileIO.requireMatches(saved,before)
            val stage=if(remove)null else File(backup,"${changes.size}.after").also {
                VerifiedFileIO.copyAtomic(saved,it)
                if(next!=null){val loaded=EpubTextFiles.load(it,MAX_TEXT);EpubTextFiles.save(it,next,loaded.snapshot)}
            }
            changes[rel]=Change(rel,before,stage?.let(VerifiedFileIO::digest),saved,stage)
        }
        try {
            for(file in inventory) {
                val rel=storage.relative(file)
                if(rel==deleted)continue
                if(file.extension.lowercase() !in textTypes && file.name!="reamicro-online-chapters.json")continue
                checkpoint("scan:$rel")
                val loaded=EpubTextFiles.load(file,MAX_TEXT)
                require(!loaded.text.contains("<!ENTITY",ignoreCase=true)) { "引用文件含自定义 XML 实体，请先展开实体再联动：$rel" }
                scanned[rel]=VerifiedFileIO.Digest(loaded.snapshot.fingerprint.size,loaded.snapshot.fingerprint.sha256)
                val next=when {
                    file.name=="reamicro-online-chapters.json" -> sidecar(loaded.text,file,root,moved,deleted)
                    file.extension.equals("css",true)->EpubLinkRewriter.css(loaded.text,file,root,moved,deleted)
                    else->EpubLinkRewriter.markup(loaded.text,file,root,moved,deleted)
                }
                if(next!=loaded.text) {
                    check(VerifiedFileIO.digest(file).sha256==loaded.snapshot.fingerprint.sha256){"规划期间文件改变，未提交"}
                    record(file,next,expected=VerifiedFileIO.Digest(loaded.snapshot.fingerprint.size,loaded.snapshot.fingerprint.sha256))
                }
            }
            for(old in moved.keys) if(old !in changes) record(storage.child(old),null)
            if(deleted!=null)record(source,null,true)
            writeJournal("prepared")
            checkpoint("prepared")
            for((rel,digest) in scanned)check(VerifiedFileIO.digest(storage.child(rel))==digest){"引用文件被并发修改：$rel"}
            for(c in changes.values)check(VerifiedFileIO.digest(storage.child(c.path))==c.before){"文件被并发修改，未开始提交：${c.path}"}
            for(target in moved.values)require(!storage.child(target).exists()){"目标被其他操作创建，未覆盖"}
        } catch(failure: Exception) {writeJournal("not_committed");throw failure}
        val applied=linkedSetOf<String>();val renamed=linkedSetOf<String>();var metadataAttempted=false
        try {
            checkpoint("commit")
            writeJournal("committing")
            for(c in changes.values) {
                if(c.stage==null)continue
                if(c.before!=c.after) {
                    check(VerifiedFileIO.digest(storage.child(c.path))==c.before){"提交前文件发生变化"}
                    applied.add(c.path)
                    VerifiedFileIO.copyAtomic(c.stage,storage.child(c.path))
                    checkpoint("write:${c.path}")
                }
            }
            for((old,new) in moved) {
                val c=changes.getValue(old)
                VerifiedFileIO.requireMatches(storage.child(old),requireNotNull(c.after))
                java.nio.file.Files.move(storage.child(old).toPath(),storage.child(new).toPath())
                renamed.add(old)
                VerifiedFileIO.requireMatches(storage.child(new),requireNotNull(c.after))
                VerifiedFileIO.requireMissing(storage.child(old));checkpoint("move:$old")
            }
            if(deleted!=null) {
                val c=changes.getValue(deleted);check(VerifiedFileIO.digest(source)==c.before){"删除前文件发生变化"}
                applied.add(deleted);check(source.delete()){"删除失败"};VerifiedFileIO.requireMissing(source)
                checkpoint("delete:$deleted")
            }
            writeJournal("files_committed")
            val cover=if(currentCover==deleted)"" else moved[currentCover]?:currentCover
            metadataAttempted=true;commitBook(cover)
            for(c in changes.values)if(c.after!=null)VerifiedFileIO.requireMatches(storage.child(moved[c.path]?:c.path),c.after)
            writeJournal("committed")
            return Result(moved[original],changes.keys.toSet(),cover,backup,moved.toMap())
        } catch(failure: Exception) {
            var rollbackError: Exception?=null
            for(old in renamed.toList().asReversed())runCatching {
                val c=changes.getValue(old);val at=storage.child(moved.getValue(old))
                val beforePath=storage.child(old)
                if(at.exists()) {
                    VerifiedFileIO.requireMatches(at,requireNotNull(c.after))
                    check(!beforePath.exists()){"回滚原路径被占用"}
                    storage.rename(moved.getValue(old),File(old).name)
                } else check(beforePath.exists()){"新旧路径均不存在"}
            }.onFailure { rollbackError=it as? Exception ?: IllegalStateException(it) }
            for(path in (applied+renamed).toList().asReversed())runCatching {
                val c=changes.getValue(path);val file=storage.child(path)
                if(file.exists()) {
                    val actual=VerifiedFileIO.digest(file)
                    check(actual==c.before || actual==c.after){"文件被外部改写，不能覆盖回滚：$path"}
                } else check(path==deleted){"回滚目标丢失：$path"}
                VerifiedFileIO.copyAtomic(c.backup,file);VerifiedFileIO.requireMatches(file,c.before)
            }.onFailure { rollbackError=it as? Exception ?: IllegalStateException(it) }
            if(metadataAttempted)runCatching(rollbackBook).onFailure { rollbackError=it as? Exception ?: IllegalStateException(it) }
            writeJournal(if(rollbackError==null)"rolled_back" else "recovery_required")
            if(rollbackError!=null){failure.addSuppressed(rollbackError!!);throw EpubWriteNotConfirmed("联动失败且部分回滚需人工恢复，备份：${backup.path}",failure)}
            throw IllegalStateException("联动失败，已恢复原文件和书架；${failure.message.orEmpty()}",failure)
        }
    }
    private fun sidecar(text: String,file: File,root: File,moves: Map<String,String>,deleted: String?): String {
        val json=JSONObject(text);val entries=json.optJSONArray("chapters")?:return text
        val next=JSONArray();var changed=false
        for(i in 0 until entries.length()) {
            val raw=entries.get(i)
            val item=raw as? JSONObject
            if(item==null){next.put(raw);continue}
            val href=item.optString("href")

            val base=if(href.startsWith("OEBPS/"))root else file.parentFile
            val rawPath=runCatching { File(base,href).canonicalFile.takeIf { it.isFile && it.toPath().startsWith(root.toPath()) }
                ?.relativeTo(root)?.invariantSeparatorsPath }.getOrNull()
            val path=rawPath ?: EpubLinkRewriter.resolve(file,href,root)
            if(path!=null && path==deleted){changed=true;continue}
            moves[path]?.let { target -> item.put("href",File(root,target).relativeTo(base).invariantSeparatorsPath);changed=true }
            next.put(item)
        }
        if(!changed)return text
        json.put("chapters",next)
        val ending=if(text.contains("\r\n"))"\r\n" else "\n"
        return json.toString(2).replace("\n",ending)+ending
    }
}
