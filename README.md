# ReaMicro LSP

用于补充阅微图书关联能力的 Android LSPosed 模块。

## 当前目标

- 定位阅微图书关联链路。
- 为关联候选补充外部搜索来源。
- 先接入次元姬，并增强/兜底刺猬猫图书搜索 provider。

## 当前结论

当前目标 APK 为 `RM2.0.A4.APK`。新版本已切到 Ktorfit 风格 REST API，并通过远端下发 JS 文件执行第三方搜索，不再沿用旧版 `N4.u` / `Bibliosurf` / gRPC 主链路。

本模块现在 Hook `BookPublishViewModel.searchByThird()` 和 `BookPublishViewModel.onIntent(...)`，插入“手动关联”候选并复用阅微原生 `postCloudBook -> relateCloudBook` 提交流程。

最新接口扫描见 `docs/reamicro-rm2-a4-interface-scan.md`，手动关联选项见 `docs/manual-association-option.md`。旧版 `RM1.3.0+` 记录仅作历史参考。

## 构建

需要 Android SDK。首次构建前确认 `local.properties` 指向本机 SDK，例如：`sdk.dir=C:/Users/<name>/AppData/Local/Android/Sdk`。

```bash
./gradlew :app:assembleDebug
```
