# RC3 单书更新后目录残留恢复

## 目标与约束

- 用户要求切换到新连接设备，修复书架第一本书此前更新报错后无法更新章节的问题。
- 本轮设备 `4e2d9aa2`，型号 `25098PN5AC`，不使用旧固定序列号。
- MT PID `21395`；boot ID `b8b0c4f5-91f3-42e7-acdb-3beab762ebf4` 前后不变。
- 不重启设备、不关闭 MT、不卸载、不清用户数据、不修改账号或书源凭证。
- 工作区已有 `.gitignore` 修改及前一任务的返回键修复均保留；未提交、未推送、未升版本。
- 原始证据在 `%TEMP%/reamicro-analysis/runtime/rc3/newdevice-online-20261029/`。
- 当前宿主 APK SHA-256 与既有 RC3 一致：
  `270c20157ea70ad41ce199ab0b475f229d847d66e85a9ffda1027c419f6e832c`。

## 根因与范围

- 目标书名：什么！我们家居然是邪神后裔？
- 书 ID `10`，uid `3`，UUID `6d94691f-7d31-513d-afbf-35e1a4541866`。
- EPUB OPF/NCX 已包含 2010 项，末章第 2000 章，书源章节索引包含 2001 项。
  分卷页计入宿主目录，因此这几个计数不应强行视为相同。
- `book_item_ref` / `book_chapter` 各只有 1994 项，末章第 1984 章。
  最后 16 个章节文件已存在且非空，但没有进入宿主目录表。
- `updateOnlineCompletionBookIncrementally` 无新增章分支，仅在
  `structureChanged=true` 时刷新目录表。若上次先写完 EPUB/索引，
  再在刷新目录表或后续步骤报错，下次因文件内容相同而跳过刷新，
  会一直把旧目录留在数据库。
- 未取得此前失败的原始堆栈，不能断言那次具体是哪个 ABI/服务失败。
  本轮证实的是持久化残留状态和更新恢复缺口。

## 修复

- `WebDavDriveHook.Support.kt` 无新增章分支也执行原有
  `refreshOnlineCompletionCatalogTables(book, bookDir)`，成功之后才返回。
- 不改章节匹配、防远端缩短、防重复下载、安全路径及目录解析为空保护。
- 不直接改写数据库、不重下载正文、不生成新的书籍 UUID。
- 新增 `OnlineChapterCatalogRecoveryTest`，固定无新增章分支仍需刷新，
  且目录为空时的失败校验不能删除。该测试为源码接线回归，不冒充运行时验收。
- 本轮安装 APK 同时包含前一任务的 `ReaderHook.BackKey.kt`，
  最终包括普通 Reader/Menu 的焦点 BACK 放行；该 Menu 版本未在旧设备补验，
  不扩大此前返回键任务的通过范围。

## 实机验证

1. 修复前单书只读回读，保存 OPF、NCX、目录索引和数据库目录。
2. 单书目录 tar 快照；2032 文件的 SHA-256 基线。
3. Release 覆盖安装后等 5 秒，仅重开阅微，未卸载清数据。
4. 实点同一书“更新”：数据库目录从 1994 项恢复为 2010 项，
   末章为第 2000 章，href `OEBPS/Text/chapter_2013.xhtml`。
5. 再次点更新：仍为 2010 项，没有重复追加。
6. 修复前后 2032 个书内文件摘要全部相同，无新增、删除或正文覆盖。
7. 首次修复回读中书籍记录全部字段相同，包括 ID/UUID/uid/cloud_id/
   阅读进度/CFI/当前章；目录行重建后行 ID 按宿主 DAO 正常重新分配。
8. 界面目录显示“共 2010 章”，滚到末尾显示第 2000 章。
   实点末章后标题“第2000章 走肾还是走心”和对应正文出现。
   阅读验证会正常更新本书阅读进度，不再声称最后仍保持起点。
9. 当前 PID `16061` 无 FATAL、无 `online completion update failed`，
   无 hook callback error；Hook 135/135 不替代功能证据。

## 工具与边界

- 该双屏设备 `uiautomator dump` 被终止，未读旧 XML 代替实时界面。
- 主屏截图固定物理 display `4630946949513469331`；
  触摸固定逻辑 display `0`。截图仅本机 OCR，未载入对话。
- 不指定 screencap display 时警告会混入 stdout，首次截图字节不可解码；
  后续使用主屏文件截图和 pull，保留失败证据，不隐藏此工具边界。
- 只读器 `Rc3OnlineBookReadProbe.java` / `rc3_online_book_read.py` 按确切书名
  查询，未导出其他书正文或改写用户数据库。
- 原有精简日志开启，成功更新的 INFO 不出现在 logcat，
  以数据库/文件/真实正文闭环判断，不以“没有日志”当失败。
- 对用户报告“其他书正常”的书没有点击更新或改写；
  打开目标书时曾误打开因近期阅读重排而变到第一行的另一书，
  立即退出，未编辑、下载或更新其内容。
- 真实外部账号切换、封面上传、字体映射等扩展验收仍暂停且未标通过。

## 构建与交付

- `newonline-catalog-recovery-build.log`：156 项 JVM 测试，0 failures / errors；
  `:app:assembleRelease` 成功，2 分 51 秒。
- 模块仍为 2.3.10 / 72。
- `app/build/outputs/apk/release/app-release.apk` SHA-256：
  `9ad2cc61a14fc28523a38321f217e988bd174a7db21a1b097a98eaf5a3ee4516`。
- 已安装新设备，回读安装路径 APK 与构建文件摘要一致。
- 实机留在目标书第 2000 章正文，供用户确认，不再自动进行其他任务。
