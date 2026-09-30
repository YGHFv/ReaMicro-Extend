# Root增强

源码位于 `module/reamicro-automation/`，APK 资产位于 `assets/module/`。
通用模块版本 **1.0 / versionCode=9**，模块 ID 保持 `reamicro_automation`。

## 功能
- Root 授权检测与带时间的检测结果。
- 明确确认后的增强启用/停用。
- 模块安装、更新、卸载和自定义 SU 入口。
- 任务配置、进度与结果同步。
- 停用后保留应用任务资料，可以手动处理任务；不启用任何替代自动唤醒。

## 模块行为
- 对照本地 KernelSU、Magisk、APatch 源码使用各自的安装/状态接口。
- Root 授权与模块就绪状态分别检查，暂存版本不冒充已经运行。
- 更新不先卸载、不清任务资料；卸载前停止后台任务、保存进度并清除 Root 凭据副本。
- KernelSU/Android 的文件锁问题已修正：生命周期脚本先进入 BusyBox ash，再打开 FD 锁。
- Root 完成通知通过专用 `RootTaskSyncReceiver` 同步，不依赖已删除的闹钟或 JobService。
- 仅支持主用户。不自动重启，不挂载 system，不需要 Metamodule。
- Root 并不自动提供深睡硬件定时唤醒能力；没有隐藏的系统闹钟兜底，不保证深睡期间绝对准时。

## 界面
Root增强只有启停状态，不再选择唤醒方式。
模块管理、确认和 SU 输入统一使用 miuix 0.9.4 原生 WindowDialog（与任务配置共用实现）。
功能行不插入横分隔线；底部按钮统一避让导航区、手势区、屏幕圆角，且无导航条时仍保留最小留白。

## 构建
```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:bundleModule
python3 tools/test-build-module.py
python3 tools/test-module-lifecycle.py
python3 tools/test-root-enhancement.py
python3 tools/test-miuix-integration.py
```

当前规范和验证范围见 [Root增强记录](../docs/root-enhancement.md)。
