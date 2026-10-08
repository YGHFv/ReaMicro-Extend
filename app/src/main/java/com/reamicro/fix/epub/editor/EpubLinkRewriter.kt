package com.reamicro.fix.epub.editor

import java.io.File
import java.net.URI
import java.util.Locale

internal object EpubLinkRewriter {
    data class Edit(val start: Int, val end: Int, val value: String = "")
    data class Attr(val name: String, val value: String, val start: Int, val end: Int, val valueStart: Int, val valueEnd: Int)
    class Node(val name: String, val start: Int, val openEnd: Int, val parent: Node?, val attrs: List<Attr>) {
        var closeStart = openEnd
        var end = openEnd
        val children = mutableListOf<Node>()
        var documentBase: URI? = null
        fun attr(name: String) = attrs.firstOrNull { it.name == name }
        fun ancestors(): Sequence<Node> = generateSequence(parent) { it.parent }
    }
    private val tags = Regex("""(?s)<!--.*?-->|<!\[CDATA\[.*?\]\]>|<\?.*?\?>|<![^>]*>|</?[A-Za-z_][\w:.-]*(?:\s+(?:\"[^\"]*\"|'[^']*'|[^'\">])*)?\s*/?>""")
    private val attrs = Regex("""\s+([\w:.-]+)\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s>]+))""")
    private val voids = setOf("img","link","meta","br","hr","input","source","embed","area","base","wbr","col","param","track")
    fun nodes(text: String, html: Boolean): List<Node> {
        val result = mutableListOf<Node>(); val stack = mutableListOf<Node>()
        for (match in tags.findAll(text)) {
            val token = match.value
            if (token.startsWith("<!") || token.startsWith("<?")) continue
            val closing = token.startsWith("</")
            val fullName = token.drop(if (closing) 2 else 1).takeWhile { it.isLetterOrDigit() || it in "_:.-" }
            val name = fullName.substringAfter(':').lowercase(Locale.ROOT)
            if (stack.lastOrNull()?.name in setOf("script", "style") && !(closing && name == stack.last().name)) continue
            if (closing) {
                val index = stack.indexOfLast { it.name == name }
                if (index >= 0) {
                    val node = stack[index]; node.closeStart = match.range.first; node.end = match.range.last + 1
                    while (stack.size > index) stack.removeAt(stack.lastIndex)
                }
                continue
            }
            val attributes = attrs.findAll(token).map { a ->
                val group = (2..4).mapNotNull { a.groups[it] }.first()
                Attr(a.groupValues[1].lowercase(Locale.ROOT), decodeXml(group.value),
                    match.range.first + a.range.first, match.range.first + a.range.last + 1,
                    match.range.first + group.range.first, match.range.first + group.range.last + 1)
            }.toList()
            val node = Node(name, match.range.first, match.range.last + 1, stack.lastOrNull(), attributes)
            node.parent?.children?.add(node); result.add(node)
            if (!token.trimEnd().endsWith("/>") && !(html && name in voids)) stack.add(node)
        }
        return result
    }
    fun decodeXml(value: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(value) {
        when (val code = it.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> runCatching { String(Character.toChars(if (code.startsWith("#x")) code.drop(2).toInt(16) else code.drop(1).toInt())) }.getOrDefault(it.value)
        }
    }
    fun xml(value: String): String = buildString {
        for (c in value) when(c) {
            '&' -> { append('&');append("amp;") }; '<' -> { append('&');append("lt;") }
            '"' -> { append('&');append("quot;") }; '\'' -> { append('&');append("apos;") }
            else -> append(c)
        }
    }
    private fun base(file: File, node: Node?): URI {
        var uri = node?.documentBase ?: file.toURI()
        if (node != null) for (n in (node.ancestors().toList().asReversed() + node)) {
            n.attr("xml:base")?.let { uri = uri.resolve(URI(it.value.replace(" ", "%20"))) }
        }
        return uri
    }
    fun resolve(file: File, href: String, root: File, node: Node? = null): String? = runCatching {
        val ref = URI(href.replace(92.toChar(), '/').replace(" ", "%20"))
        if ((ref.isAbsolute && ref.scheme != "file") || (!ref.rawAuthority.isNullOrEmpty() && ref.rawAuthority != "localhost") || ref.rawPath.isNullOrBlank()) return null
        val resolved = base(file, node).resolve(ref)
        if (resolved.scheme != "file") return null
        val target = File(resolved.path).toPath().normalize()
        val parent = root.canonicalFile.toPath()
        if (!target.startsWith(parent) || target == parent) return null
        parent.relativize(target).toString().replace('\\', '/')
    }.getOrNull()
    fun relocated(file: File, href: String, target: String, root: File, node: Node? = null): String {
        val ref = URI(href.replace(92.toChar(), '/').replace(" ", "%20"))
        if (ref.isAbsolute && ref.scheme == "file") return File(root,target).toURI().toASCIIString() +
            (ref.rawQuery?.let { "?$it" } ?: "") + (ref.rawFragment?.let { "#$it" } ?: "")
        val folder = File(base(file, node).resolve(".").path).toPath().normalize()
        val path = folder.relativize(File(root, target).toPath()).toString().replace('\\', '/')
        return URI(null, null, path, null).toASCIIString() +
            (ref.rawQuery?.let { "?$it" } ?: "") + (ref.rawFragment?.let { "#$it" } ?: "")
    }
    fun removedLine(text: String, start: Int, end: Int): Edit {
        val line = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
        var after = end
        while (after < text.length && text[after] in " \t\r") after++
        if (line <= start && text.substring(line, start).isBlank() && (after == text.length || text[after] == '\n')) {
            if (after < text.length) after++

            while (after < text.length) {
                val next = text.indexOf('\n', after).let { if (it < 0) text.length else it + 1 }
                if (text.substring(after, next).isNotBlank()) break
                after = next
            }
            return Edit(line, after)
        }
        return Edit(start, end)
    }
    fun apply(text: String, edits: List<Edit>): String {
        val selected = mutableListOf<Edit>()
        for (e in edits.distinct().sortedWith(compareBy<Edit> { it.start }.thenByDescending { it.end })) {
            val previous = selected.lastOrNull()
            if (previous != null && e.start < previous.end) {
                if (previous.value.isEmpty() && e.end <= previous.end) continue
                if (previous.value.isEmpty() && e.value.isEmpty()) {
                    selected[selected.lastIndex] = Edit(previous.start, maxOf(previous.end, e.end)); continue
                }
                error("引用修改区间冲突，原文件未改动")
            }
            selected.add(e)
        }
        val out = StringBuilder(text)
        for (e in selected.asReversed()) out.replace(e.start, e.end, e.value)
        return out.toString()
    }
    private fun srcsetParts(value: String): List<Pair<String,String>> {
        val parts=mutableListOf<Pair<String,String>>();var i=0
        while(i<value.length){
            while(i<value.length && (value[i].isWhitespace() || value[i]==','))i++
            val start=i;while(i<value.length && !value[i].isWhitespace())i++
            var url=value.substring(start,i)
            if(url.isEmpty())break
            if(url.endsWith(',')){url=url.trimEnd(',');parts.add(url to "");continue}
            val descriptor=i;var depth=0
            while(i<value.length){val ch=value[i];if(ch==','&&depth==0)break;if(ch=='(')depth++;if(ch==')')depth--;i++}
            val tail=value.substring(descriptor,i).trim();if(i<value.length)i++
            parts.add(url to if(tail.isEmpty())"" else " $tail")
        }
        return parts
    }
    fun markup(text: String, file: File, root: File, moves: Map<String,String>, deleted: String?): String {
        val html = file.extension.lowercase() in setOf("html","htm","xhtml","svg")
        val ns = nodes(text, html); val edits = mutableListOf<Edit>()
        if (html) ns.firstOrNull { it.name=="base" }?.attr("href")?.let { base ->
            val uri=file.toURI().resolve(URI(base.value.replace(" ","%20")))
            ns.filter { it.name!="base" }.forEach { it.documentBase=uri }
        }
        fun survivingSrcset(n: Node): Boolean = n.attr("srcset")?.value?.let { value ->
            srcsetParts(value).any { (href, _) ->
                href.isNotEmpty() && resolve(file,href,root,n)!=deleted
            }
        } == true
        fun remove(n: Node) { edits.add(removedLine(text,n.start,n.end)) }
        fun unwrap(n: Node) {
            edits.add(removedLine(text,n.start,n.openEnd))
            if(n.end > n.closeStart) edits.add(removedLine(text,n.closeStart,n.end))
        }
        val removedIds = ns.filter { it.name == "item" && deleted != null && resolve(file,it.attr("href")?.value.orEmpty(),root,it)==deleted }
            .mapNotNull { it.attr("id")?.value }.toSet()
        val removedSpine = ns.filter { it.name == "itemref" && it.attr("idref")?.value in removedIds }
        require(removedSpine.isEmpty() || ns.count { it.name=="itemref" } > removedSpine.size) { "不能删除最后一个阅读项；请先添加替代章节" }
        for (n in ns) {
            if (n.name=="itemref" && n.attr("idref")?.value in removedIds) { remove(n);continue }
            if (n.name=="meta" && ((n.attr("name")?.value=="cover" && n.attr("content")?.value in removedIds) ||
                n.attr("refines")?.value?.removePrefix("#") in removedIds)) { remove(n);continue }
            for (a in n.attrs) {
                if (n.name=="base") continue
                if (a.name in setOf("toc","media-overlay","fallback") && a.value in removedIds) { edits.add(Edit(a.start,a.end));continue }
                if (a.name !in setOf("href","xlink:href","src","poster","data","full-path","uri")) continue
                val baseFile = if (a.name=="full-path" || (a.name=="uri" && file.parentFile.name=="META-INF")) File(root,"_container.xml") else file

                val rawContainer = if(a.name=="full-path") runCatching {
                    File(root,a.value).canonicalFile.takeIf { it.isFile && it.toPath().startsWith(root.toPath()) }
                        ?.relativeTo(root)?.invariantSeparatorsPath
                }.getOrNull() else null
                val target = rawContainer ?: resolve(baseFile,a.value,root,n) ?: continue
                val moved = moves[target]
                if (moved != null) {
                    val value=if(a.name=="full-path") moved else relocated(baseFile,a.value,moved,root,n)
                    edits.add(Edit(a.valueStart,a.valueEnd,xml(value)));continue
                }
                if (target != deleted) continue
                when {
                    n.name=="cipherreference" -> remove(n.ancestors().firstOrNull { it.name=="encrypteddata" } ?: n)
                    n.name=="content" && n.parent?.name in setOf("navpoint","pagetarget","navtarget") -> {
                        val parent=n.parent!!
                        if(parent.children.any { it.name=="navpoint" }) {
                            unwrap(parent); parent.children.filter { it.name!="navpoint" }.forEach(::remove)
                        } else remove(parent)
                    }
                    n.name=="text" && n.parent?.name=="par" -> remove(n.parent!!)
                    n.name=="a" && n.ancestors().any { it.name=="nav" } -> {
                        val li=n.ancestors().firstOrNull { it.name=="li" }
                        if(li==null) remove(n) else {
                            val lists=li.children.filter { it.name in setOf("ol","ul") }
                            if(lists.isEmpty()) remove(li) else {
                                unwrap(li); lists.forEach(::unwrap)
                                li.children.filter { it !in lists }.forEach(::remove)
                            }
                        }
                    }
                    n.name=="img" && survivingSrcset(n) -> edits.add(Edit(a.start,a.end))
                    n.name in setOf("item","reference","rootfile","link","img","image","source","iframe") -> remove(n)
                    else -> edits.add(Edit(a.start,a.end))
                }
            }
            n.attr("srcset")?.let { a ->
                var changed=false
                val parts=srcsetParts(a.value).mapNotNull { (href,tail) ->
                    val piece=href+tail
                    val path=resolve(file,href,root,n)
                    if (path!=null && path==deleted) {changed=true;null}
                    else if (path!=null && moves.containsKey(path)) {changed=true;relocated(file,href,moves.getValue(path),root,n)+tail}
                    else piece
                }
                if(changed) {
                    if(parts.isNotEmpty())edits.add(Edit(a.valueStart,a.valueEnd,xml(parts.joinToString(", "))))
                    else if(n.attr("src")==null || resolve(file,n.attr("src")!!.value,root,n)==deleted)remove(n)
                    else edits.add(Edit(a.start,a.end))
                }
            }
            n.attr("style")?.let { a ->
                val old="x{${a.value}}";val next=css(old,file,root,moves,deleted,n)
                if(old!=next) {
                    val value=next.substringAfter('{').substringBeforeLast('}')
                    if(value.isBlank())edits.add(Edit(a.start,a.end)) else edits.add(Edit(a.valueStart,a.valueEnd,xml(value)))
                }
            }
            if(n.name=="style" && n.closeStart>n.openEnd) {
                val old=text.substring(n.openEnd,n.closeStart);val next=css(old,file,root,moves,deleted,n)
                if(old!=next) { if(next.isBlank())remove(n) else edits.add(Edit(n.openEnd,n.closeStart,next)) }
            }
        }
        var result=apply(text,edits)
        if(deleted!=null && file.extension.equals("opf",true) && result!=text) {
            val emptyGuides=nodes(result,false).filter { it.name=="guide" && it.children.isEmpty() && result.substring(it.openEnd,it.closeStart).isBlank() }
            result=apply(result,emptyGuides.map { removedLine(result,it.start,it.end) })
        }
        if(result!=text && file.extension.lowercase() in setOf("opf","ncx","xml")) EpubNavigation.parse(result)
        return result
    }
    private data class CssRef(val value: String,val start: Int,val end: Int,val fullStart: Int,val fullEnd: Int,val isImport: Boolean)
    private fun cssRefs(text: String): List<CssRef> {
        val refs=mutableListOf<CssRef>();var i=0
        fun quotedEnd(start: Int, quote: Char): Int { var p=start;while(p<text.length) { if(text[p]=='\\')p+=2 else if(text[p]==quote)return p else p++ };return text.length }
        while(i<text.length) {
            if(text.startsWith("/*",i)) { i=text.indexOf("*/",i+2).let { if(it<0)text.length else it+2 };continue }
            val isImport=text.regionMatches(i,"@import",0,7,true)
            val url=text.regionMatches(i,"url",0,3,true) && (i==0 || !text[i-1].isLetterOrDigit())
            var p=i+if(isImport)7 else if(url)3 else 0
            if(isImport || url) {
                while(p<text.length && text[p].isWhitespace())p++
                val importUrl=isImport && text.regionMatches(p,"url",0,3,true)
                if(importUrl){p+=3;while(p<text.length&&text[p].isWhitespace())p++}
                if(url || importUrl) { if(p>=text.length || text[p]!='('){i++;continue};p++;while(p<text.length&&text[p].isWhitespace())p++ }
                if(p>=text.length)break
                val quote=text[p].takeIf { it=='\'' || it=='"' }
                val start=p+if(quote!=null)1 else 0
                var end=if(quote!=null)quotedEnd(start,quote) else run {
                    var q=start;while(q<text.length){if(text[q]==92.toChar())q+=2 else if(text[q]==')')break else q++};minOf(q,text.length)
                }
                while(end>start && text[end-1].isWhitespace())end--
                var finish=if(quote!=null)quotedEnd(start,quote)+1 else end
                if(url || importUrl) { while(finish<text.length&&text[finish].isWhitespace())finish++;if(finish<text.length&&text[finish]==')')finish++ }
                refs.add(CssRef(text.substring(start,end),start,end,i,finish,isImport));i=finish.coerceAtLeast(i+1);continue
            }
            if(text[i]=='\''||text[i]=='"')i=(quotedEnd(i+1,text[i])+1).coerceAtMost(text.length) else i++
        }
        return refs
    }
    fun css(text: String,file: File,root: File,moves: Map<String,String>,deleted: String?,node: Node?=null): String {
        val refs=cssRefs(text);val edits=mutableListOf<Edit>();var document: CssSourceDocument?=null
        for(ref in refs) {
            val decoded=Regex("""\\([0-9a-fA-F]{1,6})(?:\s)?|\\(.)""").replace(ref.value) {
                if(it.groupValues[1].isNotEmpty()) runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("") else it.groupValues[2]
            }
            val target=resolve(file,decoded,root,node) ?: continue
            val moved=moves[target]
            if(moved!=null){edits.add(Edit(ref.start,ref.end,relocated(file,decoded,moved,root,node).replace("'","%27").replace("(","%28").replace(")","%29")));continue}
            if(target!=deleted)continue
            if(document==null)document=CssSourceDocument.parse(text)
            val pair=requireNotNull(document).rules.flatMap { r -> r.declarations.map { r to it } }
                .firstOrNull { (_,d)->ref.start>=d.valueStart && ref.end<=d.valueEnd }
            if(pair!=null) {
                val (rule,d)=pair;var end=maxOf(d.valueEnd,d.importantEnd)
                while(end<rule.bodyEnd) {
                    if(text.startsWith("/*",end)) {end=text.indexOf("*/",end+2).let {if(it<0)rule.bodyEnd else it+2};continue}
                    if(text[end]==';'){end++;break};if(!text[end].isWhitespace())break;end++
                }
                edits.add(removedLine(text,d.id,end))
            } else if(ref.isImport) {
                val end=text.indexOf(';',ref.fullEnd).let { if(it<0)ref.fullEnd else it+1 }
                edits.add(removedLine(text,ref.fullStart,end))
            } else edits.add(Edit(ref.fullStart,ref.fullEnd,"none"))
        }
        val result=apply(text,edits)
        if(result!=text)CssSourceDocument.parse(result)
        return result
    }
}
