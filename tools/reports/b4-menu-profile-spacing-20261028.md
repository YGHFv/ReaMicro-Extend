# B4 选文菜单与个人页背景适配（2026-10-28）

## 约束

- 全部既有改动和未跟踪文件保留；仅本地提交，不推送，不改版本号。
- 只重新加载阅微进程，没有重启手机；没有关闭、重启或修改 MT。
- MT MCP 仅访问现有阅微 APK 工作区的只读 DEX 工具，无编辑会话。
- 截图保存在本地，通过实时 UI 树和局部像素统计验证，没有载入对话。
- 本轮初始源文件快照：`%TEMP%/reamicro-analysis/runtime/b4/round2/r208-baseline`。

## 原因与修复

1. 菜单全宽留白：
   B4 `kf0.j(List, boolean, int, boolean, Modifier, Composer, int)` 的第四参
   选择内容测量宽度及 `widthIn`，否则采用固定最大宽度。
   旧适配只在紧凑模式设为 true，普通换行菜单仍被拉满。
   普通 B4 换行菜单也启用该参数，清除对应的 `0x1C00` changed bits；
   定位 Modifier、选区、宿主气泡和箭头不替换。
2. 菜单横向间隔：
   按用户要求，只将普通 B4 单元宽度改为标题估算宽度加 32dp，
   范围 60 至 100dp；双字标签为 60dp，旧版和紧凑模式仍用原指标。
3. 左右边距不对称：
   总宽公式已含两侧各 8dp，原 Modifier 顺序却是 padding 再 width，
   导致额外 16dp 成为右侧尾部留白。
   调整为 width 再 padding，总宽只计一次；上下 10dp 箭头避让不变。
4. 编辑图标误用划线：
   B4 保留 `Outlined.Edit`，R8 删除了原 `Outlined` 接收者，
   实际方法为 `yfd.d() -> lm4`。旧的一参查找失败后借用了首个划线图标。
   profile 添加 `EditKt#getEdit` 的方法 owner/name，B4 按确切无参静态签名
   调用并检查 ImageVector 返回类型；其它版本继续用原一参调用。
   编辑标题和 `openNativeSelectionEditor` 回调不改。
5. 个人页背景白线：
   B4 的安装分支提前返回，漏掉了旧版已有的 `installProfileDividerHook`。
   重新接入同一 hook，保持个人页重组作用域及背景功能开关限制；
   B4 对已确认的 divider 方法反优化，避免内联绕过 hook。

## REA 与 MT 对照

- REA：`.tools/rea-6.7.0`，固定摘要的 JADX headless MCP 0.7.1。
- 本地宿主：RM2.3.3.B4.APK，SHA-256
  `2aba06c1f870f57c12bdd3ec71f6fc95837db34f2a38363f07a98c900fc0da1b`。
- MT：现有工作区 `gv89uj9u`，未打开或分析 MT APK。
- 选文气泡：`kf0.j`，REA Java 与 MT smali 均确认第四参分支。
- 个人页：`lx7.r`，REA smali 回退及 MT smali 均确认调用
  `p7d.c(Modifier, long, Composer, int, int)`。
- 编辑图标：两端均确认 `yfd.d()Llm4;` 的名称为 `Outlined.Edit`。
- 尺寸及 padding：两端确认 `layout.b.n(Modifier,float)`、
  `j1e.n(Modifier,float,float,float,float)` 按调用顺序追加 modifier。
- 完整静态证据：`%TEMP%/reamicro-analysis/runtime/b4/r208-*`、
  `r209-*-edit-icon.json`、`r211-*-size-n.json`、`r211-*-padding-n.json`。

## 验证

- 最终 `:app:testDebugUnitTest :app:assembleRelease` 成功，3 分 28 秒。
- JVM：116 项测试，0 failures、0 errors；包含边距顺序、原生测量宽度、
  编辑图标无参 ABI、旧版/紧凑模式指标及个人页 divider 作用域检查。
- 最终 release 已安装，版本仍为 2.3.9 / 71。
- 实机菜单最终宽度 785px；第一行两端图标中心距气泡边缘分别
  113px、114px，差 1px。横向图标间距由 222px 收紧为 186px。
- 七个按钮的完整标题仍存在；编辑与划线图标的前景像素集合不相同，
  点击编辑实际打开选文编辑框。没有输入或保存正文修改。
- 最终 UI 与像素指标：
  `round2/r211-menu-pass.xml`、`round2/r211-menu-pass.png`、
  `round2/r211-menu-pass-metrics.json`；编辑框为 `round2/r211-edit-dialog.xml`。
- 当前阅微 PID 的模块日志未出现本轮菜单 callback 异常或 divider 安装失败；
  日志为 `round3/r211-final-pid4450.log`。
- MT 进程在本轮多次检查中均为 PID 18580，手机 uptime 连续增加。
- 个人页白线恢复由静态对照及用户“基本上修好了”的反馈支持；
  后续一次抓图处于书架，不能作为个人页白线的独立像素验收。
- 验证编辑框后前台切到系统分享选择器，立即停止界面操作，没有关闭选择器。

## 范围

本轮只验收普通选文菜单的布局、图标和编辑入口，并恢复旧个人页 divider 接线。
不将入口冒烟测试当作正文编辑保存、高亮、上传、搜索或全部 B4 适配的完整回归。
本地提交仅覆盖本轮涉及的源文件、profile、合同测试和记录；
这些文件已有的相关适配修改一并保留，其它工作区改动不暂存、不清理。
