# B4 运行日志与迁移遗漏审计（2026-10-28）

## 结论

完整适配仍未闭合。本轮确认并最小修复字体名称/开关映射、Sentry 初始化入口、
逐章加载刷新中的协程静态方法 owner。另确认 CSS/SVG 预览和部分原生字体指标
捕获仍未完整迁移，列为下一轮工作；不通过改算法、扩大降级或虚报 hook 计数解决。

## 本轮范围与约束

- 源码基线 HEAD：`1e34580`。既有未提交修改及未跟踪文件全部保留。
- REA `.tools/rea-6.7.0` 与 MT MCP `gv89uj9u` 交叉分析同一 B4 APK。
- 本地 APK SHA-256：
  `2aba06c1f870f57c12bdd3ec71f6fc95837db34f2a38363f07a98c900fc0da1b`。
- MT 只读现有阅微 APK 工作区，没有修改、重启、切换或分析 MT 自身 APK。
- 没有重启手机；部署只重新加载阅微进程，没有修改账号、书籍或字体设置。
- 截图没有载入对话。本轮分析采用原始日志、DEX、代码与既有验收记录。
- 证据根目录：`%TEMP%/reamicro-analysis/runtime/b4`。
- 原始源文件摘要、日志、候选清单：`audit-r212-migration/`。

## 当前与历史日志

初始阅微 PID 4450，读取完整 module/logcat 原文，保留异常的续行和 Caused by。

1. B4 profile `360/360`、unresolved=0、安装 `131/131`：
   仅表示被记录的安装项成功，不表示延迟执行的每条功能链都可用。
2. B2/B3 symbols mismatch：
   是被拒绝候选的诊断，最终选择 B4，不是当前 profile 错配。
3. 退出 ReaderViewModel 后的 highlight clear skipped：
   是生命周期结束后的清理，不改写为渲染失败。
4. external manual-edit disabled：
   旧外部手动编辑扩展已有明确 B4 禁用门禁，不和原生选文编辑、关联搜索、
   条码关联菜单混为一谈；听书/TTS 按既有用户要求移除，不再计入迁移范围。
5. Sentry 真名不存在：
   **不能据此判定 SDK 不存在**。宿主仍有 Sentry 包、性能 provider、
   NDK provider 和 App.onCreate 的混淆初始化调用。本轮恢复该调用门禁。
6. 历史在线 OPF obtain 异常：
   出现在旧 PID，前轮已接入 trd.k；当前 PID 没有重现，仍需真实更新闭环回归。
7. 历史插图 HTTP 301、设备池为空、批量 API 空响应：
   属 HTTP/书源问题，不是类名迁移证据；重试后正文 872/872 成功也不能证明
   插图下载成功。HTTP 301 仍作为独立下载兼容待办。
8. 历史 WebDAV ECONNREFUSED：
   指向既有回环测试账号，未擅自删除配置，不当作 B4 反射失败。
9. 本轮开始后日志出现用户选文保存并重排成功、pager acknowledged=true。
   这是运行观测，不是本代理执行正文写回的验收。

## 已确认并修复

### 字体名称和 FontProvider 开关

- `tk3.h([bk3]) -> ek3` 仍为原 `withName` 行为。
- FamilyName.getName 被 R8 内联，`bk3` 仅保留 `b:String`；原 profile 没有这个
  类型/字段，现有 XposedHelpers getter-to-field 兜底读不到字体名称。
- 添加 FamilyName=bk3、name=b；继续使用原有读取和字体选择逻辑。
- 与 B1 withName 的 REA Java/MT smali 对照：B4 `h` 是 embeddedFonts，
  `i` 是 buildInFonts。原 profile 两项相反，已就地纠正，没有修改业务条件。
- 证据：`audit-r213-font-withname*`、`audit-r214-family-name*`、
  `audit-r215-old-font-withname.json`、`audit-r216-old-font-withname-mt.json`。
