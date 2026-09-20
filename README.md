# MyMusic — 轻量 B 站音乐播放器（完全自包含）

一个轻量的安卓音乐播放器：**搜索 B 站视频 → 提取纯音频流 → 播放**。支持后台播放、锁屏/通知栏控制、收藏与歌单、可选登录 B 站账号以获得更高音质。

```
┌──────────────────────────────┐        ┌──────────────┐
│  安卓原生 App (Kotlin + Compose)│  直连   │  Bilibili API │
│  Media3 后台播放               │ ──────▶ │  search /     │
│  内置 WBI 签名 + 接口客户端      │  HTTPS  │  playurl(DASH)│
│  无需任何后端服务器              │        └──────────────┘
└──────────────────────────────┘
```

> **无需后端**：原生安卓没有浏览器跨域限制，App 直接调用 B 站官方接口（并实现网页端所需的 WBI 签名），装 APK 即用，不用部署任何服务器、不用填 IP。本项目只包含安卓工程，无任何服务端代码。

---

## 目录结构

```
my-music/
├── android/                 # 安卓原生 App（Android Studio 工程，自包含）
│   └── app/src/main/java/com/mymusic/player/
│       ├── network/         # BiliDirectClient：WBI 签名 + 直连 B 站接口
│       ├── player/          # Media3 后台播放服务 + 播放控制
│       ├── data/            # 设置(DataStore)、收藏/歌单(JSON)、仓库
│       ├── ui/              # Compose 界面（搜索/播放/我的/设置）
│       └── MyMusicApp.kt    # 应用入口（单例、图片加载器）
└── .github/workflows/build-android.yml  # GitHub Actions 云编译出 APK
```

---

## 一、构建安卓 App

要求：Android Studio。无需任何服务器。

1. 用 **Android Studio** 打开目录 `my-music/android`，等待 Gradle 同步完成（首次下载 Gradle 8.14.3 与依赖，几分钟）。
   - 若提示缺 `gradle-wrapper.jar`：直接点同步，Android Studio 会自动处理；或在命令行运行 `gradle wrapper --gradle-version 8.14.3`。
2. 连真机（开 USB 调试）或起模拟器，点 **Run ▶**；或出安装包：`Build → Build APK(s)`，产物在 `android/app/build/outputs/apk/`，传到手机安装。

### 首次构建：JDK / SDK 配置（本机已验证）

> 项目用 **Gradle 8.14.3 + AGP 8.13.0 + Kotlin 2.2.20**，需要 **JDK 17 或 21** 来运行。

- **⚠️ 不要直接用 AS 自带的 JBR（本机为 JDK 25）跑 Gradle**——Gradle 8.14 不支持 JDK 25，同步会报 "Unsupported Java"。请把 Gradle JDK 指到本机已有的 JDK 17：
  - 本机可复用 **DevEco Studio 自带 JDK 17**：`C:\Program Files\Huawei\DevEco Studio\jbr`（已确认 `javac 17.0.12` 可用，无需下载）。
  - 设置路径：`File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` → 选该目录（没有就点 **Add JDK…** 添加）。
- **SDK**：`android/local.properties` 已把 `sdk.dir` 指向 `D:/Android/Sdk`。首次同步时 AS 会提示下载 SDK 组件（Android 14 Platform / Build-Tools 34.0.0 / Platform-Tools）到该目录——**需要联网**，按提示安装即可。
- **命令行构建（可选）**：先双击 `setup-toolchain.cmd` 写入 `JAVA_HOME / ANDROID_HOME` 环境变量，再运行根目录 `build.bat`。

**没有本地工具链？** 推到 GitHub 触发 `Build Android APK` 工作流，自动编译并产出 debug APK 供下载（云端 runner 自带 JDK 17 与 Android SDK）。

### 使用说明

装好打开即用，无需配置。可选设置（`我的 → 设置`）：

- **音乐/心情偏好（推荐）**：`设置 → 音乐偏好 / 心情偏好` 勾选喜欢的音乐类型（流行/民谣/摇滚/古风…共 33 种）与想听的心情（开心/放松/伤感/燃…共 12 种，均可多选），推荐列表会优先展示所选内容，更改后自动刷新；两张偏好卡片均可展开/收起，不选则随机推荐热门音乐。
- **登录 B 站账号（可选）**：`我的 → 设置 → 网页登录 B 站账号`，在 App 内嵌的 B 站官方登录页完成登录（账号密码 / 短信验证码 / 扫码均可），登录成功自动保存登录态。登录后音质更高、更少被风控拦截。
- **音质**：高清（默认）/ 流畅（省流量）。

### 功能

