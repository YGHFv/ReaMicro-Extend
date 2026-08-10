// 找出一个扩展函数文件里「其实不需要接收者」的函数。
//
// 做法：先把全部 internal fun Hook.xxx() 的接收者去掉，编译，按报错定位到需要接收者
// 的函数并逐个还原，重复直到编译通过。收敛后剩下的无接收者函数就是纯逻辑，
// 可以进一步搬到引擎层并补单测。
//
// 用法：node tools/find-pure-functions.mjs <文件> <类名>

import fs from 'node:fs'
import { execSync } from 'node:child_process'

const [, , filePath, className] = process.argv
if (!filePath || !className) {
  console.error('参数不足')
  process.exit(1)
}

const RECEIVER = `${className}.`
const originalText = fs.readFileSync(filePath, 'utf8')
const declRe = new RegExp(`^(internal (?:inline |suspend |tailrec )*fun (?:<[^>]+> )?)${className}\\.([A-Za-z0-9_]+)`)

/** 需要保留接收者的函数名。 */
const needsReceiver = new Set()

function render() {
  return originalText.split('\n').map((line) => {
    const m = line.match(declRe)
    if (!m) return line
    if (needsReceiver.has(m[2])) return line
    return line.replace(`${m[1]}${RECEIVER}`, m[1])
  }).join('\n')
}

/** 找出某一行属于哪个顶层函数。 */
function functionAt(lines, lineNumber) {
  for (let i = Math.min(lineNumber, lines.length) - 1; i >= 0; i--) {
    const m = lines[i].match(/^(?:internal |private |public )?(?:inline |suspend |tailrec )*fun (?:<[^>]+> )?(?:[A-Za-z0-9_.<>*?, ]+\.)?([A-Za-z0-9_]+)\s*[(<]/)
    if (m) return m[1]
  }
  return null
}

// Windows 上 Node 的 execSync 默认走 cmd.exe，用不了 ./gradlew，且当前目录下的
// 批处理必须带 .\ 前缀
const GRADLE = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew'

for (let round = 1; round <= 30; round++) {
  fs.writeFileSync(filePath, render(), 'utf8')
  let output = ''
  try {
    execSync(`${GRADLE} :app:compileDebugKotlin --console=plain`, {
      encoding: 'utf8',
      maxBuffer: 1e8,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    console.log(`第 ${round} 轮：编译通过`)
    break
  } catch (e) {
    output = (e.stdout || '') + (e.stderr || '')
  }
  const lines = fs.readFileSync(filePath, 'utf8').split('\n')
  const escaped = filePath.replace(/\//g, '[\\\\/]')
  const errorRe = new RegExp(`${escaped}:(\\d+):\\d+ `, 'g')
  const added = new Set()
  let m
  while ((m = errorRe.exec(output))) {
    const fn = functionAt(lines, Number(m[1]))
    if (fn && !needsReceiver.has(fn)) {
      needsReceiver.add(fn)
      added.add(fn)
    }
  }
  const otherFileErrors = output.split('\n').filter((l) => l.startsWith('e: ') && !l.includes(filePath.split('/').pop()))
  // 去掉接收者后，函数变成同包顶层函数，可能与别的文件里的同名顶层函数冲突。
  // 这种情况按报错位置反查出同名函数，把接收者还回去。
  for (const line of otherFileErrors) {
    const loc = line.match(/file:\/\/\/(.+?):(\d+):\d+/)
    if (!loc) continue
    const otherPath = loc[1].replace(/^([A-Za-z]):/, (_, d) => `${d}:`)
    if (!fs.existsSync(otherPath)) continue
    const fn = functionAt(fs.readFileSync(otherPath, 'utf8').split('\n'), Number(loc[2]) + 1)
    if (fn && !needsReceiver.has(fn)) {
      needsReceiver.add(fn)
      added.add(fn)
    }
  }
  if (!added.size) {
    console.error(`第 ${round} 轮：无法继续收敛，剩余报错：`)
    output.split('\n').filter((l) => l.startsWith('e: ')).slice(0, 10).forEach((l) => console.error('  ' + l))
    fs.writeFileSync(filePath, originalText, 'utf8')
    process.exit(1)
  }
  console.log(`第 ${round} 轮：${added.size} 个函数需要接收者`)
}

const all = [...originalText.matchAll(new RegExp(declRe.source, 'gm'))].map((m) => m[2])
const pure = all.filter((n) => !needsReceiver.has(n))
console.log(`\n共 ${all.length} 个函数，其中 ${pure.length} 个不依赖 hook 实例：`)
pure.forEach((n) => console.log('  ' + n))
fs.writeFileSync(filePath + '.pure.txt', pure.join('\n'), 'utf8')