- 验收限制：真实配置没有指定宋楷映射，不临时修改用户设置；
  JVM 验证同一 getter-to-field 桥可读取宋体/楷体名称，字体替换效果仍待专项验收。

### Sentry 崩溃上传门禁

- 宿主 App.onCreate 调用 `io.sentry.android.core.h1.b(App, uu)`；
  该方法内部执行 SDK 初始化，与原 SentryAndroid.init 对应。
- B4 仅绑定确切 App/config 两参静态 void 签名，复用原 isBlockingEnabled、
  settings bootstrap 和 replacement；没有 hook 整个 App.onCreate 来取消宿主启动。
- 对真实 App.onCreate 调用方反优化；旧版路径不变。
- 增加命中日志区分“注册了 hook”和“实际跳过初始化”。
- 证据：`audit-r222-sentry-entry*`、`audit-r222-app-oncreate*`、
  `audit-r223-sentry-init*`、`audit-r224-package.json`。
- 保护边界：本轮恢复的是已确认的 SDK 初始化门禁，不能据注册日志推断所有 SDK、
  性能采集、NDK preload、其它上报或已运行旧 SDK 状态都被完全阻断。

### 逐章加载刷新协程入口

- 延迟链 `replayOnDemandJumpWhenSpineCacheReady` /
  `rebuildOnDemandVirtualWindow` 最终调用 ReaderHook.RefreshRuntime。
- getJob 已变为 `s2e.i(CoroutineContext) -> Job`，原 profile 未绑定。
- withContext 已有 jtd.q 的正确 methodOwner，但调用点仍先加载 BuildersKt=ltd
  再找方法；ltd 不持有 withContext。
- 两处改用 hostStaticMethod；添加 getJob 的确切 owner/name 及 Job.isActive=g。
  原 session generation、lease、parent Job、取消与页表逻辑保持不变。
- 证据：`audit-r230-vm-scope.json`、`audit-r231-vm-scope-mt.json`、
  `audit-r232-context-job*`、`audit-r232-withcontext*`、
  `audit-r233-job-active*`、`audit-r233-job-complete*`。
- 验收限制：普通启动不会触发逐章下载后的刷新，不能以启动无异常声明该闭环已通过；
  后续需用专用逐章加载测试书验证下载、重排、跨章和取消。

## 确认遗漏、尚未修复

1. CSS 预览及保存后缓存失效：
   `HostStyleSheetPreview.kt` 仍加载旧 StyleSheetsProvider、Cache 真名并直接调用
   getMRuleSetCache/attach/RuleSet.Companion.merge。B4 provider 为 zna，
   静态 attach=a，cache 为 lazy `zna.a -> nq8`。
   `EditableCssPreview.kt` 会静默丢弃原生计算值，基本 CSS 编辑不一定失败；
   但保存后的 invalidateHostCssPreview 会产生缓存刷新 warning。
   Cache.invalidate(key) 已被内联；nq8.c 是清空全缓存，不能冒充原单键失效。
   下一轮应对照旧实现恢复单键失效及计算值，不能轻率替换解析算法或清空整库。
   证据：`audit-r219-css-provider*`、`audit-r220-css-cache*`、
   `audit-r221-css-attach.json`、`audit-r221-cache-evict*`。
2. SVG 预览及 SVG 封面/横幅校验：
   `HostSvgPreview.kt` 仍按 `com.caverock.androidsvg.SVG` 及旧 getter/render 方法加载。
   B4 真名不存在；真实 SVG parser=kk9、文档对象=xi9，多个 API 已内联。
   影响结构页 SVG 缩略图/大图及 EpubBookPanel.requireImageReadable，
   不是 PNG/JPEG 或普通封面路径。已有安全限制保留，不为“适配”删除外部资源门禁。
   证据：`audit-r217-svg-parser*`、`audit-r229-svg-object*`。
