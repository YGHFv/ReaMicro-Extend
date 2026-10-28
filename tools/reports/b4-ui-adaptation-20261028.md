# B4 UI 适配检查点（2026-10-28）

## 本轮约束

- 保留所有既有修改和未跟踪文件，仅本地提交，不推送、不改版本号。
- 手机没有重启，只在安装模块后重新启动阅微以加载新代码。
- MT 不关闭、不重启、不修改 APK，不创建编辑会话；仅调用现有 MCP 工作区的只读 DEX 工具。
- 截图只保存在本地，通过 UI XML 和局部像素统计验证，不载入对话。
- 既有脏文件基线：`%TEMP%/reamicro-analysis/runtime/b4/ui-adaptation-20261028/baseline-hashes.json`。

## 原因与修复

1. 高亮页「补全计划」：
   B4 分支跳过旧的卡片间距包裹。恢复 Column 包裹，B4 上下各 12dp，其余宿主仍保留原间距。
2. 个人页「发现」：
   简化适配只输出文字和字符箭头，丢失图标、分隔线和右侧权重。
   恢复旧双栏结构，适配 B4 两参 Row 权重、反序 Spacer 和九参原生矢量构造器。
3. 发现页：
   移除简化 View 的调用，重新使用既有列表、宫格、顶栏操作、分类栏和书源状态。
   只桥接 B4 Row/Column/LazyRow/Text/AndroidView ABI，不修改书源、分页、筛选或下载逻辑。
   顶栏 padding getter 需要解析混淆成员，否则列表从 y=0 开始覆盖顶栏。
   按用户要求，作者与连载状态、字数、平台放在同一行，下一行只显示最新章节。
4. 详情「同步」：
   适配误把右侧存储名称移到 ListItem supporting 槽。恢复右侧大小、存储图标、名称组合。
   存储类型名称与 B4 BookSyncSizeItem 的 1/2/4 分支一致。
5. 本地导出：
   恢复旧 authCardColumnModifier 的外层左右和底部 16dp 包裹，导出实现、目录选择和上传逻辑不变。
6. 模块搜索入口：
   B4 菜单顶栏仍有 DarkMode/LightMode，之前改写目录「搜本书」是错误回退。
   改为仅绑定 ReaderMenuActions 的 byte=3 重组分支、ReaderMenuThemeClick 的 byte=2 深浅色回调。
   目录原生搜索不再改写。多接口 ComposableLambda 按 Function3 的真实 r 方法调用。
7. 详情箭头：
   编辑用字符箭头，同步用缩小的 20dp 矢量，出版用默认 24dp 矢量。
   编辑和同步共同使用出版的 NavigateNext、SurfaceContainerHighest、默认 modifier 位 4。
   两行右侧 padding 与出版一致为 16dp；不改点击回调。

## REA / MT 对照

- REA：`.tools/rea-6.7.0`，JADX headless MCP 0.7.1；JAR 摘要按现有脚本固定校验。
- 宿主：本地 RM2.3.3.B4.APK，SHA-256 `2aba06c1f870f57c12bdd3ec71f6fc95837db34f2a38363f07a98c900fc0da1b`。
- MT：现有工作区 `gv89uj9u`；终端访问当前 MCP 端点，仅 DEX 搜索、读取、outline、xref、continue。
- 对照成员：Community=t2e.a、RowScope=h79.b、Spacer=qfd.b、Box=bs0.a、
  VectorBuilder=km4、VectorPathBuilder=dr1、LazyRow=y5e.b、AspectRatio=pvb.a、
  PaddingValues=ie7.d/a、ComposableLambda=io1、Function3.r、
  ReaderMenuActions=d10.y/a、ReaderMenuThemeClick=cv2.d、Search=xs4.d、
  BookSyncSizeItem=ard.f、出版行=ard.e、NavigateNext=mod.i。
- REA 的 `defpackage` 仅为展示前缀，没有写入 profile。
- 完整独立证据：`%TEMP%/reamicro-analysis/runtime/b4/ui-r209-*` 至 `ui-r265-*`。
  部分早期候选不正确，保留失败记录；最终 AspectRatio 是 pvb.a，不是只做 scale 的 s29.a。

## 最终验证

- `:app:testDebugUnitTest :app:assembleRelease`：成功，最终构建 2 分 11 秒。
- JVM：109 项测试，0 failures、0 errors；新增 UI 合同检查，不放宽既有 shape 资产测试。
- 最终构建日志：`%TEMP%/reamicro-analysis/runtime/b4/round13/ui-r266-build.log`。
- Release：`app/build/outputs/apk/release/app-release.apk`，版本仍为 2.3.9 / 71。
- 已安装并只重启阅微进程；最终运行日志没有本轮 FATAL 或 failed in callback。
- MT PID：本轮检查前后均为 18580；设备 uptime 连续增加，无重启。
- 高亮入口：标签栏底边 y=1884，卡片顶边 y=1920，上间距 36px；
  卡片底边 y=2124，包裹底边 y=2160，下间距 36px，即本机上下各 12dp。
- 发现入口左右各有权重 filler、原生箭头，右侧发现 ImageVector 已实际渲染。
- 发现列表、宫格切换、筛选弹窗、返回均完成实机检查；
  最终列表作者行示例：「小锅不吃葱姜蒜 · 连载 / 102.9万字 / 起点中文网」。
- 备份页导出/阿里云/115 卡片左右可点击边界统一为 `[48,1172]`。
- 同步行 label、大小、存储名 y 边界一致；图标仍在右侧组合内。
- 模块菜单「全文搜索」点击实际打开独立搜索页；
  目录「搜本书」仍打开原生搜索框，二者均实机确认。
- 箭头像素验证：本地 `detail-arrow-verification.png` 内三处 72x72 区域；
  三者前景均为 292 像素，局部边界均为 `(26,17)-(47,52)`，逐像素形状集合完全相等。
  图片未载入对话。对应详情 UI：`round2/ui-r267-detail-arrows.xml`。

## 验证范围

本轮验证 UI 布局和入口分离，不进行真实书籍导出、上传、解除关联，
也不把搜索入口冒烟测试当作全文搜索结果、跳转和高亮的完整回归。
工作区其余既有适配修改保持原样，不因本轮局部检查声明全功能适配完成。
