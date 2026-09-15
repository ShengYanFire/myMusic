# MyMusic 代码审查与优化指南

> 审查对象:`android/` 全部 29 个 Kotlin 源文件 + Gradle 配置 + Manifest + 网络安全配置(当前工作区,即未提交的 aurora 改版,+2430/−845 行)。
> 审查方式:逐文件人工通读 + 三个并行子代理分区复核(网络/数据层、大 UI 屏、UI 壳与主题),可疑结论回源码或 Compose/media3 运行时源码二次核实;所有问题均给出文件与行号,可直接定位。
> 汇总:**1 个 P0、8 个 P1、24 个 P2、27 个 P3(共 60 项)**,按"第 0–3 批"路线图给出落地顺序。

---

## 修复状态(2026-09-14,本轮"解决所有问题"落地)

> 全部修改已通过 `gradlew :app:assembleDebug`(JDK 17 + AGP 8.13)完整编译并产出
> `android/app/build/outputs/apk/debug/app-debug.apk`;单元测试源码已就位
> (`src/test/`,4 个测试类),因沙箱无网络无法解析 JUnit 依赖,需在有网的机器上
> 跑 `gradlew :app:testDebugUnitTest` 验证(依赖已声明,`assembleDebug` 不受影响)。

**✅ 已修复(53 项)**

| 类 | 项 | 落地方式(简) |
|---|---|---|
| P0 | E1 | `AndroidView(onRelease = { it.destroy() })`,彻底换掉 DisposableEffect 销毁模式 |
| P1 | A1/E2 | `PlayerUiState` 移除 `positionMs` + `@Immutable`;独立 `PlayerController.positionMs: StateFlow<Long>`(仅播放中 tick);seekbar/歌词行/MiniPlayer 进度全部改为叶子自收集;瞬态提示拆 `PlayerStatusLine` 叶子;歌词 `derivedStateOf(activeIndex)` + `key = startMs` |
| P1 | B1 | 队列管线整体抽为 `PlaybackQueueCoordinator`(plain class,`TrackResolver`/`PlayerOps` 两个注入缝),MainViewModel 只留 UI 状态 |
| P1 | B2 | `onNeedResolveNext` 静态回调 → `PlayerController.needResolveNext: SharedFlow<Track>`,VM 订阅 |
| P1 | C1 | LibraryStore 存前剥 `audioUrl/audioUrls`、flow `.flowOn(Default)`、空名校验、解析容错 `sanitizeLoaded` |
| P1 | E16 | `GradientSeekBar` 三个回调 `rememberUpdatedState`,手势协程永远调最新闭包 |
| P1 | E17 | 底栏/MiniPlayer 内容色统一强制 White/White74(深色玻璃为品牌选择) |
| P1 | F1 | `src/test/`:EncWbi(官方向量+独立 MD5 交叉核对)、parseLrc/cleanTitle、runSuspendCatching、PlaybackQueueCoordinator(fake 注入) |
| P2 | A2 | 队列合并走 `mergeResolvedIntoQueue`(相等跳过,免无谓发射) |
| P2 | A3 | 睡眠定时:pause+stop+stopService+Home intent,删除 `Process.killProcess` |
| P2 | A4 | seek 预览魔数收敛为命名常量;`syncFromPlayer` 处统一确认 |
| P2 | B3 | `util/RunSuspend.kt` 的 `runSuspendCatching` 统一替换(ping/推荐池/歌词/LRCLIB),VM 各 catch 补 rethrow |
| P2 | B4 | 解析缓存 LRU(`LinkedHashMap(accessOrder=true)` 淘汰最老) |
| P2 | B5 | 消息改 `Channel<String>(BUFFERED) + receiveAsFlow`,删 consumeMessage |
| P2 | B7 | `inFlight` 在 async 的 finally 里移除 |
| P2 | B8 | `AppSettings.toggleGenre/toggleMood` 原子事务(edit 内读改写) |
| P2 | B9 | reload 进行中请求合并为脏标记,`finally` 链式补跑 |
| P2 | C2 | 库层身份全面改 uid(收藏去重/歌单增删/key/移除签名 `removeFromPlaylist(uid)`) |
| P2 | C3 | PlaybackService 媒体路径删除 Cookie(runBlocking 随之消失) |
| P2 | C6 | 媒体路径无 Cookie + `LyricsClient` 仅 https 附 Cookie + 明文放行名单移除 bilibili.com |
| P2 | D1 | WBI 取钥 `Mutex` 单飞;`wbi/wbiFetchedAt` @Volatile |
| P2 | E3 | 滑动提示 `derivedStateOf` + 独立 `SwipeDirectionHint` 叶子 |
| P2 | E4 | doSearch 开始即清 `searchResults` |
| P2 | E6 | CreatePlaylistDialog 空名/在途禁用确认;`addToPlaylist` 布尔结果透传 UI |
| P2 | E7 | `LocalAuroraColorPhase`(128 级量化 ≈8 次/秒)驱动所有纯色消费者(fill/glow/accent/text/icon/进度条),空间动画保留满帧;AuroraSky 基座渐变 `remember(dark)` |
| P2 | E18 | seekbar 滑块改 lambda `offset { IntOffset(…) }`(placement 相位) |
| P2 | E19 | GradientProgressBar 补大幅后退 snap |
| P2 | E25 | NOW_PLAYING/LYRICS 路由跳过外层天空 |
| P2 | F2 | release 开 R8 + 资源收缩,keep 规则按 Gson 反射类型精确收敛 |
| P2 | F3 | `git rm --cached *.apk` + `.gitignore` 加 `/*.apk` |
| P3 | A5 | 单一 `TrackRepository`/`LyricsRepository`/`LyricsClient`/OkHttpClient,全部由 MyMusicApp 注入 |
| P3 | A6 | ticker 仅播放中/seek 在途时更新位置(空闲零发射) |
| P3 | B10 | `instance` 在 `attachBaseContext` 发布;DataStore 触发的单例保持 `onCreate` 构建(DataStore 委托读 `applicationContext` 会在 attachBaseContext 阶段为 null、直接闪退——见下方"回归修正") |
| P3 | C4 | 共享基础 OkHttpClient 注入两客户端;Coil 图像客户端独立(带 Referer/UA 拦截器) |
| P3 | C5 | 只存 `SESSDATA`(harvest 后清 CookieManager);`allowBackup=false` |
| P3 | C7 | 读取端 `sanitizeLoaded` 容错(修 page<1/空 title/坏条目),schema 演进先有缓冲层 |
| P3 | D2 | UA 常量入 `BiliHeaders`,升至 Chrome 132 |
| P3 | D4 | `OkHttpClient.awaitCall`(enqueue + suspendCancellableCoroutine,取消即 `call.cancel()`) |
| P3 | D5 | `o.get("content").asStringOrNull()` |
| P3 | D6 | audioStream 全防御解析(`as? JsonObject`/str()),duration 取实际选中的档 |
| P3 | D7 | LyricsRepository 缓存改 `ConcurrentHashMap` |
| P3 | E5 | `isRefreshing` derivedStateOf(分支全拆自收集因共享 LazyColumn 结构限制,部分落地) |
| P3 | E9 | API<31 跳过模糊封面图(scrim+aurora 降级) |
| P3 | E10 | WebView ON_PAUSE/ON_RESUME 生命周期 |
| P3 | E11 | Library tab/选中歌单 `rememberSaveable` |
| P3 | E12 | 刷新期间保留旧词 + 按钮极光高亮反馈 |
| P3 | E13 | 队列弹窗点播改走 `vm.requestPlay` |
| P3 | E14 | 同步队列边界判断(repeat/loadingNextUid 例外),320ms 采样仅兜底且去重 |
| P3 | E20 | `AppSettings.QUALITY_HIGH/LOW` 常量;连接测试返回 `Boolean?`;偏好选项表 `remember` |
| P3 | E21 | `LocalIsDarkTheme` CompositionLocal 由主题发布 |
| P3 | E22 | `BiliHeaders`(REFERER/DESKTOP_UA/COVER_UA)四处统一引用 |
| P3 | E23 | 200ms 导航转场;LYRICS/LOGIN `launchSingleTop` |
| P3 | E24 | 通知权限被拒 Toast + strings.xml 文案 |
| P3 | F4 | release 签名:RELEASE_* 环境变量,缺省回落 debug 签名(可安装) |
| P3 | F5 | README 勾选歌词显示 + 增"近期变更"节 |

