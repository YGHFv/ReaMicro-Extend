# 模块体积分析与优化方案

分析日期：2026-11-16。使用本地 REA 6.7.0 和 MT MCP 核对宿主，再统计实际安装模块与本次 release APK 的 ZIP、DEX 数据。

## 实测结果

设备原来安装的模块与修改前本地 release APK 的 SHA-256 一致，都是 **24,878,715 字节，23.73 MiB**。这次修改后的 release APK 总大小仍是 24,878,715 字节，但压缩后的 DEX 减少了 5,787 字节。APK 的条目对齐及签名等开销抵消了这个小幅变化，删除一个功能不能解决整体体积问题。

以下为本次构建后的统计。MiB 按 1,048,576 字节计算，百分比按 APK 文件大小计算；条目压缩大小不包含 ZIP 目录、对齐和 APK 签名块。

| 内容 | APK 内大小 | 占 APK 比例 | 说明 |
| --- | ---: | ---: | --- |
| DEX | 18.43 MiB | 77.7% | 6 个 DEX，解压后约 50.54 MiB |
| 原生库 | 2.32 MiB | 9.8% | 仅 arm64-v8a，共 3 个库 |
| res 文件 | 1.28 MiB | 5.4% | 231 个文件，包含图片、XML 等 |
| assets | 0.84 MiB | 3.5% | 24 个条目，包含遮罩模板、书源、Root 模块包等 |
| resources.arsc | 0.68 MiB | 2.9% | 编译后的资源表 |
| 其他条目及打包开销 | 约 0.18 MiB | 约 0.8% | 资源元数据、签名、目录、对齐等 |

最大原生库为 `libarchive-jni.so`，约 1.94 MiB；`libdexkit.so` 约 0.38 MiB。当前已经只打包 arm64，不能通过再排除其他 ABI 获得收益。

## 为什么代码占用这么大

`app/build.gradle.kts` 的 release 配置没有开启 `isMinifyEnabled` 或 `isShrinkResources`，也没有使用现有的 `app/proguard-rules.pro`。本次构建经过 D8 转换和 DEX 压缩，未执行 R8 代码裁剪，因此依赖中未使用的类和方法也会保留。

实际 DEX 包含 **38,434 个类、251,516 个方法定义**。以下按包统计类数及指令字节，帮助确定优化方向。指令字节不包含字符串池、类型表、调试数据等，也不是压缩后的 APK 体积，不能直接作为缩包收益。

| 包或组件 | 类数 | 方法数 | DEX 指令大小 |
| --- | ---: | ---: | ---: |
| Compose | 11,124 | 72,401 | 约 4.07 MiB |
| 模块自身 `com.reamicro.fix` | 7,219 | 40,427 | 约 3.63 MiB |
| Miuix | 2,357 | 12,173 | 约 1.31 MiB |
| 其他 AndroidX | 3,621 | 24,123 | 约 1.15 MiB |
| `com.google` 下的依赖 | 2,531 | 18,912 | 约 0.66 MiB |
| Rhino | 676 | 6,213 | 约 0.45 MiB |
| Hutool | 1,336 | 10,649 | 约 0.40 MiB |
| Legado 适配代码 | 588 | 5,728 | 约 0.29 MiB |

模块同时使用 Material3、Miuix UI、图标、玻璃效果和导航模块。未启用裁剪时，这些组件会带入大量非当前页面必需的代码。

Legado 解析器还引入 Rhino、Gson、OkHttp、Jsoup、XPath、JsonPath、Commons Text、Hutool Crypto、Glide、Media3 和 libarchive。这些依赖支持书源和脚本功能；其中一部分通过脚本或反射调用，需要先确认使用边界。

## 推荐实施顺序

### 1. 先启用保守的 R8 代码裁剪

这是优先级最高、最可能产生明显收益的方案：

- release 开启 `isMinifyEnabled = true`，接入默认规则与模块规则。
- 第一阶段使用 `-dontobfuscate`、`-dontoptimize`，先移除不可达代码，再逐步评估优化和改名。
- 保留 libxposed 入口、清单组件、模块自身反射访问成员、JNI 接口及脚本暴露 API。
- 补全热重载对 Compose、协程内部成员的保留规则。例如 `ModuleUiRuntime` 按名称读取 `AndroidUiDispatcher.Main$delegate`、`GlobalSnapshotManager.started`，还访问字体缓存和运行时线程状态；现有规则未完整覆盖这些接口。
- 第一阶段可保守保留模块自身类和成员，让 R8 优先裁剪第三方依赖，再根据 `usage.txt`、`seeds.txt`、`configuration.txt` 缩小保留范围。
- 代码规则验证后开启 `isShrinkResources = true`，为按名称、反射及宿主上下文读取的资源补保留清单。

