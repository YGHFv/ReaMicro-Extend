# ReaMicro Extend

ReaMicro Extend 是一个用于阅微的 LSPosed 扩展模块，目标是补充一些日常使用中的阅读、编辑、显示和管理体验。

## 功能

- 阅读页增强：提供自动翻页、保持屏幕常亮、导入覆盖检查等辅助能力。
- 文本编辑增强：优化 EPUB/Web 编辑场景中的文本输入和保存体验。
- 字体相关增强：支持全局字体补全、阅读字体隔离和字体混淆检测。
- 账号与本地数据：提供账号数据导出和启动缓存清理相关开关。
- 云盘与本地书库：补充 WebDAV、本地书库显示和下载取消等细节能力。
- 屏幕方向控制：支持自动旋转、竖屏锁定、横屏锁定和反向旋转等设置。
- 模块设置页：在阅微内提供统一的功能开关入口。

## 构建

需要：

- Android SDK
- JDK 17

构建调试版：

```bash
./gradlew :app:assembleDebug
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

如需配置 `local.properties`，请指向本机 Android SDK，例如：

```properties
sdk.dir=C:/Users/<name>/AppData/Local/Android/Sdk
```

## 说明

本项目仅提供公开模块代码。请在遵守相关应用和平台规则的前提下使用。

## License

No license has been declared yet. Do not assume redistribution rights until a license is added.