**⏸ 明确延后(7 项,均为投入产出或风险考虑)**

| 项 | 延后理由 |
|---|---|
| E8 `Modifier.Node` 迁移 | 几十处 `composed` 调用点的机械改写,收益中等,建议独立 PR |
| E15 strings.xml 全量抽取 | 200+ 处文案机械迁移,当前已统一 4 个共享颜色/文案新常量,余量分期做 |
| B6 严格按序插入 | 审查自评"设计取舍可保留":换序插入的正确性风险大于队首 15s 超时的体验损失 |
| D3 请求级重试/退避 | 上层语义兜底(缓存/在途共享/降级)已覆盖,幂等 GET 单次退避收益小 |
| C5 Keystore/Tink 加密 | SESSDATA-only + 关备份已收敛主要暴露面;硬件密钥加密建议对外分发前做 |
| C1 Room 迁移 | JSON-on-Default + LRU + flowOn 已消主线程卡顿与体积问题;Room 属长期演进 |
| E5 分支全拆 | 共享单 LazyColumn(sticky 搜索栏 + banner)的结构限制,已落地 derived isRefreshing |

**遗留提示**

- 单测运行需联网机器:`gradlew :app:testDebugUnitTest`(junit/coroutines-test 依赖已声明,沙箱离线无法解析)。
- release 构建验证同理:`gradlew :app:assembleRelease`(R8 规则已写,离线未跑)。

---

## 一、总体评价

先说结论:**这是一套明显高于个人项目平均水准的代码**,核心链路(播放队列解析)设计得相当聪明,大部分"容易踩的坑"都主动绕开了:

**做得好的地方(值得保持)**

- **播放管道设计**:`MainViewModel.resolveSharedDeferred` 的"in-flight 去重 + 30 分钟 TTL 缓存 + 立即启动"三合一共享解析(`MainViewModel.kt:444-471`),后台队列按序小并发填充(`fillQueueInOrder`),403 时镜像轮换 → 重新解析 → 跳过死曲的三级降级(`PlayerController.kt:128-224`),分P/合集零额外请求的队列上下文推导(`playFromList`)。这些互锁逻辑注释详尽,能看出是踩坑后修出来的。
- **防御式 JSON 解析**:`BiliDirectClient` 手写 `JsonObject` + `longOrNull()/str()` 容错读取(`BiliDirectClient.kt:364-385`),规避了 Gson + Kotlin 非空字段的经典空指针陷阱,B 站各种畸形响应(数字字符串、JSON null)都能容忍;WBI 签名与官方测试向量一致。
- **Compose 细节讲究**:AuroraFlow 单一 16 秒共享时钟 + `CompositionLocal`(`AuroraFlow.kt:186-201`),动画读值尽量放 draw phase、动画 spec 全部 `remember`、`LazyColumn` key 用 uid/bvid 并主动去重防崩溃、搜索/歌词/队列全用 generation 计数防 stale 写;连 Navigation 2.8.2 的 saved-state 边界都查证并写进了注释(`MainScreen.kt:84-106`)。
- **播放服务调优**:`DefaultLoadControl` 缓冲参数针对"seek 跟手"调优(`PlaybackService.kt:57-68`),音频焦点、audio becoming noisy、跨协议重定向、桌面 UA 都处理了。

**主要问题集中在五个方向**(下文详述):

1. **三个用户可感的正确性 bug**:登录页重试卡死(Compose 副作用时序误解)、换歌后 seek 按旧时长错位(`pointerInput` 陈旧闭包)、浅色主题底栏不可读;
2. **持续性性能开销**:500ms 全量状态 tick + 极光引擎按显示帧率常开动画 + 每帧分配,播放中全 App 每秒两次整屏重组、空闲时也在烧电;
3. **数据层规模瓶颈**:收藏/歌单用"整串 JSON 塞 Preferences DataStore"且在主线程解析,数据一多必然卡;
4. **状态双源与上帝 ViewModel**:队列真源分散在 `MainViewModel` 与 `PlayerController` 两处,靠静态回调互锁,难以测试;协程取消卫生(7 处吞 `CancellationException`)与凭据安全(整串 Cookie 明文落盘、可能走明文 HTTP)也是系统性短板;
5. **工程化缺失**:零测试、release 未开混淆、APK 进了 git。

---

## 二、架构与数据流梳理

```
MainActivity ──▶ MainScreen(导航宿主 + MiniPlayer)
                   │ viewModel()
                   ▼
              MainViewModel(≈815 行:搜索/推荐/歌词/收藏/设置/队列编排)
                   │                                              ▲ 静态回调
                   ▼                                              │ onNeedResolveNext
MyMusicApp.instance(ServiceLocator: settings/api/library)   PlayerController(object 单例)
                   │                                              │ MediaController
                   ▼                                              ▼
        BiliDirectClient(WBI签名) ──TrackRepository──▶ PlaybackService(Media3 会话, ExoPlayer)
        LyricsClient(LRCLIB)      ──LyricsRepository          ▲ DefaultHttpDataSource
                                   LibraryStore(JSON/DataStore)
```

**一次"点歌"的完整时序**(理解后续问题的基础):

