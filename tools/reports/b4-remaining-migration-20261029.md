# B4 剩余迁移补齐与统一验证（2026-10-29）

## 范围与约束

- 承接 `9f7e25d` 的五项明确遗漏：CSS、SVG、文字指标、UI 缩放、内置字体预览列表。
- 全部接线和测试完成后才执行统一 `testDebugUnitTest + assembleRelease`；
  没有按子功能反复打包。后续重复执行的仅为隔离验证 runner。
- 所有既有修改保留，UTF-8 和原有换行不变；B4 profile 原有混合换行保持。
- 仅本地提交、不推送、不修改版本号、不重启手机、不改动 MT。
- 截图未载入对话；本轮没有创建或写入用户图书、账号、字体设置和书源。
- 基线摘要：`%TEMP%/reamicro-analysis/runtime/b4/remaining-20261028/baseline.json`。
- 独立取证任务未返回有效结果，未采用其结论；最终实现由主代理完成并双源确认。

## CSS 原生预览与单键缓存失效

- `HostStyleSheetPreview.kt` B4 分支进入 `B4HostCssPreview`，旧版路径保持。
- 宿主解析仍使用 `zna.a(String[]) -> yna`；规则展开、变量求值和属性合并仍调用
  `xyd.J0(ArrayList, Map)`，没有替换 CSS 解析器或引入新求值算法。
- B1 `RuleSet.Companion.mergeCustomProperties` 已内联删除；
  仅恢复其按 selector level 排序、custom property、`!important` 优先级薄包装，
  空白裁剪继续使用 B4 `vhd.c`。
- `CSSProperty.getProperty/getInitialValue/getActualValue` 分别为 e/c/a。
  旧 `isValueProvided` 的初始值与 registry 默认值比较保持；单位值比较保持 number 语义。
- B4 已删除 summary 参数，说明文字复用模块现有 `CssPropertyDescriptions`，不伪造旧 getter。
- 单键失效恢复旧 RealCache.invalidate：expireEntries → entries.remove(key) →
  writeQueue/accessQueue.remove(removed)。不调用 invalidateAll，不清空其它 CSS 缓存。
  B4 已删除 eventListener/onEvent，不为迁移添加不存在的通知。
- 取证：`rem-css-old-merge-dot`、`rem-css-old-custom`、`rem-css-old-invalidate`、
  `rem-css-merge-b4`、`rem-css-rule-init`、`rem-css-registry`、`rem-css-trim` 等。
  所有条目保存在 `%TEMP%/reamicro-analysis/runtime/b4`。

## SVG 原生预览

- 保留文档大小、边长、DOCTYPE/ENTITY、外部引用及 @import 的原安全限制。
- 真实 decoder 是 `kqa.b(ut0) -> rpc`；其分配的 parser=kk9，
  文档是 pc1 的 SVG 分支，xi9 是容器接口而非文档实体。
- 使用仅提供 InputStream 的 ut0 代理让原生 decoder 自行分配和初始化 parser。
  没有 Unsafe/allocateInstance，没有添加或替换 SVG 解析库。
- 尺寸仍取宿主 pc1.w 的文档尺寸，viewBox 从 ui9.o 读取。
- 渲染使用 `oqa.b(Canvas)`，内部仍为宿主 SVGAndroidRenderer.renderDocument；
  在同样尺寸的 Picture 上录制，保留旧 renderToPicture 的行为。
- 取证：`rem-svg-realsource*`、`rem-svg-pull-mt`、`rem-svg-dimensions*`、
  `rem-svg-render-body*`、`rem-svg-render-ctor*`、`rem-svg-old-picture*`。

## 原生文字指标与缩放

- 新增 `HostTypographyMetrics`：通过 MaterialTheme local 获取原生 Typography，
  通过已有 TextStyle.spanStyle/paragraphStyle 字段映射读取字号、行高、字距、字重。
- TextUnit 使用宿主 y5b.b/c 读取 type/value，sp/em/unspecified 不混用。
- 像素继续调用 pc2.D0(long)，保留非线性 Android fontScale 转换，不直接乘 fontScale。
- BookImageDialogStyle 和 EmbeddedHostUi 共享同一指标读取；旧版分支及各自默认字重保持。
- UI 缩放调用 yyd.f(1f) 的原 getUdp，复用真实缩放因子的懒初始化；
  不读取未初始化的静态字段，不重复推导屏幕设计宽度。
