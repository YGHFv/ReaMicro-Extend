# 阅微图书关联详细链路

> 历史记录：以下内容基于 `RM1.3.0+.APK` 反编译结果整理。当前目标 `RM2.0.A4.APK` 的最新接口扫描见 `docs/reamicro-rm2-a4-interface-scan.md`。

## 关联入口分层

### 1. 云端推荐候选

- 文件：`decompiled/reamicro/sources/D5/C1242k.java`
- 方法：`C1242k.e(name, author)`
- 请求：`BookGrpc.BookBlockingStub.searchCloudBook(SearchCloudBookRequest)`
- 参数：`name`、`author`
- 返回：`SearchCloudBookResponse`，字段包括：
  - `cloudBookId`
  - `name`
  - `author`
  - `bookPublishersList`

没命中时会把 default instance 当作空结果，然后抛 `Resources.NotFoundException` 包装成失败结果。

### 2. 第三方书搜索候选

- 文件：`decompiled/reamicro/sources/N4/u.java`
- 方法：`N4.u.b(queryType, key)`
- 实现：调用 `Bibliosurf.f16564a.b(queryType, key)`，然后用 kotlinx serialization 反序列化为 `List<ThirdPartyBook>`。
- `Bibliosurf` 是 JNI 原生库：`System.loadLibrary("bibliosurf")`，真正的站点搜索逻辑在 native 层。

### 3. 第三方来源枚举

- 文件：`decompiled/reamicro/sources/j4/l.java`
- 来源列表：`j4.l.f24612a`
- 已内置来源：
  - `1` 实体出版 / DouBan
  - `2` 起点中文网 / QiDian
  - `3` 晋江文学城 / JinJiang
  - `4` 纵横中文网 / ZongHeng
  - `7` 长佩文学 / GongZiCp
  - `8` 飞卢小说网 / FaLoo
  - `10` 刺猬猫 / CiWeiMao
  - `11` 少年梦 / ShaoNianDream
  - `13` 独阅读 / CDDaoYue
  - `14` SF漫画 / SFAcg

结论：刺猬猫不是完全缺失，阅微已经有 `queryType=10`。后续应先验证 native 搜索是否失效；如果失效，hook `N4.u.b(10, key)` 做增强即可。

### 4. 第三方搜索 flow

- 文件：`decompiled/reamicro/sources/M4/C0490h0.java`
- 行为：
  1. 清理书名括号、罗马数字、尾部数字等噪声。
  2. 先对 `j4.l.f24612a[0]` 发起搜索并 emit。
  3. 再并发搜索剩余来源并逐个 emit。
- 单源调用包装：
  - `M4/C0488g0.java`：直接返回 `N4.u.b(sourceId, key)`。
  - `M4/C0486f0.java`：捕获异常，失败返回空列表。

这是最适合补候选的 hook 点之一。

## 关联提交

### 1. 关联已有云端出版社候选

- 文件：`decompiled/reamicro/sources/f2/b.java`
- 方法：`s(Book, SearchCloudBookResponse, BookPublisher, Continuation)`
- 流程：
  1. 调用 `M4/C0476a0.V(book)`，确保本地书已有 `cloudId`。
  2. 调用 `relateCloudBook(RelateCloudBookRequest)`：
     - `userBookId = book.getCloudId()`
     - `bookPublisherId = bookPublisher.getBookPublisherId()`
  3. 成功后回写本地书：
     - `title = searchCloudBookResponse.name`
     - `author = searchCloudBookResponse.author`
     - `publisher = bookPublisher.publisher`
     - 如果本地封面为空，则用 `bookPublisher.cover` 下载/写入。

### 2. 关联第三方书候选

- 文件：`decompiled/reamicro/sources/f2/b.java`
- 方法：`t(Book, ThirdPartyBook, Continuation)`
- 流程：
  1. 调用 `M4/C0476a0.V(book)`，确保本地书已有 `cloudId`。
  2. 调用 `postCloudBook(thirdPartyBook.toPostCloudBookRequest())`，把第三方元数据提交到阅微云端。
  3. 取返回的 `bookPublisherId`。
  4. 调用 `relateCloudBook(userBookId, bookPublisherId)`。
  5. 成功后回写本地书：
     - `title = thirdPartyBook.title`
     - `author = thirdPartyBook.author`
     - `publisher = thirdPartyBook.publisher`
     - 如果本地封面为空，则用 `thirdPartyBook.cover`。

## 前置上传

- 文件：`decompiled/reamicro/sources/M4/C0476a0.java`
- 方法：`V(Book, Continuation)`
- 如果 `book.cloudId <= 0`：
  1. 尝试读取本地封面文件并计算 MD5。
  2. 可能先上传封面到网盘/对象存储。
  3. 调用 `N4.u.a(book, coverUrl, coverMD5)` -> `postUserBook`。
  4. 将返回 ID 写入 `book.cloudId`。

所以模块不应该直接写本地 `cloud_id` 绕过流程，最好复用原生 `postUserBook/postCloudBook/relateCloudBook` 链路。

## Hook 建议

### 首选：hook `N4.u.b(int queryType, String key)`

优点：
- 直接返回 `List<ThirdPartyBook>`，能复用原生 UI 和提交关联流程。
- 刺猬猫可在 `queryType == 10` 时增强原生结果。
- 次元姬可以先并入某个现有来源结果，或进一步 hook 来源列表增加新 `j4.w`。

缺点：
- 如果要让 UI 单独显示“次元姬”分组，需要处理 `j4.l.f24612a` 和 `j4.w` 来源对象。

### 次选：hook `M4.C0490h0.invokeSuspend`

优点：
- 可以在搜索 flow emit 阶段追加额外来源结果。

缺点：
- coroutine 状态机混淆严重，hook 稳定性比 `N4.u.b` 差。

### 不建议：直接改本地数据库

原因：
- 本地 `book.cloud_id` 是服务端 `postUserBook` 返回值。
- 图书关联服务端还维护 `userBookId -> bookPublisherId` 关系。
- 直接写库会导致本地显示和服务端状态不一致。
