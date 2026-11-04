# 阅微 2.3.4 beta 手动编辑适配

## 范围与根因

- 日期：2026-11-05。宿主 `app.zhendong.reamicro`，2.3.4 beta / 2330，指纹 `2.3.4-b1`。
- 使用本地 REA `.tools/rea-6.7.0` 与 MT MCP 联合核对方法体、调用链和完整 Smali，不按同名、同签名猜选。
- 旧功能源仅声明 2.3.3 能力，新宿主被兼容门禁阻断；直接解除门禁还会错误使用旧页面、顶栏 lambda 分支和关联方法。
- 详情页的书名、作者编辑及复制标识行仍调用旧图标工厂，触发 `NoSuchMethodException: bgd.d` / `drc.l`，本轮一并迁移。

## 已核验契约

| 入口 | 2.3.3 | 2.3.4-b1 |
| --- | --- | --- |
| 关联页面 | `utd.a` | `jvd.b` |
| 顶栏 lambda | `vo1.C`，分支 14 | `mr1.C`，分支 11 |
| 关联方法 | `k` | `hn0.o0` |
| 编辑图标 | `bgd.d` | `yke.e`，`Outlined.Edit` |
| 复制图标 | `drc.l` | `cee.d`，`Outlined.ContentCopy` |
| 源能力 | `manual_edit_host_233` | `manual_edit_host_234_b1` |

- `HostManualEditAbi` 集中维护已核验版本；只有 `manual-edit` 且同时声明 `manual_edit`、当前宿主能力的源可以通过门禁。未知宿主不回落至旧版 hooks。
- 保留关联页面右上角书籍 ID 点击入口，不新增悬浮按钮，不重复注入条码关联菜单。
- 候选使用宿主 ThirdPartyBook 序列化构造器 `(I, 14*String, D, String)`，mask 为 65535；预览保留六字段 UI state 的原书、推荐列表和三个状态标志。
- 手动预览只替换自己的分组，保留原生搜索结果，并防止重复添加。
- 退出页面、VM 清理及功能源热重载时关闭表单并释放引用、缓存；预览前校验 VM、页面及 bookId，防止旧表单串书。
- 封面补交使用 `v2d.a1(y48, o12)` → `o83` → `jme.j(o83)` → `b58.a`，保留阅读进度。

## 验证

- `testDebugUnitTest`：281 项，失败、错误、跳过均为 0；`assembleRelease` 成功。
- 新宿主隔离测试 `Manual234SourceContract`：必填、白名单、长度、状态规范化、原生候选构造、入口及图标实例化、预览去重、真实 MVI 基类和 StateFlow CAS 更新、原生关联事件构造、旧表单退场、封面请求及成功/失败信封处理均通过。
- 老宿主基础 `Manual233SourceContract` 通过；8 组通用原生协议检查通过。
- 全量 DexKit：1340/1340，失败 0；最终手机扫描缓存与此前逐项核验的映射一致。
- 最终宿主进程日志显示 `hook installed 142/142`、`local manual-edit host 2.3.4-b1 installed`；未再出现上述图标回调异常，无 `FATAL EXCEPTION`。
- 日志筛选中的旧 profile 指纹不匹配包含 `CancellationException` 类型名，不是运行期异常；宿主另有 ML Kit 注册器及 `ViewRootImpl.getWindowFlags` 警告，本轮不将它们报告为已修复。

实机操作全部使用 XML、原生 View 树和日志，没有将截图载入对话：

1. 打开现有书的详情及关联页面，点击右上角书籍 ID，成功打开“编辑关联”。该 AndroidView 节点未被普通 UI XML 导出，使用原生 View 树定位后验证。
2. 填入仅供测试的书名、封面 URL、字数、状态及简介，点击“预览”，出现“手动关联-起点中文网(1)”及相应候选；原搜索结果仍在。
3. 不点击候选、不提交关联；返回详情确认原元数据不变，再进入关联页面确认临时预览已清除。
4. 最终安装包的“编辑书名”“编辑作者”“复制标识”图标均可见。分别打开书名、作者编辑框后返回取消，未保存、未改动书籍数据，也未点击复制覆盖剪贴板。
5. 结束时返回书架。MT PID 与手机 boot ID 全程不变；未重启手机、未关闭 MT。

**验证边界：**没有向真实服务器提交手动关联或封面变更；隔离测试使用无网络实现的 API 替身。没有进行长时间阅读压力测试，不能据此宣称长期阅读卡顿或 OOM 已验收。早期测试桩在使用 Unsafe 分配 Book 时退出，最终已改用真实构造器和 MVI 基类并通过，不将该测试桩退出归因为宿主崩溃。

## 本地产物与复测

- 模块：`2.3.19` / 81，已安装。
- APK：`app/build/outputs/apk/release/app-release.apk`。
- APK SHA256：`7d80c8498bbac7cedf2cafee714a6876a55716e1c481ff94be64b35db541eb3a`。
- 功能源：`source-files/manual-edit.rmsource`，版本 `234-local-1`。
- 源 SHA256：`9564eb4ce33ff5afcc77869f01f62ab439aaac4b579a9d042b493bf5adbd257c`；与 APK 内置源一致。
- 宿主 SHA256：`24cff525c32788a5c843d38420347603dcb80160927abf9f201f30aef22e341c`。

`external-sources/` 和手动编辑源产物沿用仓库忽略规则，仅本地保存；提交定点适配脚本、构建工具及测试，不强制追踪私有源或二进制。脚本以前一轮已适配 2.3.3 的本地源为前置条件，不是从干净克隆独立恢复全部源的工具。已有 `2.3.3.profile` 未提交改动保留原样。

在已有本地源、Android SDK、JDK、Gradle 缓存及设备环境中：

```powershell
python tools/adapt-manual-edit-234.py
./gradlew.bat :app:bundleDebugClassesToCompileJar
python tools/build-manual-edit-local.py --version 234-local-1
./gradlew.bat :app:testDebugUnitTest :app:assembleRelease
python tools/test-manual-edit-234.py <新的英文数字标签>
```

构建工具每次使用独立临时输出目录，避免旧 class 混入。适配脚本已检查重复执行幂等。复测会重建产物，ZIP 时间戳变化可能导致 SHA256 改变。

详细 REA/MT 证据、原源备份、测试日志及 UI XML 保存在 `%TEMP%/reamicro-analysis/runtime/manual234-20261105/`；最终进程日志在 `%TEMP%/reamicro-analysis/runtime/update-20261104/manual234-final-*.log`。本轮只做本地提交，不推送。
