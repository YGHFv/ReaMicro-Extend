# ReaMicro 本地自动任务 KSU 模式（实验性）

此模块用 KSU 的开机脚本启动独立执行器，复用 APK 内的本地自动任务引擎。它不是通过模拟点击阅微界面执行任务，也不是普通 Root 看门狗的改名。

**能降低对 Android 应用进程存活的依赖，但不能保证永不被杀或绝对准时。** 深度休眠、断网、登录失效、风控、root/SELinux 限制仍然存在。

## 获取模块

- **推荐：什么都不用下载。** 配套模块 ZIP 已经打进 APK 的 assets，模块应用「配置 → 权限与后台 → 本地任务执行模式 → 说明与切换 → 使用 KSU」会自动把内置 ZIP 释放出来并用 `ksud module install` 安装，装好后自动切换到 KSU 模式。若安装后提示需要重启，重启设备后再点一次「使用 KSU」。
- 想手动刷入时，打开 [GitHub Releases](https://github.com/YGHFv/ReaMicro-Extend/releases)，在同一条 CI 预发布的 Assets 中下载配套 APK 和 `ReaMicro-Automation-KSU-<版本号>.zip`。不要使用只筛选正式版的 `releases/latest`，CI 产物标记为预发布。
- 也可在 [Actions → CI](https://github.com/YGHFv/ReaMicro-Extend/actions/workflows/ci.yml) 的成功构建中下载 `ReaMicro-Automation-ci-ksu` artifact（保留 14 天，需要登录 GitHub）；先解压 artifact 外层压缩包，再刷里面的模块 ZIP，不要把外层包交给 KernelSU。
- `main` 上 APK、`ksu/` 或打包脚本变更，以及手动运行 CI，都会在校验通过后构建 APK 和模块 ZIP；ZIP 同时上传到 Actions artifact 和该次 GitHub Release。ZIP 版本号来自 `module.prop`，不跟 APK 版本号强行绑定。

## 安装和启用

1. 安装包含本次 KSU 执行入口的配套 ReaMicro Extend APK；旧 APK 没有此功能。
2. 为**阅微补全计划/模块应用**（`com.reamicro.fix`）授权 root，不是只给阅微宿主授权。
3. 打开模块应用，进入「配置 → 权限与后台 → 本地任务执行模式 → 说明与切换」，选择「使用 KSU」。
   应用会检查模块是否已刷入，没有就用内置 ZIP 自动安装再自动切换；提示需要重启时先重启，重启后重新点一次。
   也可以跳过自动安装，在 KernelSU 管理器中手动刷入 `ReaMicro-Automation-KSU-0.1.0.zip` 并保持模块启用（此时通常要重启让 `service.sh` 生效）。
4. 稍等片刻点击「同步状态」，确认心跳和任务状态。只刷 ZIP 不会默认接管，也不会创建或启用任何本地任务。
5. 继续在原来的本地任务页面设置账号、任务和参数。模式对主 Android 用户的本地任务整体生效；不改变云端服务器任务。

目前不支持 Android 次用户/工作资料。不同阅微账号仍分开保存任务与进度。

「立即执行」在 KSU 模式下表示提交排队请求，不是等待 root 任务全部执行完成；设备醒着时通常在下一次 15 秒检查内取走，完成后可同步记录。

## 共用配置与切换等待

两种模式使用**同一套本地任务设置**，账号、任务参数和启用开关不需要重新配置；并不是让用户维护两套独立配置。

底层存储目前分开：Android 使用应用私有的 SharedPreferences，并用该应用 UID 的 Android Keystore 加密 token；KSU 使用 root 私有 JSON，应用通过受控命令同步配置与进度。root 不能直接用自己的 UID 解密应用的 Keystore 密文，也不能绕过 SharedPreferences 的进程内缓存直接修改同一个 XML 文件。

切换等待的不是复制配置，而是**本机已经开始执行的这一轮请求结束并保存最终进度**。行商旅程、轶闻等待领奖等服务端计时只保留下次执行时刻，不会一直占用本机执行锁，不需要等这些计时结束才能切换。

即使以后迁到共用 JSON，仍需要跨进程执行锁和安全交接：旧执行器发出的请求可能已在服务端生效，新执行器如果立即重跑，仍可能重复祈愿、典当或覆盖计数。缩短界面等待应使用后台交接，而不是放开两端同时执行；当前版本仍保留原有安全交接机制。

## 后台卡片

「配置 → 权限与后台 → 隐藏后台卡片」默认关闭。开启后，返回桌面或离开模块 Activity 时从最近任务列表隐藏模块卡片；重新进入模块时恢复前台卡片。关闭开关恢复普通显示。

隐藏卡片不等于强制停止、不卸载任务、不改变当前执行模式，也不是保活手段。通知、电池优化、厂商自启动等权限仍需按设备实际情况处理。

## 切回和卸载

1. 保持 APK 和 KSU 模块都在。
2. 在「本地任务执行模式」选择「使用 Android」。执行器会先停止领取新任务、等当前本机执行结束、清除 root 目录的 token 副本并导回最终进度；不等待行商旅程或轶闻解锁。
3. 确认显示 Android 模式后，再去 KernelSU 卸载模块。

清除模块应用数据、重装 APK 前也必须先正常交接；清数据会丢失应用里的执行权标记，但不会替你删除或停用 root 私有状态。

若交接超时，不会自动恢复 Android 双跑；请等当前轮次结束后重新同步/切换。单轮看门狗执行上限为 240 秒，应用侧单次停用指令等待 60 秒，长任务可能需要重试。

模块已停用但文件还在时可以切回 Android。若先卸载 KSU 模块，配套 APK 仍可发停用指令以退出 KSU 模式，但不能恢复已经被删掉的最后进度；因此不要跳过正常交接。若先删 APK，卸载脚本无法确认执行器已经停止时会保留私有状态，需人工核对，不能当作已经清除了凭据。

## 文件和安全

- 模块 ID：`reamicro_automation`。
- 脚本目录：`/data/adb/modules/reamicro_automation`。
- 私有状态：`/data/adb/reamicro-automation`，目录权限 `0700`。
- `state.json` 保存配置、进度、记录、待发通知和 **token 明文副本**，权限 `0600`；普通应用不能直接读取，但其他 root 程序能读取。不要分享此文件。
- 配置通过 root 命令标准输入传递；返回给应用的状态快照去掉 token；日志不打印 token。
- `daemon.lock` 防止多个看门狗，`execution.lock` 防止并行执行，`state.lock` 保护状态读写；不要手工删除锁和状态文件来强行切换。
- `daemon.log` 仅保留有限的执行退出状态。停止/启用模块、修改权限时先检查应用中的同步状态，不直接编辑 JSON。

跨云端/本地、多台设备和历史镜像 IPC 的限制详见 [自动任务审查](../docs/automation-audit.md)。

## 打包

在仓库根目录运行：

```powershell
.\gradlew.bat :app:assembleRelease
python tools/test-build-ksu-module.py
python tools/build-ksu-module.py
```

输出：

- `app/build/outputs/apk/release/app-release.apk`
- `outputs/ReaMicro-Automation-KSU-0.1.0.zip`

APK 构建时还会另打一份同内容的 ZIP 塞进 `assets/ksu/`（Gradle 任务 `bundleKsuModule`），
供应用自动安装；两份 ZIP 的文件清单与权限一致，都来自 `ksu/reamicro-automation/`。

ZIP 文件名按 `ksu/reamicro-automation/module.prop` 的 `version` 生成，当前是 `0.1.0`。打包白名单只包括五个模块脚本和 `module.prop`，缺少文件或版本号不合法会中止打包，不包含账号配置、token 或测试数据。脚本统一为 UTF-8 无 BOM、LF，并带 Unix 执行权限。模块运行时加载已安装 APK，因此需要配套更新，不能删掉 APK 后单独运行 ZIP。

## 无副作用自检

在已获得权限的 root shell 中执行：

```sh
sh /data/adb/modules/reamicro_automation/runner.sh self-test
```

此命令仅在 `/data/local/tmp` 建临时模拟账号，使用替身执行器检查加载、文件锁、状态持久化、快照脱敏和私有权限，完成后删除模拟文件；不读真实任务状态、不访问阅微接口、不证明真实自动任务已经运行。

本轮已通过普通 Android shell 的独立入口自检；完整的 KSU 刷入、root 接管、重启/熄屏长期运行仍需实机试用确认。
