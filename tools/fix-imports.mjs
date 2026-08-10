// 按编译报错自动补 import：把 "Unresolved reference 'X'" 里的 X 在 app 源码中
// 找到其顶层声明所在包，然后给报错文件补上 import。重复到编译通过。
//
// 用在跨包搬迁之后——搬完的调用点都会因为找不到符号而报错，逐个手补容易漏。
//
// 用法：node tools/fix-imports.mjs

import fs from 'node:fs'
import { execSync } from 'node:child_process'

const GRADLE = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew'
const packageCache = new Map()

/** 在 app 源码里查找某个顶层声明所在的包。找不到返回 null。 */
function findTopLevelPackage(name) {
  if (packageCache.has(name)) return packageCache.get(name)
  const decl = new RegExp(
    `^(?:internal |public )?(?:const )?(?:inline |suspend |tailrec )*(?:fun|val|var|class|object|interface)\\s+(?:<[^>]+>\\s+)?${name}\\b`,
    'm',
  )
  const stack = ['app/src/main/java']
  while (stack.length) {
    const dir = stack.pop()
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = `${dir}/${entry.name}`
      if (entry.isDirectory()) stack.push(full)
      else if (entry.name.endsWith('.kt')) {
        const text = fs.readFileSync(full, 'utf8')
        if (!decl.test(text)) continue
        const pkg = text.match(/^package (.+)$/m)?.[1] ?? null
        packageCache.set(name, pkg)
        return pkg
      }
    }
  }
  packageCache.set(name, null)
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
    console.log(`第 ${round} 轮：编译通过`)
    process.exit(0)
  } catch (e) {
    output = (e.stdout || '') + (e.stderr || '')
  }
  const needed = new Map()
  const re = /file:\/\/\/(.+?\.kt):\d+:\d+ Unresolved reference '([A-Za-z0-9_]+)'/g
  let m
  while ((m = re.exec(output))) {
    const [, file, name] = m
    const pkg = findTopLevelPackage(name)
    if (!pkg) continue
    const text = fs.readFileSync(file, 'utf8')
    if (text.startsWith(`package ${pkg}\n`)) continue // 同包无需 import
    if (!needed.has(file)) needed.set(file, new Set())
    needed.get(file).add(`import ${pkg}.${name}`)
  }
  let added = 0
  for (const [file, statements] of needed) {
    const text = fs.readFileSync(file, 'utf8')
    const additions = [...statements].filter((imp) => !text.includes(imp + '\n'))
    if (!additions.length) continue
    const lines = text.split('\n')
    const lastImport = lines.map((l, i) => (l.startsWith('import ') ? i : -1)).filter((i) => i >= 0).pop()
    const at = lastImport >= 0 ? lastImport + 1 : lines.findIndex((l) => l.startsWith('package ')) + 1
    lines.splice(at, 0, ...additions)
    fs.writeFileSync(file, lines.join('\n'), 'utf8')
    added += additions.length
  }
  if (!added) {
    console.error(`第 ${round} 轮：无法继续，剩余报错：`)
    output.split('\n').filter((l) => l.startsWith('e: ')).slice(0, 12).forEach((l) => console.error('  ' + l))
    process.exit(1)
  }
  console.log(`第 ${round} 轮：补了 ${added} 个 import`)
}
