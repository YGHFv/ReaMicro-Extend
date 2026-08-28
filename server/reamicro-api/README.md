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

后台修改操作带有 CSRF 校验；每个请求会返回 `X-Request-Id`，异常和限流事件写入 `/data/audit/events.jsonl`，主管理员或具备 `audit:read` 权限的管理员可以通过 `/admin/audit` 查看。子管理员创建时可以分配 `settings:write`、`packages:write`、`tasks:write`、`module:sync`、`audit:read`、`backup:admin` 和 `security:write` 权限；未分配的操作会返回 403。`backup:admin` 允许创建和校验服务器快照，但**恢复快照始终只有主管理员可以执行**。

## 代码结构

按依赖分层，下层不引用上层：

| 模块 | 职责 |
|---|---|
| `runtime.py` | 路径、环境变量、锁与可变全局 |
| `responses.py` | 统一响应包裹 |
| `audit.py` | 审计事件与任务日志 |
| `config_store.py` | 配置读写、认证模式推断、模块上传许可 |
| `crypto.py` | 密钥派生、对称加密、口令哈希与强度校验 |
| `state.py` | 任务/消息/在线/凭据持久化，归属折叠迁移 |
| `labels.py` | 枚举与标识的中文显示名 |
| `security.py` | 认证、授权、会话、CSRF、限流、归属解析 |
| `packages.py` | 内容包清单、身份复用、元数据推断、域名归一、签名 |
| `releases.py` | GitHub Release 同步 |
| `jsonpath.py` | 书源规则用的轻量 JSONPath（模块端 OnlineJsonPathCompat 的移植） |
| `rule_check.py` | 书源规则级检测：真发搜索请求，看规则还能否取到书名 |
| `retention.py` | 数据保留：审计轮转、任务日志裁剪、内容包历史限量 |
| `backups.py` | 服务器快照与加密密钥轮换 |
| `executors.py` | 云端任务的实际执行 |
| `scheduler.py` | 调度循环与执行记账 |
| `admin/format.py` | 时间、耗时、大小、摘要的显示格式化 |
| `admin/style.py` | 样式表：设计令牌、语义色调、语义间距类 |
| `admin/layout.py` | 统一外壳、按 Accept 分流的错误页 |
| `admin/paging.py` | 列表分页与大文件尾部读取 |
| `admin/views.py` | 各分区 HTML 拼装 |
| `api/*.py` | 路由，按域分文件，只做校验与调用 |
| `main.py` | 应用装配：中间件、异常处理、router 注册、启动钩子 |

两条约定值得注意：

- **可变全局都住在 `runtime`**，各模块在调用时读 `runtime.X`，不要 `from app.runtime import X`
  （那样拿到的是导入时快照）。测试重定向数据目录就是改 `runtime` 的属性。
- **跨模块调用可被打桩的函数时通过模块引用**，例如 `releases_module.sync_module_release()`
  而不是 `from app.releases import sync_module_release`，否则测试打桩源模块不生效，
  会真去连外部服务。

## 管理后台

后台分为概览、内容管理（按类型分区）、云端任务、服务器设置、子管理员与安全五个部分，所有页面共用同一套侧栏与样式，内容编辑、预览、历史版本、版本差异、任务日志和审计日志都在同一外壳内。

- **概览**：内容库与任务统计、模块在线状态（按 10 分钟心跳租约判断）、离线任务消息队列，以及全部内容列表。
- **内容管理**：按类型筛选，支持搜索、编辑、预览、历史版本、版本差异对比、回滚和删除。已发布内容需要先下架才能删除。
  书源支持**多个名称与多个访问地址**：编辑页可填历史名称（每行一个）与访问地址（每行一个），
  并单独指定主地址。关联判定为「名称与地址两类**各命中至少一项**」——源改名（地址不变）
  或换域名（名称不变）都能继续关联并通过服务器更新；坚持两类都要命中是为了防冒用，
  只对名称会让同名的无关源撞在一起，只对地址会让同一站点上的不同源撞在一起。
  每次关联都会把双方的名称与地址取并集写回，集合越大后续匹配越准，旧名与旧域名永不丢弃。
  可**逐个或批量检测可用性**，也可勾选多项**批量上架、下架、删除或检测**（批量删除同样要求已发布内容先下架）——列表里显示上次检测结果与可用地址数，只要有一个地址可达就算该源仍然可用。
  检测只允许 http/https、拒绝内网与回环地址、不跟随跨主机跳转，避免被用来探测内网。

