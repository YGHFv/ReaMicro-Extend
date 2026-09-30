# Root 增强模块

源码位于 `module/reamicro-automation/`，构建后内置于 APK 的 `assets/module/`。
模块 ID 为 `reamicro_automation`，版本以 `reamicro-automation/module.prop` 为准。

## 使用

- 支持通过 KernelSU、Magisk 或 APatch 管理，仅支持 Android 主用户。
- 安装、更新配套模块不等于开启增强；在应用内确认启用，待生效时按管理器提示重启。
- 未开启增强时仍可手动执行任务；开启后由独立后台进程处理自动任务。
- “检测 Root”用于查看权限、模块和服务状态；“同步任务”用于提交配置并取回执行结果。
- 停用保留应用任务资料；卸载需通过管理器处理，通常需要重启。

## 使用限制

- 不自动重启，不挂载系统文件，不需要挂载类元模块。
- Root 授权、模块安装状态和任务执行结果分别判断。
- 深度休眠、断网、登录失效及内核策略可能影响自动任务，不保证绝对准点。

## 构建

```sh
./gradlew :app:assembleDebug :app:bundleModule
python3 tools/build-module.py
```
