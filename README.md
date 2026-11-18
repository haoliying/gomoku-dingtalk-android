# 五子棋 · 钉钉对战（Android）

一个纯原生 Android 五子棋应用：支持自定义棋盘尺寸与连珠长度、三档 AI 对战、本地双人，以及通过**真实钉钉企业应用机器人（Stream 模式）**与好友对战。

支持两种钉钉联机方式：
- **双 App 全自动**：双方都装本应用，各自的机器人加入同一个群，落子后机器人把结构化走法发到群，对方 App 通过事件订阅自动接收并落子，无需人工转述。
- **单 App + 钉钉回复**：一方用 App 当棋盘，对方**不需要安装本应用**，直接在钉钉里回复坐标（如 `H8`）即可落子。

## 功能

- **自定义规则**：棋盘 9×9 — 19×19，获胜连珠 4 / 5 / 6 可选，设置自动保存。
- **人机对战**：简单 / 中等 / 困难三档 AI（启发式棋型评估 + Alpha-Beta 搜索，困难为四层搜索）。
- **本地双人**：同一台设备轮流落子。
- **钉钉机器人联机（真实钉钉）**：
  - 使用企业内部应用的 `AppKey / AppSecret / RobotCode`，通过 Stream 模式（WebSocket 长连接）接收机器人消息，无需公网回调地址。
  - 落子后机器人自动把「棋谱 + 当前棋盘 + 轮到谁」以文本消息发给对方钉钉。
  - 对方回复坐标、`重开`、`认输`、`同步` 等指令即可操作对局。
  - 可选填写群会话 `openConversationId`，把棋局同步播报到钉钉群留存/围观。
  - 长连接断开自动重连（3s 起步，最长 18s）。
- **棋盘体验**：自绘木纹棋盘、星位、坐标、棋子高光阴影、最后一手标记、获胜连线高亮。
- **完整棋盘逻辑**：胜负判定、悔棋、重开、平局、认输。

## 下载安装

从 [Releases](../../releases) 下载 `gomoku-dingtalk-v1.1.0.apk` 直接安装（minSdk 24 / Android 7.0+）。

> APK 使用 Android debug 证书签名，方便直接安装体验；如需上架请替换为自己的正式签名。

## 钉钉准备