1. 列表点击 → `requestPlay` → `playFromList`:先只解析**被点的那一首**(20s 超时兜底);
2. `/view` 一次请求带回 cid + 分P列表 + 合集 → 决定队列上下文(分P > 合集 > 原列表);
3. `PlayerController.playQueue`:完整逻辑队列进 state,**只有已解析条目**进 ExoPlayer;
4. 后台 `fillQueueInOrder`:下一首方向优先,4 并发逐条解析,**按序**插入播放器;
5. 点"下一首"而条目未就绪 → `onNeedResolveNext` 静态回调回 ViewModel → 按需解析 → `insertNextOnDemand` 插入并跳播;
6. 播放失败 → 镜像轮换 → 重新解析(每队列每曲一次)→ 跳过死曲。

**队列的"两个真源"**:UI 可见队列(`PlayerController.state.queue`,含未解析条目)与 ExoPlayer 时间线(只含已解析条目),由 `insertQueueResolved`/`insertNextOnDemand` 增量同步;解析缓存与在途去重又在 `MainViewModel` 手里。**这是全项目耦合最重的部分,也是测试最缺失的部分。**

---

## 三、问题清单

严重度:🔴 P0 正确性缺陷/用户可感故障 · 🟠 P1 高(性能或潜在故障) · 🟡 P2 中 · ⚪ P3 低。

### A. 播放 / 播放器

| # | 严重度 | 问题 | 位置 | 说明与修法 |
|---|--------|------|------|-----------|
| A1 | 🟠 P1 | **500ms 全量状态 tick → 全 App 每秒两次重组** | `PlayerController.kt:289-311` ticker 无条件 `_state.copy(positionMs=...)`;`MainScreen.kt:115`、`NowPlayingScreen.kt:97`、`LyricsScreen.kt:84` 均 `collectAsState()` 整个 `PlayerUiState` | `PlayerUiState` 含 `List<Track>`(不稳定类型)→ `MiniPlayer`/`PlayerSeekBar`/`FooterControls` **永远无法 skip**。播放中整棵 MiniPlayer + 播放页/歌词页骨架每秒完整重组 2 次。**修法**:① `PlayerUiState`、`Track` 标 `@Immutable`(构造后不再变);② 把 `positionMs` 拆成独立 `StateFlow<Long>`(仅 `isPlaying` 时 tick,暂停即停),进度条类组件单独收集;③ 常见字段(isPlaying/error)各自小流。收益:播放中主线程负载与功耗显著下降。 |
| A2 | 🟡 P2 | **队列合并 O(n²) 主线程开销** | `PlayerController.kt:542-544`、`587-589`:`queue.map { ... }` 每次插入重建整个 List | 大合集(几百集)后台填充时,每插一条 O(n) 重建 + 触发整列表重组,n 条累计 O(n²)。修法:队列改为不可变结构 + 局部更新(或 `SnapshotStateList`),或插入后只更新 `queueIndex`、列表引用按 uid 增量替换。 |
| A3 | 🟡 P2 | **睡眠定时到点直接 `Process.killProcess`** | `PlayerController.kt:686-700` | 杀进程方式退出:通知可能残留、状态不落盘。修法:`pause + stop + stopForeground` 后 `Activity.moveTaskToRoot`/常规 finish;真需要退出可 `exitProcess` 前先 `release` MediaController。 |
| A4 | 🟡 P2 | **seek 预览靠魔数启发式** | `PlayerController.kt:643-664`(`600ms/750ms/5s` 阈值) | 能工作但脆弱,`pendingSeek` 语义散在 4 处。修法:常量收敛 + 用 `onPositionDiscontinuity(reason=SEEK)` 作为唯一确认信号;或改 Media3 `pollForPosition`。非紧急,但重构队列时一并处理。 |
| A5 | ⚪ P3 | `resolveFresh` 每次新建 `TrackRepository` | `PlayerController.kt:731`;`MainViewModel.kt:47` 又自建一个 | `TrackRepository` 无状态,应注入单例(至少 3 处实例)。顺带把 `resolveFresh` 从 Controller 抽到仓库层。 |
| A6 | ⚪ P3 | 位置 ticker 永不停止 | `PlayerController.kt:290-311` | 空闲时虽不更新 state,协程仍每 500ms 醒一次。改:无 current 且无睡眠定时时挂起等待条件。 |

### B. ViewModel / 状态编排