- 取证：`rem-metrics-*`，包括局部密度、Typography 字段、Span/Paragraph 字段、
  TextUnit 类型、非线性 Density 转换及 getUdp。

## 内置字体列表

- B4 将 getBuildInFonts 的两项 List<Pair> 内联在 rd8 的内置字体页，
  tk3.b/c 是 Composable font family，不作为旧 List getter 的替代。
- 原生列表 ei6.y 的字体预览分支调用 `i1e.a(LazyItemScope, Pair, Composer, flags)`。
- B4 仅 hook 该确切预览行：按原宋楷匹配和 resolveFontFamily 生成新 Pair，
  保留原文字标签并附映射名称；family 参数用于原生预览字体。
- 清除 Pair 对应的 changed bits `0x70`；保留布局、原列表、设置开关和点击回调，
  不修改 FontProvider 的 songFont/kaiFont，也不短路行内容。
- 旧版继续 hook 原 getBuildInFonts；不会把返回空 hook 集合当作 B4 已迁移。
- 取证：`rem-fontlist-root-content*`、`rem-fontlist-root-lazy*`、
  `rem-fontlist-items*`、`rem-fontlist-rowexact-mt`、`rem-fontlist-row-callers`。

## 统一验证

- 完成五项代码接线、profile 和回归测试后统一编译。
- `:app:testDebugUnitTest :app:assembleRelease` 成功，2 分 28 秒。
- JVM：129 项，0 failures、0 errors。覆盖单键缓存/队列、important 优先级、
  SVG 安全限制与原生入口、字体预览 Pair、原生字段指标及非线性像素换算。
- Python 审计/日志回归：5 项通过。
- 静态 profile：360 class / 178 alias / 1046 method / 1013 field，
  映射目标与继承链上的方法字段全部存在（0 missing）。
- 静态覆盖不是全功能运行证明；132 个字面量候选仍包含旧分支、常量及其它功能，
  没有将其数量清零作为本轮完成标准。
- Release 保持 2.3.9 / 71，SHA-256
  `79dc3125d31f2874f775f692304e3a2c21477aa8d3d8402f4036f913022ebca9`。
- 构建日志：`round13/rem-final-20261029-build.log`。

## 隔离原生运行验证

- `tools/B4RemainingMigrationProbe.java` 在独立 app_process 中加载本地模块及真实 B4 APK，
  仅操作 `/data/local/tmp/reamicro-remaining-*` 专用测试文件，不加载宿主 Application。
- 文字指标：原生 BodyLarge=16sp，Density=3/fontScale=1.2 时为 56.4px；
  本测试进程内已初始化 scale=.8 时读回 .8，证明不是固定 1f fallback。
- CSS：`:root --accent` 正确求值得到 p.color=#ff0000、font-size=18px。
  在真实 nq8 中加入两个专用 key，失效 first 后 second 的 entry 身份不变；
  first 在实际 write/access queue 中均被移除。
- SVG：120x60 的红色矩形预览输出 96x48，中心像素 `ffff0000`，
  decoder、尺寸读取、Picture/Canvas render 完整运行通过。
- 初次 shell runner 缺少 Zygote 系统 Typeface 映射导致渲染 NPE。
  只在 runner 调用 `Typeface.loadPreinstalledSystemFontMap`；
  没有修改 SVG 业务代码。一次字体预加载超过原 120 秒，失败证据保留。
- 最终 runner `REMAINING_NATIVE_PROBE_PASS`，退出码 0；
  前后阅微 PID 8462、MT PID 18580 均未改变。
- 最终证据：`rem-native-fifth-20261029/native-probe.log`、
  `rem-native-fifth-20261029/probe-metadata.json`。

## 安装与验收边界

- 已覆盖安装最终 release，未强制重开阅微。
- 安装时前台为起点 GameBrowserActivity，安装后前台保持同一页面；
  不打断用户、不切到阅微、不重启或改动 MT。
- 安装记录：`remaining-20261028/module-installed-no-foreground-switch.json`。
- 当前阅微进程仍加载旧模块，不能把旧 PID 的日志当作这次 hook 安装成功。
- 内置字体列表的真实界面效果、封面弹窗 metrics 捕获的实际组合路径，
  需下次用户正常重开阅微后专项 UI 验收；对应代码、静态签名和 JVM 已补齐。
- 本轮完成五项明确迁移缺口，不宣称账号、搜索、逐章下载及全部功能回归完成。