1. 打开 [钉钉开放平台](https://open-dev.dingtalk.com/) → 创建**企业内部应用**。
2. 在应用内添加**机器人**，记下 `RobotCode`。
3. 在「凭证与基础信息」中获取 `AppKey`（Client ID）与 `AppSecret`（Client Secret）。
4. 在「权限管理」中开通机器人发送单聊消息、群聊消息相关权限，并按需设置应用可见范围（对方必须在该范围内）。
5. 发布应用版本，使配置生效。
6. 在钉钉里查看到对方的 `userId`（企业内 staffId，可在通讯录管理后台查看），填入 App。

> 不需要配置 HTTP 回调地址：本应用使用 Stream 模式主动建立长连接接收消息。

### 鉴权失败排查

配置页点「测试机器人连接」，会分别测试 **获取 accessToken** 与 **Stream 握手**，任一步失败会显示 `HTTP 状态码 · code · message · requestId`。常见原因：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `获取 accessToken` 失败，`code=invalidClientIdOrSecret` | AppKey 或 AppSecret 填错、多空格、或应用被停用 | 核对开放平台「凭证与基础信息」中的 AppKey/AppSecret，注意前后不要带空格 |
| `Stream 握手` 失败，`code=authFailed` / message「鉴权失败」 | 应用未开通 Stream 模式、未发布、或类型不是企业内部应用 | 在应用后台「开发管理 → 高级配置」中开启 Stream 模式；发布应用版本；确认是企业内部应用（不是第三方/H5） |
| `Stream 握手` 成功但收不到消息 | 机器人能力未启用，或订阅了错误的 topic | 在应用「机器人」页确认已添加机器人；本应用订阅 `/v1.0/im/bot/messages/get` |
| 能发消息但对方收不到 | 应用可见范围不包含对方，或机器人单聊权限未开通 | 开放平台「应用发布 → 可见范围」加入对方；权限管理开通机器人单聊消息相关权限 |

`requestId` 可在钉钉开放平台「开发支持 → 错误排查」中查询更详细的日志。

## 联机玩法

1. 首页选择棋盘尺寸、连珠长度 → 进入「钉钉机器人联机」。
2. 填写 `AppKey`、`AppSecret`、`RobotCode`、房间号、对方钉钉 `userId`，选择自己执黑或执白。
3. 点击「连接机器人并开始对局」，进入棋盘。
4. 你落子后，机器人会把棋局发到对方钉钉；对方直接回复坐标（如 `H8`）即可落子。
5. 对方回复 `重开` / `认输` / `同步` 可发起重开、认输、重发棋盘。
6. 若执白，进入对局时会自动把开局棋盘发给对方，等待对方先手。

### 消息格式

发给对方的是便于在钉钉里阅读的文本，例如：

```
【五子棋 · 房间 1234】第 5 手：黑 H8
15×15 · 5 连获胜 · 棋友
轮到你落子：直接回复坐标（如 H8）即可。

   A B C D E F G H I J K L M N O
15 · · · · · · · · · · · · · · ·
…
 1 · · · · ● · · · · · · · · · ·
```

接收侧支持：

- 坐标：`H8`、`h8`、`H,8`、`#1234 H8`；
- 指令：`重开` / `重来` / `再来`、`认输`、`同步` / `棋盘`；
- 兼容携带 `gomoku` 字段的 JSON 消息（`BotProtocol` 编解码）。

## 全自动联机：群消息总线 + 事件订阅

钉钉平台的硬限制（已按官方文档核实）：

- 机器人之间**不能**直接互发消息；机器人原生回调只收**用户 @ 它**的消息（群内）。
- 同一个应用建立多条 Stream 连接时，钉钉**随机选择一条**推送，不是广播。

但「两台手机全自动互传棋步」是可以实现的，关键是用**事件订阅**而非机器人回调来监听群消息：

- 两个用户各自创建自己的企业应用机器人，都加入同一个钉钉群。
- 落子时，本方机器人用 `groupMessages/send` 把结构化走法（带 `gomoku` 字段的 JSON）发到群里。
- 对方 App 通过 **Stream 事件订阅**（`user_im_message_receive_group`）监听群里**所有**消息（无需 @），自动解析 `gomoku` 字段并落子。
- 消息里带 `from`（本方 robotCode），接收方据此过滤自己发出的消息，避免自环。

### 全自动架构

```
群（用户A, 用户B, RobotA, RobotB）

用户A在App落子
  → RobotA 发群消息：{"gomoku":{"type":"move","from":"robotA","x":7,"y":7,...}}
  → 用户B的App事件订阅收到群消息 → 解析 → 自动落子 → 轮到A

用户B在App落子
  → RobotB 发群消息 → 用户A的App自动落子
```

Stream 订阅两类推送：`EVENT("*")`（开发者后台已勾选的全部事件，含群消息）+ `CALLBACK(/v1.0/im/bot/messages/get)`（机器人回调，兼容单聊/群 @ 场景）。

### 配置要求

1. 在钉钉开放平台应用的「事件与回调」中勾选 `user_im_message_receive_group`（监听指定群消息）。
2. 首次使用需用户授权应用读取 IM 消息（事件订阅是用户级能力）。
3. 两个机器人都加入同一个群，App 中填写该群的 `openConversationId`。

> 同时保留传统方式：对手无 App 时，可在钉钉单聊/群里直接回复坐标（`H8`）、`重开`、`认输` 等指令，机器人回调会自动处理。

## 构建

环境：JDK 17、Android SDK Platform 35、Build Tools 35。

```bash
./gradlew assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # 规则 / AI / 钉钉消息共 20 项单元测试
```

## 代码结构

```
app/src/main/java/com/gomoku/bot/
├── MainActivity.kt              # 首页：棋盘设置、模式入口
├── GameActivity.kt              # 对局：人机 / 本地 / 钉钉联机
├── DingTalkSetupActivity.kt     # 钉钉机器人配置与连接
├── Prefs.kt                     # 本地设置
├── game/
│   ├── GameEngine.kt            # 规则引擎：落子、胜负、悔棋、序列化
│   ├── GomokuAI.kt              # AI：棋型评分 + Alpha-Beta
│   └── BoardView.kt             # 自绘棋盘
└── net/
    ├── DingTalkApi.kt           # token、机器人单聊/群聊发消息、Stream 握手
    ├── DingTalkStreamClient.kt  # Stream WebSocket：ping/pong、ACK、重连
    ├── DingTalkSession.kt       # 会话：收发棋局、指令解析
    ├── DingTalkText.kt          # 棋局文本渲染与指令解析
    └── BotProtocol.kt           # 兼容 gomoku JSON 消息编解码
```

## 已知限制

- 需要对端在应用可见范围内，且已开通机器人单聊消息权限，否则发送会返回钉钉错误码。
- 群聊 `openConversationId` 仅用于播报/留存；群内消息只有 @机器人 时才会推送给应用。
- 由于钉钉限制，暂不支持两个 App 自动互相收发（详见上文）。
- 联机模式暂不提供悔棋（保留重开与退出），本地模式支持悔棋。
