# B4 适配 K50U 接续检查点（2026-10-27）

本文件是「调试设备由 `HA1W3A8E` 切换为 K50U」之后的接续基线。
完整迁移仍未验收完毕，不能以构建与 hook 计数作为完成依据。

## 1. 硬约束

- 工作区 `E:\MTool\Work\reamicro`，分支 `main`，HEAD `1f5eec6d5f583a45204e79ee503227be54534071`。
- 全部改动保持未暂存、未提交、未推送；本轮结束时 `git status --porcelain` 为 358 条，已跟踪改动 112 个文件。
- 不重启手机，不操作/重启 MT，只允许 force-stop 与启动阅微。
- 含中文文件禁止 here-string 重定向、`Set-Content`、`Out-File`；改用 `apply_patch`
  或 Python 显式 `encoding='utf-8', newline=''`。
- `app/src/main/assets/host-profiles/` 与 `host-bridges/` 未跟踪，`git diff` 看不到。
  行尾基线（本轮实测未变）：B2 profile 2854 CRLF/0 裸 LF，B3 2298/0，
  B4 2482 CRLF/**1 裸 LF**；B4 shapes 357 CRLF/**1 裸 LF**。
- 听书与 TTS 已按用户指令整体移除，不再适配；模块源码内仅剩注释与
  「由听书子系统迁出、仍被选中文本编辑与全文搜索复用」的标题辅助函数。
  profile 中 `setListeningMode=U`、`Book#listeningTotal=r` 属宿主类名，必须保留。

## 2. 设备与分析环境变更

- 当前设备：`adb-deb497bd-ECI5X6._adb-tls-connect._tcp`，model `22081212C`，
  market name **Redmi K50 Ultra**，`wlan0 = 192.168.10.43/24`。原 `HA1W3A8E` 已离线。
- **MT MCP 新端点 `http://192.168.10.43:8787/mcp` 就跑在 K50U 本机**，
  因此工作区编号池与旧机完全不同。`192.168.10.105` 已失效。
- B4 只读工作区已重建：`gv89uj9u`（源文件 `RM2.3.3.B4.APK`，44,845,737 字节）。
  同机另有 B1 `y23r25ri`、B2 `gocb7260`、B3 `e2qshtgt`（B3 源文件已 `sourceExists=false`）。
- 已改址文件（均逐字节校验：UTF-8 可解码、无 U+FFFD、无中文丢失、行数不变）：
  `tools/mtmcp.mjs`（默认值本就是 43，未动）、`tools/b4_analysis.py`（端点 + 工作区默认值）、
  `tools/_r11_mt.py`、`tools/_r11_mt_query.py`。
  `tools/b4_device.py` 的 `SERIAL` 改为可用环境变量 `B4_ADB_SERIAL` 覆盖，默认值指向 K50U。
- `tools/mtmcp.mjs` 的位置参数 JSON 在 PowerShell 下会被转义破坏；
  批量取证请沿用 `%TEMP%/reamicro-analysis/mt.py` 风格（`key=value`，需改 URL/WS）。
- REA 仍为 `.tools/rea-6.7.0`；APK 缓存在 `%TEMP%\reamicro-analysis\RM2.3.3.B2/B3/B4.APK`。

## 3. K50U 冷启动验收（已通过）

- `adb install -r` 最新 release：`app/build/outputs/apk/release/app-release.apk`，
  16,236,987 字节，SHA-256 `F23B479FEE13D47A20873727A1E94D04512917C7BCB18ECA7466A81EBF0E5867`。
  模块 `com.reamicro.fix 2.3.9 / versionCode 71`，`lastUpdateTime=2026-10-27 14:32:45`。
- 宿主 base.apk 与 B4 分析对象逐字节同尺寸：44,845,737 字节（`2026-10-27 14:22` 装机）。
- 进程 `24040` 日志（`%TEMP%\reamicro-analysis\k50u-modules.log`）：
  `profile=2.3.3-b4 mapped=356 unresolved=0 validation=2.3.3-b4=356/356`、
  `hook installed 131/131`、`ReaMicro LSP external feature disabled: manual-edit`（预期）。
- `ReaMicro symbols mismatch 2.3.3-b2/-b3` 共 27 行属**良性**：
  `app/src/main/java/com/reamicro/fix/core/HostSymbols.kt:114` 只在存在被拒绝候选 profile 时
  打印其结构指纹样本，与被选中的 B4 无关，不需要修复。
- `adb reverse --list` 为空；模块 prefs 无本轮新增键。

## 4. K50U 数据快照（只读，未写入）

- `books` 表 27 列（与 `ReaderPositionCopy` 字段数一致），**9 行，max id = 41**。
  K50U 是独立数据集：**没有** HA1W3A8E 上的 id4/id132–135。
- 现存书目（uid 26385）：24 我！巨龙领主、26 方外志异、27 提前登录！能看到血条我怎么输？、
  28 十日终焉、35 逍遥四公子、36 关于我加入了邪恶组织的这件事、39 上班，然后教育魔法少女、
  40 蓝白社、41 从被哥布林围攻开始的异世界生活。
  其中 **id27 `progress=0/total=0/updated=0`**，是最安全的「只读→首开」试验对象。
- `users` 两行：id3 宋壹（真实）、id26385（当前登录）。`thirdBinds` 系宿主云端字段，非模块缺陷。
- `book_chapter` 9534、`book_item_ref` 9530、`book_reading` 131、`book_sync_state` 9。
- 快照副本：`%TEMP%\reamicro-analysis\k50u-db\{app.db,app.db-wal,work\}`。
- 该设备模块设置已是长期使用的真实配置（66 键），**不得**按旧机「5 键基线」强行回滚。
  相关临时开关现状：`reader_auto_page_enabled=false`、`reader_edit_overwrite_enabled=true`、
  `edit_file_enabled=true`、`association_manual_edit_enabled=false`。
- `reamicro_fix_webdav.xml` 仍留有 B2 期 QA fixture 账号
  （`http://127.0.0.1:18765` / `reamicro-qa`，文件时间 2026-10-24，非本轮写入），
  端口无人监听；是否清理由用户决定。
- 三个真实在线源（`online_2db91f04378b83d7`、`online_3eb17212acc545f6`、
  `online_fc2ef39cc1d41375`）为用户自有配置，后续在线补全验证**优先复用**，不要新增源。

## 5. B4 符号复核（新工作区 `gv89uj9u`，MT 直读）

- `Lxo8;`（ReaderViewModel）：`t0:Loia;`、`z0:Ll2a;`、`v0:Loia;`、`x0:Loia;`、`A0:Liq8;`、
  `g:Lxz1;`，共 79 字段 ⇒ 与 profile 的
  `_initialPagerPage=t0`、`_pagerCorrection=z0`、`pagerMinPage=v0`、`pagerMaxPage=x0` 相符。
- `Ll2a;`（SharedFlowImpl）：`<init>(IILjt0;)V`、`m(Ljava/lang/Object;)Z` ⇒ `tryEmit=m` 成立。
- `Loia;`（StateFlowImpl）：`getValue`、`l`、`m`、`n` 齐备。
- 结论：**映射无需改动**；切机不需要重算 profile。

## 6. 回归与构建

- `python tools\b2_build.py :app:testDebugUnitTest`：**95 tests / 0 failures / 0 errors / 0 skipped**，
  BUILD SUCCESSFUL（日志 `%TEMP%\reamicro-analysis\runtime\build-test-full.log`）。
- `tools` 目录 `python -m unittest discover -s . -p "test_*.py"`：**17 项通过**（无 pytest 环境）。
- 2026-10-27 追加回归：`B4RecordSchemasTest` 固化 **Book 27 字段**（`of0`，
  `id=a`、`epubcfi=n`、`chapter=o`、`progress=p`、`updated=t`、`cloudId=v`、`publisher=z`）
  与 **ReaderUiState 16 字段**（`zm8`，`a..p`，`book=a`，`chapterItemsMap=j`，
  `searchResults=n`，`searching=o`，`activeSearchResult=p`）。
  证据：`%TEMP%\reamicro-analysis\runtime\b4\reader_ui_state_class.json`、
  `book_entity_class.json`（MT 构造器 + toString 双向核实）。
- 2026-10-27 16:11 重建 release 并安装 K50U：`app-release.apk` 16,236,987 字节，
  SHA-256 `0EE4B1F6338FA9C8A7F7591C734EDC0E5A7A5C4FBB320797166654849B3D5AC4`。
  冷启动 PID `28826`：`profile=2.3.3-b4 mapped=356 unresolved=0 validation=356/356`、
  `hooks 131/131`、`manual-edit disabled`（预期）。

## 7. 下一步顺序（勿停）

1. K50U 上重新做功能实机验收：文件编辑 → 图书结构（`EpubBookPanel`/`EpubWebEditorPanel`）。
   旧机该功能已 PASS（`file edit details card rendered through native ABI`），
   新机只需用 id27 或自建新书重跑，**不碰 id24/26/28/35/36/39/40/41 与 id3**。
2. 自建测试书：用 WebDAV TXT 下载转换或本地导入产生，命名带 `RM-K50` 前缀便于清理。
3. 关联补全真实操作（取消关联/重新关联/封面修复）仅在自建书上做。
4. 在线补全闭环优先复用上述三个真实源，做「搜索→下载→转为可读→删除临时产物」，
   验证前后对 prefs 与 `files/reamicro_online_sources` 做 diff。
   2026-10-27 已完成搜索入口复测：三个源均被触发；晚风里 0 结果、番茄源返回
   HTTP 500 JSON 解析失败、💰书旗返回 8 结果（`穿越兽世：兽夫凶猛追着宠` 等）。
   此前 `of0` 合成书构造异常 `argument 23 has type int, got java.lang.String`
   已由 27 字段显式构造修复，B4 首页搜索 hook 已能构造 8 条
   `reamicro-online-book://online_3eb17212acc545f6/*` 结果行。
   结果行不可见的直接阻断是 B4 宿主把 Compose 类重定位为短名（`j5`/`gf1` 等），
   `WebDavDriveHook.HostUi.kt` 的 `colorScheme()` 仍反射
   `androidx.compose.material3.MaterialTheme`，导致每行抛 `ClassNotFoundException`。
   已改为 B4 走 `B2NativeUi.theme(...)`，并移除 `getOnSurfaceVariant` 失败后的
   `MaterialThemeKt` 兜底。安装后冷启动仍为 `profile=356/356`、`hooks=131/131`。
   复测 `abc` 时仅晚风里启用（书旗/番茄源开关当前为 false），返回 0 条；
   如需复测书旗 8 行，需临时启用该源并在验收后改回。
  2026-10-27 17:00 继续用晚风里源实测 `shuqi`：搜索返回 5 条并渲染
  `home search section rendered type=81505 results=5`；点击首行触发
  `online completion tap path=reamicro-online-book://online_fc2ef39cc1d41375/0?q=shuqi`，
  启动 FULL 下载任务 `online-e4332819-32e5-40a5-be56-a4e8acb75558`
  （notification `4301`）。目录解析 `toc nodes=2919`、过滤后 `chapters=2918`，
  封面 23,998 B；中途 401 后自动重登成功，章节下载持续正常
  （最后观察到 `880/2918`）。因全书 2918 章预计耗时过长，本轮在确认链路与
  逐章下载稳定后**主动取消观察并删除 cache**，未杀进程、未写书库、未导入新书；
  因此「partial/epub ready/导入可读」尚未验收。临时启用的书旗源
  `online_source_online_3eb17212acc545f6` 已在读取当前 XML 后仅回写为 `false`，
  保留 401 重登后的新 Cookie/登录状态，未整文件回滚。
  删除 cache 后任务已无下载产物、通知不再活跃，阅微主进程 `12210` 仍在，
  `ReaMicroOnlineC` 线程处于 futex wait；搜索页停留在 `shuqi` 结果列表。
   后续快速闭环建议：在模块「在线源 → 下载配置」开启「逐章加载」
   （`online_download_on_demand_online_fc2ef39cc1d41375=true`）后复测同一结果，
   该模式首次只落前 3 章，可避开 2918 章 FULL 任务长耗时，直接验证导入可读。
5. 自动阅读：先确认无后台计时（`reamicro_reader_auto_page.xml`：`interval_seconds=10`、
   `timer_hours=0.0`）；修「更多设置」启动后路由不自动关闭；
   核对 `TapDirection`/`sm8(long)` 与 pager effect；听书相关已不需要适配。
6. 其余：选中紧凑菜单、滚动模式、账号切换与导入导出（真实仅 id3，谨慎）。
   `ReaderPositionCopy` 27 字段与 `zm8` 16 字段 UiState 保真回归待补测试。
7. 收尾：新建 `tools/reports/b4-final-progress.md` 并在 `tools/reports/b4-round7-progress.md`
   加指针；写明通过项、残余未覆盖项、旧 ManualEdit 不支持、听书/TTS 已移除。
   本地提交记录：`1f9d5a1` 固化 B4 字段回归；本轮在线补全搜索渲染修复与
   2026-10-27 实测记录也已本地提交，具体哈希见 `git log`。仅本地保存，未推送。

## 8. 取证据速查

- 模块日志：`adb shell "su -c 'cat /data/adb/lspd/log/modules_2026-10-24T19:04:18.52968.log'"`，
  务必按**最新进程号**过滤，旧进程异常会混在同一文件里。
- UI dump：`adb shell "su -c 'uiautomator dump /data/local/x.xml >/dev/null 2>&1; cat /data/local/x.xml'"`。
  书架按「最近阅读」重排，**禁止盲点固定坐标**；先 dump 再按 bounds 点击。
- MT 取证：`mt_apk_dex_read_class_outline` 的字段在 `data.fields[].sig`，
  类名/父类不在 outline 顶层返回；`mt_apk_workspace_list` 不接受 `limit` 参数。

