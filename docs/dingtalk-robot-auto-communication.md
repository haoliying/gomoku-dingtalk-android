# 钉钉机器人全自动通讯技术关键点

## 1. 背景与核心问题

### 目标
两个用户各自在手机上运行机器人服务，通过钉钉实现全自动下棋——落子后对方自动接收并落子，无需人工转述。

### 钉钉平台的硬限制
1. **机器人之间不能直接互发消息**：钉钉没有提供"机器人给机器人发消息"的 API。机器人发送消息的目标只能是**人类用户**（单聊）或**群**（群聊）。
2. **机器人原生回调只收 @ 消息**：机器人的 outgoing 回调（`/v1.0/im/bot/messages/get`）只在**人类用户 @ 机器人**时触发，机器人发的消息不会触发另一个机器人的回调。
3. **单聊仅限"人—机器人"**：机器人无法发起与另一个机器人的单聊会话。

## 2. 解决方案：群消息总线 + 事件订阅

### 核心思路
既然机器人不能直接对话，就让**群**充当消息总线，**事件订阅**充当耳朵，**机器人发群消息**充当嘴巴。

```
群（用户A, 用户B, RobotA, RobotB）

用户A落子 e4
  → RobotA 调用 groupMessages/send 发结构化消息到群
     {"gomoku":{"type":"move","from":"robotA","x":7,"y":7,"moves":"..."}}
  → 用户B的App通过事件订阅收到群里所有消息
  → 解析 gomoku 字段 → 自动落子 → 更新棋盘

用户B落子 e5
  → RobotB 发群消息 → 用户A的App自动接收并落子
```

### 为什么可行
- **机器人发群消息**：`/v1.0/robot/groupMessages/send` 是标准 API，机器人可以往自己所在的群发消息。
- **监听群所有消息**：事件订阅 `user_im_message_receive_group` 可以监听群里**所有人**（包括机器人）发的消息，**不需要 @**。
- **自环过滤**：消息体带 `from` 字段（本方 robotCode），接收方据此忽略自己发出的消息。

## 3. 关键技术点

### 3.1 Stream 模式（手机端无需公网 IP）

钉钉开放平台 Stream 模式通过 **WebSocket 出站长连接**与应用通讯，**不需要公网服务器、IP、域名**。手机上的机器人服务主动向钉钉发起 WebSocket 连接，就能接收回调和事件。

- 适用场景：无公网服务器的移动端 / 边缘设备
- 连接方式：应用主动连出（出站），手机 NAT 不影响
- 断线重连：SDK 内置指数退避重连

### 3.2 事件订阅监听群消息（全自动的关键）

事件 key：`user_im_message_receive_group`

| 特性 | 说明 |
|------|------|
| 触发条件 | 群里**任何人**发消息（人类 + 机器人） |
| 是否需要 @ | ❌ 不需要 |
| 身份 | **用户级**，需要用户授权应用读取 IM 消息 |
| 接收方式 | Stream 长连接（`type=EVENT`） |
| 自环过滤 | 当前**用户自己**发的消息不会推送（防死循环），但机器人发的消息会正常推送 |

> 这是和"机器人回调"的本质区别：机器人回调只收 @ 消息，事件订阅收群里全部消息。

### 3.3 机器人发群消息

API：`POST /v1.0/robot/groupMessages/send`

```json
{
  "msgKey": "sampleText",
  "msgParam": "{\"content\":\"{\\\"gomoku\\\":{...}}\"}",
  "openConversationId": "cidXXXXXX",
  "robotCode": "dingxxxxxx"
}
```

- 需要权限：**企业内机器人发送群消息权限**
- 消息显示为**机器人身份**（不是用户本人）
- `openConversationId`：目标群的会话 ID

### 3.4 自环过滤

事件订阅会推送群里所有消息，包括本方机器人自己发的。必须过滤：

```kotlin
val message = BotProtocol.decode(content) ?: return
// 过滤自己发出的消息
if (message.from == cfg.robotCode) return
```

- `from` 字段在发送时填入本方 `robotCode`
- 接收时比较 `from` 与本地 `robotCode`，相同则忽略

### 3.5 结构化协议（BotProtocol）

为了让双方 App 能自动解析走法，使用 JSON 结构化协议，而非纯文本：

```json
{
  "gomoku": {
    "type": "move",          // move / sync / chat / restart / surrender
    "room": "1234",           // 房间号，多局隔离
    "seq": 5,                 // 手数
    "from": "dingrobotA",     // 发送方 robotCode，用于自环过滤
    "name": "玩家A",          // 昵称
    "color": 1,               // 1=黑 2=白
    "x": 7, "y": 7,           // 落子坐标
    "size": 15, "win": 5,     // 棋盘规格
    "moves": "H8I9J10..."     // 棋谱快照
  }
}
```

