# ReaMicro API 部署与升级

## 选择编排

- `docker-compose.simple.yml`：单容器兼容部署，适合个人服务器和现有 1Panel 环境。
- `docker-compose.yml`：API 与 Worker 分离，共享同一 `/data`，适合启用云端阅读、签到和抽卡。

已有全局 Watchtower 时不要再次创建 Watchtower；确保现有 Watchtower 同时管理 `reamicro-api` 与 `reamicro-worker`。

## 从旧版升级

1. 备份整个 `/data` 卷。
2. 拉取新镜像并重建 API；如使用完整编排，同时启动 Worker。
3. 首次启动会自动把 `tasks.json`、`credentials.json` 导入 SQLite，并将旧文件改名为 `.migrated`。
4. 访问 `/health/ready`，确认 `database` 为 `ok`。
5. 登录后台创建一次服务器快照。
6. 确认云任务能在 Worker 中执行后，再长期保留 API 的 `REAMICRO_RUN_SCHEDULER=false`。

## 自动更新边界

不涉及数据库 schema 变化的补丁版本可以交给 Watchtower 自动更新。未来如升级说明标注“需要迁移”，应暂时停止 Watchtower，先创建服务器快照，再固定版本标签升级。不要直接轮换 `REAMICRO_SECRET_KEY`；应使用后台密钥轮换功能。

## 反向代理

- API 基础地址：`https://你的域名`
- 后台地址：`https://你的域名/admin`
- GitHub Webhook：`https://你的域名/v1/webhooks/github`
- 存活检查：`https://你的域名/health/live`

反向代理必须保留 `Range`、`If-None-Match`、`X-Request-Id`、`X-Hub-Signature-256` 和 `X-GitHub-Event` 请求头，且不要缓存 `/admin`、`/v1/tasks` 和 `/v1/credentials`。
