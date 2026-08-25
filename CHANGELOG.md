# 更新记录

## 主管理员首次初始化与子管理员 - 2026-08-25

- 后台主管理员改为数据卷持久化的 PBKDF2 密码哈希；首次使用环境变量引导账号登录时强制设置新的主管理员用户名和至少 12 位密码，初始化后旧引导密码立即失效。
- 后台支持分发子管理员账号，可由主管理员创建、停用、启用、随机重置密码和删除；子管理员不能管理管理员账号，避免权限升级。
- 管理后台设置页增加一键生成高熵 API Key，完整密钥仅在保存结果页显示一次，避免手工生成弱密钥。
- 增加主管理员初始化、子管理员权限隔离、停用账号和长密钥生成测试，并补充 Docker/1Panel 迁移说明。

## Docker 镜像自动发布 - 2026-08-25

- 新增独立 GitHub Actions 工作流，服务器代码推送到主分支时自动构建并发布 GHCR Docker 镜像。
- 镜像同时支持 `linux/amd64` 与 `linux/arm64`，发布 `latest`、分支、版本标签和提交 SHA 标签。
- Pull Request 只执行多架构构建验证，不推送镜像；构建启用 GitHub Actions 缓存、OCI 元数据、provenance 和 SBOM。
- 增加 Docker 构建忽略文件，排除本地环境文件、数据卷、测试缓存和 Python 字节码。
- Docker Compose 增加 Watchtower，默认每 5 分钟检查 GHCR `latest` 镜像并自动滚动更新 API 容器、清理旧镜像；仅更新显式标记的 ReaMicro 容器。
- 补充 1Panel 与私有 GHCR 部署说明：镜像仓库登录、`read:packages` 权限和 Watchtower 读取 Docker config 的挂载方式。

## 云端自动化任务安全与后台配置 - 2026-08-25

### 阅微云端任务

- 云端自动阅读、野社零点签到和野社自动抽卡统一由 API 服务器使用用户主动上传并验证的阅微登录密钥执行；密钥只以 AES-GCM 密文保存，任务列表、日志和错误响应均不返回或记录密钥。
- 自动阅读支持配置每日执行时间、阅读时长、自定义图书列表；未填写自定义图书时通过阅微接口读取最近阅读记录，并上报阅读进度和阅读时长。
- 删除阅微凭据会自动暂停关联任务；阅微 401、403、429 响应会暂停任务，避免持续重试触发风控。
- Android 设置页新增已有凭据选择、密钥上传验证、签到/抽卡/自动阅读开关、阅读时长、自定义图书和立即执行入口。

### 后台管理与任务安全

- `/admin` 云任务表单改为支持三类阅微任务，通用 HTTP 任务字段收纳到高级选项；请求 URL 不再被强制要求。
- 任务创建和配置会校验阅微凭据归属、规范化 `HH:MM` 执行时间，并按 `taskType + credentialId` 区分不同账号的任务，避免多账号配置互相覆盖。
- 任务调度器跳过运行中的任务，任务控制接口统一返回脱敏任务视图，不暴露内部加密请求字段。
- 新增服务端安全单元测试，覆盖凭据 owner 隔离、任务密文脱敏和每日时间校验。

## 内容包安装、主题与回滚管理 - 2026-08-25

- 内容库自动更新与手动检查扩展到在线书源、关联源、主题、成书样式和高亮样式五种类型。
- 关联源包按服务器清单中的真实文件扩展名写入外部源目录，安装后主动清除加载缓存；主题包支持 light/dark 双配色并接入模块原生弹窗主题。
- 内容包缓存按类型、包 ID、版本保存 payload 和清单，安装记录保存内容实体 ID、版本、构建时间与类型，避免不同类型同名包互相覆盖。
- 设置页新增已安装内容包管理，可查看版本、启用主题、回滚到本地保留的历史版本或卸载内容包。
- 后台云任务区域增加已上传阅微凭据表格，创建阅微任务时校验凭据与任务所有者一致，并统一校验每日执行时间。

## 账号密钥端到端加密备份 - 2026-08-25

- 普通模块设置备份现在明确过滤在线源用户名、密码、登录字段、Cookie 和源变量，恢复普通备份时也拒绝写入这些敏感键。
- 新增独立账号密钥备份：客户端使用 PBKDF2-HMAC-SHA256 从用户口令派生 256 位密钥，再用 AES-GCM 加密；服务器只接收带 `RCRED1` 标识的密文，无法读取账号内容。
- 密钥备份按认证用户隔离并最多保留 10 份历史版本，设置页提供加密上传与下载恢复入口。
- Docker 增加 `REAMICRO_SECRET_BACKUP_ROOT` 数据目录配置，服务端部署文档补充阅微凭据密钥、单实例调度和密文备份说明。

## API 云服务与内容热更新 - 2026-08-25

### 后台内容文件管理

- 管理后台新增内容包上传表单，可直接添加或更新在线书源、成书样式、阅读高亮样式、关联源和主题文件。
- 上传时自动生成 `manifest.json`、SHA-256、构建时间、稳定内容 ID 和别名；同一包 ID 更新时自动把上一版本归档到 `history/`。
- 配置服务器 Ed25519 PEM 私钥后，后台上传的内容包自动签名，客户端可直接完成安全更新。

### 模块设置备份

- 新增模块设置 ZIP 备份协议和服务器接口：`POST /v1/backups/module`、`GET /v1/backups/module/latest`。
- 备份包含模块 SharedPreferences、在线书源文件和备份清单，不包含 API Key、账号密码、Cookie 等 API 服务器凭据。
- 服务器按 API Key 哈希隔离备份，最多保留 20 个历史备份；客户端设置页提供上传和恢复入口。

### 服务器认证与白名单

- 服务器认证补齐公开访问、API Key、独立账号密码和阅微账号 ID 白名单四种模式，客户端已有认证配置可以直接使用。
- 管理后台可以新增、更新或删除独立账号，并按行维护阅微账号白名单。
- 独立账号密码使用 PBKDF2-HMAC-SHA256 加盐保存，不在配置文件中存储明文；备份数据按 API Key、独立账号或阅微账号分别隔离。

### 模块端 API 服务层

- 模块设置新增自定义 API 服务器，可配置公开访问、API Key、独立账号和阅微账号白名单认证模式。
- API Key 与独立账号密码使用 Android Keystore 加密保存；服务器不可用时自动降级，不影响模块原有本地功能。
- 新增服务器健康检查、能力发现、本地能力缓存、同源下载限制和禁止跨域重定向，避免认证凭据被第三方下载地址获取。

### 书源与样式版本更新

