# 阅微 2.3.3 正式版扫描与功能适配

## 目标与基线

- 用户报告最新阅微可以触发扫描，但模块功能完全不正常。
- 工作区从 `5139676` 继续，保留原 `.gitignore` 及返回键、单书目录恢复改动。
- 新设备 `4e2d9aa2`，型号 `25098PN5AC`。不重启手机、不关闭 MT、不卸载清数据。
- MT PID `21395`，boot ID `b8b0c4f5-91f3-42e7-acdb-3beab762ebf4` 前后不变。
- 实际新宿主 Manifest 为 `2.3.3 / 2330`，44,894,945 字节，
  SHA-256 `aef17c9544aff578ce2b3f4ae9dd9b27202814a1d52268dfa13aa4e731311915`。
- 旧设备仍是 RC3 `2.3.3 beta / 2320`，APK SHA-256
  `270c20157ea70ad41ce199ab0b475f229d847d66e85a9ffda1027c419f6e832c`。
- 不是同一个 APK 的覆盖安装时序问题，正式版已经重新混淆并拆分部分门面。
- 本轮证据 `%TEMP%/reamicro-analysis/runtime/latest-scan-20261029/`。
- 未升模块版本，未提交、未推送；用户数据与账号凭证未修改。

## 已确认根因

1. 原 RC3 结构仅匹配 `244/360`，按既有 90% 门槛正确拒绝旧 profile。
   原扫描得到 `1258/1300`，缺 42 项，启用 `automatic` 部分映射；
   它没有完整字段/原生 ABI/载体，功能 Hook 仅 `60/142`。
   扫描触发和扫描结果一致不等于功能适配完成。
2. 新宿主静态门面再次拆分，`methodOwner` 已定位，但若干调用只加载原类：
   ScrollPager、EpubContainer、正文 CFI 步进、源码/渲染选区投影均会漏 Hook
   或抛 `NoSuchMethodException: crd#getAllSteps`。
3. 正式版类结构规则较多，第一次候选在启动主线程同步扫描全库，
   超时触发启动 ANR。新规则里形状-only 的 Continuation 还与另一接口同形。
4. 真实搜索可列结果后，导航会立即返回起点。窄范围日志确认：
   `handleHomeBookshelfRendered -> returnToSearchOrigin -> searchNavigator.cancel`。
   原清理 Hook 在 HomeScreen 的旧主门面 `lyb` 中按短名 `e` 装到了
   实际 `(Modifier, Function1)->Modifier` 方法，而真实 HomeScreen 在 `u1e.e`。
   搜索跳转触发页面重组时误以为书架已渲染，取消请求。
   不能把这个问题归因于下载、CFI 或手动账号变更。

## 修复

- 新增独立 `host-profiles/2.3.3.profile` / `.shapes` / `.abi.json`，
  不覆盖 RC3 资产，不降低门槛，不给未知 `automatic` 构建授予原生 ABI。
- 360 主类、187 别名、1055 方法、1036 字段，68 条 methodOwner；
  静态目标存在性检查 0 问题，运行期结构校验 360/360。
- 私有 ABI 按 RC3 完整指令对应到新构建，独立资产；
  新协程及 HTTP 流式载体仅包含桥类，不打包编译 stub。
- 核对保留的协议：HTTP builder branch=12，URL/header/method=x/n/t；
  SingleFlow branch=6，原生 continuation parent=usa；导航仍使用原宿主路由栈。
- 正式版启动先使用已校验 profile，完整自动扫描交给已有后台任务；
  DexKit 全库类列表和 methodShapes 缓存复用，避免每条规则重复扫描。
- 新增独立 `automatic-2.3.3.rules.json`，旧 RC3 扫描规则原样保留。
  Continuation 从真实 `BookshelfRepository.updateBook` 尾参数槽推导，
  不靠两方法接口形状或候选排序。
