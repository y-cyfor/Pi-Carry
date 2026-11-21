# 实现状态与文档偏差

> 更新时间：2026-11-22  
> 对应版本：`0.1.3`  
> 本文记录“文档/设计稿里存在，但当前代码没有实现或只实现了一部分”的内容。除特别说明外，这些项目**暂不实施**。

## 1. 当前实际形态

- **控制端**：Android 单模块应用（`:app`），Kotlin + Jetpack Compose + SharedPreferences，没有 Room、Hilt、DataStore 或 WorkManager。
- **受控端**：Rust `pi-carry-core` + Tauri 2 托盘壳；桌面 UI 是静态 `desktop-ui/index.html`，无 Node 构建链。
- **桥接方式**：pi 扩展 `extensions/guard.ts` 通过 `ctx.ui.confirm` 发起审批，不使用本地 HTTP/Unix socket 审批服务。
- **传输**：iroh 1.3，ALPN `pi-carry/1`；客户端发起一条双向控制流，**响应和事件复用同一条流**，没有独立单向事件流。
- **会话**：pi 的 `.jsonl` 是唯一事实源；bridge 打开同一文件，不复制会话。Android 重连依靠 `session.snapshot` 补洞，`session.attach {lastSeq}` 的重放能力已在服务端和 e2e 中实现，但 Android 没有使用。
- **图片**：Android 客户端压缩后以 base64 内联在 `prompt.send` 中发送；没有 blob 上传/下载协议。
- **设备身份**：Android 持久化 iroh 客户端密钥，重连复用同一 EndpointId；默认能力为 `full`。
- **桌面平台**：正式构建和验证仅覆盖 Windows 10/11 x64；Linux/macOS 代码路径存在，但没有发布产物，也没有完成运行时验证。

## 2. 已实现

| 领域 | 当前能力 |
| --- | --- |
| 节点 | 多节点保存、扫码/粘贴链接/手动 Endpoint + 6 位码配对、在线状态、移除节点 |
| 会话 | 扫描、搜索、状态筛选、新建、打开、快照恢复、关闭、压缩、重命名（协议层） |
| 对话 | 流式文本/思考、工具卡、Markdown、用户/助手/工具图片、插话、排队、打断、命令面板 |
| 审批 | 逐次允许/拒绝、本对话都允许（critical 除外）、记住完全相同命令、120 秒超时、多设备先到先得 |
| 模型 | 动态模型目录、思考等级目录、切换模型/思考等级 |
| 设备 | 设备列表、在线状态、吊销（owner/桌面）、最近活跃 |
| 桌面 | 托盘、配对、设备、会话索引、设置、日志、单实例（Windows）、开机自启（Windows）、关闭到托盘、三档主题 |
| CLI | `serve`、`pair`、`status`、`devices`、`revoke`、`doctor`、`smoke`、`serve --json` |
| 安全 | 二维码一次性 token、6 位码 5 次锁定、路径白名单、路径组件校验、幂等键、审计表、journal 保留策略 |

## 3. 部分实现

| 项目 | 现状 | 缺口 |
| --- | --- | --- |
| 审批延长 | 服务端支持 `approval.extend` | Android 没有延长按钮 |
| 会话 fork | 服务端支持 `session.fork` | Android 命令面板只填入 `/fork `，没有 entry 选择与调用 |
| 会话重命名 | 服务端支持 `session.rename` | Android 没有重命名入口 |
| 会话条目/树 | 服务端支持 `session.entries` | Android 没有树浏览器 |
| 扩展 UI | 服务端支持 `ui.requested`/`ui.resolve` | Android 只处理审批型 confirm，没有通用 select/input/editor 界面 |
| 设备能力 | 服务端按 read/control/full/owner 校验 | 桌面只能设“新设备默认权限”，不能改已配对设备；Android 没有只读模式 UI |
| 局域网发现 | 配置里有 `enableMdns` | 没有 mDNS 实现；桌面开关已移除，二维码携带直连地址 |
| 会话冲突 | 有 `session.conflict` 事件类型和只读横幅 | 服务端没有发出该事件，`writable` 始终为 `true`，外部 pi 写同一会话不会被识别 |
| 图片数量 | 协议上限 5 张 | Android 选择器一次只能选 1 张 |
| 审计 | SQLite `audit` 表有记录 | 没有 CLI/桌面查看入口，也没有哈希链 |
| 运行统计 | 能拿到 pi 的会话状态 | 没有实时 token/context/cost 展示 |
| Android 保活 | 前台服务、开机广播存在 | 开机广播只启动空服务，不会自动恢复节点连接 |