- 建立统一内容包协议，支持在线书源、成书样式、阅读高亮样式，并为关联源和主题包预留类型。
- 内容包按版本号和构建时间双重判断更新；同版本重新构建时，只要远端 `buildTime` 更新也会触发升级。
- 下载后校验 SHA-256；服务器配置 Ed25519 公钥时，未签名或签名错误的内容包会被拒绝。
- 书源版本身份与域名分离：包清单通过固定 `contentId` 和历史 `aliases` 维持书源身份，更新域名和规则后，旧图书、登录凭据、下载策略及按需章节仍能找到更新后的书源。
- 模块启动后每天最多自动检查一次内容包更新，设置页也提供手动检查入口。

### 模块版本下发与安装

- API 服务器定时同步 GitHub Release，缓存最新 APK、版本信息、构建时间和 SHA-256，并提供模块更新检查与下载接口。
- 模块版本比较同时使用 versionCode、versionName 和构建时间，解决同版本号重新构建无法识别的问题。
- 模块可以从服务器下载 APK、校验摘要，并通过受限 ContentProvider 交给 Android 系统安装器确认安装。

### Docker 服务器与管理后台

- 新增可独立部署的 FastAPI Docker 服务，默认端口为 `5222`。
- 新增 `/admin` 管理后台，使用单独的 HTTP Basic 管理认证；管理密码未配置时后台拒绝访问。
- 后台可调整 API Key、公开访问、功能列表、最低模块版本、签名公钥、GitHub 仓库、Token、预发布策略和同步周期，设置持久化到 `/data/config/server.json`。
- 后台支持直接上传或覆盖书源、成书样式、高亮样式、关联源和主题文件，自动生成清单、SHA-256、构建时间并保留历史版本；配置私钥后自动生成 Ed25519 签名。

## 2.3.1 beta 适配 - 2026-08-19

### 所有模块设置页顶部的返回导航栏消失

- 修复升级到阅微 **2.3.1 beta（versionCode 2310）** 后，模块所有补全设置页顶部的返回栏（返回箭头 + 页面标题）整条不见的问题。页面正文照常渲染，也不崩溃，所以从表现上看不出是 hook 挂了。
- 根因是宿主 `AppTopBar` 在 `onNavigationBack` 之后新增了一个 `contentColor: Color` 参数，连带产生两个独立的失效点：
  - **方法名变了。** `Color` 是 inline value class，Kotlin 会给带这类参数的 JVM 方法名追加 mangling 后缀，`AppTopBar` 因此变成 `AppTopBar-cd68TDI`。模块按 `name == "AppTopBar"` 精确匹配，直接落空。异常被 Compose 函数代理的 `runCatching` 吞掉降级为空内容——这就是为什么顶栏静默消失而不是崩溃，只在 LSPosed 日志留一行 `AppTopBar not found`。
  - **基元形参不能传 null。** 该参数在 JVM 上是 `long`，而模块对所有「交给宿主默认值」的参数一律填 `null`。即使 `$default` 掩码已置位、宿主会忽略实参，`Method.invoke` 仍要先过一遍形参类型校验，给基元 `long` 传 `null` 会抛 `IllegalArgumentException`。这一点在修好方法名之后才会暴露，属于同一次升级的第二层。
- 修复方式：
  - 新增 `ComposeInterop.findComposableMethod`，按「基础名（`name == base` 或 `name.startsWith("base-")`）+ 末三参 `(Composer, int, int)` + 可选首参类型」定位宿主 @Composable，取参数最多者。连字符和尾参形状这两条都不可省：`AppTopBar_cd68TDI$lambda$3` 恰好也是 11 参、首参 `String`，只靠名字前缀加「取参数最多」会误选到它。
  - 顶栏实参铺设抽成 `core/AppTopBarArguments`，未覆盖的基元形参按类型填零值（`long`→`0L`、`float`→`0f`…）而不是 `null`，取值仍由宿主默认值决定。逐类型列全而不只处理 `long`，是因为 `Dp`/`TextUnit` 这类 inline class 同样落到基元上，下次宿主再加可选参数不该再断一次。
- 同一次升级还静默打坏了 **WebDAV / 本地书库账号页的标题改写**（`hookWebDavAccountTopBarTitle` 用的是同一个精确名字匹配），一并修掉。这处只在日志留一行、UI 上完全看不出，容易漏。
- 顺带做了一次全量核对：把模块里 255 个宿主方法名常量与 2.3.1 宿主 228 个类的 4285 个方法名逐一比对，确认本次只有 `AppTopBar` 这两处是真实失配，其余写死 mangling 后缀的常量（`Scaffold-TvnljyQ`、`ListItem-HXNGIdc` 等）在 2.3.1 下仍然成立。

### 顶栏参数铺设补测试

- 这处下标映射在 2.2.0 → 2.3.0 → 2.3.1 三次升级里连续断了三次，而三次的表现都是「顶栏静默消失」：编译器不报错、真机不崩、单测也覆盖不到（此前只有类名字符串的锁定测试，不校验方法签名）。
- 现在 2.2.0 的 8 参、2.3.0 的 9 参、2.3.1 的 10 参三代签名都钉在 `AppTopBarArgumentsTest` 里，逐一断言各参数落位与 `$default` 掩码取值。其中一条用等价形状的静态方法真的走一次 `Method.invoke`，直接复现「基元传 null」会抛的那个异常，而不只是断言数组内容。

## 2.0.0 - 2026-08-11

主版本号变更的原因是代码结构整体重排：功能与行为没有变动，但源码的组织方式和以前完全不同了。

### 三个既有问题

