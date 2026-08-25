# ReaMicro API Server

## 启动

```bash
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:5222/v1/health
```

默认 API 端口为 `5222`，后台管理地址为 `http://服务器地址:5222/admin`。
必须设置 `REAMICRO_ADMIN_PASSWORD` 后才能进入后台。后台使用 HTTP Basic 认证，默认用户名为 `admin`。

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
  -e REAMICRO_ADMIN_PASSWORD='请修改为强密码' \
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
