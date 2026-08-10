// 把 WebDavDriveHook 的成员函数机械外移为同包扩展函数。
//
// 保证「只搬不改」：
//   1. 只重写声明行（private fun name(...) -> internal fun WebDavDriveHook.name(...)）；
//   2. 函数体整体反缩进 4 空格，但三引号原始字符串内部一行不动——那里的空格是内容；
//   3. 搬完后把新文件里的函数体重新缩进回去，与原文逐字节比对，不一致就中止。
//
// 用法：node tools/extract-hook-cluster.mjs <源文件> <目标文件> <类名> <函数名清单文件> <文件头注释>

import fs from 'node:fs'

const [, , sourcePath, targetPath, className, namesPath, headerComment] = process.argv
if (!sourcePath || !targetPath || !className || !namesPath) {
  console.error('参数不足')
  process.exit(1)
}

const names = new Set(
  fs.readFileSync(namesPath, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean),
)
const text = fs.readFileSync(sourcePath, 'utf8')
const lines = text.split('\n')

// ---- 定位类体内的顶层成员 ----
const classStart = lines.findIndex((l) => l.startsWith(`class ${className}(`))
if (classStart < 0) throw new Error('未找到类声明')

const FUN_RE = /^    (?:(private|internal|public) )?(?:(inline|suspend|tailrec) )*fun (?:<[^>]+> )?([A-Za-z0-9_]+)\s*[(<]/
const OTHER_MEMBER_RE = /^    (?:@\w+(?:\([^)]*\))? )*(?:private |internal |public )?(?:@\w+ )*(?:const )?(?:data |inner |sealed |enum |abstract |open |inline |suspend |tailrec |lateinit |operator |override |external |infix )*(?:class|object|interface|val|var|fun|companion)\b/

/** 找出成员声明的起始行（含紧贴其上的注释与注解）。 */
function declarationStart(index) {
  let start = index
  while (start > 0) {
    const prev = lines[start - 1]
    if (/^    (\/\/|\*|\/\*|@)/.test(prev)) start--
    else break
  }
  return start
}

/** 从声明行找到该成员的结束行（下一个顶层成员的声明起点之前）。 */
function memberEnd(declLine) {
  for (let i = declLine + 1; i < lines.length; i++) {
    if (lines[i] === '}') return i // 类体结束
    if (OTHER_MEMBER_RE.test(lines[i])) return declarationStart(i)
  }
  return lines.length
}

const moved = []
for (let i = classStart; i < lines.length; i++) {
  const m = lines[i].match(FUN_RE)
  if (!m) continue
  if (!names.has(m[3])) continue
  const start = declarationStart(i)
  const end = memberEnd(i)
  moved.push({ name: m[3], declLine: i, start, end })
}

const missing = [...names].filter((n) => !moved.some((m) => m.name === n))
if (missing.length) {
  console.error('这些函数没找到或有重载歧义：', missing.join(', '))
  process.exit(1)
}
// 重载：同名多个定义时全部搬走，顺序保持
moved.sort((a, b) => a.start - b.start)

// ---- 反缩进，跳过三引号原始字符串内部 ----
//
// 唯一的非等价改动：原始字符串之外的「纯空白行」会被清成空行。这在 Kotlin 里
// 没有任何语义，且原始字符串内部的行完全不动，所以不影响任何字符串内容。
function normalizeBlankLines(block) {
  const out = []
  let inRaw = false
  for (const line of block) {
    out.push(!inRaw && line.trim() === '' ? '' : line)
    const occurrences = (line.match(/"""/g) || []).length
    for (let k = 0; k < occurrences; k++) inRaw = !inRaw
  }
  return out
}

function dedent(block) {
  const out = []
  let inRaw = false
  for (const line of block) {
    if (!inRaw) out.push(line.startsWith('    ') ? line.slice(4) : line)
    else out.push(line)
    // 一行里 """ 可能出现多次（开+闭），逐次翻转
    const occurrences = (line.match(/"""/g) || []).length
    for (let k = 0; k < occurrences; k++) inRaw = !inRaw
  }
  return out
}

function reindent(block) {
  const out = []
  let inRaw = false
  for (const line of block) {
    if (!inRaw) out.push(line === '' ? '' : '    ' + line)
    else out.push(line)
    const occurrences = (line.match(/"""/g) || []).length
    for (let k = 0; k < occurrences; k++) inRaw = !inRaw
  }
  return out
}

const chunks = []
for (const m of moved) {
  const original = normalizeBlankLines(lines.slice(m.start, m.end))
  // 去掉块尾的空行，统一由生成器加分隔
  while (original.length && original[original.length - 1] === '') original.pop()
  const dedented = dedent(original)
  // 重写声明行：去掉可见性修饰，加上接收者
  const declIndex = m.declLine - m.start
  const decl = dedented[declIndex]
  const rewritten = decl.replace(
    /^(?:private |internal |public )?((?:inline |suspend |tailrec )*)fun (<[^>]+> )?/,
    `internal $1fun $2${className}.`,
  )
  if (rewritten === decl) throw new Error('声明行重写失败: ' + decl)
  dedented[declIndex] = rewritten
  // 校验：把反缩进结果重新缩进回去，除声明行外应与原文逐字节一致
  const restored = reindent(dedented)
  restored[declIndex] = original[declIndex]
  if (restored.join('\n') !== original.join('\n')) {
    console.error('!! 反缩进不可逆，函数:', m.name)
    process.exit(1)
  }
  chunks.push({ name: m.name, body: dedented })
}

// ---- 生成目标文件 ----
const importBlock = lines.slice(0, classStart).join('\n').match(/^import .+$/gm) || []
const header = [
  `package com.reamicro.fix.hook`,
  '',
  ...importBlock,
  '',
  ...(headerComment ? headerComment.split('\\n').map((l) => l) : []),
  '',
]
const out = header.join('\n') + chunks.map((c) => c.body.join('\n')).join('\n\n') + '\n'
fs.writeFileSync(targetPath, out, 'utf8')

// ---- 从源文件删除已搬走的块 ----
const removal = new Set()
for (const m of moved) for (let i = m.start; i < m.end; i++) removal.add(i)
const remaining = lines.filter((_, i) => !removal.has(i))
fs.writeFileSync(sourcePath, remaining.join('\n'), 'utf8')

console.log(`搬出 ${chunks.length} 个函数，${moved.reduce((s, m) => s + (m.end - m.start), 0)} 行 -> ${targetPath}`)