3. 原生文字指标捕获：
   `BookImageDialogStyle.capture`、`EmbeddedHostUi.captureTypography` 仍用 loadClass
   读取 Composer/MaterialTheme/TextUnit 真名并直接反射旧 getter，B4 会静默走默认指标。
   宿主 Typography/SpanStyle/ParagraphStyle/FontWeight 的字段映射已存在，
   但两处捕获没有接到该路径。影响字号、行高、字距、字重对齐，不是编辑点击回调。
4. 宿主 UI 缩放：
   `StructureBookDetailDividerStyle.scale` 仍读取未映射的 AppScaleManager 真名，
   返回 fallback=1f。需确认 B4 实际缩放入口，不能把全局文字缩放与 dp/density 混用。
5. 内置字体列表：
   `ReaderFontCompletionHook` 仍 hook getBuildInFonts，B4 已删除该 getter。
   hookAllMethods 返回空集合仍被安装报告记为成功；withName 名称修复不自动恢复列表标签
   和被用户映射后的列表预览。B4 tk3.b/c 是 Composable 字体构造，不可直接当旧 List getter。

## 静态审计方法与局限

- 新增只读工具 `tools/b4_migration_audit.py`，导出 profile target/member 检查、
  源码真名候选、原始反射位置及完整日志，不执行数据库或宿主业务方法。
- B4 当前 360 class、159 alias，全部目标存在；映射成员沿真实 rawSup 继承链检查。
- 首次把 TextNode.getWholeText=L 误列缺口，因为特征清单的 sup 为归一化 `*`；
  REA/MT 确認该方法来自父类 vs5.L，无需修改 profile。审计器改读 rawSup，
  并新增继承/缺失/循环三个 Python 回归。
- 字面量候选最终 136 类，混有旧版分支、常量、现有 B4 替代入口与未执行功能，
  **不能宣称 136 项故障，也不能把缺口数清零当成全功能完成**。
- raw getMethod/getField 位置约 243 处，仍需按调用可达性和参数签名逐项核实；
  本轮不进行全文件字符串替换。

## 测试与后续

- 第一批字体/Sentry 修补：JVM 119 项通过，release 构建成功，已部署。
- 该批冷启动 PID 19552：B4 `360/360`、安装 `133/133`、
  crash-upload allowed=false / blocking=true；没有新的 FATAL 或 callback 异常。
- 后续加入 split coroutine owner、开关放行/阻断测试及实际命中日志。
- 最终 JVM 121 项测试，0 failures、0 errors；Python 审计/日志回归 5 项通过。
- 最终 `:app:testDebugUnitTest :app:assembleRelease` 成功，5 分 7 秒；release 已安装，
  版本仍为 2.3.9 / 71，APK SHA-256
  `00b58e07d4eff489a2f269f969e894c64581f4a3b4ce0baec8ea581c6242fd6a`。
- 最终 PID 8462：B4 360/360、unresolved=0、hooks 133/133，
  实际命中 `B4 Sentry init suppressed (upload disabled)`；
  当前 module/logcat 未出现 FATAL 或本轮 callback 异常。
- 最终静态检查 1029 个方法、996 个字段，全部映射目标及继承链上的成员存在；
  仍不代替签名、调用可达性和真实功能验收。
- 最终记录：`audit-r237-final/`、`audit-r234-followup-check/final-verification.json`、
  `audit-r234-followup-check/final-deployment.json`；
  构建日志为 `round13/audit-r235-final-build.log`。
- prefs 对比只出现原有随机背景自动刷新带来的 profile_background_image 变化；
  没有回滚自动刷新结果，没有改字体、账号、书源或上传许可。
- MT 多次检查 PID 均为 18580，uptime 连续增加。
- 优先顺序：CSS 单键缓存/原生计算预览 → SVG 原生 API → 字体列表/指标/缩放 →
  专用逐章加载完整回归 → 账号/网盘真实操作专项回归。