## 4. 文档中存在但未实现

### 4.1 功能

- **离线 outbox / 本地发送队列**：断网时发送会失败，没有消息排队、重试和“待发送”状态。
- **多图一次发送**：只能一张一张选。
- **blob 图片传输**：`image.upload`、`blob.put`、`blob.get`、内容寻址 blob 存储均未实现。
- **外部写者检测与只读接管**：没有检测本机终端里的 pi 是否正在写同一会话，也没有“接管”流程。
- **通用扩展 UI**：select、confirm、input、editor 只实现了审批型 confirm。
- **审批延长按钮**：协议有，客户端没有。
- **会话 fork/rename/entries 的客户端入口**：协议有，UI 没有。
- **设备能力调整**：不能对已配对设备单独改 read/control/full；只能设新设备默认值，或吊销后重配。
- **桌面端设备重命名**：设备列表只能吊销。
- **工作区白名单管理**：`sessions.allowedRoots` 只影响新建会话的 cwd 校验；没有“工作区列表”或手机可见目录选择器。
- **本地密码兜底**：`Secrets.passwordHash` 和 hello 的 `password` 字段存在，但没有设置密码的 CLI/桌面入口，Android 也不发送密码。
- **本机配对确认**：扫码 token 直接完成配对，没有“在电脑上确认 6 位码”的二次确认步骤。
- **mDNS 局域网发现**：只有配置字段，没有实现。
- **桌面诊断包导出**：没有 `--export-diagnostics`。
- **数据库损坏备份/只读启动**：没有 integrity check 和 `.corrupt-*` 备份流程。
- **审计查看/哈希链**：没有。

### 4.2 平台与交付

- **Linux/macOS 正式支持**：README 只应把它们标为“源码可尝试构建、未验证”，当前没有安装包、托盘验证、开机自启实现或单实例实现。
- **Tauri 安装包**：只有 Windows 便携 zip；没有 MSI/NSIS、DMG、AppImage/deb。
- **自动更新/代码签名/公证**：没有。
- **Android 真机运行时验证**：没有可用真机或模拟器；触摸、扫码、前台服务、真实网络切换未在设备上验收。
- **Android instrumented tests**：没有 `androidTest` 套件。
- **CLI 服务安装**：没有 systemd/launchd/Windows 计划任务安装命令。
- **`pi-carry audit` / `logs` / `service` 子命令**：文档设计里有，CLI 没有。

### 4.3 架构文档偏差

- `plan/01-architecture.md` 里的 Room、DataStore、Outbox、Guard HTTP、blob store、admin IPC 均未按原设计实现。
- `plan/02-protocol.md` 里的独立事件流、blob 流、桌面 full 上限 3 台、手机默认 control 已不适用。
- `plan/04-android.md` 里的多模块、Hilt、Room、Coil、图片 blob 管线未采用。
- `plan/05-desktop.md` 里的设备能力调整、诊断包、`tauri-plugin-autostart`、托盘 `visible=false` 等未完全实现。
- `plan/06-security-ops.md` 里的“只读命令 low 自动允许”“高风险永远逐次”“审计哈希链”“本地 HTTP guard”与实际不一致。

## 5. 审批的实际规则

1. 所有 `tool_call` 都由 `extensions/guard.ts` 拦截，并调用 `ctx.ui.confirm`。
2. bridge 把 confirm 转成审批卡；`--approve` 只跳过 pi 的项目信任提示。
3. 风险分类：read 工具 low；只读 bash 仍是 medium；写操作/未知命令 high；危险片段 critical。
4. 默认 `auto_allow_low_risk=false`，所以只读 bash 也会询问。
5. “本对话都允许”只放行该会话后续非 critical 工具；规则只在内存，重启桥/App 后失效。
6. “记住此命令”只对 `bash/shell/exec` 的完全相同命令生效；当前实现**没有按风险等级排除 critical**，已记住的 critical 命令会被直接放行。这是已知偏差，修复前不要把“记住此命令”用于危险命令。
7. 审批超时默认拒绝；桌面端没有审批入口。
