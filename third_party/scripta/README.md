# Scripta 编辑器集成

- 上游：[YuKongA/scripta](https://github.com/YuKongA/scripta)。
- 固定提交：`23820ff3085016a7490b1c1bb1c9dc76168be052`，另见 [UPSTREAM_COMMIT](UPSTREAM_COMMIT)。
- 许可证：[Apache-2.0](LICENSE)，许可证副本随应用打包。
- 本项目仅集成编辑器库，裁剪了测试、示例应用及未使用的上游构建文件，生产源码保留原样。
- Android 构建入口为 `editor/reamicro.android.gradle`，使用主项目的工具链和依赖版本。

完整接口与上游文档请参阅原项目。大型文档使用 `rememberCodeEditorController`，避免将全文放入可保存的 Bundle 状态；文件写入应在后台线程完成。