`third_party/legado/android/consumer-rules.pro` 当前整体保留脚本绑定、Legado、Rhino 和 Hutool。书源脚本能通过 `Packages` 动态访问 Java API，不能直接删除这些保留规则来追求体积。

验收应覆盖模块独立页面、宿主注入页面、原进程热重载、在线书源脚本、章节下载、EPUB 编辑、压缩文件导入、自动任务及 JNI 调用。实际收益必须以裁剪后的 APK 和运行验证为准，本次没有启用 R8，也没有承诺缩至某个固定大小。

### 2. 精简 UI 与解析依赖

在 R8 结果的基础上评估：

- 统一 Material3 与 Miuix 的重复控件使用，逐步迁移当前仍依赖 Material3 的弹窗、编辑器和工具栏。
- 按实际图标使用情况保留必要实现，避免整体保留未用图标；先查看 R8 已删除的内容，避免重复改造。
- 对 Legado 的 Glide、Media3、Commons、Hutool 等依赖建立调用清单，分清 Java 直接调用和书源脚本调用，再替换只需少量能力的依赖。
- 检查 Scripta、导航及窗口尺寸相关传递依赖，移除已经不再使用的模块。

模块为了原进程热重载，明确要求自己的 Compose 和协程运行时使用独立 ClassLoader。把模块依赖改成 `compileOnly`、直接复用宿主运行时，会改变现有隔离与回收机制，需要单独的架构设计和兼容验证。

### 3. 最后处理资源与原生库

- 逐项检查 `assets/epub_header_mask` 中的 PNG 模板，适合的文件改为无损 WebP或按实际显示尺寸降低分辨率。
- 删除确认未使用的资源，并评估仅保留需要的资源语言；动态加载资源应先建立引用清单。
- libarchive 如需缩小，应重新编译并只保留产品实际支持的格式，验证 ZIP、RAR、7z 等现有导入能力。
- `.so` 已以未压缩条目保存，支持 Android 直接加载。强制压缩可能使 APK 变小，却增加解压与安装开销，应分别测量下载大小、安装占用和加载表现。

资源文件、assets 和资源表合计约 2.79 MiB，占 APK 约 11.8%。图片优化属于后续工作，主收益应从 DEX 裁剪寻找。

## 本次功能修改与验证

- 删除模块“行商结束时间”开关、偏好读取、显示 Hook，以及专用 `home.merchant` 查询和验证契约。旧偏好即使曾为开启，也不再被读取。
- 阅读补全的开关名称由“翻页动画调节”改为“动画调节”。
- 自动任务直接尝试祈禳，失败后记录原因并继续行商或每日轶闻；未增加材料库存预检查。
- 行商持久化祈禳跳过状态，出发重试不重复祈禳，并兼容旧版 `materials_unavailable` 记录。
- `:app:testDebugUnitTest` 通过，43 项测试、0 失败；新增 6 项祈禳流程测试。
- `:app:assembleRelease` 通过，APK 已覆盖安装。日志确认同一阅微进程完成 API102 热重载，233/233 项 Hook 安装成功，MT 进程保持不变。
- 自动任务行为使用本地模拟服务器验证，未手动触发真实账户祈禳或消费材料。

## 证据与本地文件

REA 6.7.0 对当前安装的宿主进行了 JADX 分析，MT MCP 使用工作区 `vsnyq0mt` 核对相同逻辑：

- 祈禳响应 `ga8` / `PrayTaoistBlessingRes` 包含 `success`、`message`、`blessing`、`todayPrayCount`。
- 商人界面 `a3c.y` 读取行程 `endTime`，按 `Asia/Shanghai` 生成“预计 HH:mm 完成”。REA 对该方法返回完整 smali，MT 伪代码可读，两者互相核对。
- REA 的模块清单分析确认 APK 条目组成，再使用 ZIP 和 DEX 二进制统计获取上面的数值。

原安装模块 SHA-256：
`45a003e043dcd261294f762bf7a2e2012a2617fe8fd541a7be766bbc45effc98`

本次 release SHA-256：
`b92464a8cdbca8740805b2ae033a21f0150241af765e32d03bd20a83814f087b`

完整分析输出保存在本地 `build/task-20261116/`，包括 REA/MT JSON、修改前后体积统计、原始文件备份和选择性提交补丁；该目录不提交。APK 位于 `app/build/outputs/apk/release/app-release.apk`。
