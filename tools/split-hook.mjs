// 通用的大 hook 拆分流水线：把一个 hook 类按功能簇机械拆成同包扩展函数文件。
//
// 是 tools/split-webdav-drive-hook.mjs 的泛化版本，步骤相同：
//   1. companion object 里的常量提升到子包（函数留在原处——它们可能是对外 API）；
//   2. 非 inner 的嵌套类型提升到同一个子包；
//   3. 成员扩展函数（private fun String.foo()）提升为子包顶层扩展；
//   4. 类成员 private 放宽为 internal（扩展函数看不见 private）；
//   5. 按功能簇把成员函数搬成 internal fun Hook.xxx()；
//   6. 清理各簇文件里带过来的未用 import。
//
// 每个函数搬迁都做「反缩进 -> 重新缩进 -> 与原文逐字节比对」校验，不一致直接中止。
//
// 用法：node tools/split-hook.mjs <配置文件.json>
//
// 配置格式见 tools/split-config/*.json。

import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const configPath = process.argv[2]
if (!configPath) {
  console.error('用法：node tools/split-hook.mjs <配置文件.json>')
  process.exit(1)
}
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'))
const { hookPath, className, subPackage, clusters, commonFooter, keepInClass = [], pureHelpers = [] } = config
const subDir = `app/src/main/java/${subPackage.replace(/\./g, '/')}`
const hookDir = path.dirname(hookPath)
const hookPackage = fs.readFileSync(hookPath, 'utf8').match(/^package (.+)$/m)[1]

const read = () => fs.readFileSync(hookPath, 'utf8').split('\n')

/**
 * 去掉一行里的字符串与字符字面量，只留代码骨架。
 *
 * 用于花括号配对计数：常量表里有 '{'、'}' 这样的字符字面量（例如朗读的包裹符集合），
 * 直接数括号会把 companion 的结束位置算错，进而把常量表拦腰截断。
 */
function codeOnly(line) {
  return line
    .replace(/\\./g, '')
    .replace(/'[^']*'/g, "''")
    .replace(/"[^"]*"/g, '""')
}

function classStartIndex(lines) {
  const i = lines.findIndex((l) => l.startsWith(`class ${className}(`))
  if (i < 0) throw new Error('未找到类声明')
  return i
}

function importsOf(lines) {
  return lines.slice(0, classStartIndex(lines)).join('\n').match(/^import .+$/gm) || []
}

