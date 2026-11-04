# 阅微 2.3.4 beta：DexKit 70 项失败逐项核验

## 结论

- 2026-11-04，模块 2.3.18（versionCode 80），宿主 2.3.4 beta（versionCode 2330）。
- 修复前 1270/1340；正式 release 独立进程扫描 1340/1340，失败 0，目标映射差异 0。
- 手机更新后重新扫描 1340/1340（14117ms），点击扫描对话框的“重启阅微”后已正常回到书架。
- 宿主启动符号验证 360/360、hook 安装 142/142；本次启动日志未见 FATAL EXCEPTION。
- 全程未重启手机，MT PID 保持 6421；只操作阅微/模块，未向对话提供任何图片。
- 仅本地保存及提交；已有手动编辑相关工作保持原样，不混入本次提交。

宿主 APK SHA-256：`24cff525c32788a5c843d38420347603dcb80160927abf9f201f30aef22e341c`。

模块 APK SHA-256：`6afa968ec2bcb0c7ad15c7c50ea1b3b9378136118093bba1e7a6676b2064ad3b`。

## 修复原则

1. 保留全部 441 类 + 899 方法规则，不通过删规则、降覆盖率或取第一候选消除失败。
2. 三种 DEX payload（0x100/0x200/0x300）在 DexKit 2.x 中占原位置、opcode 为 0；只归一这三种编码，仍比较完整指令流。未知编码直接拒绝。
3. 无字符串锚点的方法在原 facade 类内零匹配时，才按完整 opcode/签名/附加约束全局搜索，处理 R8 静态方法分散。
4. 为同形目标补充已解析逻辑类型、参数/返回类结构、稳定平台调用及数值约束；类关系推导同样检查结构。
5. 宋体默认字体 getter 由 ReaderViewModel 构造调用者约束；height 与 requiredHeight 由构造标志 1/0 区分；两个 Compose 函数使用已核实的分组常量区分。
6. 扫描引擎版本 2 → 3，旧的部分成功缓存自动失效。

根因标签可重叠：类结构及签名关系 6；DEX payload 编码归一 15；R8 方法归属迁移 24；同形签名消歧 25；数值常量消歧 3；ReaderViewModel 构造调用链 1；setter 角色纠正 2。

## 两处实际映射纠错

- `EpubSelectionController#setDragHandleVisual`：`ra3.B(Lr99;)V` → `ra3.C(Lp5a;)V`。B 修改位置状态，并非手柄视觉状态。
- `EpubSelectionController#setDraggingHandle`：`ra3.C(Lp5a;)V` → `ra3.D(Ldt2;)V`。D 参数与 `getDraggingHandle` 返回值一致。
- REA 与 MT 均确认 `ra3.H(Ldt2;Lp5a;)V` 依次调用 D、C；原生反射进一步验证 getter/setter/startHandleDrag 的类型闭环。原始 ABI 短名表没有跟着误改。

## 交叉验证与限制

- 每一项都有 REA 6.7.0 与 MT MCP 证据；6 个类逐一比较方法签名全集，64 个方法逐一比较 MT 完整 opcode 名序列、原生 DexKit opcode 流和字符串集合。
- `BookshelfRepository#importBook` 的 REA Java 输出达到 1 MiB 上限而截断；没有把部分输出当完整证据，该项以 MT 分页获取的完整 Smali + 原生 DexKit 指令/字符串校验补足。
- 276 项 JVM 单元测试、5 项规则资产回归全部通过；原始 opcode/字符串及类关系摘要均保留在机器可读清单中。
- 负例：把 height、115 网盘卡片、AboutScreen 的数值约束和 builtInSong 的调用者约束替换为不存在的值，仅对应 4 项变为 candidates=0，没有猜选其他目标。负例仅在独立测试 APK 中运行，未安装。
- 2.3.3 宿主兼容回归：旧/新引擎均 1314/1314，全部扫描输出逐项相同。14 个 ReaMicroScan.Owner 临时角色 ID 的后缀不是当前实际类名，因此这项比较仅用于确认引擎无回退，不将后缀当正确性基准。
- 8 组原生协议检查通过：协程、分页、HTTP 流、SVG、存储反射、UI 基元、搜索/发现、个人页装饰。
- 目录更新探针再次通过增量 SQL、进度保留、原生 collector、Room 原子性、更新桥接和其他书籍失效隔离（3 次查询仅向当前阅读者交付 2 次）。
- 扫描定位成功不等于全部 UI 和长期阅读验收。本轮没有进行数小时真实书库阅读/内存压力测试；先前缓存治理与后台更新隔离修复保持不变。

