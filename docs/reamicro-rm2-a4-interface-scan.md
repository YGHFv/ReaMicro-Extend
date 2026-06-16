# 阅微 RM2.0.A4 接口扫描

> 基于 `RM2.0.A4.APK` 反编译结果整理。JADX 输出目录：`decompiled/reamicro-rm2-a4/`。

## APK 基本信息

- 包名：`app.zhendong.reamicro`
- 主 Activity：`app.zhendong.reamicro.MainActivity`
- APK SHA256：`D3F93EAF4E9F0C16B22E563BBBB00B368AAB402A147DE134625E10444260504E`

## 关联主链路变化

RM2.0.A4 已从旧版 gRPC/Protobuf 与 native `Bibliosurf` 搜索链路，切换为 Ktorfit 风格 REST API 加远端下发 JS 第三方搜索脚本。

关键文件：

- `app/zhendong/reamicro/repository/api/Api.java`
- `app/zhendong/reamicro/repository/BookRepository.java`
- `app/zhendong/reamicro/ui/book/BookPublishViewModel.java`
- `app/zhendong/reamicro/ui/book/BookPublishUIState.java`
- `app/zhendong/reamicro/ui/book/BookPublishUIEvent.java`
- `app/zhendong/reamicro/arch/fs/ThirdPartyFileManager.java`

## 关联相关 REST 接口

`Api.java` 中和图书关联直接相关的接口：

```text
POST rest/book/search-cloud-book
POST rest/book/search-relation-book-info
POST rest/book/post-cloud-book
POST rest/book/relate-cloud-book
POST rest/book/relating-book-info
POST rest/book/post-user-book
POST rest/user/query-third-party
GET  rest/user/download-third-party?name={name}
```

`postCloudBook` 仍复用 `ThirdPartyBook.toPostCloudBookRequest()`，然后通过 `relateCloudBook(userBookId, bookPublisherId)` 建立用户图书和云端出版社信息之间的关联。

## 第三方搜索模型

新包名：

```text
app.zhendong.reamicro.data.search.ThirdPartyBook
app.zhendong.reamicro.data.search.ThirdParty
app.zhendong.reamicro.data.search.ThirdPublisher
```

`ThirdPartyBook` 构造函数仍为 14 个字符串：

```text
title, author, alias, subtitle, original, intro, cover,
translator, publisher, publishDate, price, isbn, words, status
```

`ThirdParty` 是 UI 中的分组对象：

```text
ThirdParty(publisher: String, books: List<ThirdPartyBook>)
```

## 第三方来源

`ThirdPublisher` 中存在的来源：

```text
1  实体出版
2  起点中文网
3  晋江文学城
4  纵横中文网
7  长佩文学
8  飞卢小说网
9  QQ阅读
10 刺猬猫
11 少年梦
13 独阅读
14 SF漫画
```

`ThirdPartyKt.getThirdPublisherList()` 当前未包含 `QQ阅读`，但类已经存在。

## JS 搜索脚本更新

`ThirdPartyFileManager.checkAndUpdateFiles()` 调用：

```text
Api.queryThirdParty()
Api.downloadThirdParty(name)
```

脚本保存到用户存储的 `third` 子目录。`getAllJsFiles()` 读取本地 JS 文件，按文件名 `_` 前缀生成 `JsFile.type`，`BookPublishViewModel.searchByThird()` 再并发执行这些 JS 并把结果追加到 `BookPublishUIState.thirdPartyBooks`。

## 当前模块 Hook 点

模块已切到 RM2.0.A4 的稳定类名：

- 在 `BookPublishViewModel.searchByThird()` 之后追加一个 `ThirdParty("手动关联", listOf(sentinel))` 分组。
- 在 `BookPublishViewModel.onIntent(BookPublishUIEvent)` 前拦截 `RelateThirdParty`，如果点击的是 sentinel，就弹出手动关联表单。
- 表单提交后构造新包名下的 `ThirdPartyBook`，再调用原生 `onIntent(RelateThirdParty(thirdPartyBook))`，复用阅微原生 `postCloudBook -> relateCloudBook` 流程。

这样不会直接写本地数据库，也不会绕开阅微服务端关联状态。
