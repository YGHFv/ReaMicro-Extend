// 把类里的「成员扩展函数」（private fun String.foo() 这类）提升为指定包的顶层扩展函数。
//
// Kotlin 无法表达「既是 A 的扩展、又是 B 的成员」的函数在类体之外的形式，所以这些
// 函数没法像普通成员那样搬成 WebDavDriveHook 的扩展；同时它们留在类里也不行——
// 成员扩展只在类体内可见，搬出去的簇调用不到。
//
// 因此把它们提升为不依赖 hook 实例的顶层扩展函数。若某个函数确实用到了 hook 状态，
// 提升后会编译失败，需要单独处理（改为显式传入 hook）。
//
// 用法：node tools/hoist-member-extensions.mjs <源文件> <目标文件> <类名> <目标包名>

import fs from 'node:fs'

const [, , sourcePath, targetPath, className, targetPackage] = process.argv
if (!sourcePath || !targetPath || !className || !targetPackage) {
  console.error('参数不足')
  process.exit(1)
}

const lines = fs.readFileSync(sourcePath, 'utf8').split('\n')
const classStart = lines.findIndex((l) => l.startsWith(`class ${className}(`))
if (classStart < 0) throw new Error('未找到类声明')

// 接收者类型里可能出现泛型与包名：Class<*>、android.database.Cursor、Map<String, Any> 等
const EXT_RE = /^ {4}(?:private |internal |public )?(?:inline |suspend |tailrec )*fun (?:<[^>]+> )?([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*(?:<[^()]*>)?)\.([A-Za-z0-9_]+)\s*\(/
const OTHER_MEMBER_RE = /^ {4}(?:@\w+(?:\([^)]*\))? )*(?:private |internal |public )?(?:@\w+ )*(?:const )?(?:data |inner |sealed |enum |abstract |open |inline |suspend |tailrec |lateinit |operator |override |external |infix )*(?:class|object|interface|val|var|fun|companion)\b/

function declarationStart(index) {
  let start = index
  while (start > 0 && /^ {4}(\/\/|\*|\/\*|@)/.test(lines[start - 1])) start--
  return start
}

function memberEnd(declLine) {
  for (let i = declLine + 1; i < lines.length; i++) {
    if (lines[i] === '}') return i
    if (OTHER_MEMBER_RE.test(lines[i])) return declarationStart(i)
  }
  return lines.length
}

function normalizeBlankLines(block) {
  const out = []
  let inRaw = false
  for (const line of block) {
    out.push(!inRaw && line.trim() === '' ? '' : line)
    const n = (line.match(/"""/g) || []).length
    for (let k = 0; k < n; k++) inRaw = !inRaw
  }
  return out
}

function dedent(block) {
  const out = []
  let inRaw = false
  for (const line of block) {
    out.push(inRaw ? line : line.startsWith('    ') ? line.slice(4) : line)
    const n = (line.match(/"""/g) || []).length
    for (let k = 0; k < n; k++) inRaw = !inRaw
  }
  return out
}

function reindent(block) {
  const out = []
  let inRaw = false
  for (const line of block) {
    out.push(inRaw ? line : line === '' ? '' : '    ' + line)
    const n = (line.match(/"""/g) || []).length
    for (let k = 0; k < n; k++) inRaw = !inRaw
  }
  return out
}

const found = []
for (let i = classStart; i < lines.length; i++) {
  const m = lines[i].match(EXT_RE)
  if (!m) continue
  found.push({ receiver: m[1], name: m[2], declLine: i, start: declarationStart(i), end: memberEnd(i) })
}

const chunks = []
for (const f of found) {
  const original = normalizeBlankLines(lines.slice(f.start, f.end))
  while (original.length && original[original.length - 1] === '') original.pop()
  const dedented = dedent(original)
  const declIndex = f.declLine - f.start
  const decl = dedented[declIndex]
  const rewritten = decl.replace(/^(?:private |internal |public )?/, 'internal ')
  dedented[declIndex] = rewritten
  const restored = reindent(dedented)
  restored[declIndex] = original[declIndex]
  if (restored.join('\n') !== original.join('\n')) {
    console.error('!! 反缩进不可逆，扩展函数:', f.receiver + '.' + f.name)
    process.exit(1)
  }
  chunks.push(dedented)
}

const importBlock = lines.slice(0, classStart).join('\n').match(/^import .+$/gm) || []
const header = [
  `package ${targetPackage}`,
  '',
  ...importBlock,
  '',
  `// 从 ${className} 提升出来的成员扩展函数。`,
  '//',
  '// 原先它们是「既是 String/Context/Any 的扩展、又是 hook 成员」的成员扩展函数，',
  '// 这种函数只在类体内可见。把功能簇拆成同包扩展函数后调用不到，因此提升为不依赖',
  '// hook 实例的顶层扩展函数。',
  '',
]
fs.writeFileSync(targetPath, header.join('\n') + chunks.map((c) => c.join('\n')).join('\n\n') + '\n', 'utf8')

const removal = new Set()
for (const f of found) for (let i = f.start; i < f.end; i++) removal.add(i)
fs.writeFileSync(sourcePath, lines.filter((_, i) => !removal.has(i)).join('\n'), 'utf8')

console.log(`提升 ${chunks.length} 个成员扩展函数 -> ${targetPath}`)