| # | 严重度 | 问题 | 位置 | 说明与修法 |
|---|--------|------|------|-----------|
| B1 | 🟠 P1 | **MainViewModel 上帝对象(815 行,7 个职责)** | `MainViewModel.kt` 全文件 | 搜索 + 分页 + 推荐 + 歌词 + 收藏 + 设置 + **队列解析编排**(缓存/在途去重/分块填充)全部塞一个 VM。队列编排与 UI 无关,却是全项目最复杂的逻辑。**修法**:抽 `PlaybackQueueCoordinator`(持有 resolvedCache/inFlight/queueJob/generation,暴露 `playFromList`/`onNeedResolveNext` 为 Flow/接口),VM 只做 UI 状态映射。拆分后单测才有可能。 |
| B2 | 🟠 P1 | **单例静态回调互锁,且无生命周期清理** | `MainViewModel.kt:286` 往 `PlayerController.onNeedResolveNext`(object 单例)挂 VM 捕获的 lambda;`onCleared` 不解除 | Activity 销毁重建之间短暂窗口里,旧 VM 的已取消 `viewModelScope` 会让"下一首"静默失效;也属内存泄漏隐患。修法:回调改 Flow(`MutableSharedFlow<Track>`)由 Controller 暴露、VM 订阅,天然解除;或 `onCleared` 里置空。 |
| B3 | 🟡 P2 | **`CancellationException` 被系统性吞掉(7 处)** | `MainViewModel.kt:217`(doSearch)、`353`(reloadRecommendations)直接 `catch (e: Exception)`;**数据层 5 处 `runCatching`**:`BiliDirectClient.kt:198-201`(ping)、`TrackRepository.kt:66/72/76-77`(推荐池并发搜索)、`LyricsRepository.kt:30-31`、`LyricsClient.kt:82` | `runCatching` 连 `CancellationException` 一起 catch:① 被防抖取消的旧搜索闪一条"搜索失败";② 取消中的 `ping()` 汇报"无法连接 B 站"(误诊断网);③ 取消中的歌词请求吞掉取消后**还会继续发起 LRCLIB 兜底请求**(白打一次网络);④ 破坏结构化取消语义。**修法**:写一个 `runSuspendCatching` 辅助(先 `catch (c: CancellationException) { throw c }` 再 catch Throwable)统一替换 5 处;`MainViewModel` 两处 catch 前补 rethrow(项目里 `fillQueueInOrder`、`resolveWithSeasonResult` 都写对了,这些地方漏了)。 |
| B4 | 🟡 P2 | **解析缓存满时整表清空** | `MainViewModel.kt:462`:`resolvedCache.clear()` | LRU 语义变"全清",热门曲目缓存同时失效。修法:`LinkedHashMap(accessOrder=true)` + 淘汰最老,或迭代删头。 |
| B5 | 🟡 P2 | **消息用会合并(conflated)的 StateFlow:快速连发被静默丢弃** | `MainViewModel.kt:106-115`;`MainScreen.kt:117-124` | 具体机制:`showSnackbar` 挂起约 4 秒等消失;挂起期间新 `showMessage` 覆盖 StateFlow 值(合并);snackbar 消失后 `consumeMessage()` 把**没看过的第二条**清成 null——第二条永远不显示还被抹掉。而 App 恰好连续发这种序列:"解析音源中…" → "连播合集:共 N 集"(`MainViewModel.kt:570/678-679`)。修法:一次性事件用 `Channel<String>(BUFFERED) + receiveAsFlow()`,删掉 consume 步骤。 |
| B6 | ⚪ P3 | `fillQueueInOrder` 严格按序插入 | `MainViewModel.kt:760-773` | 队首一条卡满 15s 超时,已解析的后续条目也插不进。设计取舍可保留;可选:超时条目标记跳过、其余继续,或乱序解析按序插入窗口化。 |
| B7 | ⚪ P3 | `inFlight` 在 scope 取消时残留已取消 Deferred | `MainViewModel.kt:453-470` | VM 销毁才触发,影响极小;`await()` 会抛 Cancellation,链路安全。顺手在 async 的 finally 里 remove 即可。 |
| B8 | 🟡 P2 | **偏好切换是非原子读改写:快速多点会丢选择** | `MainViewModel.kt:78-87`(`toggleGenre`/`toggleMood`:`first()` 读在 edit 事务外) | 两次快速点击各起一个协程,都在任一写入落地前 `first()` → 第二次写基于过期集合,把第一次的选择覆盖掉(丢更新)。DataStore 的 edit 是串行的,但这个读在事务外。修法:读移进 edit 事务——`AppSettings.toggleGenre(id)` 直接 `settingsDataStore.edit { prefs -> prefs[key] = (prefs[key] ?: emptySet()).let { if (id in it) it - id else it + id } }`。 |
| B9 | 🟡 P2 | **加载中改偏好被丢弃,违背 UI 的"自动刷新"承诺** | `MainViewModel.kt:340-341`(`if (loadingRecommendations.value) return`)+ `324-329`(combine 收集器);`SettingsScreen.kt:387-391`(承诺"更改后推荐列表会自动刷新") | 偏好变化触发 reload;一次慢网络 reload 进行中时,后续变化撞上 guard 被直接丢弃,且结束后无人补触发——信息流保持旧个性化,而设置页明说会自动刷新。修法:合并去抖——reload 进行中收到新请求就置"脏"标记,`finally` 里复查重跑。 |
| B10 | ⚪ P3 | ServiceLocator 实例模式脆弱 | `MyMusicApp.kt:31-38` | 编译通过但"挪 `attachBaseContext`"的修法被证明不可行:DataStore 的 `preferencesDataStore` 委托构建 Flow 时读 `context.applicationContext`,attachBaseContext 阶段该值为 null,会 NPE 直接闪退(已回滚 `instance` 留 attachBaseContext、DataStore 单例留 onCreate)。`PlayerController.init` 也把启动耦合到 MediaController 绑定。更稳妥是显式传依赖(ViewModelProvider.Factory)。 |

### C. 数据层

| # | 严重度 | 问题 | 位置 | 说明与修法 |
|---|--------|------|------|-----------|
| C1 | 🟠 P1 | **收藏/歌单整串 JSON 存 Preferences DataStore + 主线程解析 + 存了过期字段** | `LibraryStore.kt:30-36`(flow.map 里 Gson 解析,收集发生在 `viewModelScope` = Main.immediate);`41-50`(每次 toggle 全量重序列化重写);`MainViewModel.kt:666-668`(读出时还得手动剥离过期 URL) | 三个叠加的问题:① 每次收藏 = 读整串 + 反序列化 + 序列化 + 写整个文件;每次 DataStore 发射 = 全量重新解析;全在主线程——收藏几百首时点一次心形都能掉帧;② 持久化的 `Track` 带着几小时就过期的 `audioUrl/audioUrls` 直链(存了无用数据,还放大 JSON 体积);③ `Preferences` 本质是 KV 存储,被当数据库用。**修法**(按投入递增):① 保存前 `track.copy(audioUrl = null, audioUrls = emptyList())`;② `favorites`/`playlists` flow 加 `.flowOn(Dispatchers.Default)` + store 内缓存解析结果;③ JSON 落文件(`files/library.json` + 原子写)替代 Preferences;④ 长期换 Room(歌单-曲目两张表,增量写)。 |
| C2 | 🟡 P2 | **身份体系不统一:库用 bvid,队列用 uid** | `LibraryStore.kt:44`(toggleFavorite 按 bvid 去重)、`81`(歌单按 bvid 去重);`NowPlayingScreen.kt:438`(`favorites.any { it.bvid == … }`);`LibraryScreen.kt:205/364`(key=bvid);而播放队列全用 `Track.uid`(分P带 `-Pn` 后缀) | 同一视频的两个分P:互相顶掉收藏、无法共存于歌单、收藏心形状态共享。修法:库层统一以 `uid` 为身份(去重/移除/key 全换),旧数据迁移规则"bvid 视为 P1 的 uid"。 |
| C3 | 🟡 P2 | **PlaybackService 主线程 `runBlocking` 读 DataStore,且 Cookie 只读一次** | `PlaybackService.kt:36-38` | 两个问题:① 服务 onCreate 阻塞主线程读磁盘(StrictMode 违例);② Cookie 在服务创建时固化进 `DefaultHttpDataSource` 请求头——**登录/换号后,已运行的播放服务仍用旧 Cookie**,直到服务重启。修法:`settings.cookie` 用 `stateIn` 预热后取首值,或收集 flow 变更时重建 data source factory;至少文档注明"登录后需重启播放"。 |
| C4 | ⚪ P3 | 三个独立 OkHttpClient;LyricsClient 还随 ViewModel 重建 | `BiliDirectClient.kt:29-32`、`LyricsClient.kt:26-29`、`MyMusicApp.kt:43-56`;`MainViewModel.kt:118-121` | 三套连接池/线程池;`api` 是 App 单例而 `LyricsClient` 每次 VM 重建就新建一个(连带新 OkHttpClient),作用域不一致。修法:MyMusicApp 里建一个基础 client 注入两者(Coil 那个带拦截器的独立 client 可保留)。 |
| C5 | 🟡 P2 | **明文保存整串 Cookie(SESSDATA + bili_jct CSRF 令牌)且参与云备份** | `AppSettings.kt:24/39-43`;`LoginScreen.kt:110-115`(harvest 存的是 WebView **整只** cookie jar:SESSDATA、bili_jct、DedeUserID、buvid3);`AndroidManifest.xml:13`(`allowBackup="true"` 且无 `dataExtractionRules`) | SESSDATA 是密码级长期凭据,bili_jct 更能发起写操作/CSRF;整串明文躺在 `files/datastore/settings.preferences_pb`,云备份/换机迁移会把登录态带出设备(WebView CookieManager 里还常驻一份)。**修法**:只留必要的 `SESSDATA`(`cookie.split(";").firstOrNull { it.trim().startsWith("SESSDATA=") }`),`allowBackup=false` 或备份规则排除 DataStore;更严格用 Keystore 加密(EncryptedSharedPreferences/Tink)。个人自用风险中等,对外分发前必须处理。 |
| C6 | 🟡 P2 | **登录态 Cookie 可能走明文 HTTP(媒体/字幕路径)** | `network_security_config.xml:6-10`(bilibili.com/bilivideo.com/hdslb.com 放行明文);`PlaybackService.kt:47/51`(Cookie 注入所有媒体请求 + 允许跨协议重定向);`LyricsClient.kt:41-44`(Cookie 发往调用方给的字幕 URL,`normalizePic` 只升级 `//` 前缀,`http://` 原样保留) | 配置注释自认"CDN 偶尔走 http"——一旦命中的 CDN 节点或重定向是 http,SESSDATA 就明文上天,共享 Wi-Fi 的中间人可完整截获会话。而**媒体 CDN 根本不需要 Cookie**(Referer+UA 足够拉流)。**修法**:媒体 DataSource 去掉 Cookie 头(或仅 `request.url.isHttps` 时附加,Media3 `ResolvingDataSource`);明文放行名单移除 `bilibili.com`,只留确实需要的 CDN 域。 |
| C7 | ⚪ P3 | Gson 反序列化绕过构造器:字段缺省值失效 | `LibraryStore.kt:117`;`Track.kt:14-24` | Gson 经 Unsafe 实例化(不走构造器、不应用默认值):老数据缺 `page` 字段会得到 `page = 0` 而非默认 1(直接破坏 `uid` 的 `page > 1` 逻辑);缺 `title` 则非空 `String` 里塞 null,首次访问 NPE——而读取路径包在 `runCatching` 里,坏一条静默丢整库。当前字段齐全时无碍,**任何一次 schema 演进都可能触发**。修法:换 kotlinx.serialization/Moshi codegen(尊重空安全与默认值);或加校验 TypeAdapter。 |

