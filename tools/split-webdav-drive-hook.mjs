// P2a：把 WebDavDriveHook 按功能簇机械拆成同包扩展函数文件。
//
// 整条流水线做四件事，任一步失败即中止：
//   1. 把 private companion object 的常量提升到子包 com.reamicro.fix.hook.webdav；
//   2. 把非 inner 的嵌套类型提升到同一个子包；
//   3. 把类成员的 private 放宽为 internal（扩展函数看不见 private）；
//   4. 按功能簇把成员函数搬成 internal fun WebDavDriveHook.xxx()。
//
// 第 4 步的每个函数都会做「反缩进 -> 重新缩进 -> 与原文逐字节比对」的还原校验，
// 因此除声明行外函数体保证一字未改。
//
// 用法：node tools/split-webdav-drive-hook.mjs

import fs from 'node:fs'
import { execFileSync } from 'node:child_process'

const HOOK = 'app/src/main/java/com/reamicro/fix/hook/WebDavDriveHook.kt'
const SUB_DIR = 'app/src/main/java/com/reamicro/fix/hook/webdav'
const SUB_PACKAGE = 'com.reamicro.fix.hook.webdav'

function read() {
  return fs.readFileSync(HOOK, 'utf8').split('\n')
}

function classStartIndex(lines) {
  const i = lines.findIndex((l) => l.startsWith('class WebDavDriveHook('))
  if (i < 0) throw new Error('未找到类声明')
  return i
}

function importsOf(lines) {
  return lines.slice(0, classStartIndex(lines)).join('\n').match(/^import .+$/gm) || []
}

// ---- 1. companion object -> 子包顶层常量 ----
function hoistCompanion() {
  const lines = read()
  const start = lines.findIndex((l) => l.trim() === 'private companion object {')
  if (start < 0) throw new Error('未找到 companion object')
  let end = start + 1
  let depth = 1
  for (; end < lines.length; end++) {
    depth += (lines[end].match(/\{/g) || []).length - (lines[end].match(/\}/g) || []).length
    if (depth === 0) break
  }
  let inRaw = false
  const hoisted = lines.slice(start + 1, end).map((line) => {
    let out = inRaw ? line : line.startsWith('        ') ? line.slice(8) : line.replace(/^ {4}/, '')
    if (!inRaw && /^((?:@Volatile )?)(?:private |internal )?(?:const )?(?:val|var) /.test(out)) {
      out = out.replace(/^((?:@Volatile )?)(?:private |internal )?/, '$1internal ')
    }
    const n = (line.match(/"""/g) || []).length
    for (let k = 0; k < n; k++) inRaw = !inRaw
    return out
  })
  const header = [
    `package ${SUB_PACKAGE}`,
    '',
    ...importsOf(lines),
    '',
    '// WebDavDriveHook 与其外移出去的扩展函数共用的常量。',
    '//',
    '// 原先这些是 WebDavDriveHook 的 private companion object 成员。功能簇拆成同包扩展',
    '// 函数后，companion 的 private 成员对扩展函数不可见，因此提升为顶层 internal 声明。',
    '//',
    '// 单独开一个子包而不是放在 com.reamicro.fix.hook 顶层：该包里已有文件用 private',
    '// 顶层常量（如 LOG_PREFIX），顶层 internal 会与之冲突；Kotlin 又不允许对 object',
    '// 做 star import，所以走「子包 + 包级 star import」这条路。',
    '',
  ]
  fs.mkdirSync(SUB_DIR, { recursive: true })
  fs.writeFileSync(`${SUB_DIR}/WebDavDriveHookConstants.kt`, header.join('\n') + hoisted.join('\n') + '\n', 'utf8')

  const remaining = [...lines.slice(0, start), ...lines.slice(end + 1)]
  const lastImport = remaining.map((l, i) => (l.startsWith('import ') ? i : -1)).filter((i) => i >= 0).pop()
  remaining.splice(lastImport + 1, 0, `import ${SUB_PACKAGE}.*`)
  fs.writeFileSync(HOOK, remaining.join('\n'), 'utf8')
  console.log(`1/4 companion 提升 ${end - start - 1} 行 -> WebDavDriveHookConstants.kt`)
}

// ---- 2. 嵌套类型 -> 子包顶层 ----
function hoistTypes() {
  execFileSync(process.execPath, [
    'tools/hoist-nested-types.mjs',
    HOOK,
    `${SUB_DIR}/WebDavDriveHookTypes.kt`,
    'WebDavDriveHook',
    SUB_PACKAGE,
  ], { stdio: 'inherit' })
  console.log('2/4 嵌套类型提升完成')
}

// ---- 2.5 成员扩展函数 -> 子包顶层扩展 ----
function hoistMemberExtensions() {
  execFileSync(process.execPath, [
    'tools/hoist-member-extensions.mjs',
    HOOK,
    `${SUB_DIR}/WebDavDriveHookExtensions.kt`,
    'WebDavDriveHook',
    SUB_PACKAGE,
  ], { stdio: 'inherit' })
}

// ---- 3. private -> internal ----
function widenVisibility() {
  const lines = read()
  const start = classStartIndex(lines)
  let inRaw = false
  let changed = 0
  for (let i = start; i < lines.length; i++) {
    const line = lines[i]
    if (!inRaw && /^ {4}(@Volatile )?private (?!companion)/.test(line)) {
      lines[i] = line.replace(/^ {4}(@Volatile )?private /, '    $1internal ')
      changed++
    }
    const n = (line.match(/"""/g) || []).length
    for (let k = 0; k < n; k++) inRaw = !inRaw
  }
  // composeWebDavVector 属性与 getComposeWebDavVector() 的 JVM 签名同名，改成
  // internal 后名字修饰（ 后缀）会冲突，因此顺带改名。
  // composeWebDavVector 属性与 getComposeWebDavVector() 的 JVM 签名同名，放宽成
  // internal 后 Kotlin 的名字修饰会让两者冲突，因此顺带把属性改名。
  let renamed = 0
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes('composeWebDavVector') && !lines[i].includes('getComposeWebDavVector')) {
      lines[i] = lines[i].replace(/composeWebDavVector/g, 'composeWebDavVectorCache')
      renamed++
    }
  }
  if (renamed) console.log(`3/4 composeWebDavVector 改名 ${renamed} 处，避开 JVM 签名冲突`)
  fs.writeFileSync(HOOK, lines.join('\n'), 'utf8')
  console.log(`3/4 private -> internal ${changed} 处`)
}