### 3.6 Stream 订阅两类推送

握手时同时订阅事件订阅和机器人回调：

```json
{
  "topics": [
    {"topic": "*", "type": "EVENT"},
    {"topic": "/v1.0/im/bot/messages/get", "type": "CALLBACK"}
  ]
}
```

- `EVENT("*")`：接收开发者后台已勾选的全部事件（含群消息）
- `CALLBACK`：机器人原生回调（兼容单聊/群 @ 场景，对手无 App 时手动回复坐标）

## 4. 钉钉开放平台配置步骤

### 4.1 创建应用与机器人
1. 钉钉开放平台 → 创建**企业内部应用**
2. 应用内添加**机器人**，记录 `RobotCode`
3. 获取 `AppKey`（Client ID）和 `AppSecret`（Client Secret）

### 4.2 开通权限
- 企业内机器人发送单聊消息权限
- 企业内机器人发送群聊消息权限
- IM 消息读取权限（事件订阅所需）

### 4.3 配置事件订阅
1. 应用 → 「事件与回调」
2. 勾选 `user_im_message_receive_group`（监听指定群消息）
3. 保存（Stream 模式无需填写回调地址）

### 4.4 建群与加机器人
1. 在钉钉里建一个群，把两个用户和两个机器人都拉进去
2. 获取群的 `openConversationId`（可通过钉钉开放平台接口或机器人回调里的 `conversationId` 获取）

### 4.5 用户授权
首次使用时，用户需授权应用读取 IM 消息（事件订阅是用户级能力）。

## 5. 代码实现要点

### 5.1 发送走法（DingTalkSession.sendProtocol）

```kotlin
private fun sendProtocol(message: BotMessage) {
    val payload = BotProtocol.encode(message)  // 带 from=robotCode
    sender.execute {
        // 优先发到群（消息总线）
        if (cfg.openConversationId.isNotBlank()) {
            DingTalkApi.sendGroup(cfg, payload)
        }
        // 回退单聊（兼容传统方式）
        else if (cfg.peerUserId.isNotBlank()) {
            DingTalkApi.sendOto(cfg, payload)
        }
    }
}
```

### 5.2 接收群消息（DingTalkSession.onGroupEvent）

```kotlin
private fun onGroupEvent(json: JSONObject) {
    val content = json.optJSONObject("text")?.optString("content").orEmpty()
    if (content.isBlank()) return

    // 1. 解析结构化协议
    val message = BotProtocol.decode(content) ?: return
    // 2. 自环过滤
    if (message.from == cfg.robotCode) return
    // 3. 房间校验
    if (room.isNotBlank() && message.room != room) return
    // 4. 触发落子
    emit(Event.Message(message))
}
```

### 5.3 Stream 事件分发（DingTalkStreamClient）

```kotlin
when (type) {
    "EVENT" -> onEventMessage(data)       // 群消息事件订阅
    "CALLBACK" -> onBotMessage(data)      // 机器人原生回调
}
```

## 6. 能力边界

| 能力 | 状态 | 说明 |
|------|------|------|
| 两台手机全自动互传走法 | ✅ | 群消息总线 + 事件订阅 |
| 机器人直接给机器人发消息 | ❌ | 钉钉不支持 |
| 监听群所有消息（无需 @） | ✅ | 事件订阅 `user_im_message_receive_group` |
| 消息显示为用户本人 | ⚠️ | 走法消息显示为机器人身份 |
| 手机端无需公网 IP | ✅ | Stream 模式 WebSocket 出站 |
| 多局隔离 | ✅ | room 房间号字段 |

## 7. 替代方案对比

| 方案 | 全自动 | 各自有机器人 | 无中心服务器 | 复杂度 |
|------|--------|-------------|-------------|--------|
| 单机器人+群（需 @） | ❌ | ❌ | ✅ | 低 |
| 交叉单聊+人工发送 | ⚠️ 半自动 | ✅ | ✅ | 中 |
| 共享后端中转 | ✅ | ✅ | ❌ | 高 |
| **群消息总线+事件订阅（本方案）** | ✅ | ✅ | ✅ | 中 |

## 8. 参考资料

- [钉钉 Stream 模式接入](https://open.dingtalk.com/document/resourcedownload/introduction-to-stream-mode)
- [钉钉事件订阅](https://open.dingtalk.com/document/development/dingtalk-cli-event-subscription)
- [机器人发送群消息](https://open.dingtalk.com/document/development/the-robot-sends-a-group-message)
- [机器人接收消息](https://open.dingtalk.com/document/development/receive-message)
