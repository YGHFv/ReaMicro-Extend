# 代码结构

本文记录 2026-08 重构后的包划分、分层约定，以及重构用到的可重放工具。

## 分层

```
com.reamicro.fix
├── core/        宿主互操作基建
├── online/      在线补全引擎（epub / search / download）
├── cloud/       云盘（webdav / local）
├── association/ 关联补全搜索
├── settings/    模块设置的键、快照与读写
├── logging/     日志策略
├── ai/ tts/ notification/ importer/ reader/ compat 之外的功能模块
└── hook/        宿主 hook 与 UI 注入
```

约定：

- **宿主类名只出现在 `core/HostClasses.kt`。** 本模块所有功能都靠反射调用宿主类，
  宿主一升级、类名一变，功能就静默失效。225 个类名常量集中在这一个文件里，
  由 `HostClassesTest` 逐一钉死取值。别处再写 `"app.zhendong.*"` 字面量属于回退。
- **`hook/` 只做 hook 与 UI 注入。** 能脱离 hook 实例运行的逻辑应该在 `online/` 或
  `cloud/`，那里可以直接写 JVM 单测。
- **`online/` 与 `cloud/` 不反向依赖 `hook/`。** 需要共享的横切能力（日志）放
  `logging/`。

## hook 的组织方式

三个大 hook 按功能簇拆成同包扩展函数文件：

```
hook/
├── WebDavDriveHook.kt              类声明、状态字段、install()
├── WebDavDriveHook.OnlineDownload.kt
├── WebDavDriveHook.OnlineSearch.kt
├── WebDavDriveHook.OnlineEpub.kt
├── WebDavDriveHook.WebDav.kt
├── WebDavDriveHook.LocalLibrary.kt
├── WebDavDriveHook.HostHooks.kt    所有 hookXxx() 安装点
├── WebDavDriveHook.HostUi.kt
├── WebDavDriveHook.Support.kt
├── webdav/                         上述文件共用的常量、类型、扩展
├── ReaMicroSettingsHook.*.kt + settings/    同上
└── ReaderHook.*.kt + reader/                同上
```

簇文件里的函数形如 `internal fun WebDavDriveHook.foo()`。这样拆的原因：

- Kotlin 没有 partial class，而这些函数共享大量 hook 实例状态，拆成协作者类需要
  先解开状态耦合——那是独立的一步，不该和「把文件变小」混在一次改动里。
- 扩展函数拆分是纯机械变换，编译器能校验全部引用，风险可控。

三处 Kotlin 语义限制导致的额外安排：

1. companion object 的 private 成员对扩展函数不可见 → 常量提升到 `hook/<name>/`
   子包的顶层 internal 声明，各簇文件通过包级 star import 引用。不放同包顶层是因为
   `hook/` 里已有文件使用 private 顶层常量会冲突，而 Kotlin 不允许对 object 做
   star import。
2. 成员扩展函数（`private fun String.foo()`）只在类体内可见 → 同样提升到子包。
3. `inner class` 需要外部实例，留在原类里；簇文件用显式 import 引用其类型。

## 安装自检

`core/HookInstallReport` 给每个 hook 安装动作登记结果，启动时打印
`hook installed N/M, failed: [...]`，设置页「关于 → 模块自检」可查看按功能分组的
明细。

宿主升级后先看这一行：数字掉了就知道哪个 hook 没装上，不必靠功能表现反推。
单个 hook 抛异常不会中断后续 hook 的安装。

## 重构工具

`tools/` 下的脚本都是幂等可重放的，配置驱动：

| 脚本 | 作用 |
| --- | --- |
| `split-hook.mjs <config>` | 通用的大 hook 拆分流水线，配置见 `split-config/` |
| `split-webdav-drive-hook.mjs` | WebDavDriveHook 的专用版本（先于通用版写成） |
| `extract-hook-cluster.mjs` | 按函数名清单把成员搬成扩展函数 |
| `hoist-nested-types.mjs` | 嵌套类型提升到子包 |
| `hoist-member-extensions.mjs` | 成员扩展函数提升到子包 |
| `find-pure-functions.mjs` | 用编译器判定哪些函数不依赖 hook 实例 |
| `extract-engine.mjs` | 把纯逻辑函数搬进引擎包并自动补 import |
| `move-packages.mjs` | 按映射表搬包并更新 import |
| `fix-imports.mjs` | 按编译报错自动补 import |
| `gen-settings-contract-test.mjs` | 生成设置项键名/默认值的锁定测试 |

搬迁类脚本都做同一件事保证「只搬不改」：函数体反缩进后再重新缩进回去，与原文逐
字节比对，不一致直接中止。三引号原始字符串内部一行不动——那里的空格是内容。

`find-pure-functions.mjs` 的判定方式值得一提：它先去掉全部扩展函数的接收者，编译，
按报错把确实需要接收者的函数逐个还原，重复到收敛。因此「这个函数不依赖 hook 实例」
是编译器给的结论，不是人工判断。

## 验证流程

每次改动都要跑：

```
./gradlew :app:testDebugUnitTest    # 单测
./gradlew :app:detekt               # 体积/复杂度基线，report-only 不阻断
./gradlew :app:assembleRelease      # 编译 + 签名
adb install -r && adb logcat | grep "hook installed"   # 装机自检
```

`detekt` 只开 `LargeClass` / `LongMethod` / `TooManyFunctions` 三条体积规则
（配置见 `config/detekt/detekt.yml`），用于对比重构前后是否收敛，不做风格检查。

## 已知且刻意保留的告警

- `XposedModuleSettings.readSnapshot` 273 行：这是「一次性构造 70 字段 data class」
  的必然形状，拆成 part1/2/3 只会把一个可一眼核对的表达式变成跨函数的状态机。
  真正的风险（改错键名导致用户设置丢失）由 `ModuleSettingsContractTest` 覆盖。
- 若干 `openXxxDialog` 超过 120 行：它们是线性的 View 构建代码，拆开反而更难读。
- `ReaderDialogueHighlightHook`、`EpubWebEditorPanel`、`AccountCompletionController`
  仍超阈值，属于下一轮可做的对象，本轮未动。
