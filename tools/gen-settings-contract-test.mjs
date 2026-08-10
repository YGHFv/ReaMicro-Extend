// 一次性脚本：生成 ModuleSettings 存储契约的锁定测试。
// 用法：node tools/gen-settings-contract-test.mjs

import fs from 'node:fs'

const source = fs.readFileSync('app/src/main/java/com/reamicro/fix/settings/ModuleSettings.kt', 'utf8')
const PRIVATE_KEYS = new Set(['KEY_ASSOCIATION_SOURCE_PREFIX', 'KEY_ONLINE_SOURCE_PREFIX', 'KEY_TTS_SOURCE_PREFIX'])

const keys = [...source.matchAll(/const val (KEY_[A-Z0-9_]+)\s*=\s*"([^"]*)"/g)]
  .map((m) => [m[1], m[2]])
  .filter(([name]) => !PRIVATE_KEYS.has(name))

// 只钉字面量：由其它常量推导出来的默认值断言等于把推导逻辑抄一遍，没有价值
const LITERAL = /^("(?:[^"\\]|\\.)*"|true|false|-?\d[\d_]*[LfF]?|-?0x[0-9A-Fa-f]+)$/
const defaults = [...source.matchAll(/const val (DEFAULT_[A-Z0-9_]+)\s*=\s*(.+)/g)]
  .map((m) => [m[1], m[2].trim()])
  .filter(([, value]) => LITERAL.test(value))

const CHUNK = 25
const lines = []
lines.push('package com.reamicro.fix.settings', '')
lines.push('import org.junit.Assert.assertEquals', 'import org.junit.Test', '')
lines.push(
  '/**',
  ' * 设置项存储契约的锁定。',
  ' *',
  ' * KEY_* 的字符串值就是 SharedPreferences 里的键名，改一个字符等于把用户已有的设置',
  ' * 全部丢弃，而编译器完全看不出来。DEFAULT_* 决定用户从未设置过时的行为。',
  ' *',
  ' * 这里把两者逐一钉死。确实要改键名或默认值时同步改期望值——这一步摩擦是故意的，',
  ' * 用来把「有意的行为变更」与「重构时手滑」区分开。',
  ' *',
  ' * 只覆盖字面量常量；由其它常量推导出来的默认值不在此列。',
  ' *',
  ' * 本文件由 tools/gen-settings-contract-test.mjs 生成。',
  ' */',
  'class ModuleSettingsContractTest {',
  '',
)
lines.push('    @Test')
lines.push('    fun `偏好文件名锁定`() {')
lines.push('        assertEquals("reamicro_fix_module_settings", ModuleSettings.PREFS_NAME)')
lines.push('    }', '')

for (let i = 0; i < keys.length; i += CHUNK) {
  lines.push('    @Test')
  lines.push(`    fun \`设置键名锁定 ${i / CHUNK + 1}\`() {`)
  for (const [name, value] of keys.slice(i, i + CHUNK)) {
    lines.push(`        assertEquals("${value}", ModuleSettings.${name})`)
  }
  lines.push('    }', '')
}

lines.push('    @Test')
lines.push('    fun `键名互不重复`() {')
lines.push('        val all = listOf(')
for (const [name] of keys) lines.push(`            ModuleSettings.${name},`)
lines.push('        )')
lines.push('        assertEquals(all.size, all.toSet().size)')
lines.push('    }', '')

for (let i = 0; i < defaults.length; i += CHUNK) {
  lines.push('    @Test')
  lines.push(`    fun \`默认值锁定 ${i / CHUNK + 1}\`() {`)
  for (const [name, value] of defaults.slice(i, i + CHUNK)) {
    lines.push(`        assertEquals(${value}, ModuleSettings.${name})`)
  }
  lines.push('    }', '')
}
lines.push('}')

fs.writeFileSync(
  'app/src/test/java/com/reamicro/fix/settings/ModuleSettingsContractTest.kt',
  lines.join('\n') + '\n',
  'utf8',
)
console.log(`生成契约测试：键名 ${keys.length} 条，默认值 ${defaults.length} 条`)
