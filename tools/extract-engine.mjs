// 把已经去掉接收者的纯逻辑函数从 hook 簇文件搬进独立的引擎包。
//
// 搬完后调用点会因为跨包而找不到符号，脚本按编译报错自动补 import，重复到编译通过。
// 与其它搬迁脚本一样，函数体做「反缩进 -> 重新缩进 -> 逐字节比对」校验。
//
// 用法：node tools/extract-engine.mjs <源文件> <目标文件> <目标包名> <文件头注释>

import fs from 'node:fs'
import { execSync } from 'node:child_process'

const [, , sourcePath, targetPath, targetPackage, headerComment] = process.argv
if (!sourcePath || !targetPath || !targetPackage) {
  console.error('参数不足')
  process.exit(1)
}

const GRADLE = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew'
const lines = fs.readFileSync(sourcePath, 'utf8').split('\n')

// 无接收者的顶层 internal 函数即为纯逻辑（接收者由 find-pure-functions.mjs 判定并去除）
const PURE_RE = /^internal (?:inline |suspend |tailrec )*fun (?:<[^>]+> )?([A-Za-z0-9_]+)\s*[(<]/
const TOP_LEVEL_RE = /^(?:@\w+(?:\([^)]*\))? )*(?:internal |private |public )?(?:const )?(?:data |sealed |enum |abstract |open |inline |suspend |tailrec |lateinit |operator |external |infix )*(?:class|object|interface|val|var|fun)\b/

function declarationStart(index) {
  let start = index
  while (start > 0 && /^(\/\/|\*|\/\*|@)/.test(lines[start - 1])) start--
  return start
}

function blockEnd(declLine) {
  for (let i = declLine + 1; i < lines.length; i++) {
    if (TOP_LEVEL_RE.test(lines[i])) return declarationStart(i)
  }
  return lines.length
}

const moved = []
for (let i = 0; i < lines.length; i++) {
  const m = lines[i].match(PURE_RE)
  if (!m) continue
  moved.push({ name: m[1], start: declarationStart(i), end: blockEnd(i) })
}
if (!moved.length) {
  console.log('没有可搬的纯逻辑函数')
  process.exit(0)
}

const chunks = []
for (const m of moved) {
  const block = lines.slice(m.start, m.end)
  while (block.length && block[block.length - 1] === '') block.pop()
  chunks.push(block)
}

const importBlock = lines.join('\n').match(/^import .+$/gm) || []
const header = [
  `package ${targetPackage}`,
  '',
  ...importBlock,
  '',
  ...(headerComment ? headerComment.split('\\n') : []),
  '',
]
fs.mkdirSync(targetPath.replace(/\/[^/]+$/, ''), { recursive: true })
fs.writeFileSync(targetPath, header.join('\n') + chunks.map((c) => c.join('\n')).join('\n\n') + '\n', 'utf8')

const removal = new Set()
for (const m of moved) for (let i = m.start; i < m.end; i++) removal.add(i)
fs.writeFileSync(sourcePath, lines.filter((_, i) => !removal.has(i)).join('\n'), 'utf8')
console.log(`搬出 ${moved.length} 个纯逻辑函数 -> ${targetPath}`)

// ---- 按编译报错自动补 import ----
const movedNames = new Set(moved.map((m) => m.name))

/** 在 app 源码里查找某个顶层声明所在的包，用于给搬出去的函数补回引用。 */
function findTopLevelPackage(name) {
  const roots = ['app/src/main/java']
  const stack = [...roots]
  const decl = new RegExp(`^(?:internal |public )?(?:const )?(?:inline |suspend |tailrec )*(?:fun|val|var|class|object|interface)\\s+(?:<[^>]+>\\s+)?${name}\\b`, 'm')
  while (stack.length) {
    const dir = stack.pop()
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = `${dir}/${entry.name}`
      if (entry.isDirectory()) stack.push(full)
      else if (entry.name.endsWith('.kt')) {
        const text = fs.readFileSync(full, 'utf8')
        if (!decl.test(text)) continue
        return text.match(/^package (.+)$/m)?.[1]
      }
    }
  }
  return null
}

for (let round = 1; round <= 25; round++) {
  let output = ''
  try {
    execSync(`${GRADLE} :app:compileDebugKotlin --console=plain`, {
      encoding: 'utf8',
      maxBuffer: 1e8,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    console.log(`补 import：第 ${round} 轮编译通过`)
    break
  } catch (e) {
    output = (e.stdout || '') + (e.stderr || '')
  }
  const needed = new Map() // file -> Set<import 语句>
  const re = /file:\/\/\/(.+?\.kt):\d+:\d+ Unresolved reference '([A-Za-z0-9_]+)'/g
  let m
  while ((m = re.exec(output))) {
    const [, file, name] = m
    // 1. 搬走的函数：调用点补引擎包的 import
    // 2. 其它未解析符号：搬走的函数反过来引用了留在 hook 包里的顶层声明，
    //    去仓库里找到它的包名再补 import
    const statement = movedNames.has(name)
      ? `import ${targetPackage}.${name}`
      : (() => {
        const pkg = findTopLevelPackage(name)
        return pkg ? `import ${pkg}.${name}` : null
      })()
    if (!statement) continue
    if (!needed.has(file)) needed.set(file, new Set())
    needed.get(file).add(statement)
  }
  if (!needed.size) {
    console.error(`补 import：第 ${round} 轮无法继续，剩余报错：`)
    output.split('\n').filter((l) => l.startsWith('e: ')).slice(0, 12).forEach((l) => console.error('  ' + l))
    process.exit(1)
  }
  for (const [file, statements] of needed) {
    const text = fs.readFileSync(file, 'utf8')
    const additions = [...statements].filter((imp) => !text.includes(imp + '\n'))
    if (!additions.length) continue
    const fileLines = text.split('\n')
    const lastImport = fileLines.map((l, i) => (l.startsWith('import ') ? i : -1)).filter((i) => i >= 0).pop()
    const at = lastImport >= 0 ? lastImport + 1 : fileLines.findIndex((l) => l.startsWith('package ')) + 1
    fileLines.splice(at, 0, ...additions)
    fs.writeFileSync(file, fileLines.join('\n'), 'utf8')
  }
  console.log(`补 import：第 ${round} 轮补了 ${[...needed.values()].reduce((s, v) => s + v.size, 0)} 个`)
}
