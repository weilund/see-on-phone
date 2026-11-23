<div align="center">

<img src="app/docs/icon.png" width="120" alt="鱼眼 See on Phone">

# 鱼眼 · See on Phone

**让 AI 住进手机 —— 在手机上聊天，需要时自己看世界。**

一个 Android App + 一个电脑插件，同一个仓库，同一份 Apache-2.0。

[![License](https://img.shields.io/badge/license-Apache--2.0-5B9CFF.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-5.0%2B%20·%20no%20Gradle-9B7BFF.svg)](#-自己编译)
[![Deps](https://img.shields.io/badge/runtime%20deps-0-3DDC97.svg)](CREDITS.md)

[它是什么](#-它是什么) · [装哪个](#-装哪个) · [App](#-鱼眼-app) · [插件](#-电脑插件-dsh-see-on-phone) · [出处与署名](CREDITS.md) · [许可](LICENSE)

</div>

---

> **English, briefly.** *See on Phone* puts an AI agent **on** the phone instead of on a server:
> a self-contained Android chat app that can open the camera, fold several frames into one
> contact sheet, remember things, write files, speak, and listen — no PC required. A companion
> Node plugin lets a desktop agent borrow the same camera. Blind navigation is **one optional
> mode**, not the product.

## 🐟 它是什么

**鱼眼是一个能自己看东西的 AI 聊天 App。** 你打字或说话，模型需要知道"眼前有什么"时，
自己调用 `look` 打开摄像头、看 1~8 秒、把几帧拼成一张宫格、然后回答你。
不需要电脑、不需要上传视频流、不需要装 ffmpeg。

它同时还能：**听**（语音输入）、**说**（系统 TTS）、**记**（跨会话记忆）、
**写文件**（自己的一个工作区目录）、**搬会话**（和桌面 DSH 互通）。

电脑上的插件 `dsh-see-on-phone` 是另一半：让**桌面上的 agent** 借手机的眼睛看一眼，
一张 JPEG 回来 —— 屏幕里看不到你桌上的杯子。

### 三条设计取向（决定了它长成现在这样）

| 取向 | 具体做法 |
|---|---|
| **端上跑，不绕服务器** | 模型 API 你自己填；画面在手机本地拼成一张 `471×628` 的宫格（4~7 KB）再发出去。没有中转、没有统计 |
| **相机是工具，不是取景器** | 只有 `look` 被调用时借相机，没人要 3 秒后自动关。想自己看画面进"通话页" |
| **导盲只是读者之一** | `normal`（你看得见，它给你细节）和 `guide`（你看不见，它只给结论和动作）是**同一个模型的两个提示词**，不是两套代码。**它不是辅具**，见 [NOTICE](NOTICE) |

## 📦 装哪个

仓库的 [Releases](https://github.com/SAOS-3624/see-on-phone/releases) 里每次都放**两个文件**，
按你需要挑：

| 你想要 | 下这个 | 还需要 |
|---|---|---|
| 手机上有个能聊天、能看东西的 AI | `see-on-phone-<ver>.apk` | 一个 OpenAI 兼容的 API Key |
| 让电脑上的 agent 借手机的眼睛 | `dsh-see-on-phone-<ver>.tgz` | **必须先装上面那个 APK**（手机就是服务端） |
| 两个都要 | 两个都下 | 同上 |

> **不需要新仓库，也不需要两个仓库。** App 和插件是一个产品的两端，版本一起发、契约一起改，
> 分开放只会让"插件 0.4 配 App 0.29"这种错配变成常态。Release 用两个资产区分就够了。

## 🐠 鱼眼 App


### 能干什么

- **聊天**：流式输出、Markdown 渲染、思考过程可折叠、上下文预算可调（2k~900k）
- **看**：`look` 工具 —— 开相机 → 采样 → 挑帧 → 拼宫格 → 交给模型。宫格是**按镜头切换点**排的，
  动得多的帧给大格子，静止的给一条；不是平均切九宫格
- **陪**：`look_screen` 工具 + 悬浮球 —— 把帧源从相机换成**手机屏幕**，挂着一起看视频、打游戏。
  一次录屏授权，之后点悬浮球就开/关；它按自己的节奏说，不刷屏
- **听**：系统语音识别，单句输入
- **说**：用系统 TTS。装一个语音引擎（讯飞 / 搜狗 / Google TTS / 厂商自带）就能有
  多种音色，装了什么设置里就能选什么。**我们不自己联网合成** —— 原因见[出处](#-出处与署名)
- **记**：`memory_read` / `memory_write`，跨会话；每轮自动带上
- **写**：`files` 工具，一块固定工作区 `Download/DSH-Go/workspace/` —— 文件管理器里能直接看到、拷走、删
  （目录名沿用 `DSH-Go`，这样老版本的会话、记忆、日志原地还能用，不用迁移）
- **搬**：会话与记忆的摊平导出/增量导入，和桌面 `phone-bridge` 对得上
- **给别人看**：内置 HTTP 服务（`127.0.0.1:4444`），电脑插件靠它工作

### 界面

| 会话页 | 通话页 | 设置 | 对话 |
|---|---|---|---|
| 消息流 + 输入栏 + 工具状态 | 实时预览 + 字幕（离开就还相机） | 分组卡片，二十来项配置一页到底 | 置顶 / 今天 / 更早，搜索和新建 |

<p align="center">
<img src="app/docs/shot-chat.png" width="23%" alt="会话页">
<img src="app/docs/shot-call.jpg" width="23%" alt="通话页">
<img src="app/docs/shot-settings.png" width="23%" alt="设置页">
<img src="app/docs/shot-conv.png" width="23%" alt="对话列表">
</p>

> 截图是实机（华为，1084×2412）跑的 1.0.0，没有修饰。

### 它看到的是什么

**不是平均切的九宫格。** 先算逐帧变化分 → 找镜头切点 → 按切点密度分档 →
行 DP 装箱：动得多的帧给大格子，没动的给一条。所以一张图里信息量是**被分配过**的。

<p align="center">
<img src="app/docs/sheet-4.jpg" width="26%" alt="4 帧：静止场景">
<img src="app/docs/sheet-6.jpg" width="26%" alt="6 帧：中等变化">
<img src="app/docs/sheet-9.jpg" width="26%" alt="9 帧：变化剧烈">
</p>

一张 `471×628` 的 JPEG，**4~7 KB**，约 **197 token**。

### 一起看屏幕（陪伴模式）

相机看的是你眼前，屏幕看的是你手里。**同一条"连续看"的链路，只是换了帧源** ——
采样、挑帧、拼宫格、按节奏说话全复用，所以模型那边的体验和看摄像头一模一样。

- **开**：设置 › 陪伴 › 一起看屏幕 → 系统弹一次「开始录制或投放」，点允许。
  之后顶部会出现一个**悬浮球**（红点 = 正在看），点一下开、再点一下关，长按直接关掉。
  也可以说一句"你看看我屏幕"，它会自己调 `look_screen`
- **边看边说**：每轮它说话时，球旁边会冒一个**小气泡**，就一句对当前画面的反应，
  十几秒后自己消失（不打断你在看的视频）。球拖到右半边时气泡自动排到左边，不会被屏幕边裁掉
- **说的是人话，不是解说**：气泡里那句按**弹幕 / 短视频评论区**的口吻来 —— 只讲自己的反应
  （好笑、离谱、想吐槽、替里面的人着急），**不转播画面**（不说“屏幕上是一个…”）。
  你主动问“这页写的什么”时它照常正经回答，那种轮次不套这个口吻
- **关了相机**：看屏幕时相机是**真的还回去了**（不占着、不亮指示灯），所以不会两个同时开
- **它在看什么**：`/screen.jpg` 能拿到最后一帧；`GET /companion?on=1` / `?on=0` 用 HTTP 开关
- **会看到什么**：YouTube/相册/游戏/网页都行；**银行、支付、密码这类加了 `FLAG_SECURE`
  的界面录出来是纯黑** —— 这是系统的保护，不是 bug，它会照实说"这块屏幕不让录"
- **横屏更清楚**：竖屏宽 720，横屏自动抬到 1280×576（看片/打游戏的场景，字幕和血条要能认）
- **首页一键开**：空会话页最上面那个「◉ 一起看屏幕」，点一下就开始（没授权会弹系统弹窗）
- **转屏自动重来**：竖屏画布塞横屏内容会被裁掉两边，所以转屏时会自动停并**重新弹一次**
  系统授权；点一下「开始录制」就按横屏建 1280×576 —— 完整画面，不再只剩中间一条
- **不落盘**：屏幕帧只在内存里走一圈，不写文件、不进会话历史；免费额度按张算，和 `look` 同一本账

### 装法

```bash
# 1. 从 Releases 下 APK，然后
adb install -r see-on-phone-<ver>.apk

# 2. 打开「鱼眼」→ 右上角齿轮 → 接口 → 填 Key
#    接口：https://api.deepseek.com          （任何 OpenAI 兼容端点都行）
#    模型：deepseek-flash                    （能看图、能读文件）
```

第一次点"看"会申请相机权限。**装在应用商店之外**（sideload），系统会提示风险，正常。

### 用 OpenCode Go

手机上除了 DeepSeek 直连，还可以把接口切到 **OpenCode Go**（同一个包月额度，
模型列表里也有一批别家的）。设置页「接口」弹窗顶部有两个预设按钮，点
`OpenCode Go` 就会把端点/模型填好，再填它自己的 key 即可。

**它和直连是"二选一"**：同时只能走一个，改动只影响之后的请求。

和直连比，Go 这条路要多带一个请求头 —— 少了它，**服务端在验 key 之前就把你挡了**：

| 请求头 | 值 | 说明 |
|---|---|---|
| `Authorization` | `Bearer <opencode key>` | 两个服务商都用这个头，key 不共通 |
| `x-opencode-session` | 当前对话 id（稳定） | 少它 → `400 MissingSessionID`；App 自动加 |
| `User-Agent` | `SeeOnPhone/<版本>` | 别用通用 HTTP 库的名字，会被当成爬虫 |

几个差异（App 已经替用户处理，知道一下少踩坑）：

- Go 下**没有**服务端联网搜索、文件上传、自动标题 —— 这几个能力位会被关掉，
  界面上对应的按钮/入口会明确说"切到 DeepSeek 直连才能用"；
- 但**联网还是能开**：Go 下开联网时，搜索由 App 内部再打一次 DeepSeek 官方的
  `/v1/tools/search` 代跑，所以要在同一弹窗下半部分填**第二组 DeepSeek key**
  （可以就是直连用的那个）；
- Go 的 DeepSeek 模型需要在 Console 的 `Settings › Privacy › Regions` 选
  `Global`，不然请求会失败；
- 花费：Go 的响应里带 `usage.cost`（真实美元扣费，含缓存折扣与峰谷），App 直接用它，
  界面上会标「服务端计费」；直连没有这个字段，仍按牌价估算、标「牌价估算」，和实际账单可能有小差。
  两种来源都另加 App 自己发起的搜索/读网页调用（`$0.00075/次`）。

不想开 App 也能验一下端点通不通（**不需要 key**，只验那两个前置校验）：

```bash
node scripts/probe-opencode.mjs
# 或者带 key 真打一次
OPENCODE_GO_KEY=sk-... node scripts/probe-opencode.mjs
```

想在电脑上直接把接口切过去（省得在手机上敲 key），App 的 HTTP 服务有 `/config`：

```bash
adb forward tcp:4444 tcp:4444
curl "http://127.0.0.1:4444/config?base=https://opencode.ai/inference/go/openai/v1&model=deepseek-v4.1-flash&key=sk-...&searchbase=https://api.deepseek.com&searchkey=sk-..."
curl "http://127.0.0.1:4444/status"      # 看当前 provider / 模型 / 花费
```

⚠️ 参数名**全小写**（`searchkey`、`searchbase`，不是 `searchKey`、`searchBase`）——
查询串解析会把键名统一转小写，驼峰写法会被**静默忽略**（不报错，值不生效）。

### 自己编译

不需要 Gradle，不需要 Android Studio。只要 **JDK 17** + 一份 `android.jar` + 四个 jar（d8 / aapt2 / apksigner / lib）：

```bash
export ANDROID_SDK=/path/to/android-sdk     # 里面有 android.jar 和 build-tools
bash app/build.sh                            # 6 步：javac → d8 → aapt2 compile → link → 塞 dex → 签名
```

产物 `app/see-on-phone.apk`，约 220 KB（图标是 WebP）。构建细节与踩过的坑：[`app/docs/BUILD.md`](app/docs/BUILD.md)。

<details>
<summary><b>为什么 targetSdk 是 29（而不是 34）</b></summary>

- ≤ 28 会被新系统弹"此应用是针对旧版 Android 打造的"，OPPO/小米上都见过；
- ≥ 30 强制分区存储，`Download/鱼眼/` 下的工作区文件就写不进去，得改成 SAF 或申请全盘权限；
- Android 12 的后台启动限制只管 targetSdk ≥ 31 的 App，而它要开机自启；
- sideload 没有 Play 的 targetSdk 门槛，所以 29 没有代价。

</details>

## 🖥 电脑插件 `dsh-see-on-phone`

```bash
npm i -g dsh-see-on-phone      # 或者从 Release 里的 .tgz 装
```

模型调用 `see_phone` → 插件 HTTP 打手机 `/look` → 手机自己采、自己挑、自己拼 → **一张 JPEG** 回来。
**电脑这边不抽帧、不装 ffmpeg、不拉原始流。**

| action | 干什么 | 等待 |
|---|---|---|
| `look`（默认） | 抓一张宫格 | 手机采集 1~2 秒（`wait` 可调 0.5~8） |
| `watch` | 看"这一会儿发生了什么" | 默认 8 秒 |
| `peek` | 最快的一张 | 1 秒 |
| `status` | 手机状态（相机/上下文/思考/朗读） | — |
| `stop` | 停语音（相机归手机自己关） | — |

`mode:"normal"`（默认，用户看得见画面）或 `mode:"guide"`（导盲：只给结论和动作）。
配置只有两项，在 `~/.dsh/see-phone.json`：`url` 和 `mode` —— 档位、节奏、前后摄像头**都在手机 App 里**，
两个地方各存一份配置最后一定会打架。

```bash
node scripts/doctor.mjs      # 手机通不通、语音播放有没有
node scripts/smoke.mjs 6     # 拍 6 秒存成 jpg，自己看
```

### 搬会话（手机 ⇄ 电脑）

手机上聊过的可以搬到电脑的 DSH 里接着聊，反过来也行。装完插件就自带这个命令：

```bash
dsh-see-on-phone sync               # 一条命令：从手机拉包 + 导进 DSH
dsh-see-on-phone status             # 先看看手机在不在
dsh-see-on-phone export latest      # 反向：把 DSH 里最近改过的那条推到手机
dsh-see-on-phone push               # （导出后再推）
dsh-see-on-phone doctor             # 体检：哪些会话 DSH 打不开、为什么
dsh-see-on-phone repair --write     # 修（默认只看不改；每个文件先备份 .bak-*）
```

| 你担心的 | 实际行为 |
|---|---|
| 会不会覆盖我原来的会话 | **只加不删**。手机侧按时间戳增量追加，电脑侧新建 / 补齐，从不 truncate |
| 图片呢 | **不跟着搬**：导出时换成一行「图已省略」占位（省时间省流量），手机上的原图还在 |
| 电脑上看得到图吗 | 看不到。这是刻意的 —— 那段对话在电脑上只有文字 |
| 搬完为什么列表没变 | 列表是 DSH 进程里的缓存，**完全退出再打开**才刷新 |

不插线也能搬：手机 App → 设置 → 数据 → 打开「局域网」，然后
`dsh-see-on-phone sync --host 192.168.1.5:4444`（IP 用手机上显示的那个）。

> 导进 **WorkBuddy 侧边栏**也行：`dsh-see-on-phone import --wb`（这条线要 Node 22.5+）。
> 插线时先用 `-s <序列号>` 指定设备（`adb devices` 第一列），插两台时必用。

## 🧭 出处与署名

拼图那套（切点检测 → 档位 → 行 DP 装箱 → 拼图 → token 换算）最初写在
[`dsh-see-now`](https://github.com/SAOS-3624/dsh-see-now) 和
[`dsh-video-storyboard`](https://github.com/SAOS-3624/dsh-video-storyboard) 里，
App 里这份是**同一套算法的 Java 逐行移植**（同作者，同许可），曾经逐字节对拍过。

切点那套的**思想来源**是 [PySceneDetect](https://github.com/Breakthrough/PySceneDetect)（BSD-3）的
`ContentDetector`：把 HSV 三通道的差异加权成一个变化分。它用 OpenCV 在原始分辨率上算，
我们在 64×36 网格上手写 —— 取的是结论，没有读它的源码。

### 在线音色（小轩那些）

App 里有 5 个 Edge 在线音色（小轩 / 晓晓 / 晓伊 / 云希 / 云扬），要自己算 `Sec-MS-GEC` 令牌。
那套算法是**照着 [edge-tts](https://github.com/rany2/edge-tts) 的 Python 实现翻译成 Java 的** ——
著作权上属于演绎作品，所以 `Drm.java` / `EdgeTts.java` 这两个文件是 **LGPL-3.0**，不是 Apache-2.0。

**LGPL 不是 GPL。** 它有「组合作品」例外，**不会**把同一个 APK 里的 Apache-2.0 代码拖下水；
除这两个文件外，仓库其余代码仍是 Apache-2.0。逐条见 [CREDITS §4.1](CREDITS.md)。

2.1.0 曾以「GPL 会污染整包」为由把这条链路整段删掉（852 行）——那个判断**不准确**，
2.3.0 加了回来，并把许可标对。它**默认不启用**：设置 → 音色 里选中才会联网合成，
失败会自动回落系统 TTS，不会让 App 哑掉。

当前代码树**只有这一处**第三方源码。逐条见 **[CREDITS.md](CREDITS.md)**。

## ⚖️ 许可

[Apache-2.0](LICENSE)。拿去商用、改名、闭源分发都可以，保留版权与许可声明即可。

许可全文跟着二进制走：npm 包里有 `LICENSE` / `NOTICE` / `CREDITS.md`；
APK 里打进 `res/raw/`，手机上「设置 → 关于 → 许可」能直接看。

**但它不是辅具。** 这个 App 会把画面变成文字描述，它**会看错、会延迟、会在暗处失灵**。
过马路、上下楼梯、避障 —— 用白杖、用导盲犬、用无障碍设施。详见 [NOTICE](NOTICE)。

---

## ☕ 打赏

**不影响上面任何一个字**：代码是 Apache-2.0，不收费、不需要授权、不搞"打赏才给好功能"。
觉得它替你省了时间，就扫下面这个码请我喝杯咖啡 —— 纯自愿，不给也照样能用全部功能。

<p align="center">
  <img src="docs/reward-qr.png" alt="赞赏码（微信 / 支付宝）" width="240">
</p>

