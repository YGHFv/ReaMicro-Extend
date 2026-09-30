# 番茄关联书源

基于“大灰狼融合 5.5.16”规则适配的搜索与书籍信息 Provider。
来源信息保留在 `manifest.json`，不携带账号密钥，也不执行登录或 Cookie/VIP 脚本。

## 范围与文件

- 用于阅微的番茄关联搜索，不是完整的 Legado 运行时，不负责听书、漫画、短剧或章节下载。
- `src/`：生成独立 DEX 源包的 Java 源码，不编入应用主类路径。
- `manifest.json`：源包入口、标识与接口版本。
- `../fanqie.rmsource`：实际装入 APK、由宿主加载的源包。
- `../../tools/build-fanqie-source.py`：javac → D8 → ZIP 构建工具。

支持字段解析、去重、官网搜索兜底及受限并发。HTTP 放行仅覆盖当前请求及重定向，不全局修改宿主策略或 TLS 校验；拒绝 HTTPS 降级及包含凭证的 URL。HTTP 查询本身不受 TLS 加密。

## 重建

先编译模块接口类，再运行：

```sh
python3 tools/build-fanqie-source.py \
  --module-jar app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar \
  --android-jar "$ANDROID_HOME/platforms/android-37.0/android.jar" \
  --d8-jar "$ANDROID_HOME/build-tools/35.0.0/lib/d8.jar"
```

SDK 路径按本机环境调整。脚本仅在编译和归档检查成功后替换源包。
修改 Provider 后须先重建源包，再构建 APK；升级后重启宿主阅微，使模块和内置书源重新加载。
