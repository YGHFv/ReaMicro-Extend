// 包结构归位：把文件搬到与其内容相符的包，并同步更新 package 声明、import 与测试。
//
// 只改包名与 import，不动任何函数体。搬完后由编译器与单测验证。
//
// 用法：node tools/move-packages.mjs

import fs from 'node:fs'
import path from 'node:path'

const MAIN = 'app/src/main/java'
const TEST = 'app/src/test/java'

// 文件名（不含 .kt） -> 目标包
const MOVES = {
  // 生成 EPUB 的标记与样式
  OnlineBodyMarkup: 'com.reamicro.fix.online.epub',
  OnlineChapterHeadingMarkup: 'com.reamicro.fix.online.epub',
  OnlineChapterImageMarkup: 'com.reamicro.fix.online.epub',
  OnlineVolumeHeadingMarkup: 'com.reamicro.fix.online.epub',
  OnlineEpubFontEmbedder: 'com.reamicro.fix.online.epub',
  OnlineEpubImageCompat: 'com.reamicro.fix.online.epub',
  OnlineEpubStyleCss: 'com.reamicro.fix.online.epub',
  OnlineEpubStylePreview: 'com.reamicro.fix.online.epub',
  OnlineHeaderImageComposer: 'com.reamicro.fix.online.epub',
  // 下载与按需加载
  OnlineChapterContentValidator: 'com.reamicro.fix.online.download',
  OnlineChapterUpdatePlanner: 'com.reamicro.fix.online.download',
  OnlineOnDemandBridge: 'com.reamicro.fix.online.download',
  OnlineOnDemandMetadata: 'com.reamicro.fix.online.download',
  OnlineOnDemandPrefetchPlanner: 'com.reamicro.fix.online.download',
  OnlineFanqieBatchCompat: 'com.reamicro.fix.online.download',
  OnlineHttpFailureCompat: 'com.reamicro.fix.online.download',
  // 搜索与书源取值
  OnlineSourceValueCompat: 'com.reamicro.fix.online.search',
  OnlineHtmlEntityDecoder: 'com.reamicro.fix.online.search',
  // 真正的云盘部分
  WebDavRemoteClient: 'com.reamicro.fix.cloud.webdav',
  WebDavBackupImportState: 'com.reamicro.fix.cloud.webdav',
  WebDavDownloadTasks: 'com.reamicro.fix.cloud.webdav',
  WebDavUiState: 'com.reamicro.fix.cloud.webdav',
}

function walk(dir, acc = []) {
  if (!fs.existsSync(dir)) return acc
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, acc)
    else if (full.endsWith('.kt')) acc.push(full)
  }
  return acc
}

/** 找到某个类名当前所在文件（main 或 test）。 */
function locate(root, name) {
  return walk(root).find((f) => path.basename(f) === `${name}.kt`)
}

const renames = []
for (const [name, targetPackage] of Object.entries(MOVES)) {
  for (const [root, suffix] of [[MAIN, ''], [TEST, 'Test']]) {
    const from = locate(root, name + suffix)
    if (!from) continue
    const oldPackage = fs.readFileSync(from, 'utf8').match(/^package (.+)$/m)[1]
    if (oldPackage === targetPackage) continue
    const to = `${root}/${targetPackage.replace(/\./g, '/')}/${name}${suffix}.kt`
    renames.push({ from, to, name: name + suffix, oldPackage, targetPackage })
  }
}

if (!renames.length) {
  console.log('没有需要搬迁的文件')
  process.exit(0)
}

// 1. 物理搬迁并改写 package 声明
for (const r of renames) {
  const text = fs.readFileSync(r.from, 'utf8').replace(/^package .+$/m, `package ${r.targetPackage}`)
  fs.mkdirSync(path.dirname(r.to), { recursive: true })
  fs.writeFileSync(r.to, text, 'utf8')
  fs.unlinkSync(r.from)
  console.log(`搬迁 ${r.name}: ${r.oldPackage} -> ${r.targetPackage}`)
}

// 2. 全仓更新 import
const importMap = new Map()
for (const r of renames) {
  const simple = r.name
  importMap.set(`import ${r.oldPackage}.${simple}`, `import ${r.targetPackage}.${simple}`)
}
let touched = 0
for (const file of [...walk(MAIN), ...walk(TEST)]) {
  let text = fs.readFileSync(file, 'utf8')
  const original = text
  for (const [from, to] of importMap) text = text.split(from + '\n').join(to + '\n')
  if (text !== original) {
    fs.writeFileSync(file, text, 'utf8')
    touched++
  }
}
console.log(`更新 import 的文件数 ${touched}`)

// 3. 清理搬空的目录
for (const dir of new Set(renames.map((r) => path.dirname(r.from)))) {
  if (fs.existsSync(dir) && fs.readdirSync(dir).length === 0) {
    fs.rmdirSync(dir)
    console.log(`删除空目录 ${dir}`)
  }
}
console.log('搬迁完成，接下来跑 tools/fix-imports.mjs 补同包变跨包后缺的 import')