## 本地证据

`C:\Users\YGHF\AppData\Local\Temp\reamicro-analysis\runtime\scan70-20261104`

- `baseline-failures.json`、`failure-rules.json`：原始 70 项及修复前规则。
- `rea-mt/`：逐项 REA 输出、分页 MT Smali、纠正后的 C/D/H/j 独立证据。
- `release/`：实际签名 release APK、1340 项比对、原生方法审计、空失败/误定位列表。
- `negative/`、`legacy/`：消歧负例与旧版引擎回归。
- `device-cache.json`：手机新扫描缓存，key 以 scan-v3 开头。
- `catalog-probe.log`：本轮目录更新隔离回归。手机 UI/XML、模块启动及协议日志位于同级 `update-20261104/scan70-*`。

本仓库 `docs/dexkit-2.3.4-b1-audit.json` 保存 70 项前后目标、原始约束摘要、REA/MT 文件摘要和通过状态。

## 复核入口

```powershell
python tools/test_auto_scan_rules.py
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
python tools/refine_auto_scan_rules.py --profile app/src/main/assets/host-profiles/2.3.4-b1.profile --apk <host.apk> --rules <rules-copy.json> --failures docs/dexkit-2.3.4-b1-audit.json --cache-dir <analysis-cache>
```

生成器使用审计清单作失败输入，在规则副本上再运行与最终资产字节一致。`AutomaticScanProbe.java` 与 `Rc3ProtocolProbe.java` 编译为独立 dex 后，可在 `/data/local/tmp/reamicro-scan70-*` 中验证正式包；探针不写宿主缓存或用户书库。

## 70 项清单