- ScrollPager/EpubContainer/正文节点步骤/选择投影按真正 methodOwner 读取。
- HomeScreen/BookshelfScreen 清理 Hook 限定真实 owner、准确入口和 Composer 参数，
  不再把同名普通 Modifier 方法当页面。
- 结果页在未验证 ABI 或仍有失败项时明确提示，不能显示“一致就功能可用”。
- 所有 `LATESTNAV` 临时日志与探针均移除。

## 实测与隔离回归

- 最终运行 PID `7535`，profile=2.3.3，360/360，Hook 134/134。
- 完整后台扫描 `1314/1314`，0 failures，6366ms；无启动 ANR 或该 PID FATAL。
- 真实设置：个人页进入设置，补全计划及关联/在线/云盘/字体/阅读等分类实际显示。
  没有逐项点击所有设置，更不等于所有写回功能已验收。
- 真实阅读：此前单书目录 2010 项仍显示，第 2000 章可打开对应正文。
- 真实全文搜索：对另一书搜索 `2000`，518 文档、1,614,872 字符，2 处结果。
  点击第一处定位到第 79 章，界面“已定位 1/2”；下一处到第 91 章；
  返回原位回到第六章。当前 PID 无 search navigation failed 或 hook callback error。
  没有将所有翻页模式和高亮像素标成通过。
- 最终 feature fixture：账号快照/边距、原 27 字段关联复制、元数据保护、
  逐章字段、同步授权回调、OPF UUID 持久化、在线目录导入、搜索索引和
  源码导航 mapper 均通过，`FEATURE_FAILURES=[]`。
- 最终 protocol fixture：协程立即/挂起/取消、Paging/SingleFlow、
  HTTP 构造及文件流式写入通过，`PROTOCOL_FAILURES=0`。
- 隔离 UUID fixture 最初用无效 `full-audit-independent` 文本，
  正式版 OPF writer 会标准化为 `urn:uuid:...`，不是持久化没写入。
  改用真实 UUID `d44517bb-2300-42dc-bb9a-57b63f791699` 后回读通过；
  未删除生产写回校验。
- JVM 159 项，0 failures / errors；静态 profile 检查 0 问题。

## 工具与边界

- REA 6.7.0 固定 JADX 引擎摘要校验读取当前正式版 APK。
- 新设备 MT MCP 服务不可连接，未重启 MT；通过旧设备原本有效的 MT
  只读 workspace `zz3ctgjp` 分析同一摘要 APK。原 RC3 workspace 未覆盖。
- 多屏截图固定物理主屏 `4630946949513469331`，触摸逻辑 display=0。
  Accessibility 进程在新设备被系统终止，使用本机 OCR；没有载入截图到对话。
- 系统通知遮挡、最近阅读重排造成部分坐标操作未命中目标；
  失败截图保留，只有再次确认页面与正文后才记通过。
- 真实账号服务有既有 401/超时及 root bridge credential_failed；
  未改账号、未把这些请求通过标成适配完成。
- 无所有功能全量验收：其他翻页方式/高亮像素、账号切换和数据恢复、
  真实封面上传、本地书库/资源编辑、字体映射、发现全路径、Root 调度仍待验。
- 私有 ABI 迁移有三条未使用/签名变更项保留在 `abi-unresolved.json`，
  不将全 profile 目标存在或 Hook 计数描述为所有压力路径通过。

## 最终产物

- 构建日志 `runtime/b4/round13/latest-release-verified-build.log`：3 分 20 秒成功。
- 模块仍为 2.3.10 / 72；APK
  `app/build/outputs/apk/release/app-release.apk`，16,847,857 字节。
- SHA-256 `3f629de2cf9b8de82b8b912dbf62cedef88f1b895e32edbbf8f8c03184f77b27`。
- 新设备安装路径 APK 与构建摘要一致。
- 当前阅微回到第六章原阅读位置；MT 与 boot ID 不变。
- 保留工作区已有改动与证据，不自动提交推送或执行剩余全量验收。
