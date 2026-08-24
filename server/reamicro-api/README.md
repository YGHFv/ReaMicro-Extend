# ReaMicro API Server

## 启动

```bash
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:5222/v1/health
```

默认 API 端口为 `5222`，后台管理地址为 `http://服务器地址:5222/admin`。
必须设置 `REAMICRO_ADMIN_PASSWORD` 后才能进入后台。后台使用 HTTP Basic 认证，默认用户名为 `admin`。

配置 `REAMICRO_API_KEY` 后，客户端请求需要携带：

```text
X-ReaMicro-Api-Key: <key>
```

当前实现提供：

- `/v1/health`
- `/v1/meta`
- `/v1/diagnostics`
- `/v1/releases/module/latest`
- `/v1/packages?kind=online_source|epub_style|highlight_style|association_source|theme`
- `/v1/packages/{kind}/{packageId}/download`
- `/v1/backups/module` 与 `/v1/backups/module/latest`
- `/v1/backups/credentials` 与 `/v1/backups/credentials/latest`
- `/v1/credentials/reamicro`
- `/v1/tasks` 以及暂停、恢复、立即执行、配置、删除和日志接口

阅微登录密钥使用 `REAMICRO_SECRET_KEY` 派生的 AES-GCM 密钥保存。生产环境必须设置随机长值并持久化；丢失或修改该值后既有凭据无法解密。账号密钥备份由 Android 客户端使用用户口令加密，服务器只保存 `RCRED1` 密文。

云任务支持 `yeshe_checkin`、`yeshe_draw_card` 和 `cloud_auto_read`。自动阅读可指定图书，未指定时读取阅微最近阅读记录；服务器按配置上报阅读进度和时长。

当前版本使用 Docker 数据卷内的 JSON 与文件存储，适合个人或小规模部署。多实例部署前应迁移到共享数据库、对象存储和独立 Worker，避免多个调度器同时执行同一任务。

## 内容包目录

将包放到 `data/packages/<kind>/<packageId>/`，目录中包含 `manifest.json` 和 payload 文件：

```json
{
  "packageId": "source.example",
  "kind": "online_source",
  "version": "1.0.0",
  "schemaVersion": 1,
  "sha256": "payload 的 SHA-256",
  "signature": "可选的 Ed25519 签名",
  "payload": "source.example.json",
  "name": "示例书源"
}
```
