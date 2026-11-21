# carry.v1 协议摘要

完整设计见 [`plan/02-protocol.md`](../plan/02-protocol.md)。实现以 `crates/protocol` 为准。

## 传输

- iroh QUIC，ALPN `pi-carry/1`。
- 每个连接一条客户端发起的双向控制流。
- 帧格式：4 字节大端无符号长度 + UTF-8 JSON。
- 控制帧上限 1 MiB，事件帧上限 4 MiB；没有独立的 blob 流，图片以 base64 内联在 `prompt.send`。
- **响应与事件复用同一条控制流**：服务端按顺序写入，客户端按 `type` 分发；响应按 `id` 关联。不存在单独的单向事件流。

## 帧

### 请求

```json
{"type":"request","id":"r1","op":"node.status","payload":null}
```

### 响应

```json
{"type":"response","id":"r1","ok":true,"payload":{"node":{}}}
```

失败：

```json
{"type":"response","id":"r1","ok":false,"error":{"code":"PERMISSION_DENIED","message":"设备权限不足"}}
```

### 事件

```json
{"type":"event","sessionId":"01J...","seq":42,"event":{"type":"message.delta","kind":"text","delta":"你"}}
```

- `seq` 在会话内从 1 单调递增；服务端先写 journal 再发送。
- 客户端读完必须回 `{"type":"ack","sessionId":"01J...","seq":42}`，服务端用它裁剪重放窗口。
- 断线重连后客户端用 `session.attach {lastSeq}` 补洞；缺口过大时服务端返回快照要求。

### 心跳

```json
{"type":"ping","nonce":1732000000000}
{"type":"pong","nonce":1732000000000,"serverTime":"2026-11-19T12:00:00.000Z"}
```

## 握手

首帧必须是 `hello`：

```json
{
  "type": "request",
  "id": "hello",
  "op": "hello",
  "payload": {
    "protocol": "carry.v1",
    "client": {
      "name": "Pixel",
      "platform": "android",
      "deviceClass": "phone",
      "appVersion": "0.1.0",
      "locale": "zh-CN"
    },
    "token": "<配对 token 或 null>",
    "code": "<6 位码或 null>",
    "password": null,
    "resumeSessionId": null
  }
}
```

成功响应：

```json
{
  "type": "response",
  "id": "hello",
  "ok": true,
  "payload": {
    "protocol": "carry.v1",
    "node": {"id":"...","name":"Studio PC","platform":"windows","piInstalled":true},
    "device": {"id":"...","capability":"full","name":"Pixel"},
    "serverTime": "2026-11-19T12:00:00.000Z"
  }
}
```

旧设备凭 iroh Endpoint 公钥识别，不需要再次 token；token/code 只用于首次配对。

## 能力等级

| 能力 | 允许 |
| --- | --- |
| `read` | 查看节点、会话、历史 |
| `control` | 发送 prompt、steer/abort、审批 |
| `full` | 发送 prompt、审批、切换模型/思考等级、查看命令 |
| `owner` | 吊销设备（当前只有桌面本地管理路径使用；手机端没有设备管理入口） |

服务端对每个请求重新读取设备记录；已吊销设备立即被拒绝。

## 操作

| op | 说明 |
| --- | --- |
| `hello` | 认证/配对 |
| `node.status` | 节点与端点状态 |
| `session.list` | 会话索引 |
| `session.create` | 新建会话（cwd 可空） |
| `session.open` / `session.attach` | 打开/附着；`open` 返回会话摘要，`attach` 可按 `lastSeq` 重放事件 |
| `session.snapshot` | 显式拉快照 |
| `session.close` | 关闭并释放子进程 |
| `session.fork` / `session.compact` / `session.rename` | 会话操作（协议已支持；Android 只有 compact 入口） |
| `prompt.send` | `{sessionId,message,mode,clientId,images}`，mode = `auto|steer|queue|followUp` |
| `prompt.abort` / `queue.clear` | 打断/清队列 |
| `approval.list` / `approval.resolve` / `approval.extend` | 审批 |
| `ui.resolve` | 回复 pi 扩展 UI 请求 |
| `model.set` / `thinking.set` | 模型与思考等级 |
| `models.list` / `thinking.levels` / `commands.list` | 动态目录 |
| `device.list` / `device.revoke` | 设备列表/吊销；吊销需要 owner，手机端没有入口 |
| `node.settings` | 节点设置（只读） |

`prompt.send` 的 `clientId` 是幂等键：桥成功把 prompt 交给 pi 后才记录；客户端重试不会重复执行。单条消息最多 5 张图片，当前 Android 选择器一次只能选 1 张。

`session.attach` 的 `lastSeq` 重放已在服务端和 e2e 测试中实现；Android 当前重连使用 `session.snapshot` 补洞，没有使用 `lastSeq`。

## 事件

| 事件 | 说明 |
| --- | --- |
| `message.delta` | `kind=text|thinking`、`delta`、`contentIndex` |
| `message.final` | 完整消息 |
| `tool.started/updated/ended` | 工具生命周期 |
| `approval.requested/updated/resolved` | 审批 |
| `run.state` | `state` 是字符串：`idle|streaming|waiting_approval|waiting_input|retrying|error` |
| `queue.updated` | steering/followUp 队列 |
| `session.updated` / `session.compacted` | 会话状态；`session.conflict` 是保留事件，当前服务端不会发出 |
| `ui.requested` / `ui.resolved` | 扩展 UI 对话框 |
| `notification` | 通知类事件 |
| `device.revoked` | 设备被吊销 |
| `error` | 带 `code`/`message` |

## 错误码

`AUTH_REQUIRED`、`AUTH_FAILED`、`PAIRING_INVALID`、`PAIRING_EXPIRED`、`PAIRING_LOCKED`、`PAIRING_DENIED`、`PERMISSION_DENIED`、`SESSION_NOT_FOUND`、`SESSION_BUSY`、`SESSION_CONFLICT`、`APPROVAL_NOT_FOUND`、`APPROVAL_EXPIRED`、`APPROVAL_ALREADY_RESOLVED`、`NO_APPROVER`、`SLOW_CONSUMER`、`PROTOCOL_VERSION_UNSUPPORTED`、`PAYLOAD_TOO_LARGE`、`RATE_LIMITED`、`UNSUPPORTED`、`INVALID_ARGUMENT`、`PI_NOT_INSTALLED`、`PI_SPAWN_FAILED`、`PI_EXITED`、`INTERNAL`。

## 兼容性

- 协议版本变化时提升 `PROTOCOL_VERSION` 并拒绝不兼容客户端（`PROTOCOL_VERSION_UNSUPPORTED`）。
- 帧解析忽略未知字段；新增事件类型客户端应忽略而不是断开。
- 所有时间使用 RFC3339 UTC 毫秒；审批超时只使用服务端时间。
