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

内容包、备份、任务和多用户账号将在后续版本加入 PostgreSQL、Redis、对象存储及 Worker。

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
