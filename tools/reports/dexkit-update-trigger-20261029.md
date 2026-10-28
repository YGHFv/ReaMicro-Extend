# 更新后自动弹出 DexKit 扫描

日期：2026-10-29（设备与工作区本地时间）。

## 行为

- 首次启用本功能，或阅微/模块安装状态变化，下次阅微主页面恢复前台时弹出扫描窗口。
- 阅微优先使用系统 PackageInfo.lastUpdateTime；按不相等判断，覆盖安装和降级均会触发。
- 模块同样优先查询系统更新时间。本机阅微受包可见性限制无法查询模块 PackageInfo，
  此时使用 LSPosed 提供的已加载模块 APK 修改时间，并结合路径、大小、mtime 摘要。
- 安装状态摘要不是内容哈希，扫描器原有 APK 内容 SHA-256、规则摘要及引擎版本缓存保持不变。
- 显示不确定进度加载指示后，在后台强制非缓存扫描，完成后切换到原扫描结果页。
- 只有扫描返回完整结果、安装状态没有再次变化，才持久化本次更新标记。
  完整扫描中存在定位失败仍显示失败明细并记一次完成；异常中断不记完成。
- 同次进程不重复启动相同更新的任务。切后台时隐藏窗口，恢复前台接回原任务或结果。
- 不替换当前已经安装的 Hook，不改变安装 Hook 前的符号初始化流程；
  新宿主首次启动可能先做一次启动解析，进入前台再做用户要求的可见重扫。

## 实现

- HostScanUpdateStamp：纯数据更新判定、原因及安全序列化。
- DexKitUpdateScan：后台检查、前台窗口生命周期、强制扫描与持久化完成标记。
- ReaMicroHookEntry：主 Activity onResume、暂停、销毁接线。
- ReaMicroSettingsHook.DexKit：手动入口和更新触发共用同一结果窗口构建。
- 完成记录位于宿主私有 SharedPreferences `reamicro_fix_dexkit_updates`，
  不修改用户书籍、账号或原有设置。

## 验证

- `testDebugUnitTest + assembleRelease` 成功，最终构建 2 分 6 秒。
- JVM 146 项，0 failures；新增 7 项覆盖首次、同版本重装、双更新、回滚、摘要变化、
  同进程去重及损坏记录。
- 首次自动弹窗实际显示加载指示，非缓存扫描 1300/1300 后显示结果。
- 关闭后完全重开阅微，没有再次弹窗。
- 覆盖安装相同模块 APK，versionName 仍为 2.3.9：
  自动提示“检测到模块已更新”，真实非缓存扫描 1300/1300。
- 该次扫描中切后台并恢复，日志仅 1 次 started / 1 次 finished，返回接回同一结果。
- 覆盖安装相同 RM2.3.3.RC3.APK，versionName 仍为 2.3.3 beta：
  系统宿主更新时间变化，自动提示“检测到阅微已更新”，扫描 1300/1300。
- 此后完成标记中仅宿主时间变化，模块时间保持；普通重开再次确认无弹窗。
- 最终运行期映射 360/360、启动 Hook 134/134，无 FATAL 或本轮回调异常。
- 初版因宿主无法查询模块 PackageInfo 未触发扫描，已用框架 APK 兜底修复；
  失败日志保留，不作为最终通过证据。
- 未重启手机、未关闭或重启 MT、未使用子代理；MT PID 始终为 18580。
- 没有载入截图，UI 验证只读取 Accessibility XML。

## 产物与证据

- Release 版本保持 2.3.9 / 71，已覆盖安装。
- APK SHA-256：`77d1d0d3b8f3926178d5b5269a620417bd03fc42c299dd205d7ed28fa609a143`。
- 构建日志：`%TEMP%/reamicro-analysis/runtime/b4/round13/dexkit-update-second-build.log`。
- 本轮状态记录：`%TEMP%/reamicro-analysis/runtime/rc3/update-trigger/`。
- 运行日志：该 runtime/rc3 下的 `update-first-success`、`update-unchanged`、
  `update-module-reinstall`、`update-host-reinstall`、`update-final-no-repeat`。
- 用户既有 `.gitignore` 修改及临时文件保持原样，不纳入本次提交；仅本地提交、不推送。