- **高亮日志开关关不掉日志**：设置页那个「高亮日志」开关此前只接到了界面上，代码里没有任何地方读它，只有性能日志一处生效。正文每渲染一段就产生一条的 `dialogue highlight applied` 完全不受控。现在 9 处非错误的高亮日志统一受开关管辖，错误日志不受影响，任何时候都能看到。
- **卡片头图贴住页顶**：两处成因。预览里抵消 body 上内边距的那条规则是无条件注入的，且优先级高于样式自身，把卡片头图、浮印留白头图这两个本该在上方留白的样式也拽到了页顶；成书那侧则是头图 `figure` 作为 body 第一个子元素，上外边距按 CSS 规则折叠到了 body 外面。现在两处都按「头图自己的上外边距是否为 0」判断该不该贴边——不看 `duokan-bleed`，因为电影裁幅头图只声明了 `leftright`、细线装帧头图根本没声明，但两者都该贴顶。新增测试锁住全部 14 个内置头图样式的贴边归属。
- **切换深浅色时背景闪烁**：三处独立成因，靠真机日志逐个定位。
  - 宿主两个主题共用同一个 `EpubBackgroundState`，按实例记住的主题在切换瞬间是切换前的值，导致切到深色时先闪一下浅色的图。
  - `invalidateHostBackgroundState` 先写入一次性刷新令牌再写回原值来精确失效 composition，有一帧落在两次写入之间时令牌会被当成背景地址交给宿主，加载不到就画出没有背景的一帧；令牌还会被记成「宿主原值」，下次恢复时永久写回。
  - 最主要的一处：深色下宿主会把深浅两个分支**都执行一遍**，而分支包装 lambda 里写了 `currentReaderDark = dark`，浅色分支跑完把全局的当前阅读主题改成了浅色且没有还原，之后所有不在分支内的读取都按浅色解析——翻页时每页重来一遍，就是在两套背景之间来回闪。现在当前阅读主题只由 `rememberReaderDark` 一处维护，分支只声明「此刻在渲染哪个分支」并在退出时还原；主题面板里三处同类的越权写入也一并去掉。
  - 取值判定抽成 `ReaderBackgroundValueResolver` 并补了 7 条测试——这类一帧的问题真机上截图根本抓不到。

### 启动自检

- 每个 hook 安装动作都会登记结果，启动时打印一行 `hook installed N/M, failed: [...]`；设置页「关于 → 模块自检」可以看按功能分组的明细与失败原因。宿主升级后先看这一行就能定位掉线的 hook，不必再靠功能表现反推。
- 单个 hook 抛异常不再中断后续 hook 的安装。此前一个功能挂掉会连带它后面的所有功能一起消失，而日志里只有一行栈。

### 宿主类名集中管理

- 散落在 24 个文件里的 442 处宿主/框架类名字面量收敛成 `core/HostClasses.kt` 的 225 个常量。宿主升级时改这一个文件即可，不必再满仓 grep。其中 17 个类名此前在不同文件里用不同常量名重复定义过。
- 新增测试把 225 个取值逐一钉死：这些字符串是模块与宿主之间的唯一契约，写错一个字符功能就静默消失而编译器毫无反应。
- 顺带修掉一个隐患：`WebDavDriveHook` 里的 Compose 颜色转换硬编码了混淆方法名 `toArgb-8_81llA` 且没有兜底，而另外三处都是「按名字前缀查找 + 位运算兜底」，Compose 版本一升级只有这一处会崩。

### 设置项存储契约

- 给 70 个 SharedPreferences 键名与 56 个默认值加了锁定测试。键名就是磁盘上的键，改一个字符等于把用户已有设置全部丢弃，而编译器和运行时都不会报错。

### 代码结构重排

三个巨型文件按功能簇拆开，行为零变更：

| | 之前 | 之后 |
| --- | --- | --- |
| `WebDavDriveHook` | 14635 行 / 619 函数 | 拆成 9 个文件，最大 2313 行 |
| `ReaMicroSettingsHook` | 8874 行 / 433 函数 | 拆成 12 个文件，最大 1842 行 |
| `ReaderHook` | 7088 行 / 405 函数 | 拆成 9 个文件，最大 2249 行 |

- 拆分由可重放的脚本完成（`tools/split-hook.mjs` 等），每个函数搬迁后都会把反缩进结果重新缩进回去与原文逐字节比对，不一致直接中止；三引号原始字符串内容保证未被改动。搬完再用多重集比对确认没有任何函数体被改写。
- 用编译器判定出 176 个不依赖 hook 实例的函数，抽成 `online/epub`、`online/search`、`online/download`、`cloud/webdav`、`cloud/local` 五个引擎包，这些现在可以直接跑 JVM 单测。
- 包结构归位：此前 `webdav/` 包里 22 个文件只有 4 个真跟 WebDAV 有关，其余是在线补全的 EPUB 生成、下载与搜索逻辑，同一个功能被拆在两个语义不符的包里。`compat/` 并入 `core/`。
- 分层约定与工具清单见 `docs/architecture.md`。

### 测试与度量

- 单元测试 284 → 360 项。
- 引入 detekt 做体积与复杂度度量，只报数不阻断构建（`./gradlew :app:detekt`）。`LargeClass` 从 5 处降到 2 处，且都不是上面那三个文件。

## 1.4.8 - 2026-08-11

### 修复阅微 2.3.0 beta 新构建导致的深色背景失效
- 从 LSPosed 模块日志里拿到了确凿证据：`hook epub background failed: EpubBackground 方法未找到`，整个 `hookEpubBackground` 没装上。
- 根因是宿主重构了正文背景：新增 `EpubBackgroundState`，`EpubBackground` 由 `(Composer, I)` 变成 `(EpubBackgroundState, Composer, I, I)`，`EpubContainer` 的 Composer 下标由 12 移到 13，背景值也从 `ComposableSingletons` 的 lambda 改由 `EpubBackgroundState.getBackground()` 提供（原来的 `getLambda$1672513034` 已不存在，同名 singleton 的 lambda 变成了 Function3）。
- 修复：方法定位一律改成「方法名 + 动态查找 Composer 参数下标」，不再写死参数个数与位置；背景值读取优先挂 `EpubBackgroundState.getBackground()`，找不到时回退旧的 singleton lambda；深色分支不再自造 lambda，而是直接复用宿主传给 `ThemeToggleContent` 的浅色绘制内容 —— 宿主换实现也不受影响。
- **背景跟随深浅色切换**：新版整个阅读器共用一个 `rememberEpubBackgroundState` 实例，按实例缓存主题会把第一次的深浅结果永久钉死，导致切主题后背景不跟着变。走新路径时改用实时的主题判定，不再按实例缓存。

### 分割线识别规则收紧
- **只有前后都还有正文的省略号段才算转场**：章节开头、结尾的孤立省略号是排版装饰或残留，不再误判成分割线。
- **连续多段省略号合并成一条分割线**，不再连出好几条。
- 规则抽成 `OnlineBodyMarkup.planTransitions` 纯函数，生成与迁移两条路径共用同一套判断。

### 早期下载的图书一并迁移
- 最早下载的章节里省略号仍是普通 `<p>……</p>`，历史分割线的结构也未必对得上当前样式（菱形、渐隐线等把效果挂在 `te-transition--*` 上，结构不对就退回显示原始符号）。现在点「更新」时会把这两种情况都改写成当前分割样式的结构。

### 头图预览贴边
- 贴边头图靠 `duokan-bleed` 出血到页顶，WebView 不认这个指令，预览里头图上方总留一条纸色。预览样式里抵消掉 body 的上内边距，贴边效果与成书一致。
- 上一版头图预览完全不显示，是因为 Android 11 起 WebView `allowFileAccess` 默认为 `false`，合成好的头图与设备字体都走 `file://` 被静默拦下；头图 `figure` 又是 `line-height: 0`，图片失败后高度归零就整个不见了。预览 WebView 已开启文件访问。

