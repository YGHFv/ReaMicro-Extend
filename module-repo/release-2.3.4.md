## 2.3.4

### 期物与典当

- 修掉期物列表读不全的根因：背包返回的字段名是宿主 `MaterialItem` 的 `name` / `quality`，之前按 `propName` / `propQuality` 读取，导致每条都因「名字为空」被丢掉，清单里只剩内置的 11-18 项；旧字段名保留作兜底。
- 「禁当期物」弹窗新增「刷新期物清单」：现拉 `get-pawn-count` 与 `get-user-materials`，把当日期物与背包全量并进图鉴，第一次配置不用等任务跑过一轮。
- 「禁当期物」改成点选锁定：配置页列出席物，点一下锁定/解锁，锁定项在典当时跳过；一项都不锁即任何期物都典当。
- 品质配色改用游戏真实值（`LoreCardKt.getQualityColor`），排序同步为 LIMIT > GOLD > RED > BLUE > GREEN > GREY；不再自造档位，未知品质不着色。
- 服务端「今天没有期物」时返回的 `specialPropId=0` 不再被记成一行点不动的假期物。

### 通知

- 每日轶闻把奖励明细（阅历/彩筹/期物）写进通知正文与任务记录，物品名就地按品质着色，不点开也能看到领到什么。
- 自动行商的新行商消息带结束时间和新运签效果，并在记录详情中单独列出。

### 字体与界面

- 在线源搜索结果行的城市/作者/元信息改用全局字体：这些行是原生 `TextView`，原先不在 Compose 与 Dialog 的覆盖范围内。
- 模块配置里的「改配置」按钮统一改为「配置」。

### 自动任务与 KSU

- 配套 KSU 模块 ZIP 由 Gradle 任务 `bundleKsuModule` 打进 APK assets；点「使用 KSU」会自动安装内置模块再切换模式，不再需要手动下载。
- KSU 守护跑完任务后的广播会立即在后台线程同步并发出通知，不再等 JobScheduler 的兜底期限。
- 期物典当的「禁当期物」按任务配置，旧配置沿用默认清单，显式清空表示不禁止任何期物。

完整更新记录见 [CHANGELOG.md](https://github.com/YGHFv/ReaMicro-Extend/blob/main/CHANGELOG.md)。