- 🔍 搜索 B 站视频，点击即在当前列表内连播（上一首/下一首可切歌，后台自动解析整张列表）；支持「全部播放」。
- 🕘 搜索历史：空词（首页）即显示本地搜索历史，点词复搜、单条 × 删除、一键清空；完成一次搜索即自动记录（去重、最新在最前）。
- 📚 合集/选集连播：点到的视频若属于 UP 主的合集（ugc_season），自动把整个合集设为播放队列；若是多P视频（视频选集，一个视频含 P1…Pn 多首歌），自动把全部分P设为播放队列——均从所点那一首开始按顺序连播（分P列表与合集复用解析音源的同一个 /view 请求，不增加额外请求）。
- 🎵 偏好推荐：设置喜欢的音乐类型与心情（卡片可收起展开），推荐页（`为你推荐`）优先展示所选内容，更改即自动刷新。
- ▶️ 后台播放 / 锁屏控制：播放后切后台或锁屏继续，通知栏与锁屏可暂停/切歌（Media3 MediaSessionService）。
- 🔀 随机播放：播放页一键开关，按随机顺序遍历当前队列（「上一首」可沿随机历史回退）；开关与单曲/列表循环独立、可组合。通知栏 / 锁屏 / 蓝牙的「下一首 / 上一首」同样遵循随机顺序（`SessionNavigationPlayer` 接管系统 UI 的切歌命令）。
- 📋 正在播放列表：播放页点列表按钮查看当前队列（含后台仍在解析的条目），正在播放的一行高亮，点任意条目直接跳播。
- ❤️ 收藏：播放页点 ♥ 保存到本地。
- 📋 歌单：新建歌单、加入歌曲、播放歌单、删除/移除。
- 🔐 网页登录 B 站账号：内嵌官方登录页，账号密码/短信验证码/扫码登录后自动保存登录态。
- ⚙️ 设置：网页登录、音质、B 站连通性测试。

---

## 二、技术要点

- **直连 B 站 + WBI 签名**：`/x/web-interface/wbi/search/type`、`/x/player/wbi/playurl` 需要 `wts + w_rid` 签名，密钥取自 `/x/web-interface/nav`（App 内缓存 10 分钟）。实现见 `network/BiliDirectClient.kt`。
  - 签名算法参考向量：`img_key=7cd084941338484aae1ad9425b84077c`、`sub_key=4932caff0ff746eab6f01bf08b70ac45` → `mixin_key=ea1db124af3c7062474693fa704f4ff8`（与 B 站官方实现一致）。
- **合集连播**：`/x/web-interface/view` 的 `ugc_season.sections[].episodes[]` 与 cid 同一次请求返回（识别合集零额外请求）。点播属于合集的视频时，整个合集成为播放队列（含尚未解析的条目），播放器先播所点那一集，其余条目在后台按 4 条一块解析、解析完即插入队列（优先补齐"下一首"）。
- **纯音频流**：`playurl?fnval=16` 返回 DASH，默认取最高码率的 `dash.audio[].baseUrl`（可选低码率）。
- **请求头**：音频 CDN 与封面图都注入 `Referer: https://www.bilibili.com/`（ExpoPlayer 的 `DefaultHttpDataSource` / Coil），符合 B 站防盗链要求。
- **URL 编码**：查询串预先按 WBI 规则编码后整体拼入 URL 由 OkHttp 原样解析，避免二次编码破坏签名。
- **数据持久化**：设置用 Preferences DataStore；收藏/歌单用 DataStore 存 JSON（轻量，无需数据库）。

---

## 三、注意事项与免责声明

- 本工具仅作学习交流，**请勿**用于商业用途或大规模抓取；请遵守 B 站用户协议与版权规定，注意保护个人账号安全（Cookie 请勿外泄）。
- 未登录时部分视频可能无法获取音频流（需大会员内容会被 B 站拒绝），网页登录后可缓解；如遇 `-412` 风控，请稍后再试或网页登录 B 站账号。
- 音源直链有有效期，换歌/重开应用时会重新解析。

---

## 四、Roadmap（可选）

- [x] 音质选择（高清/流畅）
- [x] 完全自包含（无需后端）
- [x] 歌词显示（B站AI字幕优先，LRCLIB 兜底；滚动高亮、点击跳播、独立歌词页）
- [x] 播单记忆上次进度

---

## 五、近期变更

- **代码体检**：按 `CODE-REVIEW.md` 完成一轮系统性修复——播放状态拆分
  （进度条不再每 500ms 触发整屏重组）、极光时钟量化（约 8 次/秒的变色，
  空间动画保持满帧）、队列管线提取为可单测的 `PlaybackQueueCoordinator`、
  WBI 密钥并发单飞、网络请求改为可取消、明文/备份等安全项加固；
  详情见 `CODE-REVIEW.md` 的"修复状态"标注。
- **极光视觉**（未入库的前次改版已并入本次提交）：全套 living aurora
  动效 —— 天幕、进度条、按钮、底栏在同一个 16 秒时钟上呼吸。