除地址可达性外还支持**规则级检测**：按书源自己的 `searchUrl` 与 `ruleSearch` 真发一次搜索，
看还能不能取出书名。这能发现地址检测发现不了的失效——站点改版后域名还在、首页 200，
但搜索接口字段变了，规则取不到任何东西。判定分四类：规则可用、规则失效（站点疑似改版）、
无法检测（规则依赖 JS 脚本，服务端没有 JS 引擎，**不算失效**）、不可达。
规则解析用 `jsonpath.py`，它是模块端 `OnlineJsonPathCompat` 的移植，语义与模块保持一致——
两边解析结果不同，检测结论就没有意义。
- **云端任务**：同步密钥的验证、启用停用和删除（删除会自动暂停依赖它的任务），任务的立即运行、暂停、恢复、取消和删除，以及执行日志。
- **服务器设置**：认证模式单选、模块 Release 同步（含"包含预发布 Release"开关）、快照策略、
  用户模块上传、内容包签名公钥，以及**数据保留阈值**（审计日志上限与历史份数、单任务日志上限、
  内容包历史保留份数）。页面下方显示各类数据的当前占用，可手动触发一次清理。
- **用户管理**：按阅微账号 ID 列出用户，显示各自的同步密钥、任务、上传内容数与在线状态。
  可逐个开关"连接服务器""上传内容库"与账号启用状态。模块首次上报心跳时自动建档；
  白名单里已有但未建档的账号可一键补齐。**停用后该账号的全部接口调用都会被拒绝**
  （能力发现与健康检查除外）。用户档案只能收回权限，不能授予白名单之外的权限。
  可为每个用户设置**上传上限**（内容包个数，0 为不限）：只限制新建，关联到已有内容包不占额度，
  否则用户想更新自己的源会被自己的配额挡住；删除内容后额度自动释放。后台上传的内容包没有
  归属，不计入任何用户的配额。
- **子管理员与安全**：子管理员列表与重置密码、停用、删除；API Key 列表与创建、吊销；服务器快照列表与创建、下载、校验、恢复；服务器加密密钥轮换；审计日志入口。

时间统一按北京时间显示，并附带相对时间；状态、渠道、任务类型和审计动作均显示中文说明。后台页面出错时返回可读的提示页而不是 JSON。

样式约定：**状态徽标只走 `.tone-ok` / `.tone-warn` / `.tone-bad` / `.tone-idle` / `.tone-info`
五种语义色调**（由 `labels.status_tone` 映射），不要把原始枚举值当 CSS 类名——那样新增一个
枚举值就会渲染成无样式的灰块，且不报错、很难发现。**间距用 `--gap-*` 变量与 `.stack` /
`.mt` / `.mb` 等语义类**，不写内联 style，否则同一种间距会在各处漂移。深色模式跟随系统
`prefers-color-scheme`，仅覆盖 `:root` 变量实现。

内容列表与审计日志分页显示，每页 50 条。审计日志从文件尾部按块回读，不把整个文件读进内存。

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

客户端首次只请求 `/v1/discovery` 判断服务器认证模式，再按服务器要求发送认证信息。配置 `REAMICRO_API_KEY` 后，客户端请求需要携带：

```text
X-ReaMicro-Api-Key: <key>
```

当前实现提供：

- `/v1/health`（公开存活检查，不需要 API Key；详细依赖状态仍使用 `/health/dependencies`）
- `/v1/discovery`（公开能力发现，不返回密钥、账号或白名单内容）
- `/v1/meta`
- `/v1/diagnostics`
- `/v1/releases/module/latest`
- `/v1/packages?kind=online_source|epub_style|highlight_style|association_source|theme`
- `/v1/packages/{kind}/{packageId}/download`
- `/v1/packages/upload/policy`、`/v1/packages/upload` 与 `/v1/packages/match`（模块上传与关联内容库）
- `/v1/backups/module` 与 `/v1/backups/module/latest`
- `/v1/backups/credentials` 与 `/v1/backups/credentials/latest`
- `/v1/credentials/reamicro`
- `/v1/tasks` 以及暂停、恢复、立即执行、配置、删除和日志接口

内容包 `dependencies` 支持 `kind`、`packageId`（也可匹配稳定 `contentId` 或历史 `aliases`）、`minVersion`、`maxVersion` 和 `required`。列表响应会返回 `dependenciesSatisfied`、`resolvedDependencies` 与 `unresolvedDependencies`；必需依赖缺失时不能发布、回滚为发布态或下载，从而避免客户端安装不完整的书源、样式和主题。

所有 API 异常响应都包含 `requestId`。未捕获异常只返回统一的 `INTERNAL_ERROR`，不会把服务器路径、凭据或 Python 异常详情暴露给客户端；可使用响应头 `X-Request-Id` 在审计日志中定位请求。

