# B4 高亮自定义 CSS 字号修复（2026-10-29）

## 根因

- 只读配置确认两个现有高亮样式都保存了 `font-size: 0.3em`，没有修改用户配置。
- `ReaderDialogueHighlightHook.parseCssFontSize` 仍调用旧 TextUnitKt.getSp/getEm。
  B4 已删除该门面及两个一参工厂，异常被 runCatching 吞掉，字号返回 null；
  颜色和九宫格仍能绘制，因此看起来像 CSS 没生效。
- B4 保留通用工厂 `jtd.k(float, long)`，与旧 getSp/getEm 的打包类型一致：
  sp=`0x100000000`，em=`0x200000000`。
- 备用 Brush 版 SpanStyle 构造器第三参是字号，不是颜色。
  旧适配把颜色放进第三参，并给了 null Brush，导致颜色和字号丢失。
  构造器筛选中的 first 还会提前抛错，不能真正回退到该分支。

## 最小修复

- B4 字号转换改为调用经过双源确认的通用 TextUnit 工厂。
- 补充该方法的 methodOwner/name；不把已删除的 getSp/getEm 映射到不同签名的方法。
- Brush 分支传入 SolidColor(color)，第三参传 fontSize，其他槽位保持。
- 构造器筛选改为 firstOrNull，正常 16 参 color 版优先顺序不变。
- 规则匹配、选区、所有者标记、缓存、背景和九宫格绘制未做额外修改。
- 既有支持列表不扩大；本轮修复原本已支持的 font-size，不声称支持任意 CSS 属性。

## 双源证据

- REA `.tools/rea-6.7.0`、JADX headless MCP 0.7.1；
  MT MCP 现有工作区 `gv89uj9u`，仅调用阅微 APK 的只读工具。
- B4 APK SHA-256：
  `2aba06c1f870f57c12bdd3ec71f6fc95837db34f2a38363f07a98c900fc0da1b`。
- 原始证据根目录：`%TEMP%/reamicro-analysis/runtime/b4`。
- TextUnit 工厂：`hl-css-native-pack.json` / `hl-css-native-pack-mt.json`。
- 旧 getSp/getEm：`hl-css-old-sp-reference.json` / `hl-css-old-em-reference-mt.json`。
- SpanStyle 16/17 参：`hl-css-cfa-ctor16.json`、`hl-css-cfa-ctor17.json`、
  `hl-css-cfa-color-mt.json`、`hl-css-cfa-assign-mt.json`。
- 对照源码 commit `14bc732`：该构造分支由 B4 适配新增，不是用户 CSS 语法变化。
- 本轮源文件/配置快照：`hl-css-20261029/`。

## 验证

- `:app:testDebugUnitTest :app:assembleRelease` 成功，2 分 13 秒。
- JVM 131 项测试，0 failures、0 errors；Python 审计/日志回归 5 项通过。
- 隔离 runner 调用真实生产 `createHighlightSpanStyle` 和 B4 SpanStyle 类型：
  0.3em、2em、24px、18sp 的数值及类型位全部正确。
- 强制测试同一生产函数的 17 参 Brush 回退分支：
  0.3em 正确，前景色为 `ffff980000000000`，不是 Unspecified。
- CSS 原生预览、缓存单键失效、SVG、Typography 的既有隔离回归仍通过。
- 最终隔离进程退出码 0，`REMAINING_NATIVE_PROBE_PASS`；
  前后阅微 PID 27727、MT PID 18580 不变。
- 测试函数建立的 Handler/线程导致首次完成后进程未退出；
  只终止经过 cmdline 确认的专用隔离进程，并让 runner 成功后显式退出，
  没有终止手机、阅微、MT 或其他应用。
- 最终证据：`hl-css-r4-native/native-probe.log`、`hl-css-r4-native/probe-metadata.json`；
  构建日志为 `round13/hl-css-r2-build-build.log`。
- Release 已覆盖安装，版本保持 2.3.9 / 71，SHA-256
  `161d13d240639a45939d083ab0756b5ec13325c74b725baad47df22ecec7d143`。
- 没有强制切换或重开阅微；用户随后正常重开，当前 PID 31483，
  B4 校验 360/360、hooks 133/133，未发现本轮高亮反射/回调异常。
- 日志中的 MLKit registrar 构造缺失属于宿主 ComponentDiscovery，
  不当作这次高亮 CSS 故障，也没有顺手改动。
- 实际阅读页面的字号视觉对比尚未完成；隔离验证证明字号已经进入生产 SpanStyle，
  不能替代所有翻页模式及实际排版效果的完整 UI 验收。

## 工作区约束

- 全部既有改动保留，仅本地提交、不推送、不改版本号。
- 没有重启手机，没有修改、重启或切换 MT。
- 截图未载入上下文，没有修改用户高亮设置或正文文件。
- 该源文件原有未提交的 B4 绘制/行查询适配随本地保存一并保留。