## 1.4.7 - 2026-08-10

### 分割样式按各自结构渲染
- 修复「除第一个以外的分割样式预览都还是 ※※※」。根因：预览和成书都写死输出 `p.te-divider-line`，而菱形转场、文字转场、渐隐线转场把效果挂在 `te-transition--*` 修饰类上，图片转场则要 `div.te-divider-image > img`，只换 CSS 不换结构自然看不出区别 —— **成书里同样不生效**，不只是预览问题。
- 样式模型新增 `markup`，从 TEpub-Editor 一并移植（共 9 条样式带结构）。预览与成书都按样式自带的结构渲染，图片转场会把 markup 里的 `src` 换成书内实际路径；样式没带结构时仍用默认的文字分割线。新增测试守住 5 条分割样式的预览各不相同。

### 标题字号修正
- 修复部分标题样式（清爽杂志章题、电影字幕章题等）标题过大到换行。根因：内置样式移植自 TEpub，那边标题标签是 `h3`（默认 1.17em），模块用的是 `h1`（默认 2em）；这些样式的 `.te-chapter-title` 没有显式 `font-size`，于是 2em 再乘 `.te-chapter-name` 的 1.16~1.26em，直接撑到 2.3em 以上。
- 基础 CSS 增加 `h1.te-chapter-title, h1.te-volume-title { font-size: 1.17em }` 对齐 h3 基准，样式自己声明了字号的照常覆盖。

### 头图预览显示蒙版效果
- 头图预览此前只是把原图（或占位图）塞进 `figure`，看不出蒙版裁切。现在预览会先用 `OnlineHeaderImageComposer` 按该样式的蒙版实际合成一遍再显示，与成书结果一致。
- **还没选原图时也能看**：新增 `composePlaceholder`，用渐变示意图套蒙版，先看清蒙版裁出的形状再决定选哪张图。合成结果按「样式 + 原图 + 修改时间」缓存，避免每次输入 CSS 都重算上百万像素。

### 其它
- 移除「下载配置」页底部的**「应用到已下载图书」**按钮，相关批量重写代码一并删除。
- 字体预览只在支持字体的类别（标题 / 卷标）加载。

### 阅微更新后的接口核查
- 设备上的阅微为 2.3.0 beta（versionCode 2202）新构建，`base.apk` 由 58,012,842 增至 58,719,380 字节。已核对 MCP 侧样本与设备包 `classes.dex` 字节数一致（30,824,488），确认分析的是同一版本。
- **把模块硬编码引用的 121 个宿主类逐一在新包中校验，全部存在**；深色背景所依赖的 `ReaderThemesKt` 方法签名与 `ComposableSingletons$ReaderThemesKt` 的 `lambda__1576463935$lambda$0`（Lottie 月亮动画锚点）均未变化。
- 因此**深色背景失效的原因本轮未能定位**，不是类或方法被改名。模块日志走 LSPosed 自有日志、不进 logcat，需要导出 LSPosed 日志或提供具体复现步骤后再排查。

## 1.4.6 - 2026-08-10

### 字体设置收敛到标题与卷标
- 头图、分割、插图三类样式不再显示字体选择与字体模式 —— 这些位置基本没有成段文字，字体设置只是干扰。生成成书 CSS 时也一并把关，即使历史配置里带了字体也不注入。
- **内置样式 CSS 里的 `font-family` 全部清洗掉**（生成脚本新增 `stripFontFamily`）。字体统一由样式面板的字体选择控制，CSS 里再写一份只会互相打架，而且 TEpub 样板里的 `"zdy1"`、`"llf"` 这类字体名在设备上根本不存在。
- **字体默认改为「仅声明字体名」**，直接调用本机同名字体，不再默认把字体文件塞进 EPUB（每本书省下几 MB）。需要嵌入时仍可在弹窗里切到「嵌入 EPUB」。

### CSS 分段改为按需展开
- 「整体 / 序号 / 内容」等分段现在**默认全部不选中，CSS 编辑框隐藏**；点某一段才展开并载入该段 CSS，再点同一段则回写内容并收起。所有五类样式的编辑都是这套逻辑。
- 分段与标题各部分的对应关系（本次核对无误）：**序号** = `.te-chapter-number`，即「第三章」；**内容** = `.te-chapter-name`，即标题正文；**整体** = `.te-chapter-title`，外层容器。卷标同理对应 `.te-volume-number` / `.te-volume-name` / `.te-volume-title`。上一版看起来错乱是因为首段 CSS 被清空（已于 1.4.5 修复），映射本身一直是对的。

## 1.4.5 - 2026-08-10

### 修复标题样式显示不正常：CSS 首段被清空
- 修复打开成书样式配置弹窗时，**第一段 CSS 被抹掉**的问题。表现为 CSS 输入框空着只显示灰色提示，预览里标题左对齐、字号超大 —— 因为 `.te-chapter-title` 的 `text-align: center` / `font-size` / `margin` 整段丢失，只剩序号段和内容段生效。点过「完成」或「设为当前」后这份被清空的 CSS 还会存进配置。
- 根因：分段切换函数 `switchSection` 的第一步是「把输入框当前内容回写到上一段」，而首次进入弹窗时直接调用了它，此刻输入框还是空的，于是刚解析出来的第一段被空串覆盖。
- 拆成 `loadSection`（只载入、不回写，供首次进入）与 `switchSection`（先回写旧段再载入新段），首次进入改用前者。新增回归测试：对全部 47 条内置样式做「拆段 → 拼回」往返，逐块比对声明必须一字不差，并单独钉住 `title-classic-red` 的居中与字号声明。

### 内置样式可重置
- 上述 bug 会把被污染的 CSS 存成内置样式的改写记录，修复代码后这些记录仍在。配置弹窗新增**「重置为内置」**按钮（仅内置样式显示），丢弃该样式的全部改写记录与删除标记，恢复内置库原样。
- 顺带调整按钮布局：「导出」与「重置为内置」单独一行，主按钮行保持「删除 / 设为当前 / 完成 / 取消」，避免五个按钮挤在一行。

## 1.4.4 - 2026-08-10

