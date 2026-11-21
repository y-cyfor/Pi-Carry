# 运行与运维

## 1. 进程模型

- `pi-carry-desktop.exe`：Tauri 托盘壳，进程内启动一个 `Bridge`。
- `pi-carry serve`：无 UI 的桥接进程，适合服务器或调试。
- 每个打开的手机会话对应一个 `pi --mode rpc --session <jsonl>` 子进程；会话关闭、桥退出或 pi 崩溃时清理。
- Android 的 `CarryService` 是 `connectedDevice` 前台服务，负责保持连接和审批通知。

同一时间只允许一个桥实例持有 `~/.pi-carry` 下的状态数据库和 iroh 端点。桌面端使用 Windows 命名互斥量保证单实例；CLI 未加互斥，调试时不要同时运行两个 `serve`，也不要在桌面端运行时再启动 `serve`。

## 2. 文件与目录

| 路径 | 内容 |
| --- | --- |
| `~/.pi-carry/config.toml` | 节点、传输、审批、会话、服务配置 |
| `~/.pi-carry/secrets.json` | iroh 端点私钥、可选桥密码 Argon2id 哈希（0600；当前没有设置密码的界面/命令） |
| `~/.pi-carry/state.db` | SQLite：设备、会话索引、journal、审批、审计、幂等表 |
| `~/.pi-carry/logs/` | `pi-carry.log` / `desktop.log` |
| `~/.pi-carry/extensions/guard.ts` | 工具调用拦截扩展，启动时自动写入 |
| `~/.pi-carry/run/` | 预留运行态文件 |

`PI_CARRY_HOME` 可覆盖整个数据目录，`PI_CARRY_SESSIONS` 可覆盖会话扫描目录；测试用它们做隔离。

## 3. 配对

1. 桌面“配对手机”页点击“生成二维码”；或 CLI `pi-carry serve` 从日志读取 `pi-carry://pair?...`。
2. 手机扫码/粘贴链接/输入 Endpoint ID + 6 位码。
3. 首次设备会写入 `devices` 表；新设备的默认 capability 由 `sessions.defaultCapability` 决定（默认 `full`）。该设置只影响之后新配对的设备，当前版本不能单独修改已配对设备的能力，只能吊销后重新配对。
4. 桌面可随时吊销设备；吊销会写入审计并广播 `device.revoked`，该设备的连接被服务端拒绝。手机端没有设备管理入口。

二维码包含：

```
pi-carry://pair?v=1&endpoint=<iroh endpoint id>&token=<one-time token>
  &relay=n0|https://...&addr=<ip:port>&addr=...
```

token 10 分钟有效且只能使用一次；6 位码同样 10 分钟、5 次错误后锁定 10 分钟。

扫码时 token 会自动完成配对，因此无需再输 6 位码；6 位码是摄像头不可用、只能手动输入 Endpoint ID 时的备用凭据。配对没有“在电脑上二次确认”的步骤。手机首次连接会生成并持久化一个 iroh 客户端密钥，后续重连复用同一 EndpointId，服务端据此识别为同一台设备；若在系统里清除应用数据，或升级前使用旧版本，会生成新身份，需要在桌面吊销旧设备。

## 4. 网络

- `relayMode = "n0"`：使用 n0 公共 relay + DNS/pkarr，跨网络可用。
- `relayMode = "disabled"`：只直连，适合同一局域网或自建 VPN。
- `relayMode = "custom"` + `relayUrls`：自托管 iroh relay（需可用的 HTTPS relay URL）。
- `bindAddr` 默认 `0.0.0.0:0`；二维码里会列出本机所有非回环 IPv4/IPv6 地址和实际端口。
- Windows 防火墙需要允许该进程的 UDP 入站；否则手机会退回 relay。
- 当前版本未实现 mDNS 发现；配对二维码已包含局域网直连地址，无需额外组播权限。

## 5. pi 接入

桥按以下顺序解析 pi：

1. `node.piPath`（配置显式路径）。
2. `PI_CARRY_PI` 环境变量。
3. PATH 中的 `pi` / `pi.cmd`。

Windows 上如果命中的是 `pi.cmd`，桥优先寻找同目录的 `pi-launcher.js`，直接调用 `%LOCALAPPDATA%\pi-node\current\node.exe`，绕过 `cmd.exe` 对管道 stdin 的处理。找不到 launcher 时才回退到 `cmd /C`。

启动参数：

```
--mode rpc --session <session.jsonl> --approve --no-extensions -e <guard.ts>
```

`--approve` 只用于避免 pi 的项目信任提示；真正的工具审批由 `guard.ts` + 桥完成。

## 6. 审批

`guard.ts` 对每个 `tool_call` 调用 `ctx.ui.confirm("pi-carry 审批", "PI_CARRY_APPROVAL:"+JSON)`。桥解析该前缀，分类工具与风险，向所有在线手机广播 `approval.requested`。

- 先到先得；任一设备批准/拒绝后写 `resolved`。
- 120 秒超时默认拒绝。`approval.extend` 只在协议层存在，Android 当前没有延长按钮。
- “本对话都允许”只放行该会话后续非 critical 工具，规则在内存中，桥或 App 重启后失效。
- “记住此命令”只对完全相同的 `bash/shell/exec` 命令生效。当前实现没有按风险等级排除 critical，已知危险命令被记住后也会直接放行；修复前不要对危险命令使用该选项。
- 只读 bash 当前仍归类为 medium，默认也会询问；这是“审批过严”的主要原因。
- 桌面端没有审批入口，避免“在受控端自批”。

## 7. 故障排查

| 症状 | 检查 |
| --- | --- |
| `pi 未安装` / `PI_NOT_INSTALLED` | `pi --version`、`pi-carry doctor`、`node.piPath` |
| `pi 已退出：stdout EOF` | 是否通过 cmd shim 启动；确认 `pi-launcher.js` 存在；看 `~/.pi-carry/logs` |
| 手机连不上局域网 | 防火墙 UDP、二维码里的 addr 是否是手机可达网段、`relayMode` |
| 4G 连不上 | `relay=n0` 或自托管 relay；检查 relay URL/证书 |
| 审批一直等待 | 手机前台服务是否存活、通知权限、桥是否收到 `approval.requested` |
| 会话提示忙 | 同一会话已有其他手机/设备正在运行；当前版本不检测本机终端里的 pi 外部写者，也不会阻止两端同时写同一会话 |
| 手机重开后工具记录消失 | 确认使用 0.1.3+；旧版本只恢复文本和思考 |
| 桌面窗口超出屏幕 | 确认使用最新构建；窗口会按显示器工作区自适应 |

日志默认在 `~/.pi-carry/logs/`，桌面“诊断日志”页可直接查看最后 200 行。

## 8. 重置

```bash
# 保留 pi 会话，只重置 pi carry 的配对与索引
rm -rf ~/.pi-carry
```

删除 `~/.pi-carry` 会同时删除已配对设备、journal 和端点密钥；手机需要重新配对。不要删除 `~/.pi/agent/sessions`，那是 pi 的会话事实源。