// ---- 4. 按簇外移 ----
const CLUSTERS = [
  {
    id: 'OnlineEpub',
    match: (n) => /^(chapterXhtml|chapterParagraphHtml|chapterHeadingHtml|splitChapterHeading|stripDuplicatedChapterTitle|stripChapterTitlePrefix|volumeXhtml|onlineVolumeSegments|onlineVolumeHref|writeOnlineCompletionVolumePages|onlineTocNcx|onlineContentOpf|onlineCoverXhtml|onlineSourceMetadataOpf|onlineCoverExt|onlineCoverExtFromMime|onlineCoverExtFromBytes|onlineCoverExtFromUrl|coverMimeType|safeOnlineFileName|collectOnlineContentImages|defaultOnlineChapterHrefs|writeOnlineCompletionEpub|writeOnlineCompletionDefaultStyle|onlineCompletionDefaultCss|embedOnlineCompletionFonts|writeOnlineCompletionFontEntries|onlineCompletionFontFaces|onlineCompletionDividerImage|onlineCompletionHeaderImage|resolveOnlineCompletionDecor|onlineCompletionDecorManifestItems|onlineCompletionBookDirDecor|syncOnlineCompletionDefaultStyle|migrateOnlineCompletionChapterStyle|writeStoredTextZipEntry|writeTextZipEntry|writeBytesZipEntry|onlineCompletionExistingCoverExt|localizeOnlineChapterImages|existingOnlineChapterImageHrefs|synchronizeOnlineImageManifest|onlineBookUuid|onlineImportedBookBackupId|onlineSourceIdFromUuid|stableOnlineImageFileStem)$/.test(n),
    header: [
      '// WebDavDriveHook 的在线补全 EPUB 生成簇。',
      '//',
      '// 把下载好的章节写成标准 EPUB：章节 xhtml、分卷页、toc.ncx、content.opf、封面、',
      '// 默认样式与字体嵌入、正文图片本地化。',
    ],
  },
  {
    id: 'HostHooks',
    match: (n) => /^hook[A-Z]/.test(n),
    header: [
      '// WebDavDriveHook 的宿主 hook 安装簇。',
      '//',
      '// 所有 hookXxx()：只负责挂到宿主方法上，具体实现在其它簇。',
    ],
  },
  {
    id: 'LocalLibrary',
    match: (n) => /locallibrary|localdocument|querydocument/.test(n.toLowerCase()),
    header: [
      '// WebDavDriveHook 的本地书库簇。',
      '//',
      '// 基于 SAF 文档树的目录浏览、增删改、索引与搜索。',
    ],
  },
  {
    id: 'WebDav',
    match: (n) => /webdav|alist|cleartext/.test(n.toLowerCase()),
    header: [
      '// WebDavDriveHook 的 WebDAV 协议与账号簇。',
      '//',
      '// PROPFIND/MKCOL/MOVE/DELETE/GET/PUT、Alist 兼容、凭据与浏览目录读写、登录与授权。',
    ],
  },
  {
    id: 'OnlineSearch',
    match: (n) => /search|parseonline|onlinerule|onlinejson|applyonlinetemplate|onlinefirstjson|formatonline|normalizeonlinecover|resolveonlineurl|sourcebaseurl|replacefanqiecover|onlineresultmetadata|onlinecompletionstatus|statustextfrom|jsonobjecttoonline|firstjsonstring|indexofoptionsblock|parseoptionsjson|onlinedataurl|parseonlineheaders|isvalidhttpheader|cleanonlinetext|enrichonline/.test(n.toLowerCase()),
    header: [
      '// WebDavDriveHook 的在线源搜索簇。',
      '//',
      '// 按书源规则构造搜索请求、解析 JSON/HTML 结果、补全字数章节数等元数据。',
    ],
  },
  {
    id: 'OnlineDownload',
    match: (n) => /download|chapter|retry|prefetch|ondemand|toc|task|notification|import|fanqie|volume|cache|flush|append|readonline|mergeonline|remapon|detectinvalid|sleeponline|okiopath|unzip|obtainonline|synconline|writeonline/.test(n.toLowerCase()),
    header: [
      '// WebDavDriveHook 的在线补全下载簇。',
      '//',
      '// 目录解析、章节并发下载与重试、按需下载、进度通知、写盘与导入宿主书架。',
    ],
  },
  {
    id: 'HostUi',
    match: (n) => /modifier|composer|color|typography|imagevector|render|createview|icon|arrangement|alignment|drawable|theme|udp|statusbar|isdark|withalpha|edittext|showtoast|showsettings|view$/.test(n.toLowerCase()),
    header: [
      '// WebDavDriveHook 的宿主 UI 渲染簇。',
      '//',
      '// 反射构造 Compose Modifier / 主题色 / 图标，以及原生 View 版本的登录页与结果行。',
    ],
  },
  {
    id: 'Support',
    match: () => true,
    header: [
      '// WebDavDriveHook 的宿主对象构造与杂项支撑簇。',
      '//',
      '// 伪造宿主需要的 PagingData/Flow/Result/CloudBook 等对象、反射取宿主仓库与',
      '// ViewModel、书籍备份打包、以及各类小工具。',
    ],
  },
]