### D. 网络

| # | 严重度 | 问题 | 位置 | 说明与修法 |
|---|--------|------|------|-----------|
| D1 | 🟡 P2 | **WBI 密钥并发竞态:冷启动重复打 `/nav`** | `BiliDirectClient.kt:286-301`(`wbi`/`wbiFetchedAt` 普通变量);`TrackRepository.recommendPool` 并发 8+2 个搜索 | 首次进推荐页,所有并发请求都看到 `wbi==null`,各自请求 `/nav`——一次刷新约 10 个重复请求,浪费且更易触发风控。修法:`Mutex` 包住取钥,或 `lazy + Deferred` 单飞(失败时重置)。 |
| D2 | ⚪ P3 | UA 硬编码 Chrome 120 | `BiliDirectClient.kt:399-401` | 会逐渐显旧。抽常量并定期更新;或按 `WebView UA` 生成。 |
| D3 | ⚪ P3 | 无请求级重试/退避 | `BiliDirectClient.get` | 现由上层语义兜底(缓存/在途共享/降级),可接受;若做,仅对幂等 GET 加 1 次短退避。 |
| D4 | ⚪ P3 | `execute()` 不感知协程取消 | `BiliDirectClient.kt:271-273`;`LyricsClient.kt:45-47/99-101` | 线程模型(IO)没问题,但已取消的请求会跑完(最长 30s 读超时),与 B3 的吞取消叠加放大。修法:`enqueue` + `suspendCancellableCoroutine`,`invokeOnCancellation` 里 `call.cancel()`。 |
| D5 | ⚪ P3 | `fetchBiliSubtitle` 的 `content` 字段遇 JSON null 会抛异常 | `LyricsClient.kt:54`:`o.get("content")?.asString` | `JsonNull` 不是 Kotlin null,`.asString` 直接抛 `UnsupportedOperationException`(同文件的 `asStringOrNull` 助手就是为此而生却没用上):一条值为 null 的 `content` 会废掉整个字幕解析(上层兜底降级到 LRCLIB),而不是跳过这一行。修法:`o.get("content").asStringOrNull()?.trim().orEmpty()`。 |
| D6 | ⚪ P3 | `audioStream` 解析两处不够防御 + duration 取自未排序首元素 | `BiliDirectClient.kt:169/181-183/188-193` | `map { it.asJsonObject }`、`el.asString` 遇畸形元素直接抛(与全文件的防御风格不一致);`duration` 取自 API 原始顺序的第一个音质档,而 `urls.first()` 是排序后的最优档——档位 duration 缺失时会错失。修法:统一 `as? JsonObject` / `str()` 风格;duration 取 `ordered.first()`。 |
| D7 | ⚪ P3 | `LyricsRepository` 缓存无并发保护 | `LyricsRepository.kt:26/29/33-37` | 当前唯一调用方在主线程串行,无碍;但类自身不设防,未来换线程即成数据竞争。修法:`ConcurrentHashMap` 或 `Mutex`;顺带可缓存"无歌词"负结果,免得反复探测同一首。 |

### E. UI / Compose