启用完整 API/Worker 编排时，Worker 默认每天创建一次服务器快照，并按 `REAMICRO_SERVER_SNAPSHOT_RETENTION` 保留最近 30 份；可在后台设置中调整是否启用、间隔和保留数量。后台云任务表提供立即执行、暂停和恢复操作，操作会记录审计事件。

阅微登录密钥使用 `REAMICRO_SECRET_KEY` 派生的 AES-GCM 密钥保存。生产环境必须设置随机长值并持久化；丢失或修改该值后既有凭据无法解密。账号密钥备份由 Android 客户端使用用户口令加密，服务器只保存 `RCRED1` 密文。

云任务支持 `yeshe_checkin`、`yeshe_draw_card` 和 `cloud_auto_read`。自动阅读可指定图书，未指定时读取阅微最近阅读记录；服务器按配置只上报阅读时长，不修改书籍阅读进度。

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

## 用户模块上传内容库

后台「服务器设置 → 用户模块上传」勾选启用，并把允许上传的阅微账号 ID 逐行填入上传白名单后，模块端「API 服务器 → 上传内容库」即可把本机书源和关联源提交到服务器内容库。模块只能上传 `online_source` 和 `association_source`，单个内容上限 8 MB；样式和主题仍然只能由后台管理员发布。

同一个源的判定口径是**名称 + 域名**：书源的域名取自 `bookSourceUrl` 等字段的主机名，忽略协议、端口、`www.` 前缀和大小写。关联源是 ZIP 归档、清单里通常没有站点地址，此时退化为**名称 + 稳定标识**（`id`、`contentId` 或历史 `aliases`）比对，避免仅凭名称误判。

上传时服务器命中已有同名同域的源，不会覆盖服务器内容，只把 `packageId`、`contentId` 和当前版本回给模块建立关联；未命中才新建内容包，并写入 `uploadOwner` 记录上传者。新建的包同样立即回关联信息，因此两种情况模块都能在后续「检查内容库更新」时收到该源的更新。

模块端「关联内容库」调用 `/v1/packages/match` 批量比对，命中的源全量登记关联。关联只写映射不下载内容，登记版本为 `0.0.0`，因此下一次检查更新一定判定为有新版本，用服务器版本覆盖本机内容；书源沿用原有源 ID、关联源沿用原有文件名，已下载图书、登录凭据和下载策略不会失联。

内容包支持 `status`（`draft`、`testing`、`published`、`unpublished`）、`channel`（`stable`、`beta`、`nightly`）和依赖数组 `dependencies`。客户端公共列表和下载仅返回 `published` 内容。后台上传会检查 JSON/CSS 编码与结构，旧版本自动进入 `history`；管理接口可查询历史、切换发布状态和回滚指定版本。书源应长期保持同一 `contentId`，把旧域名和旧书源 ID 加入 `aliases`，模块更新时即可保留既有图书关联。

可选配置 `REAMICRO_GITHUB_WEBHOOK_SECRET` 后，将 GitHub Release Webhook 指向 `/v1/webhooks/github`，Content type 选择 `application/json`，Secret 填写相同值，服务器会在 Release 发布后立即同步。模块 APK 下载支持 ETag 和 HTTP Range 断点续传；更新检查可通过 `/v1/releases/module/latest?channel=stable|beta|nightly` 选择渠道。

渠道语义：预发布 Release 同步为 `beta`，正式 Release 同步为 `stable`。只有请求 `channel=stable` 时会拒绝 `beta` 版本并返回 404 `RELEASE_NOT_FOUND`；请求 `beta` 或 `nightly` 时正式版和预发布版都会返回。若仓库只发布预发布 Release，必须在后台勾选“包含预发布 Release”，否则 GitHub 的 `/releases/latest` 找不到正式版，同步会失败。模块端“模块更新渠道”默认选择预发布，与本项目 CI 的发布方式一致。

Release 的 `versionName` 优先取 tag，tag 不是语义版本号时（例如 CI 的 `ci-123-1`）从 Release 标题中提取，便于客户端按版本号比较新旧。

运维监控可使用认证请求访问 `/metrics` 获取 Prometheus 指标。主管理员可通过 `/admin/api-keys` 查看密钥元数据，并使用带 `X-Admin-CSRF` 的 POST `/admin/api-keys/{keyId}/revoke` 吊销泄露密钥。GitHub Actions 会先运行服务端单元测试和编译检查，只有通过后才构建并发布 GHCR 镜像。
