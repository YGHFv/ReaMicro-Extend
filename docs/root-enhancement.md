# Root增强

## 版本与支持范围

配套模块版本为 **1.0 / versionCode 9**，模块标识为 `reamicro_automation`。
支持通过当前 KernelSU、Magisk 或 APatch 管理模块；只支持 Android 主用户。

- 未开启增强时，本地任务仅支持手动执行。
- 开启后由独立后台进程执行自动任务，不定时打开阅微界面。
- 安装或更新配套模块不等于开启增强；模块待生效时按管理器提示重启。
- 不自动重启，不挂载系统文件，不需要挂载类元模块。
- 不保证深度休眠期间准点执行，不使用隐藏的 Android 闹钟或 JobScheduler 兜底。

## 页面与操作

页面顺序：启用 Root增强、Root 授权、检测 Root、模块管理、任务状态、同步任务。

- **检测 Root**：只读检查本应用权限、模块和后台任务服务，显示上次检测时间。
- **同步任务**：提交任务配置、取回进度和结果，并尝试启动后台任务服务；使用独立的同步状态。
- **模块管理**：安装、更新、卸载，设置 SU 路径，并查看版本、旧脚本及错误信息。
- 进入页面、切换标签或回到前台不主动检测 Root，也不自动发起任务同步。
- 配置变更和任务完成事件仍进行必要的后台同步；真实命令仍校验 Root 身份。
- 检测结果仅是进程内的历史快照，不作为后续执行命令的权限凭证。

## 启停与数据

启用、停用和卸载必须确认。停用前等待当前任务安全结束并保存进度，再清除 Root 侧账号凭据副本。
停用保留模块文件和应用任务资料；卸载通过管理器处理，通常需重启设备。
更新不先卸载，不直接覆盖正在使用的模块目录，也不以待生效版本冒充已经运行。

`LegacyAndroidWakeCleanup` 仅取消旧版本已有的闹钟和作业，不创建新唤醒。
`RootTaskSyncReceiver` 专门处理 Root 任务完成事件。

## 界面与版本检查

Root 弹窗和任务配置共用 miuix `AppWindowDialog`。模块安装状态与任务运行状态分别展示。
运行记录仅表示后台进程近期活动，不代表网络请求或业务任务成功。执行结果请查看“记录”。
修改 SU 路径不会修改管理器设置或授予权限；实际程序路径如 `/system/bin/su` 区分大小写。

## 构建与验证

```sh
./gradlew :app:testDebugUnitTest :scripta-editor:testDebugUnitTest :app:assembleDebug
python3 tools/test-build-module.py
python3 tools/test-module-lifecycle.py
python3 tools/test-root-enhancement.py
python3 tools/test-root-entry.py
python3 tools/test-root-panel.py
python3 tools/test-root-wording.py
python3 tools/test-final-238.py
python3 tools/build-module.py
```

产物为应用 APK 和 `ReaMicro-Automation-Root-1.0.zip`。测试不等于已验证所有设备或长期深睡行为。