### 配置弹窗预览重做：与成书 CSS 完全一致
- 修复预览效果与实际排版严重不符。根因：预览只把**当前样式那几段 CSS** 注入 WebView，没带基础排版（body 楷体、`p{text-indent:2em}`、`.te-volume-page` 容器等），还硬编码了 `p{margin:0.6em 0}` 和 WebView 默认白底 16px，与成书是两套渲染。
- 新增 `OnlineEpubStylePreview`：预览注入的 CSS 就是 `OnlineEpubStyleCss.build()` 的输出 —— 与写进 `Styles/default.css` 的内容逐字一致。`OnlineEpubStyleSettings.withDraft` 把编辑中的草稿覆盖进设置再拼装，改一个字预览就跟着变。
- 预览侧只保留最小 reset（`html/body/img` 三条），另加一层模拟阅微阅读页的纸张外观（`#F5EFE0` 底、17px、`20px 18px` 页边距）。预览高度由 240dp 提到 320dp 并允许滚动。
- 顺带用 MT MCP 核过宿主的 CSS 能力：`org.epub.css.property.*` 有 679 个类（含 `BoxShadow`/`BorderRadius`/`BackgroundImage`/`CSSGradient`/`Content`/`Display`），`org.epub.css.query.Selector` 支持 `@font-face`，内置样式与字体嵌入在宿主侧无需降级。

### 图片装饰转场可以选图了
- 样式模型新增 `assetPath`。分割样式的 CSS 含 `.te-divider-img`（即「图片装饰转场」）时，配置弹窗出现「选择装饰图 / 更换图片」按钮；选中的图复制到模块私有目录 `reamicro_epub_style_assets/`。
- 成书时分割行改为输出 `div.te-divider-image > img.te-divider-img`，图片写入 `OEBPS/Images/divider.<ext>` 并登记 manifest；没选图时仍是原来的文字分割线。

### 头图蒙版与自动生成
- 新增本地脚本 `tools/gen-header-masks.mjs`：把 TEpub-Editor 的 8 张样板图提取 alpha 通道存成 8-bit 灰度 PNG，落到 `app/src/main/assets/epub_header_mask/`。原图共 ~17 MB，只取蒙版后**合计 647 KB**。
- `tools/gen-epub-styles.mjs` 补上解析 `headerTemplateStyle(...)` 工厂调用的能力，之前跳过的 7 条样板头图现在都进了样式库，并各自带上 `maskAsset` / `sampleWidth` / `sampleHeight`。内置样式总数 40 → **47**（14 头图 / 12 标题 / 4 插图 / 5 分割 / 12 卷标）。
- 新增 `OnlineHeaderImageComposer`，照搬 TEpub 的 `buildProcessedHeaderFromAsset`：按样板尺寸建画布 → 用户图 cover 缩放居中 → 蒙版拉伸后以 `PorterDuff.Mode.DST_IN` 裁切（先抽样确认蒙版确有透明像素）→ 输出 PNG。蒙版通过既有的 `ReaderHighlightImageAssets` `asset://` 机制从模块 APK 读取。
- 头图样式弹窗新增**套用范围**：关闭 / 每章 / 卷首页 / 每卷首章（默认关闭）。选好原图并选定范围后，合成结果写入 `OEBPS/Images/header.png`（全书一份），按范围插到章节或卷首页正文顶部的 `figure.te-header-figure`。范围一旦不为「关闭」，头图 CSS 也会进入成书样式表。

### 字体两种模式
- 样式新增 `embedFont`，弹窗字体行下方多一组二选一：**嵌入 EPUB** / **仅声明字体名**。
- 嵌入模式与此前一致（复制进 `OEBPS/Fonts/` + `@font-face`）；仅声明模式不复制任何文件，CSS 里直接写字体文件名（去扩展名）作为 `font-family`，交给阅读器自行解析。预览两种模式都用 `file://` 直读设备字体，所见即所得。

### 样式导出导入带图
- 配置弹窗按钮行新增**「导出」**，写到下载目录；列表页首行长按导入、每行长按导出保留。
- 导出 JSON 把关联图片以 base64 内嵌（`assetData` / `assetName`），并带上 `embedFont` / `maskAsset` / 样板尺寸；导入时图片自动还原到模块私有目录并回填路径，别人拿到单个 JSON 即可开箱即用。字体因体积过大仍只导出选择路径。

### 仓库同步
- `.gitignore` 里 `tools/` 整目录忽略导致代码生成脚本没入库。改为 `tools/*` 加两条放行：`!tools/gen-epub-styles.mjs`、`!tools/gen-header-masks.mjs`。头图蒙版 assets 随源码入库。其余忽略项（`.claude/`、`outputs/`、`source-files/*.rmsource`、`external-sources/` 等）经核对均为有意保留。

## 1.4.3 - 2026-08-10

### 在线补全新增「下载配置」：成书样式可自定义
- 「在线补全」设置页顶部新增独立的**下载配置**入口，点进去可分别配置**标题样式 / 头图样式 / 分割样式 / 卷标样式 / 插图样式**五类。每类的列表页与「高亮样式」页完全一致：首行「添加配置」（点击新建、长按导入 JSON），其下每条样式一行，当前选中项标「当前」，点击编辑、长按导出。
- 配置弹窗复用高亮样式那一套组件与视觉（`SettingsDialogColors` / `settingsDialogCard` / `settingsDialogInput` / `settingsDialogButtonRow`）。**字体选择与高亮样式保持一致**，同样支持「跟随全局字体」、内置字体与字体库文件。
- **CSS 分段填写**：弹窗内 CSS 输入框上方是一排可点击的分段切换（标题/卷标为「整体 / 序号 / 内容」，插图为「容器 / 图片 / 图注」，分割为「整体」），点击切换后输入框载入对应选择器的声明块，可分别完整填写；切换时自动保存草稿。
- **实时预览**：弹窗内嵌 WebView，用与成书完全一致的正文结构渲染当前编辑中的 CSS，输入 250ms 防抖后刷新；选中字体文件时预览通过 `file://` 直读设备字体，与成书效果一致。
- **内置样式移植自 TEpub-Editor**：7 头图 / 12 标题 / 4 插图 / 5 分割，另由 12 条标题样式派生出 12 条卷标样式（选择器换成卷首页接口），共 40 条。`OnlineEpubStyleLibrary.kt` 由本地脚本 `tools/gen-epub-styles.mjs`（`tools/` 不入库）从 `epubStyleLibrary.ts` 生成，样式 id 与 TEpub 保持一致便于对照。仅样板图工厂产生的头图条目（只有 base64 样板图差异、CSS 相同）未移植。
- **头图样式只保存不写入成书**：在线补全下载的书没有章节头图来源，该类样式可配置、可预览，为后续接入头图生成预留接口，弹窗内有明确提示。

