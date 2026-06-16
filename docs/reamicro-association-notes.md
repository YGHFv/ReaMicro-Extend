# 阅微图书关联逻辑确认

> 历史记录：以下内容基于 `RM1.3.0+.APK`。当前目标 `RM2.0.A4.APK` 的最新接口扫描见 `docs/reamicro-rm2-a4-interface-scan.md`。

当前工作区只有 `RM1.3.0+.APK`，没有原始源码。通过解包 APK 并提取 dex 字符串，能确认：

- 图书关联不是本地静态 JSON/书源列表实现，APK 内没有发现本地书源配置文件。
- APK 内存在 `api/book/*` 的 gRPC/Protobuf 类型和方法名：`SearchBookInfo`、`SearchCloudBook`、`SearchRelationBookInfo`、`RelatingBookInfo`、`RelateCloudBook`。
- 本地 Room `book` 表包含 `cloud_id`、`publisher`、`backup_type`、`backup_id` 等字段，更像是保存已关联/已备份状态。
- 因此“候选搜索”和“关联提交”主逻辑在阅微服务端；客户端负责发起搜索、展示候选、提交关联，并把关联后的 `cloud_id/publisher` 写回本地。

本模块先实现新增搜索源的独立 provider：刺猬猫、次元姬。后续 hook 阶段应优先拦截阅微 `SearchRelationBookInfo`/候选列表所在链路，把 provider 返回的候选合并进 UI，而不是直接改本地数据库。


## 新增来源实现状态

- 次元姬：网页搜索页会内嵌 `bookId`、`bookName`、`authorName`，当前 provider 已按这些字段解析，并保留一个 API 候选入口用于后续验证。
- 刺猬猫：网页能发现搜索路径 `/get-search-book-list/0-0-0-0-0-0/全部/{key}`，但本机实测返回完整页面且没有候选列表；App 接口 `https://app.hbooker.com/bookcity/get_filter_search_book_list` 返回加密/签名后的内容，不是裸 JSON。当前 provider 保留网页兜底解析，后续要补 HBooker 响应解密/签名逻辑后才算稳定可用。
- Hook 策略：先拦截阅微候选搜索结果并合并外部 provider，不建议直接写本地 `book.cloud_id`，否则会和服务端关联状态不一致。


## 2026-06-08 进一步定位

JADX 反编译后确认：阅微已经内置刺猬猫第三方来源，`queryType=10`，枚举类为 `j4.n`，展示名为“刺猬猫”。第三方搜索不是直接写在 Kotlin/Java 里，而是通过 JNI `Bibliosurf.search(queryType, query)` 完成。

更详细链路见 `docs/reamicro-association-flow.md`。
