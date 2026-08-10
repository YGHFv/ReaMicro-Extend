// 把类里的嵌套类型（非 inner）机械提升为指定包的顶层 internal 声明。
//
// 拆分大 hook 时，外移出去的扩展函数需要引用这些类型；留在类里就得处处写
// Outer.Type 限定名。提升到同一个子包后，配合包级 star import 即可直接引用，
// 原有引用点一处不改。
//
// 与 extract-hook-cluster.mjs 一样保证「只搬不改」：反缩进跳过三引号原始字符串，
// 搬完重新缩进回去与原文逐字节比对。
//
// 用法：node tools/hoist-nested-types.mjs <源文件> <目标文件> <类名> <目标包名>

import fs from 'node:fs'

const [, , sourcePath, targetPath, className, targetPackage] = process.argv
if (!sourcePath || !targetPath || !className || !targetPackage) {
  console.error('参数不足')
  process.exit(1)
}

const lines = fs.readFileSync(sourcePath, 'utf8').split('\n')
const classStart = lines.findIndex((l) => l.startsWith(`class ${className}(`))
if (classStart < 0) throw new Error('未找到类声明')

const TYPE_RE = /^    (?:(?:internal|private|public) )?((?:data |sealed |enum |value |abstract |open )*)(class|object|interface) ([A-Za-z0-9_]+)/
const OTHER_MEMBER_RE = /^    (?:@\w+(?:\([^)]*\))? )*(?:private |internal |public )?(?:@\w+ )*(?:const )?(?:data |inner |sealed |enum |abstract |open |inline |suspend |tailrec |lateinit |operator |override |external |infix )*(?:class|object|interface|val|var|fun|companion)\b/

function declarationStart(index) {
  let start = index
  while (start > 0 && /^    (\/\/|\*|\/\*|@)/.test(lines[start - 1])) start--
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

const hoisted = []
for (let i = classStart; i < lines.length; i++) {
  const m = lines[i].match(TYPE_RE)
  if (!m) continue
  if (lines[i].includes(' inner ')) continue // inner class 需要外部实例，不能提升
  const start = declarationStart(i)
  const end = memberEnd(i)
  hoisted.push({ name: m[3], declLine: i, start, end })
}

const chunks = []
for (const h of hoisted) {
  const original = normalizeBlankLines(lines.slice(h.start, h.end))
  while (original.length && original[original.length - 1] === '') original.pop()
  const dedented = dedent(original)
  const declIndex = h.declLine - h.start
  const decl = dedented[declIndex]
  const rewritten = decl.replace(/^(?:private |internal |public )?/, 'internal ')
  dedented[declIndex] = rewritten
  const restored = reindent(dedented)
  restored[declIndex] = original[declIndex]
  if (restored.join('\n') !== original.join('\n')) {
    console.error('!! 反缩进不可逆，类型:', h.name)
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
  `// 从 ${className} 提升出来的嵌套类型。`,
  '//',
  '// 拆分成同包扩展函数后，这些类型要在多个文件里出现；提升到子包顶层配合包级',
  '// star import，引用点无需加限定名。inner class 需要外部实例，仍留在原类里。',
  '',
]
fs.writeFileSync(targetPath, header.join('\n') + chunks.map((c) => c.join('\n')).join('\n\n') + '\n', 'utf8')

const removal = new Set()
for (const h of hoisted) for (let i = h.start; i < h.end; i++) removal.add(i)
fs.writeFileSync(sourcePath, lines.filter((_, i) => !removal.has(i)).join('\n'), 'utf8')

console.log(`提升 ${chunks.length} 个嵌套类型 -> ${targetPath}`)