### 成书标记结构对齐 TEpub 接口
- 生成端标记全部改为 TEpub-Editor 的标准接口，内置样式因此可以原样套用：
  - 章节标题 `div.te-chapter-heading > div.te-chapter-number + h1.te-chapter-title` → **`h1.te-chapter-title > span.te-chapter-number + span.te-chapter-name`**
  - 卷标题 → **`h1.te-volume-title > span.te-volume-number + span.te-volume-name`**（卷首页容器与装饰符不变）
  - 插图 `div.online-illustration > img` → **`figure.te-illustration > img.te-illustration-image`**
  - 分割 `p.divider-line` → **`p.te-divider-line fg1`**（双 class 兼容按 `p.fg1` 编写的样式）
  - 正文段落加上 `class="te-paragraph"`
- **已下载的书自动迁移**：`syncOnlineCompletionDefaultStyle` 扩展了迁移链路，新增旧标题双层结构、裸 `h1`、旧插图 `div`、旧分割 `p` 四条改写，且对新结构幂等；卷首页文件按内容整份重写自动跟随。基础 CSS 保留 `.online-illustration` 与 `p.divider-line` 作为迁移前的兜底。
- `Styles/default.css` 不再是写死常量，改由 `OnlineEpubStyleCss.build` 按选中样式拼装：基础排版 + 四类已套用样式 + `@font-face`。整本下载与增量更新两条路径都会生效。

### 样式字体随书嵌入
- 样式里选中字体库文件时，字体按内容哈希（SHA-1 前 12 位）去重后复制进 `OEBPS/Fonts/`，登记进 `content.opf` manifest，并在 CSS 里生成 `@font-face` 绑定到该类样式的主选择器。同一字体全书只嵌一份，`mimetype` 仍是包内第一个条目。选中「楷体」等内置字体时只写 `font-family` 名不嵌文件。

### 应用到已下载图书
- 「下载配置」页新增**「应用到已下载图书」**：扫描用户书库中由模块生成的在线补全 EPUB（按 `content.opf` 里的 `reamicro-online-source-id` 识别），在后台线程逐本重写 CSS 并迁移章节结构，完成后 toast 回报处理/更新/失败数量。只改 CSS 与章节正文，不动 spine 与目录，因此无需刷新目录表。不点这个的话，改完样式的书需要点一次「更新」才会生效。

## 1.4.2 - 2026-08-10

### 在线补全图书新增卷首页
- 在线补全下载/更新的 EPUB 此前只有章节标题页、没有卷首页，多卷书在阅读时看不到分卷。现仿起点为**每一卷**生成独立的卷首页文档，卷名与正文分开成页。
- **卷的来源仍是在线源的多级目录**：沿用既有的 `OnlineChapter.volumeTitle` 提取链路（`isVolume` 规则 → 节点 `type/kind` → 无正文 URL 的层级节点 → `volume_name/part_name/section_name/group_name` 等字段），目录中连续同卷名的章节归为一卷，未分卷的章节不生成卷首页。**未改动目录解析逻辑**，避免带正文 URL 的章节被误判成卷而丢失正文。
- 卷标格式识别只用于**卷首页的换行排版**：新增 `OnlineVolumeHeadingMarkup.parse`，把卷名拆成「卷序号 + 卷名」两行（序号小字在上、卷名大字在下，与章节标题页一致），支持带/不带分隔符的 `第x卷`、`第x节`、`第x小节`、`第x章/部/篇/册/季/回/集/辑/幕`、`序 / 序章 / 序言 / 序幕 / 序曲 / 楔子 / 前言 / 引子 / 番外 / 外传 / 后记 / 终章 / 尾声`，以及 `Volume 2 / Part 3` 等拉丁写法；`第 十 卷` 这类夹空白的序号会压缩成 `第十卷`。**只有序号没有卷名时只显示序号**；识别不出卷标时整串当作卷名单行显示。卷名过长由样式自动换行。
- EPUB 结构：卷首页写为 `OEBPS/Text/volume_XXXX.xhtml`，登记进 `content.opf` manifest，并在 spine 中插到该卷首章之前；`toc.ncx` 的卷 `navPoint` 由原来指向「该卷第一章」改为指向卷首页，点击目录里的卷名即可跳到卷首页。章节文件名与 href 分配规则不变（`chapter_XXXX.xhtml`），因此按需下载、增量更新的 href 匹配、失败章节重试均不受影响。
- 覆盖整本下载（`writeOnlineCompletionEpub`）与增量更新（`appendOnlineCompletionChapters`）两条路径；增量更新时按内容比对增量重写卷首页，并清理卷数变少后残留的旧 `volume_XXXX.xhtml`。已下载的旧书在下次「更新」后即可获得卷首页。
- 新增 `.te-volume-page / .te-volume-ornament / .te-volume-number / .te-volume-title` 样式（装饰符用 `※` 以保证 CJK 字体下必定有字形）。新增 `OnlineVolumeHeadingMarkupTest` 覆盖上述各类卷标写法。

## 1.4.1 - 2026-08-09

### 在线源图书长按菜单顶部「网址链接」标签改显源名
- 在线源下载图书的长按菜单，左上角原本显示的类型标签「网址链接」现改为显示对应在线源的名字（如「晚风里」等）。**不改动**下方「详情」（关联）行的显示。
- 实现：`WebDavDriveHook` 新增 `hookOnlineCompletionBookSourceLabel`，挂钩 `app.zhendong.reamicro.arch.FileSource.queryName(String): String`（该顶部标签由 `BookLocalSheetKt.BookInfoTitle(title, subtitle, uri)` 调用 `FileSource.queryName(uri)` 渲染，在线源图书 uri 为详情网址故显示为「网址链接」）。在 `afterHookedMethod` 中用已捕获的当前书 `onlineCompletionLocalSheetBook` 经 `onlineImportedBookSourceInfo` 解析在线源，命中时把返回值替换为 `info.sourceName`。仅在长按菜单打开且为模块生成的在线补全 EPUB 时生效（`queryName` 仅被 `BookInfoTitle` 调用），普通图书与「详情/关联」行均不受影响；解析不出真实源名（空 /「未知源」）或已一致时不改写，保留原「网址链接」兜底。

## 1.4.0 - 2026-08-04

### 阅读背景扩展
- 重做阅微 2.3.0 beta 阅读页背景接管：浅色与深色使用独立图片池、当前项和持久化路径，深色主题面板以背景列表替换原月亮动画，并修复深色背景错误应用到浅色正文的问题。
- 修复点击背景后必须返回书架才能生效的问题：保留宿主 Compose `State.getValue()` 的读取订阅，按浅色/深色捕获正文背景 State，通过一次性刷新令牌使当前正文精确失效并重新触发以 URI为 key 的图片加载，同时恢复宿主原值，避免污染全局背景配置。
- 新增内置浅色梅花背景和深色星空背景；首次使用时复制到阅微私有背景目录并分别注册为对应主题默认项。内置项不可删除，支持选择、自定义图片添加与移除、恢复默认。
- 恢复“阅读补全 → 背景扩展”独立开关；优化缩略图圆角、橙色边框和选中勾，选择与磁盘持久化分离，减少点击卡顿和对翻页手势的阻塞。

