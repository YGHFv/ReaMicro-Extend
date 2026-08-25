# ReaMicro API Server

## 启动

```bash
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:5222/v1/health
```

默认 API 端口为 `5222`，后台管理地址为 `http://服务器地址:5222/admin`，登录页为 `/admin/login`。后台登录成功后使用 HttpOnly Cookie 会话，默认有效期 12 小时；生产环境必须通过 HTTPS，并保持 `REAMICRO_ADMIN_COOKIE_SECURE=true`。本地明文测试可临时设为 `false`，不要在公网长期关闭。
首次部署必须设置 `REAMICRO_ADMIN_PASSWORD` 作为主管理员一次性引导密码。第一次使用该账号打开 `/admin` 时会强制进入初始化页面，设置新的主管理员用户名和至少 12 位密码；初始化完成后，环境变量中的引导密码立即失效，主管理员密码只以 PBKDF2 哈希保存到 `/data/config/server.json`。如果遗失主管理员密码，请通过数据卷备份恢复配置，或在停机维护时移除 `primaryAdmin` 后重新使用引导密码初始化。

主管理员可以在后台分发子管理员账号。子管理员使用同一个 `/admin/login` 地址登录，可以协助调整服务器设置、上传内容包和创建云任务，但不能管理主管理员或其他子管理员。主管理员可以停用、删除或随机重置子管理员密码；随机密码只在操作结果页显示一次，请立即保存。密码重置、账号停用或删除会立即清理该账号的全部后台会话。旧 HTTP Basic 仅作为迁移兼容方式保留。

后台修改操作带有 CSRF 校验；每个请求会返回 `X-Request-Id`，异常和限流事件写入 `/data/audit/events.jsonl`，主管理员或具备 `audit:read` 权限的管理员可以通过 `/admin/audit` 查看。子管理员创建时可以分配 `settings:write`、`packages:write`、`tasks:write`、`module:sync`、`audit:read` 和 `security:write` 权限；未分配的操作会返回 403。

## GitHub Actions 镜像

主分支中的服务器代码发生变化后，GitHub Actions 会自动构建 `linux/amd64` 和 `linux/arm64` 镜像并推送到：

```text
ghcr.io/yghfv/reamicro-extend/reamicro-api:latest
```

版本标签（例如 `v2.1.0`）会同时生成同名镜像标签，每次构建还会生成 `sha-<短提交号>` 标签。可以直接运行：

```bash
docker run -d --name reamicro-api \
  -p 5222:5222 \
  -v reamicro-api-data:/data \
  -e REAMICRO_ADMIN_PASSWORD='仅用于首次初始化的引导密码' \
  -e REAMICRO_SECRET_KEY='请设置随机长密钥并永久保存' \
  ghcr.io/yghfv/reamicro-extend/reamicro-api:latest
```

`docker-compose.yml` 已内置 Watchtower。启动编排后，它每 5 分钟检查一次 `latest` 镜像摘要；GitHub Actions 发布新镜像后，Watchtower 会自动拉取并滚动重启 API 容器，同时清理旧镜像。可通过 `.env` 中的 `REAMICRO_IMAGE_UPDATE_INTERVAL` 调整间隔：

```bash
docker compose up -d
docker compose logs -f watchtower
```

### 1Panel 部署与私有 GHCR

在 1Panel 的「容器 → 编排」中粘贴本文件的 `docker-compose.yml` 内容即可。若 GHCR Package 为私有包，先在 1Panel 的容器镜像仓库中添加 `ghcr.io` 凭据，或在服务器终端执行：

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u YOUR_GITHUB_USER --password-stdin
```

Token 至少需要 `read:packages` 权限。API 容器能否拉取镜像不代表 Watchtower 一定能拉取；Watchtower 需要读取 Docker 登录配置。在 Linux 服务器上把以下卷加入 `watchtower.volumes`（确认宿主机文件确实存在）：

```yaml
      - /root/.docker/config.json:/config.json:ro
