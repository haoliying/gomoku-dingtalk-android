# 五子棋 · 钉钉对战（Android）

一个纯原生的 Android 五子棋应用：支持自定义棋盘尺寸与连珠长度、三档 AI 对战、本地双人，以及通过**内置的钉钉机器人**在局域网内跨设备对战。棋盘信息、落子与聊天均以钉钉自定义机器人消息格式实时同步。

## 功能

- **自定义规则**：棋盘 9×9 — 19×19，获胜连珠 4 / 5 / 6 可选，设置自动保存。
- **人机对战**：简单 / 中等 / 困难三档 AI（启发式棋型评估 + Alpha-Beta 搜索，困难为四层搜索）。
- **本地双人**：同一台设备轮流落子。
- **钉钉机器人联机**：
  - 每台设备内置一个 HTTP 机器人服务（默认端口 `18765`），接口与钉钉自定义机器人一致：`POST /robot/send`。
  - 房主执黑并决定棋盘大小，加入方执白；进去后自动同步棋局、聊天、重开请求。
  - 所有消息同时携带完整落子序列，丢包或顺序错乱时可自动校对恢复。
  - 可选填写真实钉钉群机器人 Webhook，把每一步落子同步到群里围观（单向）。
- **棋盘体验**：自绘木纹棋盘、星位、坐标、棋子高光阴影、最后一手标记、获胜连线高亮、触感反馈。
- **完整棋盘逻辑**：胜负判定、悔棋、重开、平局、认输（联机消息层已支持）。

## 下载安装

从 [Releases](../../releases) 下载 `gomoku-dingtalk-v1.0.0.apk` 直接安装（minSdk 24 / Android 7.0+）。

> 仓库中的 APK 使用 Android debug 证书签名，方便直接安装体验；如需上架请替换为自己的正式签名。

## 联机玩法

1. 两台手机连接**同一个 Wi-Fi**。
2. 房主：进入「钉钉机器人联机」→ 填写昵称、房间号 → 点击「创建房间（执黑）」。
3. 房主把界面显示的地址（形如 `http://192.168.1.8:18765/robot/send`）发给对方；也可以只告诉对方 `192.168.1.8:18765`。
4. 对方：在同页填写昵称、相同房间号与房主地址 → 点击「加入房间（执白）」。
5. 连接成功后自动进入棋盘，双方落子、聊天实时同步。

若两台设备端口冲突或公司网络限制，可以在页面上修改「机器人端口」，双方各自填写可用端口即可（加入方填写的端口用于自己接收消息）。

### 内置机器人的协议

服务端接受钉钉机器人格式的 JSON，并附带 `gomoku` 字段承载棋局信息：

```json
{
  "msgtype": "text",
  "text": { "content": "【五子棋】小明 落子 H8，第 12 手" },
  "gomoku": {
    "v": 1,
    "type": "move",
    "room": "1234",
    "seq": 12,
    "name": "小明",
    "x": 7,
    "y": 7,
    "moves": "7,7;8,8;...",
    "size": 15,
    "win": 5
  }
}
```

- 消息类型：`join` / `accept` / `move` / `chat` / `restart_req` / `restart_ok` / `restart_no` / `surrender` / `leave` / `sync`。
- 端点：`POST /robot/send` 接收消息；`GET /` 或 `GET /ping` 可用于探活。
- 因为 `text.content` 是标准钉钉字段，同一份消息可直接转发到真实钉钉群机器人 Webhook。

## 构建

环境：JDK 17、Android SDK Platform 35、Build Tools 35。

```bash
# 已在 local.properties 配置 sdk.dir 时可省略 ANDROID_HOME
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # 规则 / AI / 机器人协议共 17 项单元测试
```

## 代码结构

```
app/src/main/java/com/gomoku/bot/
├── MainActivity.kt              # 首页：棋盘设置、模式入口
├── GameActivity.kt              # 对局：人机 / 本地 / 联机
├── RemoteSetupActivity.kt       # 机器人联机大厅：建房、加入、日志
├── Prefs.kt                     # 本地设置
├── game/
│   ├── GameEngine.kt            # 规则引擎：落子、胜负、悔棋、序列化
│   ├── GomokuAI.kt              # AI：棋型评分 + Alpha-Beta
│   └── BoardView.kt             # 自绘棋盘
└── net/
    ├── BotProtocol.kt           # 钉钉格式消息编解码
    ├── DingTalkBotServer.kt     # 内置机器人 HTTP 服务端
    ├── BotHttp.kt               # 消息发送（局域网 / 钉钉 Webhook）
    ├── RemoteSession.kt         # 会话状态：握手、落子、聊天、重开
    └── NetUtils.kt              # 局域网 IP
```

## 已知限制

- 联机依赖同一局域网内的直连 HTTP，暂不支持公网穿透；公网场景需要自建中继或使用钉钉群机器人 + 服务端回调。
- 钉钉群 Webhook 只做单向消息同步，不能作为对局通道。
- 联机模式暂不提供悔棋（保留重开与退出），本地模式支持悔棋。