### 阅微 2.3.0 beta 适配与在线阅读
- 适配新版导入链路、设置页顶栏和主页内容结构，恢复覆盖导入、云盘/本地书库/在线补全导入、补全设置标题和主页图片背景。
- 优化番茄在线整本/批量下载、HTTP 失败处理、章节内容校验与 EPUB 插图嵌入；支持正文图片标记、下载、manifest 登记及失败回退。
- 修复在线书源登录字段和密钥别名兼容，补充 `X-Key` 等字段解析；完善在线补全通知、听书命令与精简日志策略。
- 无文件参数启动导入入口时改为静默退出，保留真实导入失败提示，避免启动时误弹“未找到要导入的文件”。

## 1.3.9 - 2026-08-03

### 在线补全书源：番茄（api.yuezhi.me）填了密钥仍 401
- 修复 loginUi 密钥字段名为 **`X-Key`** 的 JSON 书源，保存密钥后请求仍缺少 `X-API-Key`、接口持续返回 401「未填写密钥」的问题。根因：`OnlineSourceAuth.credentialHeaders` 只认 `密钥 / apiKey / api_key / apikey / qq_api_key` 这几个固定字段名，取不到用户实际保存在 `X-Key` 字段下的值，于是不下发 `X-API-Key`，搜索/详情/目录/正文全部 401。
- 改为新增 `resolveApiKey` 多重回退解析密钥：① header 内联 JS 里 `getLoginInfoMap().get("字段")` 显式引用的字段 → ② 常见别名（新增 `X-Key`/`x_key`/`xkey`/`key`/`token`/`授权码`/`令牌`/`access_key`）→ ③ 字段名符合“密钥/key/token/apiKey”特征者 → ④ 仅有唯一一个登录字段时直接用它。段评接口取密钥处（原写死 `["密钥"]`）同步改用 `resolveApiKey`。

### 在线补全书源：正文插图适配，下载时自动嵌入
- 修复番茄等书源正文 `contentHtml` 内的 `<img>` 插图在下载时被整本丢弃的问题。根因：模块把正文归一化成纯文本时用 `<[^>]+>` 删掉了所有标签，图片随之消失，再按行 `xmlEscape` 成 `<p>`，插图彻底无法显示，更不会被下载。
- 新增 `OnlineChapterImageMarkup`：删标签前先把 `<img src>` 转成能穿过纯文本管线的标记（并解码 `&amp;` 等实体），生成章节 XHTML 时再还原为真正的 `<img>`。整本下载（`writeOnlineCompletionEpub`）时按标记逐张下载插图、写入 `OEBPS/Images/`、登记进 `content.opf` manifest，并在正文引用 `../Images/xxx`；下载失败的图片自动跳过并回退为远程 `<img>`，不阻断整本导入。新增 `.online-illustration` 居中样式；`OnlineChapterContentValidator` 放行“纯插图无文字”章节，避免被误判为空章。

## 2.3.0 beta 适配 - 2026-08-03

### 导入功能（覆盖导入 / 云盘下载 / 本地书库 / 在线补全）
- 修复升级到阅微 **2.3.0 beta（versionCode 2202）** 后模块导入相关功能全部失效的问题。根因：宿主给导入链路新增了进度回调参数，模块里所有按“固定参数个数”定位宿主方法的写法全部失配 → `NoSuchElementException` / 找不到方法，导致导入无反应或报错。
  - `BookshelfRepository.importBook` 由 6 参增至 **7 参**（在 `size` 与 `Continuation` 间新增 `Function1<Int,Unit>` 进度回调）。
  - `EpubFileManager.import` 由 `(Path, Path)` 2 参增至 **`(Path, Path, Function1<Int,Unit>)` 3 参**。
- 修复点：
  - `ReaderImportOverwriteHook` 覆盖导入预检的 `EpubFileManager.import` 定位：由 `size==2 && 全 okio.Path` 改为按方法名 `import` + 前两参 `okio.Path` + 返回 `Pair` 匹配，取参数最少者，兼容 2.2.0/2.3.0。
  - `WebDavDriveHook` 新增 `findImportBookMethod`/`importBookArgs` 兜底：按方法名 + 末参 `Continuation` 定位 `importBook`，并按实际参数个数用无副作用的 `Function1` 代理补齐进度回调（旧版不补）。
  - 在线补全导入 `importOnlineCompletionBookLocked`、`importOnlineCompletionEpubDirectory` 改用上述兼容逻辑（`EpubFileManager.import` 进度参可空，补 `null`）。
  - WebDAV/本地书库来源覆盖 hook `hookWebDavImportBookSource`：`importBook` 定位由 `size==6` 改为 `size>=6 && 末参 Continuation`，恢复导入后书籍来源 URL / 大小的写入（`args[3]/args[4]` 下标在新版不变）。
  - 兜底修正未被调用的 `importWebDavDownloadedBook` 同类写法，避免后续复用踩坑。

### 设置页顶栏标题（所有补全设置页）
- 修复 2.3.0 beta 下模块所有设置子页顶部标题消失、并伴随 `AppTopBar/8 not found` 报错的问题。根因：宿主 `AppTopBarKt.AppTopBar` 由 8 参改为 **9 参**（参数重排 + 新增尾随内容槽 `Function3`），旧代码写死找 8 参并按固定顺序传值 → 找不到方法抛异常，顶栏标题渲染失败。
- 改为按“方法名 + 首参 String + 末三参 `(Composer,int,int)`”定位 `AppTopBar`，并**按参数类型**映射（title→String 位、返回回调→`Function0` 位、图标/insets 按类型填、其余走 `$default` 掩码默认值），签名再变也不错位；`WebDavDriveHook` 账号页标题 hook 同步改为按名字匹配。

### 主页补全（个人中心背景）
- 修复 2.3.0 beta 下主页补全背景完全不生效的问题。根因：`ProfileScreen` 改用 `Scaffold` 重排，`ProfileScreen$lambda$0$1` 变成 topBar（几乎无背景调用），真正的内容区变成带 `PaddingValues` 的 content lambda（`lambda$0$2`，含 fillMaxSize/background/头像/卡片）；模块仍在 `lambda$0$1` 打开背景注入窗口 `inProfileLambda`，内容区渲染时开关为 false，颜色/背景/`fillMaxSize` 注入全部落空。
- 改为按“`ProfileScreen$lambda` 前缀 + 参数含 `PaddingValues`”定位 content lambda 作为注入窗口，找不到时回退旧 `lambda$0$1`，兼容旧版本。