| # | 逻辑目标 | 核验后的实际目标 | 处理 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | `app.zhendong.reamicro.arch.components.AppTopBarKt` | `Ljme;` | 类结构及签名关系 | 通过 |
| 2 | `kotlin.Pair` | `Lbs7;` | 类结构及签名关系 | 通过 |
| 3 | `org.epub.html.EpubCFI$SideBias` | `Lj93;` | 类结构及签名关系 | 通过 |
| 4 | `ReaMicroHost.HttpResponseBody` | `Lpa9;` | 类结构及签名关系 | 通过 |
| 5 | `org.epub.html.EpubCFI$CharacterOffset` | `Lc93;` | 类结构及签名关系 | 通过 |
| 6 | `androidx.compose.foundation.layout.WindowInsets` | `Lwmc;` | 类结构及签名关系 | 通过 |
| 7 | `ReaMicroHost.BookCoverSheetContent#invoke` | `Lil0;->r(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;` | DEX payload 编码归一 | 通过 |
| 8 | `ReaMicroHost.BuiltInFontRow#render` | `Lyxd;->a(Lwv5;Lbs7;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 9 | `ReaMicroHost.ReaderHighlightPagerContent#invoke` | `Lop8;->y(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;` | DEX payload 编码归一 | 通过 |
| 10 | `ReaMicroHost.SyncAuthContent#render` | `Lq20;->y(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;` | DEX payload 编码归一 | 通过 |
| 11 | `androidx.compose.foundation.BackgroundKt#background-bw27NRU` | `Lq87;->q(Lgp6;JLaga;)Lgp6;` | R8 方法归属迁移 | 通过 |
| 12 | `androidx.compose.foundation.layout.FlowLayoutKt#FlowRow` | `Lm69;->a(Lgp6;Lgy;Ljy;Lld0;IILhr1;Ltx3;II)V` | R8 方法归属迁移 | 通过 |
| 13 | `androidx.compose.foundation.layout.SizeKt#height-3ABfNKs` | `Landroidx/compose/foundation/layout/b;->f(Lgp6;F)Lgp6;` | 同形签名消歧、数值常量消歧 | 通过 |
| 14 | `androidx.compose.ui.graphics.drawscope.DrawContext#getCanvas` | `Lgf1;->w()Lk71;` | 同形签名消歧 | 通过 |
| 15 | `androidx.compose.ui.node.DrawModifierNodeKt#invalidateDraw` | `Lkt2;->H(Ljt2;)V` | R8 方法归属迁移 | 通过 |
| 16 | `androidx.compose.ui.platform.ComposeViewContext#getLifecycleOwner$ui` | `Lct1;->d()Lj26;` | 同形签名消歧 | 通过 |
| 17 | `androidx.compose.ui.text.MultiParagraph#getLineBottom` | `Ljt6;->b(I)F` | 同形签名消歧 | 通过 |
| 18 | `androidx.compose.ui.text.MultiParagraph#getLineTop` | `Ljt6;->f(I)F` | 同形签名消歧 | 通过 |
| 19 | `androidx.compose.ui.text.TextLayoutResult#getLineLeft` | `Lzkb;->g(I)F` | 同形签名消歧 | 通过 |
| 20 | `androidx.compose.ui.text.TextLayoutResult#getLineRight` | `Lzkb;->h(I)F` | 同形签名消歧 | 通过 |
| 21 | `androidx.paging.compose.LazyPagingItems#getItemSnapshotList` | `Loz5;->c()Lj15;` | 同形签名消歧 | 通过 |
| 22 | `app.zhendong.reamicro.arch.EpubFileManager#import` | `Lpp4;->x(Lwv7;Lwv7;Loqc;)Lbs7;` | DEX payload 编码归一 | 通过 |
| 23 | `app.zhendong.reamicro.arch.components.DividerKt#SimpleDivider-iJQMabo` | `Lrt0;->e(Lgp6;JLtx3;II)V` | R8 方法归属迁移 | 通过 |
| 24 | `app.zhendong.reamicro.repository.BookshelfRepository#importBook` | `Lxo0;->o0(Lf08;Lwv7;Lqk7;Ljava/lang/String;Ljava/lang/Long;Lkotlin/jvm/functions/Function1;Lo12;)Ljava/io/Serializable;` | DEX payload 编码归一 | 通过 |
| 25 | `app.zhendong.reamicro.repository.BookshelfRepository#importTxtBook` | `Lxo0;->q0(Lqh0;Lwv7;Ljava/lang/String;Ljava/lang/Long;Lo12;)Ljava/io/Serializable;` | DEX payload 编码归一 | 通过 |
| 26 | `app.zhendong.reamicro.sync.S3ManifestStore#commit` | `Lra0;->l(Ljava/util/List;Ljava/util/List;Ljava/util/Map;Ljava/util/Set;Lo12;)Ljava/lang/Object;` | DEX payload 编码归一 | 通过 |
| 27 | `app.zhendong.reamicro.ui.backup.components.DriveCardKt#DeleteButton` | `Lfke;->c(ZLkotlin/jvm/functions/Function1;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 28 | `app.zhendong.reamicro.ui.backup.components.DriveCardKt#Yun115NetDiskCard` | `Lfke;->e(Lkg1;ZLkotlin/jvm/functions/Function1;Ltx3;I)V` | R8 方法归属迁移、数值常量消歧 | 通过 |
| 29 | `app.zhendong.reamicro.ui.home.HomeScreenKt#BookshelfSelectionBottomBar` | `Lmyd;->a(Lxe4;ZLwu3;Lwu3;Lwu3;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 30 | `app.zhendong.reamicro.ui.home.HomeScreenKt#OldBookCover-ziNgDLE` | `Lmyd;->f(Ljava/lang/String;FLtx3;I)V` | R8 方法归属迁移 | 通过 |
| 31 | `app.zhendong.reamicro.ui.home.HomeScreenKt#OldDatabaseMigrationOverlay` | `Lmyd;->g(Ljava/util/List;Ljava/util/Map;Lpl6;ZLwu3;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 32 | `app.zhendong.reamicro.ui.reader.components.CurlPagerKt#CurlPager` | `Lhfe;->b(Lke6;Lbv8;Lav8;Lkr8;Ljr8;Lhr8;Lir8;Lk6b;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 33 | `app.zhendong.reamicro.ui.reader.components.CurlPager_androidKt#PlatformCurlPager` | `Ljfe;->d(IIZILkotlin/jvm/functions/Function1;Lkv3;Lkv3;Lkv3;Lwu3;Lhr1;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 34 | `app.zhendong.reamicro.ui.reader.components.EpubContainerKt#EpubBackground` | `Lqme;->c(Ly83;ZLtx3;II)V` | R8 方法归属迁移 | 通过 |
| 35 | `app.zhendong.reamicro.ui.reader.components.EpubContainerKt#rememberCornerInfoHeights$lambda$4$heightFor` | `Lqme;->i(FFI)F` | R8 方法归属迁移 | 通过 |
| 36 | `app.zhendong.reamicro.ui.reader.components.SwipePagerKt#SwipePager` | `Lyie;->e(Lke6;Lbv8;Lav8;Lkr8;Ljr8;Lhr8;Lir8;Lk6b;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 37 | `app.zhendong.reamicro.ui.reader.components.SwipePagerKt#SwipePagerContent` | `Lyie;->f(Lke6;Lbv8;Lav8;Lkr8;Lk6b;Lbr7;ZLkotlin/jvm/functions/Function1;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 38 | `app.zhendong.reamicro.ui.reader.components.SwipePagerKt#SwipePagerEffects` | `Lyie;->g(Lke6;Lkr8;Ljr8;Lhr8;Lir8;Lk6b;Lbr7;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 39 | `app.zhendong.reamicro.ui.reader.components.SwipePagerKt#blockOutOfBoundsHorizontalPagerDrag` | `Lyie;->h(Lk6b;Lbr7;Lke6;Lrv6;)Le7c;` | R8 方法归属迁移 | 通过 |
| 40 | `app.zhendong.reamicro.ui.reader.compose.ReaderHighlightScreenKt#StyledQuotePreview-uDo3WH8` | `Leyd;->l(Ljava/lang/String;Lzbb;Ljava/lang/String;JFLtx3;II)V` | DEX payload 编码归一 | 通过 |
| 41 | `app.zhendong.reamicro.ui.setting.AboutScreenKt#AboutScreen` | `Lqje;->a(ILtx3;)V` | R8 方法归属迁移、数值常量消歧 | 通过 |
| 42 | `app.zhendong.reamicro.ui.storage.c115.Y115NetDiskAccountScreenKt#LogOut` | `Lwud;->b(Lwu3;Ltx3;I)V` | R8 方法归属迁移 | 通过 |
| 43 | `kotlin.Pair#equals` | `Lbs7;->equals(Ljava/lang/Object;)Z` | 同形签名消歧 | 通过 |
| 44 | `kotlin.Pair#hashCode` | `Lbs7;->hashCode()I` | 同形签名消歧 | 通过 |
| 45 | `kotlin.coroutines.EmptyCoroutineContext#minusKey` | `Lr53;->f0(Ld32;)Le32;` | 同形签名消歧 | 通过 |
| 46 | `kotlin.coroutines.EmptyCoroutineContext#plus` | `Lr53;->G0(Le32;)Le32;` | 同形签名消歧 | 通过 |
| 47 | `kotlinx.coroutines.BuildersKt#runBlocking` | `Le2c;->g0(Le32;Lkv3;)Ljava/lang/Object;` | R8 方法归属迁移 | 通过 |
| 48 | `kotlinx.coroutines.DelayKt#delay` | `Lff9;->l(JLn12;)Ljava/lang/Object;` | R8 方法归属迁移 | 通过 |
| 49 | `kotlinx.coroutines.DelayKt#toDelayMillis-LRDsOJo` | `Lff9;->V(J)J` | R8 方法归属迁移 | 通过 |
| 50 | `kotlinx.coroutines.JobSupport#minusKey` | `Ly95;->f0(Ld32;)Le32;` | 同形签名消歧 | 通过 |
| 51 | `kotlinx.coroutines.JobSupport#plus` | `Ly95;->G0(Le32;)Le32;` | 同形签名消歧 | 通过 |
| 52 | `kotlinx.coroutines.flow.FlowKt#emitAll` | `Lg05;->F(Lpl3;Lnl3;Ln12;)Ljava/lang/Object;` | R8 方法归属迁移 | 通过 |
| 53 | `org.epub.FontProvider#builtInSong` | `Lsq3;->e()Laq3;` | 同形签名消歧、ReaderViewModel 构造调用链 | 通过 |
| 54 | `org.epub.css.RuleSet#initialize` | `Lik9;->b(Ljava/util/Map;)Ljava/util/List;` | DEX payload 编码归一 | 通过 |
| 55 | `org.epub.css.Style#toString` | `Lw2b;->toString()Ljava/lang/String;` | DEX payload 编码归一 | 通过 |
| 56 | `org.epub.css.StyleSheets#getNodeStyle` | `Ln3b;->a(Lc97;Ljava/lang/String;Z)Lw2b;` | DEX payload 编码归一 | 通过 |
| 57 | `org.epub.css.StyleSheets#preprocessStyle` | `Ln3b;->c(Lg43;Ljava/util/ArrayList;Lw2b;Lw2b;Z)V` | DEX payload 编码归一 | 通过 |
| 58 | `org.epub.html.EpubCFI$SideBias#equals` | `Lj93;->equals(Ljava/lang/Object;)Z` | 同形签名消歧 | 通过 |
| 59 | `org.epub.html.EpubCFI$SideBias#hashCode` | `Lj93;->hashCode()I` | 同形签名消歧 | 通过 |
| 60 | `org.epub.html.node.ContentDom#buildContent` | `Lry1;->j(Lgu$b;Ln97;Leu0;Ljava/util/concurrent/atomic/AtomicReference;I)V` | DEX payload 编码归一 | 通过 |
| 61 | `org.epub.html.node.ContentDom#getLocation` | `Lry1;->b()Ln93;` | 同形签名消歧 | 通过 |
| 62 | `org.epub.html.node.ContentDom#getStyle` | `Lry1;->c()Lw2b;` | 同形签名消歧 | 通过 |
| 63 | `org.epub.html.node.NodeDom#getLocation` | `Ln97;->b()Ln93;` | 同形签名消歧 | 通过 |
| 64 | `org.epub.html.node.NodeDom#getStyle` | `Ln97;->c()Lw2b;` | 同形签名消歧 | 通过 |
| 65 | `org.epub.structure.opf.Opf#coverRelativePath` | `Lqk7;->b(Lwv7;)Ljava/lang/String;` | DEX payload 编码归一 | 通过 |
| 66 | `org.epub.ui.BodyKt#markColorTokenToColor` | `Lkh0;->C(Ljava/lang/String;)J` | DEX payload 编码归一 | 通过 |
| 67 | `org.epub.ui.EpubSelectionController#getDraggingHandle` | `Lra3;->j()Ldt2;` | 同形签名消歧 | 通过 |
| 68 | `org.epub.ui.EpubSelectionController#getSelection` | `Lra3;->k()Lw5a;` | 同形签名消歧 | 通过 |
| 69 | `org.epub.ui.EpubSelectionController#setDragHandleVisual` | `Lra3;->C(Lp5a;)V` | setter 角色纠正、同形签名消歧 | 通过 |
| 70 | `org.epub.ui.EpubSelectionController#setDraggingHandle` | `Lra3;->D(Ldt2;)V` | setter 角色纠正、同形签名消歧 | 通过 |