```

若 1Panel 使用了其他系统用户登录 Docker，请将 `/root/.docker/config.json` 换成该用户的实际 Docker config 路径。也可以把 GHCR Package 设置为公开，避免在 Watchtower 容器中管理凭据。

Watchtower 只更新带有 `com.centurylinklabs.watchtower.enable=true` 标签的 API 容器。它通过 Docker Socket 管理容器；即使挂载标记为只读，对 Docker API 仍具有较高权限，因此只应使用可信的 Watchtower 镜像和受控的 1Panel 主机。若 GHCR 镜像为私有包，需要让 Docker 守护进程先登录 GHCR，或将包含登录凭据的 Docker config 以只读方式挂载给 Watchtower。

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

内容包 `dependencies` 支持 `kind`、`packageId`（也可匹配稳定 `contentId` 或历史 `aliases`）、`minVersion`、`maxVersion` 和 `required`。列表响应会返回 `dependenciesSatisfied`、`resolvedDependencies` 与 `unresolvedDependencies`；必需依赖缺失时不能发布、回滚为发布态或下载，从而避免客户端安装不完整的书源、样式和主题。

所有 API 异常响应都包含 `requestId`。未捕获异常只返回统一的 `INTERNAL_ERROR`，不会把服务器路径、凭据或 Python 异常详情暴露给客户端；可使用响应头 `X-Request-Id` 在审计日志中定位请求。

启用完整 API/Worker 编排时，Worker 默认每天创建一次服务器快照，并按 `REAMICRO_SERVER_SNAPSHOT_RETENTION` 保留最近 30 份；可在后台设置中调整是否启用、间隔和保留数量。后台云任务表提供立即执行、暂停和恢复操作，操作会记录审计事件。

阅微登录密钥使用 `REAMICRO_SECRET_KEY` 派生的 AES-GCM 密钥保存。生产环境必须设置随机长值并持久化；丢失或修改该值后既有凭据无法解密。账号密钥备份由 Android 客户端使用用户口令加密，服务器只保存 `RCRED1` 密文。

云任务支持 `yeshe_checkin`、`yeshe_draw_card` 和 `cloud_auto_read`。自动阅读可指定图书，未指定时读取阅微最近阅读记录；服务器按配置上报阅读进度和时长。

当前版本使用 Docker 数据卷内的 SQLite 事务库、JSON 配置与文件存储，适合个人或小规模部署。任务锁可避免同一数据卷上的多个 Worker 重复执行；真正的多主机部署仍应迁移到 PostgreSQL、共享对象存储和独立 Worker。

云任务和阅微凭据现使用 `/data/state/reamicro.sqlite3` 的 SQLite WAL 事务库。升级时会自动导入旧的 `tasks.json` 和 `credentials.json`，并把原文件保留为 `.migrated`。后台“服务器数据保护”可以创建包含数据库和 `server.json` 的 ZIP 快照，保存于 `/data/backups/server`，最多保留 30 份。存活与就绪检查分别为 `/health/live` 和 `/health/ready`。

生产部署建议启用 Compose 中的 `worker` 服务：API 设置 `REAMICRO_RUN_SCHEDULER=false`，Worker 设置为 `true`，并确保两者挂载同一个 `/data`。任务失败会指数退避并在超过重试上限后暂停；API 创建任务和凭据时可发送 `Idempotency-Key` 防止网络重试重复创建。若暂时只运行 API 容器，默认调度器仍保持兼容运行。

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

内容包支持 `status`（`draft`、`testing`、`published`、`unpublished`）、`channel`（`stable`、`beta`、`nightly`）和依赖数组 `dependencies`。客户端公共列表和下载仅返回 `published` 内容。后台上传会检查 JSON/CSS 编码与结构，旧版本自动进入 `history`；管理接口可查询历史、切换发布状态和回滚指定版本。书源应长期保持同一 `contentId`，把旧域名和旧书源 ID 加入 `aliases`，模块更新时即可保留既有图书关联。

可选配置 `REAMICRO_GITHUB_WEBHOOK_SECRET` 后，将 GitHub Release Webhook 指向 `/v1/webhooks/github`，Content type 选择 `application/json`，Secret 填写相同值，服务器会在 Release 发布后立即同步。模块 APK 下载支持 ETag 和 HTTP Range 断点续传；更新检查可通过 `/v1/releases/module/latest?channel=stable|beta|nightly` 选择渠道。

运维监控可使用认证请求访问 `/metrics` 获取 Prometheus 指标。主管理员可通过 `/admin/api-keys` 查看密钥元数据，并使用带 `X-Admin-CSRF` 的 POST `/admin/api-keys/{keyId}/revoke` 吊销泄露密钥。GitHub Actions 会先运行服务端单元测试和编译检查，只有通过后才构建并发布 GHCR 镜像。