## 1.3.2 - 2026-07-15

### 首页云盘/书库
- 修复首页搜索结果中 WebDAV、本地书库分组标题图标在展开后概率性变成 115 图标、折叠后也不恢复的问题。根因是 Compose 重组会直接重跑 `CloudResultList` 绕过调用点的图标 depth 包裹，改为在 `CloudResultList` 方法 hook 内按 `type` 参数设置/弹出 depth，初次合成与重组都覆盖。

### WebDAV 下载
- 兼容 OpenList/AList 部署（WebDAV 端点根即某挂载点内容）：搜索经 fs API 得到的路径带挂载前缀，直接 GET 会 404。现在 GET 404 时逐段剥离挂载前缀重试，仍失败则回退到 AList `fs/get` 的免鉴权直链 `raw_url` 下载，与搜索使用的 API 命名空间一致。

### 2.2.0 适配
- 覆盖导入元数据同步：适配 2.2.0 `Book.copy` 25 参（在 `updated` 与 `cloudId` 间新增 `pinnedAt`），修复覆盖导入后进度/封面/出版方同步不过去（旧写法 `NoSuchElementException` 静默失败）。
- 在线补全本地书构造：适配 2.2.0 Book 25 参构造器，改为取“首参为 long 的最长构造器”并按类型填默认值、按字段顺序赋关键字段，抗后续字段增减。
- 关联封面修复：适配 2.2.0 起点书的 BookOverview 页（`BookOverviewViewModel`/`BookOverviewUiState`），修复封面修复上下文从未捕获、恒提示“当前页面无法执行封面修复”的问题。
- 阅读自动翻页：适配 2.2.0 `ReaderSettings` 签名变化（新增 `Function0` 首参），改为只要求末两参为 `(Composer, Int)` 匹配。

### 主页补全（原「个人中心背景」）
- 补全设置入口「个人中心背景」更名为「主页补全」，并从列表末尾移动到「旋转补全」上方。
- 移除设置页内「启用个人中心背景」「使用图片背景」两个开关，以及「背景颜色」入口行。
- 纯色背景效果停用：`canShowProfileBackground` 触发条件改为需勾选图片背景且已选择图片路径（`profileBackgroundUseImage && profileBackgroundImage 非空`），历史填写过的纯色数值不再生效。纯色相关代码保留但不再触发。
- 主页背景触发方式简化为「选了图片就显示」：通过系统选择器选中图片后自动写入路径并置 `useImage=true`，无需再手动开关。

### 弹窗样式还原
- 还原全模块共享的 `SettingsDialogColors` 配色：恢复为自适应深色/浅色 + 系统强调色（`colorAccent`）的原始方案，撤销 PR 引入的硬编码暖米色调。
- 恢复被删除的 `settingsThemeColor` 辅助函数与 `android.util.TypedValue` 导入。
- 所有自定义输入弹窗（含主页背景的模糊/透明度/显示方式/裁剪位置等）随之恢复模块原本样式，取消 PR 新增的背景压暗与取色配色。

### 移除背景图片
- 「背景图片」行支持长按移除（仅在已选择图片时启用）。
- 移除会删除已保存的背景图片文件，并复位 `profileBackgroundImage`、`profileBackgroundUseImage`、`profileBackgroundEnabled`，使 `canShowProfileBackground` 变为 false，所有主页补全背景功能随之全部失效。

## 1.2.5 - 2026-07-05

### 高亮样式
- 新增内置浅色默认样式「彩色玻璃·紫」，内置图片资源打包到模块 APK，阅读页和设置页预览均通过模块资源解码。
- 原默认样式改名为「橙色默认」，并补充 `font-size: 0.9em` 配置。
- 默认浅色/深色样式改为快捷入口语义，不再作为普通样式名称保存。
- 高亮样式列表支持显示当前浅色默认、深色默认标记，内置默认样式删除规则同步修正。
- 修复内置默认高亮样式修改后保存不生效的问题。

### 高亮规则
- 高亮规则选择样式时，深色/浅色默认项固定显示在顶部，并用括号展示当前实际默认样式。
- 单书规则增加「跟随全局」能力：每本书独立控制是否跟随全局高亮规则。
- 阅读页排版菜单新增高亮入口，可快速打开全局高亮规则和单书高亮规则开关列表。
- 单书高亮规则只显示本书规则，全局高亮规则支持固定的「跟随全局」开关。
- 修复冷启动首次打开有单书规则的书时高亮不显示的问题，并将书籍上下文预热改为静默路径，减少进入阅读页时的重组闪烁。

### 阅读页菜单
- 高亮入口改为复用阅微排版菜单现有 Row/按钮渲染方式插入，避免手写菜单行样式。
- 高亮规则弹窗复用阅微字体弹窗入口和动画。
- 字体弹窗和高亮规则弹窗高度统一调整为 355dp。
- 高亮规则弹窗标题栏使用与阅微字体弹窗一致的关闭按钮样式。
- 修复高亮规则弹窗中系统返回键错误关闭外层排版/样式面板的问题。

### 阅读补全
- 阅读补全设置项「高亮选中」更名为「选中高亮」。
- 选中文本创建单书高亮规则后，阅读页高亮状态会同步刷新。

### 内嵌搜索
- 内嵌搜索入口按开关状态替换不同按钮：关闭时替换左侧返回，开启时替换右侧深浅色切换。
- 修复左侧返回按钮误触发搜索的问题。
- 优化搜索入口定位逻辑，减少误匹配宿主按钮。

### 导入与覆盖
- 修复覆盖导入时取消导入、独立导入失败的问题。
- 覆盖导入检查逻辑改为匹配阅微导入流程，避免不同文件走合并路径导致异常膨胀。

### 书架与详情
- 新版阅微详情页恢复「更新」按钮，并显示在线源名称。
- 图书长按菜单中「更新」「分组」调整为同一行，并补充分割线表现。
- WebDAV/在线源展示逻辑适配新版数据刷新状态。

### 兼容性与资源
- 模块入口记录自身 APK 路径，用于 Xposed 环境下读取内置高亮图片资源。
- 新增模块资源解码 fallback，避免宿主进程无法直接读取模块 assets 时图片背景丢失。
- 补充单元测试覆盖默认样式、导入覆盖和高亮规则设置行为。
