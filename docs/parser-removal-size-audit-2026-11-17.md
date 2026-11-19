# 书源增强解析移除与 APK 体积复核

复核日期：2026-11-17。检查提交 `d3a0db06b303aa10d0974a9148de51e8dd67c080` 的源码、`releaseRuntimeClasspath` 以及 GitHub CI #305 发布的实际 APK，并与 2.3.19 发布包对比。

## 移除情况

增强解析功能已移除：设置开关、隐藏解锁联动、专用登录与发现界面、网页桥接、搜索/目录/正文/图片调用分支、解析状态与会话对象，以及 `:legado-parser` 第三方模块均已删除。

实际 CI #305 APK 的 DEX 中，以下类定义数量均为 **0**：

- `io.legado`、`com.script`、`org.mozilla`、`cn.hutool`
- `com.bumptech.glide`、`androidx.media3`、`me.zhanghai.android.libarchive`
- `org.jsoup`、`com.jayway.jsonpath`、`org.apache.commons`、`com.google.gson`

DEX 字节扫描未找到 `EnhancedOnlineParser`、`OnlineParserMode`、`HostSourceBrowserBridge` 或 `Lio/legado/`。运行依赖树也没有相应解析依赖。APK 原生库仅有 `libandroidx.graphics.path.so` 与 `libdexkit.so`，`libarchive-jni.so` 已移除。

复核发现两处非执行代码残留：`assets/licenses/Legado-LICENSE.txt`（APK 内压缩后 12,127 字节）和为 Hutool 添加的 `META-INF/INDEX.LIST` 打包排除规则。本次补充删除。历史分析文档与 README 中对阅读书源格式的说明保留；原有书源格式兼容解析继续使用。

OkHttp 与协程仍有独立用途：手动关联的封面预览、地址校验使用 OkHttp，模块界面、异步任务及热重载使用协程。`smali-dexlib2` 用于宿主 DEX 索引、调用分析和续体生成，并引入 Guava。这些依赖有增强解析之外的实际调用。

## 实际发布包大小

统一使用 Release APK，MiB 按 1,048,576 字节计算。

| 发布包 | 提交/来源 | 字节 | MiB |
| --- | --- | ---: | ---: |
| 2.3.19 | `ci-2.3.19-294-1` | 17,602,404 | 16.79 |
| 增强解析移除前的 2.3.21 | `ci-2.3.21-304-1` / `c6d89af` | 24,854,569 | 23.70 |
| 增强解析移除后的 2.3.21 | `ci-2.3.21-305-1` / `d3a0db0` | 19,466,526 | 18.56 |

移除后相对 CI #304 减少 **5,388,043 字节 / 5.14 MiB / 21.68%**。相对 2.3.19 仍增加 **1,864,122 字节 / 1.78 MiB / 10.59%**。

发布包：

- [CI #305](https://github.com/YGHFv/ReaMicro-Extend/releases/tag/ci-2.3.21-305-1)
- [CI #304](https://github.com/YGHFv/ReaMicro-Extend/releases/tag/ci-2.3.21-304-1)
- [2.3.19](https://github.com/YGHFv/ReaMicro-Extend/releases/tag/ci-2.3.19-294-1)

## 剩余增量来自哪里

对 2.3.19 与 CI #305 实际 APK 的 ZIP 条目按压缩大小统计：

| 内容 | 2.3.19 | CI #305 | 差值 |
| --- | ---: | ---: | ---: |
| 根目录 DEX | 13,342,670 | 16,111,059 | +2,768,389 |
| 原生库 | 405,080 | 405,080 | 0 |
| assets | 1,699,365 | 787,971 | −911,394 |
| res | 1,345,654 | 1,339,852 | −5,802 |
| resources.arsc | 700,012 | 710,732 | +10,720 |

表中不包含 ZIP 目录、条目对齐和 APK 签名块，因此分类差值之和与整包差值存在少量打包开销差异。

剩余增长集中在 DEX。类定义复核：

| 包/组件 | 2.3.19 类数 | CI #305 类数 |
| --- | ---: | ---: |
| 模块自身 `com.reamicro.fix` | 4,916 | 7,135 |
| `com.android.tools.smali` | 0 | 986 |
| Guava `com.google.common` | 1 | 1,982 |
| Compose `androidx.compose` | 11,124 | 11,124 |
| Miuix `top.yukonga.miuix` | 1,775 | 2,357 |
| 协程 `kotlinx.coroutines` | 876 | 922 |

`6aa220a` 同时增加增强解析、宿主 DEX 分析库，并切换 Miuix 源码模块、扩展界面组件和宿主兼容实现。后续还增加了云端同步、备份、下载与热重载修复。移除增强解析后，这些独立功能仍在。

新旧版本均未启用 Release 的 R8 代码与资源裁剪。依赖中的未调用代码仍会打包；这是目前存在的体积优化空间。类数与指令统计能够确认代码来源，不能精确分摊各组件对压缩后 DEX 的字节贡献。Compose 类数及方法数一致，本次增长没有新增第二套 Compose 类。

## 本地与 CI 差异

`d3a0db0` 的本地 Release APK 为 19,556,204 字节，比 CI #305 多 89,678 字节。根目录 DEX 大小仅差 1 字节，主要差异来自本地 `source-files` 下被 Git 忽略、未提交的四份 `.rmsource`，被现有打包任务包含：

- `ciweimao.rmsource`
- `manual-edit.rmsource`
- `wanfengli.rmsource`
- `youshu.rmsource`

这四份书源在本地 APK 中的压缩大小合计 89,045 字节。比较公开发布大小时应使用 CI APK。

## 证据

本地审计数据位于不提交的 `build/parser-removal-audit/`：实际 CI APK、Release API 返回、包体分类、解析依赖类统计。Gradle 运行依赖树检查通过。

补充清理后的 `:app:assembleRelease` 构建通过，最终 APK 条目、DEX 和 ZIP 完整性检查通过，不再包含 Legado 许可证。补充清理前后的本地包为 19,556,204 → 19,543,936 字节，减少 12,268 字节，约 12 KiB；这种非代码残留不能解释相对旧版的 MiB 级差距。

清理后本地 APK SHA-256：`00ac39516df19b5713b0daba5d1324c659da6e4791000d9901839cf2b407e656`。