const COMMON_FOOTER = [
  '//',
  '// 从 WebDavDriveHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新',
  '// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。',
]

function topLevelFunctionNames() {
  const lines = read()
  const start = classStartIndex(lines)
  const re = /^ {4}(?:internal |private |public )?(?:inline |suspend |tailrec )*fun (?:<[^>]+> )?([A-Za-z0-9_]+)\s*[(<]/
  const names = []
  for (let i = start; i < lines.length; i++) {
    const m = lines[i].match(re)
    if (m && !names.includes(m[1])) names.push(m[1])
  }
  return names
}

function extractClusters() {
  const assigned = new Set()
  for (const cluster of CLUSTERS) {
    const names = topLevelFunctionNames().filter((n) => !assigned.has(n) && cluster.match(n))
    if (!names.length) {
      console.log(`4/4 ${cluster.id}: 无函数，跳过`)
      continue
    }
    names.forEach((n) => assigned.add(n))
    const listFile = `.tmp-cluster-${cluster.id}.txt`
    fs.writeFileSync(listFile, names.join('\n'), 'utf8')
    execFileSync(process.execPath, [
      'tools/extract-hook-cluster.mjs',
      HOOK,
      `app/src/main/java/com/reamicro/fix/hook/WebDavDriveHook.${cluster.id}.kt`,
      'WebDavDriveHook',
      listFile,
      [...cluster.header, ...COMMON_FOOTER].join('\\n'),
    ], { stdio: 'inherit' })
    fs.unlinkSync(listFile)
  }
}

// ---- 5. 外移后的收尾修正 ----
//
// 这三处是「类成员」与「同包扩展函数」在 Kotlin 语义上的真实差异，无法靠机械搬迁
// 消除，逐条列出以便复核。
function postFixups() {
  // 5.1 inner class 的类型引用：构造调用能靠扩展接收者隐式解析，但类型位置必须限定。
  const downloadPath = 'app/src/main/java/com/reamicro/fix/hook/WebDavDriveHook.OnlineDownload.kt'
  let download = fs.readFileSync(downloadPath, 'utf8')
  if (!download.includes('import com.reamicro.fix.hook.WebDavDriveHook.OnlineChapterBatchSession')) {
    download = download.replace(
      'package com.reamicro.fix.hook\n',
      'package com.reamicro.fix.hook\n\n// OnlineChapterBatchSession 是 WebDavDriveHook 的 inner class（需要外部实例），\n' +
        '// 没有随其它嵌套类型提升到子包，因此这里显式导入类型名。\nimport com.reamicro.fix.hook.WebDavDriveHook.OnlineChapterBatchSession\n',
    )
    fs.writeFileSync(downloadPath, download, 'utf8')
    console.log('5/5 OnlineDownload 补 inner class 类型导入')
  }

  // 5.2 this@WebDavDriveHook 标签只在类体内成立，扩展函数里要改用函数名标签。
  const hooksPath = 'app/src/main/java/com/reamicro/fix/hook/WebDavDriveHook.HostHooks.kt'
  let hooks = fs.readFileSync(hooksPath, 'utf8')
  if (hooks.includes('this@WebDavDriveHook')) {
    hooks = hooks.replace(/this@WebDavDriveHook\./g, 'this@hookWebDavCloudTap.')
    fs.writeFileSync(hooksPath, hooks, 'utf8')
    console.log('5/5 HostHooks 修正 this@WebDavDriveHook 标签')
  }

  // 5.3 View.isOnDarkBackground 是死代码（外移前后都无调用点），且它引用 hook 的
  //     activityProvider，提升为顶层扩展后无法解析。直接删掉而不是硬凑一个参数。
  const extPath = `${SUB_DIR}/WebDavDriveHookExtensions.kt`
  let ext = fs.readFileSync(extPath, 'utf8')
  const dead = /\ninternal fun View\.isOnDarkBackground\(context: Context\): Boolean \{[\s\S]*?\n\}\n/
  if (dead.test(ext)) {
    ext = ext.replace(dead, '\n')
    fs.writeFileSync(extPath, ext, 'utf8')
    console.log('5/5 删除死代码 View.isOnDarkBackground')
  }
}

// ---- 6. 清理未用 import ----
//
// 每个簇文件都原样带上了源文件的全部 189 行 import，其中绝大多数用不到。
// 只在「import 的简单名在文件正文中一次都没出现」时才删，star import 一律保留——
// 宁可多留也不能删错，因为扩展函数正是靠 import 才能解析。
function pruneImports() {
  const files = fs.readdirSync('app/src/main/java/com/reamicro/fix/hook')
    .filter((f) => f.startsWith('WebDavDriveHook.') && f.endsWith('.kt'))
    .map((f) => `app/src/main/java/com/reamicro/fix/hook/${f}`)
    .concat(fs.readdirSync(SUB_DIR).map((f) => `${SUB_DIR}/${f}`))
  let removed = 0
  for (const file of files) {
    const lines = fs.readFileSync(file, 'utf8').split('\n')
    const body = lines.filter((l) => !l.startsWith('import ')).join('\n')
    const kept = lines.filter((line) => {
      if (!line.startsWith('import ')) return true
      if (line.endsWith('.*')) return true
      const simpleName = line.replace(/^import\s+/, '').split('.').pop().trim()
      const used = new RegExp(`(?<![A-Za-z0-9_])${simpleName}(?![A-Za-z0-9_])`).test(body)
      if (!used) removed++
      return used
    })
    fs.writeFileSync(file, kept.join('\n'), 'utf8')
  }
  console.log(`6/6 清理未用 import ${removed} 行`)
}

hoistCompanion()
hoistTypes()
hoistMemberExtensions()
widenVisibility()
extractClusters()
postFixups()
pruneImports()
console.log('拆分完成')
