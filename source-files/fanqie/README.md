# 番茄关联源：大灰狼 5.5.16 兼容版

参考用户提供的 `大灰狼融合5.5.16(vip完全版).json`。原始文件 SHA256 记录在 `manifest.json`，不修改或执行其中的登录脚本。

## 范围

这是宿主阅微中“番茄补全关联”的搜索/书籍信息 Provider，不是完整的 Legado 运行时，也不承担听书、漫画、短剧或章节下载。

- 使用原书源的服务器列表、User-Agent 和 `/search` 小说/番茄参数。
- 支持 `x:书名@番茄`、`x：书名@番茄` 简写，并正确编码特殊字符。
- 适配 `book_id`、`source`、`tab`、`thumb_url`、`abstract`、`word_number`、`status`、`score`、`tags` 和最近章节信息。
- 支持解析新版 `data:;base64,...,{"type":"qingtian"}` 描述，从中取得真实书籍 ID；关联展示链接使用可直接打开的番茄官网地址，不把 Legado 的请求描述当成网页地址。
- 排除推广项、缺少 ID 的条目、其他来源和非小说媒体；按稳定 ID 去重。
- 保留番茄官网搜索兜底；镜像并发数限制为 3，并传播线程取消。
- 不携带任何账号密钥，不生成或伪造 VIP Cookie。网络权限和服务端鉴权继续由原服务及宿主环境决定。

## 布局

- `src/`：供源包构建与单元测试共同使用的 Java 源码。
- `manifest.json`：保留 `apiVersion=1`、`id=fanqie` 和原入口类。
- `../fanqie.rmsource`：实际装入 APK assets、由宿主加载的 DEX 源包。
- `../../tools/build-fanqie-source.py`：可复现的 javac → D8 → ZIP 构建脚本。

源代码不会编进应用 main 类路径，仅加入 test 源集，避免与外部 DEX 加载的 Provider 重名。

## 重建

先编译模块的接口类，然后运行：

```sh
python3 tools/build-fanqie-source.py \
  --module-jar app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar \
  --android-jar "$ANDROID_HOME/platforms/android-37.0/android.jar" \
  --d8-jar "$ANDROID_HOME/build-tools/35.0.0/lib/d8.jar"
```

SDK 路径按本机环境调整。脚本只会在 javac、D8 和归档检查均成功后替换 `source-files/fanqie.rmsource`。修改源码后必须重建此包，再构建最终 APK。

## 测试

`FanQieSourceProviderTest` 与 `FanQieHttpTest` 共 11 个离线测试，覆盖新版字段映射、Base64 描述、异常/鉴权响应、去重、URL 安全及本地 HTTP 往返。测试不需要真实账号，不调用真实阅微任务。

升级 APK 后重启宿主阅微，使模块代码与内置关联源重新加载。

## compat3：宿主明文策略兼容
2.3.2 的 network_security_config 默认禁止明文，Manifest 的 usesCleartextTraffic=true 不足以放行大灰狼 HTTP 主接口。compat3 配合模块独立 AssociationNetworkHook，在单次 /search 请求消费期间进入精确端点范围，finally 撤销，不全局修改策略或 TLS 校验；拒绝主 HTTP 请求重定向。保持入口/apiVersion=1，但旧模块没有桥接时仍服从其宿主策略，因此需要更新模块后重启宿主，不能仅导入源包。

完整根因、旧 ZIP 对照、宿主六源响应及测试边界见 `docs/fanqie-association-compat3.md`。主接口为 HTTP，查询词不受 TLS 加密；未执行登录、Cookie/VIP 脚本。

## compat4：不再绑定固定端点
HTTP 权限范围随番茄补全的实际请求创建，域名、端口、路径变化无需改模块白名单；范围只在线程/请求期间存在。最多跟随 3 次重定向，每跳重新授权，拒绝 HTTPS 降级及凭证 URL，不修改 TLS 校验。书源本身仍需提供正确地址，不能自动猜测服务器的新端口。
本节取代 compat3 的“固定 IP:端口 /search、拒绝所有 HTTP 重定向”限制。新模块和 compat4 源包配合使用，更新后重启宿主。完整报告见 `docs/host232-native-compat4.md`。