| # | 严重度 | 问题 | 位置 | 说明与修法 |
|---|--------|------|------|-----------|
| E1 | 🔴 P0 | **登录页"重新加载"会销毁新建的 WebView(并泄漏旧实例)** | `LoginScreen.kt:134-136`:`DisposableEffect(retryCount) { onDispose { webView?.destroy() } }` 配 `key(retryCount) { AndroidView(update = { webView = it }) }` | Compose 运行时顺序(已在 `CompositionImpl.applyChangesInLocked` 源码核实:节点变更先执行,RememberObserver 的 `onDispose` 后派发):retryCount 变化的那次重组里,新 WebView 的 `factory/update` 先把 `webView` 指向**新实例**,随后旧 DisposableEffect 的 `onDispose` 才执行——`destroy()` 命中新实例,旧实例永远没人销毁(Chromium 渲染器/JS 原生泄漏)。用户可见症状:被销毁的 WebView 不再回调,`loading` 永远为 true,**"正在加载 B 站登录页…"遮罩卡死,重试按钮等于坏了**。**修法**:删掉 var + DisposableEffect,直接 `AndroidView(factory, update, onRelease = { it.destroy() })`——`onRelease` 与实例严格绑定;或捕获实例引用再 dispose。 |
| E2 | 🟠 P1 | **三个整屏每 500ms 全量重组(状态粒度过粗)** | `NowPlayingScreen.kt:97-101`(state 读点遍布 460 行函数体:98/120/311/320/329/337/391/419/525);`LyricsScreen.kt:84-85/205-211`(`positionMs` 传参变化 → `LyricsList` + `FooterControls(整个 PlayerUiState)` 每 tick 重跑,`itemsIndexed` 无 key);`MainScreen.kt:115`(根作用域整读 → 捕获不稳定 `playerState` 的 `bottomBar` lambda 让 **Scaffold 的 bottomBar 槽不可 skip**,MiniPlayer + 底栏 + 900ms 天空亮度动画全跟着重跑) | `PlayerUiState` 含 `List<Track>`(不稳定)→ 持有它的组件**永远无法 skip**。播放中:整棵 MiniPlayer、播放页全部分支(含 `favorites.any`,438)、歌词页骨架 + `activeIndex` 线性扫,每秒重跑 2 次。**修法**:① 顶部只收集 `current`(map 后 collect),或只收 `isPlaying` 派生切片;② 瞬态状态(error/retrying/buffering/loadingNext,311-347 行)收进一个自收集的小 `PlaybackStatusLine()` 叶子组件;③ `PlayerSeekBar` 内部自行收集 position;④ 歌词页把 `activeIndex` 用 `derivedStateOf` 收敛成叶子只传 Int;⑤ `itemsIndexed(lines, key = { _, l -> l.startMs })`。与 A1(positionMs 独立流)配套做。 |
| E16 | 🟠 P1 | **拖动条 `pointerInput(Unit)` 陈旧闭包:换歌后 seek 全部按旧时长计算** | `Components.kt:505-537`(tap/drag 手势闭包捕获初次的 `onValueChange/onSeekFinished`)+ `PlayerSeekBar` 的 `maxMs = state.durationMs`(686-697) | `pointerInput(Unit)` 只在 Unit key 下启动一次,协程里拿着**第一次组合时**的回调;`onSeekFinished` 闭包又捕获当时的 `maxMs`。从 4 分钟歌切到 3 分钟歌后,每次点击/拖动仍按 `fraction × 240000` 计算——**核心交互系统性错位**(超出新时长才被播放器截断)。修法:`GradientSeekBar` 内对三个回调用 `rememberUpdatedState` 再在手势里调用。 |
| E17 | 🟠 P1 | **浅色主题下 MiniPlayer/底栏内容几乎不可读** | `AppGradients.kt:24/27`(`BarGlass = 0xF20B1622`、`BarBottom = 0xFF070D18` 固定夜色玻璃);`MainScreen.kt:260`(标题用 `onSurface`,浅色主题=近黑墨 0xFF0E2420);`332-334`(底栏渐变同用夜色玻璃,未选中 tab 用 `onSurfaceVariant` 深灰绿) | 浅色主题是完整支持的(`Theme.kt:48-55` 有整套浅色板),但两条玻璃栏写死了深夜色:近黑文字落在近黑玻璃上,对比度≈1:1。播放/暂停珠已经强制 `Color.White`(292),文字却没强制——混用暴露了意图。修法:栏面随主题走(浅色:半透明 surface + 正常 onSurface 文字),或明确"深色玻璃"为品牌选择并把栏内文字统一强制 White70/White40。 |
| E3 | 🟡 P2 | **滑动提示在 composition 里读动画值:整个播放页每帧重组** | `NowPlayingScreen.kt:468`:`if (abs(discOffset) > hintThresholdPx && !trackSwitching)` | `discOffset` 是 `animateFloatAsState`(141-145),整页滑动手势期间以帧率变化;这个 composition 级读取让**整个 NowPlayingScreen 作用域每个动画帧(60-120Hz)失效一次**,而布尔值整个手势只翻转两次。(213-217 的 `graphicsLayer` 读取是对的——draw phase。)修法:`val showHint by remember { derivedStateOf { abs(discOffset) > hintThresholdPx && !trackSwitching } }`。 |
| E18 | 🟡 P2 | **拖动条滑块用非 lambda `offset`:播放中每帧重组** | `Components.kt:613`:`.offset(x = (maxW * display) - 3.dp)` | `display` 是 500ms 滑动动画值,播放期间几乎总在动;非 lambda `offset(Dp)` 是 composition/layout 读 → `BoxWithConstraints` 整个内容(Canvas、滑块 Box、pointerInput 链)按帧率重执行,叠加在 Canvas 已正确延迟的 draw 失效之上纯属浪费。修法:`Modifier.offset { IntOffset(…) }`(layout 相位)或并入已有的 `graphicsLayer`(616)块用 `translationX`。 |
| E4 | 🟡 P2 | **搜索失败静默保留旧结果,错误永远不渲染** | `SearchScreen.kt:160`(error 分支要求 `results.isEmpty()`);`MainViewModel.kt:206-223`(doSearch 开始时不清 `searchResults`,catch 也不 showMessage) | 新关键词搜索失败时:旧列表仍在 → `results.isNotEmpty()` 分支胜出 → 用户看到**上一个关键词**的结果,零错误提示(唯一信号是刷新 spinner 停了)。修法:doSearch 开始时清空结果,或 `error != null` 时无条件渲染一条内联错误条。 |
| E5 | 🟡 P2 | **SearchScreen 一个作用域收集 15 个 StateFlow:每次击键/每个 flag 翻转全屏重组** | `SearchScreen.kt:85-99`、`113-117`(`query` 在屏体里读取:113/122/212) | 每个击键重跑整个 SearchScreen(含 LazyColumn 内容注册);每个 `loading*` 翻转(加载更多开始/结束、resolving)同样全屏。修法:`when` 各分支拆成 `ResultsContent`/`RecommendationsContent` 自收集子组件;`isRefreshing` 包 `derivedStateOf`。 |
| E6 | 🟡 P2 | **对话框无防重复提交 + 无条件成功提示 + 空名可创建** | `NowPlayingScreen.kt:491-497/504-509`;`LibraryScreen.kt:140-145`;`LibraryStore.kt:70/81`;`Components.kt:826-827`(空名确认键不禁用,store 也不校验 → 建出 `name = ""` 的歌单) | ① `addToPlaylist` 对重复 bvid 返回 false,但 UI 无视返回值照样弹"已加入歌单";② 对话框在 DataStore 挂起往返期间仍可交互,快速双击"创建"会建出两个歌单(id 是毫秒时间戳,差 1ms 都不同);③ 无错误处理。修法:store 返回值透传到 UI;in-flight 标志禁用确认按钮 + `name.isNotBlank()` 才可确认;先关对话框再提示。 |
| E7 | 🟡 P2 | **极光引擎以显示帧率做无效动画 + 每帧分配,所有屏所有状态常开** | `AuroraFlow.kt:344-355`(基座渐变按 `dark` 是常量,却每帧重建)、`119`(`auroraWindow` 每帧 new List)、`160`(`auroraFill` 每元素每帧 new `Outline`)、`383-395`(3 个幕帘 Brush)、`220-224`(`AuroraText`/`auroraAccent` 在**composition 相位**读 `phase.value` → 每实例每帧重组:底栏选中标签、设置页摘要、每个歌单行的 `AuroraIcon`);`Components.kt:107-114/157-246`(flowColors 列表、drawSilkTail 每帧 4 Path+4 Brush) | 16 秒共享时钟让每个极光元素的 draw 相位按 60-120Hz 失效——包括完全空闲的设置页和暂停的播放器;十几个 auroraFill + AuroraSky ≈ 每秒 1000+ 短命对象(120Hz 翻倍)+ 持续 Choreographer 唤醒,为一个每秒几次更新就无视觉差异的漂移付电池/GC 税。**修法**:① `remember(dark)` 缓存基座渐变与静态颜色;② **量化发布相位**:`derivedStateOf { (phase.value * 128f).toInt() / 128f }` ≈ 8 次/秒,视觉无差,失效降 ~16×;③ `AuroraText/auroraAccent` 叶子 API 内部同样量化(每处一行);④ `drawSilkTail` 复用 Path/Brush 实例。另见 E25(叠加天空)。 |
| E25 | 🟡 P2 | 沉浸页三层全屏动画叠加 | `MainScreen.kt:160-164`(全局天空)+ `Components.kt:653-669`(BlurredCoverBackdrop 自带第二个 AuroraSky + 60dp blur 封面) | 播放页/歌词页上:外层天空继续每帧画 56 星+3 幕帘(被遮罩盖住大半,只有顶部 ~20% 透出),底下还有背板自己的天空 + 模糊封面——最重的页面上三层全屏帧率动画。修法:当前路由是 NOW_PLAYING/LYRICS 时跳过或冻结外层天空(沉浸背板自带呼吸感)。 |
| E8 | 🟡 P2 | `Modifier.composed` 实现 auroraFill/auroraGlow | `AuroraFlow.kt:264/278` | `composed` 每个调用点都建工厂节点,官方已建议迁 `Modifier.Node`。数量几十处,收益中等;可在重构主题时统一做。 |
| E9 | 🟡 P2 | `Modifier.blur(60.dp)` 在 API<31 是空操作 | `Components.kt:648`;minSdk=26 | 26–30 设备上播放页背景只是暗化半透明,视觉降级。可选:静态云端预处理模糊图,或接受降级并注明。 |
| E10 | ⚪ P3 | WebView 无生命周期暂停 | `LoginScreen.kt:202-258` | 只有 destroy-on-dispose;后台时登录页 JS 定时器/媒体继续跑。修法:`DisposableEffect` + `LifecycleEventObserver`(ON_PAUSE → `onPause()`,ON_RESUME → `onResume()`)。 |
| E11 | ⚪ P3 | Library 的 tab/选中歌单未 rememberSaveable | `LibraryScreen.kt:63-64` | 旋转/进程重启丢 tab 与歌单详情(而 LazyColumn 滚动位置倒是存了,行为不一致)。修法:`rememberSaveable { mutableStateOf(…) }`。 |
| E12 | ⚪ P3 | 强制刷新歌词无反馈 | `LyricsScreen.kt:136/167-171` | 已有歌词时点"重新获取":`loading=true` 但 `lines` 非空仍走列表分支——用户看到旧歌词,毫无刷新迹象。修法:`loading` 且行非空时叠加一个小 spinner。 |
| E13 | ⚪ P3 | 队列弹窗点播绕过 `requestPlay` 管道 | `NowPlayingScreen.kt:528-537` | 唯一直接调 suspend `vm.playFromList` 的地方:无行内 spinner、不可取消、不受 `playJob` 接管保护。修法:改走 `vm.requestPlay(state.queue, index, useListAsQueue = true) {}`。 |
| E14 | ⚪ P3 | 320ms 启发式竞态可能弹假"已是最后一首" | `NowPlayingScreen.kt:148-167` | 滑动切歌经异步 binder 超过 320ms 未生效时,仍判 `stayed` → 假提示。修法:派发前同步判队列边界(`queueIndex >= queue.lastIndex`),不要采样固定延时后的状态。 |
| E15 | ⚪ P3 | 硬编码文案/颜色/魔数 + 超大文件 + 重复实现 | 全部 ui/*.kt:文案约 200+ 处;`Color(0xFFFFC96B)`/`0xFF7EC8FF`(326/334/344);`delay(320)/(420)`、`tween(160)`;UA 硬编码(LoginScreen.kt:506-508);NowPlayingScreen 740 行混屏+列表+3 个对话框;**两个不同的私有 `AuroraSpinner`**(LoginScreen.kt:373 vs LyricsScreen.kt:302);"全部播放"两处实现;`CreatePlaylistDialog` 接线复制粘贴(NowPlayingScreen.kt:501-511 vs LibraryScreen.kt:137-147) | 抽 `strings.xml`/主题色/命名常量;对话框与 QueueSheet 拆独立文件;合并 AuroraSpinner。机械工作,可分批。 |
| E19 | ⚪ P3 | MiniPlayer 进度条换歌时倒滑 | `Components.kt:317-322` | `GradientSeekBar` 对大幅后退有 snap 逻辑(435),`GradientProgressBar` 没有——每次换歌,填充从 ~0.9 滑回 0,肉眼可见地"倒带"。修法:套用同款 snap 条件。 |
| E20 | ⚪ P3 | SettingsScreen 杂项 | `SettingsScreen.kt:143-151/205/307/81` | "high"/"low" 魔法串跨 3 个文件;成功样式靠中文子串匹配(`it.contains("正常")`)——应返回类型化结果;`MusicGenres.ALL.map{}` 每次重组重算且不稳定 List 参数让卡片不可 skip——`remember` 包一下;滚动位置旋转即丢(`rememberScrollState`,展开状态倒是可保存)。 |
| E21 | ⚪ P3 | 深浅色判定三处各自读取 | `Theme.kt:128-129`、`MainScreen.kt:161`、`SearchScreen.kt:397` 各自 `isSystemInDarkTheme()` | 一旦给 `MyMusicTheme` 加应用内主题开关,AuroraSky/backgroundTop 会与 Material 配色静默不一致。修法:MyMusicTheme 发布一个 `LocalIsDarkTheme`,处处读它。 |
| E22 | ⚪ P3 | B 站 Referer/UA 请求头在 4 条路径重复手写 | `MyMusicApp.kt:46-54`、`BiliDirectClient.kt:264-265`、`LyricsClient.kt:39-40`、`PlaybackService.kt:41-46` | B 站轮换风控要求时要改 4 处;MyMusicApp 还重新手打了一遍 `BiliDirectClient.UA` 已集中管理的 UA。修法:统一引用共享常量(如 `BiliHeaders`)。 |
| E23 | ⚪ P3 | 导航默认 700ms 淡入淡出 + 模态路由可双推 | `MainScreen.kt:165-169/180/198` | 默认转场期间新旧两屏同时组合动画——正是 AuroraSky/blur 最重的时刻;`navigate(LYRICS)/navigate(LOGIN)` 无 `launchSingleTop`,双击叠两个条目(要按两次返回)。修法:设 200ms 的 enter/exitTransition;模态路由加 `launchSingleTop = true`。 |
| E24 | ⚪ P3 | 通知权限被拒是静默 no-op | `MainActivity.kt:16-17/24-31` | Android 13+ 拒绝后,设置页承诺的"通知栏与锁屏可控制播放"静默失效,无理由说明无重试入口。修法:拒绝时给提示,设置页提供重新请求。 |

### F. 工程 / 构建

| # | 严重度 | 问题 | 位置 | 说明与修法 |
|---|--------|------|------|-----------|
| F1 | 🟠 P1 | **零测试** | `android/app/build.gradle.kts` 无任何 test 依赖;工程无 test 目录 | 全项目最复杂、bug 密度最高的恰好是纯逻辑:`fillQueueInOrder`、`playNext/isNextInTimeline`、`resolvePositionMs`、`TrackRepository`(分P/合集/快速路径)、`parseLrc`/`cleanTitle`、`encWbi`(有官方测试向量!)、`LyricsClient.pickBest`。全部可 JVM 单测(fake api 即可)。**建议先补这 7 处,再动 B1 重构**——没有测试网,拆 God ViewModel 等于盲飞。 |
| F2 | 🟡 P2 | release 未开混淆 + icons-extended 全量引入 | `build.gradle.kts:25`(`isMinifyEnabled=false`)、`:56` | debug APK 22MB 主要是 icons-extended 的几千个图标类。开 R8 + `material-icons-extended` 按需(或自定义图标集),体积可砍半以上。 |
| F3 | 🟡 P2 | APK 构建产物进 git | 仓库根 3 个 22MB APK(1 个已提交,2 个 untracked);`.gitignore` 无 `/*.apk` | 每发一版仓库膨胀 20MB+。加 `/*.apk` 到 .gitignore,产物走 GitHub Actions artifacts(工作流已存在)。 |
| F4 | ⚪ P3 | release 无签名配置、无 `assembleRelease` 变体区分 | `build.gradle.kts:23-31` | 对外分发前需 signingConfig;debug/release 同 applicationId 互覆盖。 |
| F5 | ⚪ P3 | README 与实现漂移 | `README.md:100` Roadmap "歌词显示" 未勾选(已实现) | 文档同步,顺手把 aurora 改版补进"技术要点"。 |

---

## 四、优化路线图(按投入产出排序)

### 第 0 批 · 半天内可完成的快赢(不改架构)

1. **E1**:登录页改 `AndroidView(onRelease = { it.destroy() })` —— 修复重试卡死 + 原生泄漏(唯一 P0);
2. **E16**:`GradientSeekBar` 三个回调 `rememberUpdatedState` —— 修复换歌后 seek 错位(核心交互);
3. **E17**:浅色主题底栏文字强制白色或栏面随主题 —— 修复浅色模式不可读;
4. **B3**:新增 `runSuspendCatching` 辅助替换 5 处 `runCatching`,MainViewModel 两处 catch 前补 rethrow —— 恢复结构化取消;
5. **B8**:`toggleGenre/toggleMood` 读移进 DataStore edit 事务 —— 消丢更新;
6. **B5**:消息改 Channel —— 修丢消息;
7. **E4**:doSearch 开始时清空旧结果(或错误条无条件渲染);
8. **E11**:Library tab/selectedId 改 `rememberSaveable`;
9. **D5**:`LyricsClient.kt:54` 改用 `asStringOrNull()` —— 1 行;
10. **F3**:`.gitignore` 加 `/*.apk`,`git rm --cached` 已提交 APK;
11. **A5/B4**:TrackRepository 单例注入;缓存改 LRU 淘汰;
12. **C4/E22**:OkHttpClient 共享基座、B 站请求头常量统一;
13. **C1(部分)**:保存收藏前剥离 `audioUrl/audioUrls` 过期字段。

### 第 1 批 · 性能与安全主菜(1–2 天,收益最大)

1. **A1+E2**:拆 `positionMs` 独立流(仅播放时 tick);`PlayerUiState`/`Track` 标 `@Immutable`;三屏状态读取按叶子收敛(StatusLine/LyricsList derivedStateOf/items key);**E18** 滑块 offset 改 lambda;
2. **E7+E25**:极光时钟量化(128 级 ≈ 16× 失效下降)+ 静态 Brush/渐变缓存 + `AuroraText/auroraAccent` 内部量化 + 沉浸页跳过外层天空 —— 全 App 功耗/GC 最大单笔收益;
3. **C1**:LibraryStore 解析移出主线程 + 写放大治理(flowOn + 缓存;或 JSON 文件);
4. **C3**:PlaybackService 去掉 `runBlocking`,Cookie 变更可生效;
5. **C6**:媒体路径去掉 Cookie 头、明文白名单收窄到 CDN 域 —— 堵住明文凭据外泄;
6. **D1**:WBI 取钥单飞(Mutex);
7. **B9**:推荐 reload 的脏标记合并。

### 第 2 批 · 结构重构(先补测试!)

1. **F1**:为 `fillQueueInOrder`/`playNext`/`TrackRepository`/`parseLrc`/`encWbi`/`pickBest` 写 JVM 单测;
2. **B1+B2**:抽 `PlaybackQueueCoordinator`(解析缓存、在途去重、队列填充、onNeedResolveNext 改 SharedFlow),MainViewModel 瘦身为 UI 状态映射;
3. **C2**:收藏/歌单身份统一 uid + 旧数据兼容;
4. **A2**:队列增量更新,消 O(n²)。

### 第 3 批 · 发布准备

- F2:R8 混淆 + 资源收缩(注意补 proguard keep 规则:Gson 反射的 `Track`/`Playlist`);
- C5:备份规则排除凭据(只存 SESSDATA、可再加 Keystore 加密);
- E15:文案抽 strings.xml;
- A3:睡眠定时退出改优雅路径;
- F4:签名配置;
- E23:导航转场收紧 + 模态路由 `launchSingleTop`。

---

## 五、一句话总结

**逻辑正确性和防御性是这个项目的强项,但三个交互级 bug(登录重试卡死、seek 换歌错位、浅色主题底栏不可读)需要先修;之后的杠杆点不在"再修 bug",而在①把播放位置的持续重组与极光帧率动画量化降下来(功耗/流畅)、②把数据层从"整串 JSON"升级为可增量更新的存储、③恢复协程取消卫生并收紧凭据存取、④给最复杂的队列编排补上测试后再拆掉上帝 ViewModel。** 按四批推进,每批独立可验证、可回滚。
