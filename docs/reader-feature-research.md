# 阅读页面长按复制 & 划线书签 — 实现研究

## 架构发现（2026-06-08，基于 RM2.0.A4.APK dex 分析）

### UI 架构

```
app.zhendong.reamicro.ui.reader
├── ReaderViewModel          — 阅读页 ViewModel (MVI)
│   ├── addBookmark()        — 添加书签
│   ├── deleteBookmark()     — 删除书签
│   └── jumpBookmark()       — 跳转书签
├── ReaderUiIntent           — UI 意图（密封类）
│   ├── BookmarkAdd          — 添加书签意图
│   ├── BookmarkDelete       — 删除书签意图
│   ├── BookmarkJump         — 跳转书签意图
│   └── TapDirection         — 点击方向（翻页）
├── ReaderUiState            — UI 状态
├── ReaderScreenKt           — 主阅读 Composable 屏幕
│   └── InitEpubWindow       — EPUB 窗口初始化
├── components/
│   ├── SwipePagerKt         — 滑动翻页组件
│   ├── EpubContainerKt      — EPUB 内容渲染容器
│   ├── TapGesturesBoxKt     — ⭐ 触摸手势处理（长按截获点！）
│   └── ReaderBottomBarKt    — 底部栏（目录/进度/设置）
├── compose/
│   ├── ReaderCatalogKt      — 目录页
│   ├── ReaderSettingsKt     — 设置页
│   ├── ReaderThemesKt       — 主题页
│   └── ReaderTypeSettingKt  — 排版设置
└── theme/                   — 主题颜色
```

### 书签数据模型

```
app.zhendong.reamicro.data.reader.Bookmark  — 书签数据类 (Kotlinx Serializable)
app.zhendong.reamicro.data.epub.Epub
  ├── addBookmark()          — ⭐ EPUB 书签添加（使用 CFI 定位）
  └── updateBookmark()       — EPUB 书签更新
app.zhendong.reamicro.data.epub.EpubPage     — EPUB 页面数据
app.zhendong.reamicro.data.epub.EpubPageData — EPUB 页面内容

Route$Reader               — 导航路由（含 book ID）
ReadRepository             — 阅读数据仓库
```

### 关键发现

1. **EPUB 定位使用 CFI** (Canonical Fragment Identifier)
   - `Epub.addBookmark()` 和 `Epub$ensureBookCfiVersion` 确认
   - 书签包含 CFI 位置标记

2. **MVI 架构**
   - `ReaderViewModel` 继承 `MVIViewModel`
   - 通过 `ReaderUiIntent` 处理用户操作
   - 直接 Hook `ReaderViewModel.addBookmark()` 即可添加书签

3. **触摸手势**
   - `TapGesturesBoxKt` 处理所有触摸事件
   - 需要在此加入长按检测和文本选择逻辑

## 实现方案

### 阶段 1：长按复制

**Hook 目标**：`TapGesturesBoxKt` 的触摸回调

方案：在 `TapGesturesBox` 的 `pointerInput` / `detectTapGestures` 中注入长按回调。
当检测到长按时：
1. 通过 `AndroidComposeView` 获取当前 Compose 节点
2. 使用 `TextLayoutResult` 获取长按位置的文字
3. 调用系统 `ClipboardManager` 复制文本
4. 弹出 Toast 提示 "已复制"

### 阶段 2：文本划线

**Hook 目标**：`EpubContainerKt` + Compose `Text` / `AnnotatedString`

方案：
1. 长按选中文本后，将选中范围包裹 `SpanStyle(background = Color.Highlight)`
2. 保存划线信息到本地 Map（章节+CFI → 划线列表）
3. 刷新渲染（重新设置 `AnnotatedString`）

### 阶段 3：书签持久化

**Hook 目标**：`ReaderViewModel.addBookmark()`

方案：
1. 构造 `Bookmark` 对象（CFI + 选中文本 + 时间戳）
2. 调用 `ReaderViewModel.addBookmark(bookmark)`
3. 阅微原生逻辑会调用 `Epub.addBookmark()` + `ReadRepository` 同步到服务端

## 已清理的临时文件

- `dex_out/` 目录中的 dex 提取文件可删除