// ---- 1. companion 常量 -> 子包顶层 ----
//
// 只搬常量，不搬函数：companion 里的函数常常是给别的 hook 调的对外入口
// （如 ReaMicroSettingsHook.openReaderCompletionPlanFromReader），搬走会破坏调用点。
function hoistCompanionConstants() {
  const lines = read()
  const start = lines.findIndex((l, i) => i > classStartIndex(lines) && /^ {4}(private )?companion object \{$/.test(l))
  if (start < 0) {
    console.log('1/6 无 companion object，跳过')
    return
  }
  let end = start + 1
  let depth = 1
  for (; end < lines.length; end++) {
    const code = codeOnly(lines[end])
    depth += (code.match(/\{/g) || []).length - (code.match(/\}/g) || []).length
    if (depth === 0) break
  }

  // 只提升 const val / val：companion 里的 var 是类级可变状态（如 activeInstance），
  // 留在原处更贴近它的语义，而且它常常以宿主类或本类为类型，搬走还要额外补 import。
  // companion 体按「成员声明」切块：8 空格缩进 + 成员关键字才算一个块的开始，
  // 紧贴其上的注释归属下一个成员，块一直延续到下一个成员声明之前（多行字面量、
  // charArrayOf(...) 这种跨行初始化因此能完整跟着走）。
  const DECL_RE = /^ {8}(?:@\w+(?:\([^)]*\))? )*(?:private |internal |public )*(?:const )?(?:val|var|fun|class|object|interface) /
  // 提升 const val / val / var；但类型是 hook 类自身的 var（如 activeInstance）留在原处：
  // 它是「当前活跃实例」这种与类绑定的状态，搬到子包既不贴切又要额外补类型 import。
  const HOISTABLE_RE = /^ {8}(?:@\w+(?:\([^)]*\))? )*(?:private |internal |public )*(?:const )?(?:val|var) /
  const isHoistable = (line) => HOISTABLE_RE.test(line) && !new RegExp(`:\\s*${className}\\b`).test(line)

  const body = lines.slice(start + 1, end)
  const declIndexes = []
  body.forEach((line, i) => {
    if (DECL_RE.test(line)) declIndexes.push(i)
  })
  /** 把紧贴声明之上的注释行一起算进块里。 */
  const blockStart = (i) => {
    let s = i
    while (s > 0 && /^ {8}(\/\/|\*|\/\*)/.test(body[s - 1])) s--
    return s
  }
  const hoisted = []
  const kept = []
  let cursor = 0
  for (let d = 0; d < declIndexes.length; d++) {
    const declLine = declIndexes[d]
    const from = blockStart(declLine)
    const to = d + 1 < declIndexes.length ? blockStart(declIndexes[d + 1]) : body.length
    // 声明之前的游离行（空行等）原样保留
    if (from > cursor) kept.push(...body.slice(cursor, from))
    const block = body.slice(from, to)
    if (isHoistable(body[declLine])) hoisted.push(block)
    else kept.push(...block)
    cursor = to
  }
  if (cursor < body.length) kept.push(...body.slice(cursor))

  if (!hoisted.length) {
    console.log('1/6 companion 内无可提升常量，跳过')
    return
  }

  const flatten = (block) => {
    let inRaw = false
    return block.map((line) => {
      let out = inRaw ? line : line.replace(/^ {8}/, '')
      if (!inRaw && /^((?:@Volatile )?)(?:private |internal )?(?:const )?(?:val|var) /.test(out)) {
        out = out.replace(/^((?:@Volatile )?)(?:private |internal )?/, '$1internal ')
      }
      const n = (line.match(/"""/g) || []).length
      for (let k = 0; k < n; k++) inRaw = !inRaw
      return out
    })
  }

  const header = [
    `package ${subPackage}`,
    '',
    ...importsOf(lines),
    '',
    `// ${className} 与其外移出去的扩展函数共用的常量。`,
    '//',
    `// 原先是 ${className} 的 companion object 成员。功能簇拆成同包扩展函数后，`,
    '// companion 的 private 成员对扩展函数不可见，因此提升为子包顶层 internal 声明，',
    '// 由各簇文件通过包级 star import 引用。companion 里的函数没有搬——它们是给其它',
    '// hook 调用的对外入口。',
    '',
  ]
  fs.mkdirSync(subDir, { recursive: true })
  fs.writeFileSync(
    `${subDir}/${className}Constants.kt`,
    header.join('\n') + hoisted.map((b) => flatten(b).join('\n')).join('\n') + '\n',
    'utf8',
  )

  const rebuilt = [...lines.slice(0, start + 1), ...kept, ...lines.slice(end)]
  const lastImport = rebuilt.map((l, i2) => (l.startsWith('import ') ? i2 : -1)).filter((i2) => i2 >= 0).pop()
  rebuilt.splice(lastImport + 1, 0, `import ${subPackage}.*`)
  fs.writeFileSync(hookPath, rebuilt.join('\n'), 'utf8')
  console.log(`1/6 companion 提升 ${hoisted.length} 个常量 -> ${className}Constants.kt`)
}

function runTool(tool, args) {
  execFileSync(process.execPath, [tool, ...args], { stdio: 'inherit' })
}

// ---- 2/3. 嵌套类型与成员扩展 -> 子包 ----
function hoistTypes() {
  runTool('tools/hoist-nested-types.mjs', [hookPath, `${subDir}/${className}Types.kt`, className, subPackage])
}

function hoistMemberExtensions() {
  runTool('tools/hoist-member-extensions.mjs', [hookPath, `${subDir}/${className}Extensions.kt`, className, subPackage])
}

// ---- 4. private -> internal ----
// ---- 3.5 纯工具函数 -> 子包顶层 ----
//
// 提升出去的成员扩展函数会调用一些不依赖 hook 状态的小工具（路径归一化之类）。
// 那些工具留在类里就调不到，因此按配置把它们一并提升为子包顶层函数。
// 若某个函数其实用到了 hook 状态，提升后编译会失败——由编译器兜底。
function hoistPureHelpers() {
  if (!pureHelpers.length) return
  const listFile = '.tmp-pure-helpers.txt'
  fs.writeFileSync(listFile, pureHelpers.join('\n'), 'utf8')
  runTool('tools/extract-hook-cluster.mjs', [
    hookPath,
    `${subDir}/${className}PureHelpers.kt`,
    className,
    listFile,
    [
      `// 从 ${className} 提升出来的纯工具函数。`,
      '//',
      '// 它们不依赖 hook 状态，但被同样提升到本子包的成员扩展函数调用，留在类里就调不到。',
    ].join('\\n'),
    subPackage,
    '--no-receiver',
  ])
  fs.unlinkSync(listFile)
}

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
  fs.writeFileSync(hookPath, lines.join('\n'), 'utf8')
  console.log(`4/6 private -> internal ${changed} 处`)
}

// ---- 5. 按簇外移 ----
/** 类体内的一级成员函数名。keepInClass 里的函数不参与外移——通常是 install() 这类
 *  入口，或者会读写 companion private 成员的函数。 */
function topLevelFunctionNames() {
  const lines = read()
  const start = classStartIndex(lines)
  const re = /^ {4}(?:internal |private |public )?(?:inline |suspend |tailrec )*fun (?:<[^>]+> )?([A-Za-z0-9_]+)\s*[(<]/
  const names = []
  for (let i = start; i < lines.length; i++) {
    const m = lines[i].match(re)
    if (m && !names.includes(m[1]) && !keepInClass.includes(m[1])) names.push(m[1])
  }
  return names
}

const movedNames = new Set()

function extractClusters() {
  const assigned = new Set()
  for (const cluster of clusters) {
    const match = cluster.match === '*' ? () => true : new RegExp(cluster.match, 'i')
    const names = topLevelFunctionNames().filter(
      (n) => !assigned.has(n) && (match === true || (typeof match === 'function' ? match(n) : match.test(n))),
    )
    if (!names.length) {
      console.log(`5/6 ${cluster.id}: 无函数，跳过`)
      continue
    }
    names.forEach((n) => {
      assigned.add(n)
      movedNames.add(n)
    })
    const listFile = `.tmp-cluster-${cluster.id}.txt`
    fs.writeFileSync(listFile, names.join('\n'), 'utf8')
    runTool('tools/extract-hook-cluster.mjs', [
      hookPath,
      `${hookDir}/${className}.${cluster.id}.kt`,
      className,
      listFile,
      [...cluster.header, ...commonFooter].join('\\n'),
      hookPackage,
    ])
    fs.unlinkSync(listFile)
  }
}

// ---- 5.4 companion 里对同名实例函数的委托 ----
//
// companion 常有 fun foo(...) = activeInstance?.runCatching { foo(...) } 这种对外入口：
// 名字与实例方法相同，靠 runCatching 的接收者区分。实例方法变成扩展函数后，
// 隐式接收者解析会先命中 companion 自己的同名函数（变成递归），因此显式写出 this.。
function qualifyCompanionDelegates() {
  let text = fs.readFileSync(hookPath, 'utf8')
  let count = 0
  for (const name of movedNames) {
    const multiline = new RegExp(`(runCatching \\{\\s*\\n\\s+)(${name}\\()`, 'g')
    const inline = new RegExp(`(runCatching \\{ )(${name}\\()`, 'g')
    text = text.replace(multiline, (_, prefix, call) => {
      count++
      return `${prefix}this.${call}`
    })
    text = text.replace(inline, (_, prefix, call) => {
      count++
      return `${prefix}this.${call}`
    })
  }
  if (count) {
    fs.writeFileSync(hookPath, text, 'utf8')
    console.log(`5/6 companion 委托调用加显式 this. ${count} 处`)
  }
}

// ---- 6. 清理未用 import ----
function pruneImports() {
  const files = fs.readdirSync(hookDir)
    .filter((f) => f.startsWith(`${className}.`) && f.endsWith('.kt'))
    .map((f) => `${hookDir}/${f}`)
    .concat(fs.existsSync(subDir) ? fs.readdirSync(subDir).map((f) => `${subDir}/${f}`) : [])
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

// ---- 5.5 inner class 的类型引用 ----
//
// inner class 需要外部实例，没有随其它嵌套类型提升到子包。构造调用能靠扩展接收者
// 隐式解析，但出现在类型位置（参数、返回值、变量声明）时必须限定，因此给用到它的
// 簇文件补上显式导入。
function importInnerClasses() {
  const lines = read()
  const inner = []
  for (const line of lines) {
    const m = line.match(/^ {4}(?:internal |private |public )?inner (?:class|object) ([A-Za-z0-9_]+)/)
    if (m) inner.push(m[1])
  }
  if (!inner.length) return
  const files = fs.readdirSync(hookDir)
    .filter((f) => f.startsWith(`${className}.`) && f.endsWith('.kt'))
    .map((f) => `${hookDir}/${f}`)
  let added = 0
  for (const file of files) {
    let text = fs.readFileSync(file, 'utf8')
    const body = text.split('\n').filter((l) => !l.startsWith('import ')).join('\n')
    const needed = inner
      .filter((name) => new RegExp(`(?<![A-Za-z0-9_.])${name}(?![A-Za-z0-9_])`).test(body))
      .map((name) => `import ${hookPackage}.${className}.${name}`)
      .filter((imp) => !text.includes(imp + '\n'))
    if (!needed.length) continue
    const fileLines = text.split('\n')
    const lastImport = fileLines.map((l, i) => (l.startsWith('import ') ? i : -1)).filter((i) => i >= 0).pop()
    const at = lastImport >= 0 ? lastImport + 1 : fileLines.findIndex((l) => l.startsWith('package ')) + 1
    fileLines.splice(at, 0, ...needed)
    fs.writeFileSync(file, fileLines.join('\n'), 'utf8')
    added += needed.length
  }
  console.log(`5/6 补 inner class 类型导入 ${added} 个`)
}

// ---- 4.5 文件级 private 顶层声明 -> 子包 internal ----
//
// hook 文件里常有 `private fun dp(context, value)` 这类文件级私有顶层工具。
// 文件私有意味着搬出去的簇文件看不到它；而直接改成同包 internal 会与其它 hook
// 文件里的同名文件私有声明冲突（本包里 dp 就有三份各自私有的实现）。
// 因此挪到子包，由各簇文件通过包级 star import 引用。
function hoistFilePrivateTopLevel() {
  const lines = read()
  const TOP_PRIVATE = /^private (?:inline |suspend |tailrec )*(?:fun|val|var|const val|class|object) ([A-Za-z0-9_]+)/
  const NEXT_TOP = /^(?:@\w+|private |internal |public |fun |val |var |const |class |object |interface |import |package )/
  const blocks = []
  for (let i = 0; i < lines.length; i++) {
    if (!TOP_PRIVATE.test(lines[i])) continue
    let start = i
    while (start > 0 && /^(\/\/|\*|\/\*|@)/.test(lines[start - 1])) start--
    let end = i + 1
    while (end < lines.length && !NEXT_TOP.test(lines[end])) end++
    blocks.push({ start, end, name: lines[i].match(TOP_PRIVATE)[1] })
  }
  if (!blocks.length) return
  const moved = blocks.map((b) =>
    lines.slice(b.start, b.end)
      .join('\n')
      .replace(/^private /m, 'internal ')
      .replace(/\n+$/, ''),
  )
  const header = [
    `package ${subPackage}`,
    '',
    ...importsOf(lines),
    '',
    `// 原先是 ${path.basename(hookPath)} 里的文件级 private 顶层工具函数。`,
    '//',
    '// 功能簇拆成同包扩展函数后这些工具不可见；改成同包 internal 又会与其它 hook 文件里',
    '// 各自的同名文件私有实现冲突，因此挪到子包由包级 star import 引用。',
    '',
  ]
  const target = `${subDir}/${className}FileHelpers.kt`
  fs.mkdirSync(subDir, { recursive: true })
  fs.writeFileSync(target, header.join('\n') + moved.join('\n\n') + '\n', 'utf8')
  const removal = new Set()
  for (const b of blocks) for (let i = b.start; i < b.end; i++) removal.add(i)
  fs.writeFileSync(hookPath, lines.filter((_, i) => !removal.has(i)).join('\n'), 'utf8')
  console.log(`4/6 文件级 private 顶层声明提升 ${blocks.length} 个 -> ${path.basename(target)}`)
}

hoistCompanionConstants()
hoistTypes()
hoistMemberExtensions()
hoistPureHelpers()
widenVisibility()
hoistFilePrivateTopLevel()
extractClusters()
qualifyCompanionDelegates()
importInnerClasses()
pruneImports()
console.log('拆分完成')
