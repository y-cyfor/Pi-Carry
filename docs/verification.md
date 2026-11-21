# 验证记录

> 更新时间：2026-11-22  
> 对应版本：`0.1.3`  
> 环境：Windows 11、Rust 1.100.0 GNU、JDK 21、Android SDK 36、Gradle 8.11.1、pi 1.5.2。

## 1. Rust 测试

```bash
source scripts/env.sh
cargo fmt --all -- --check
cargo test -p pi-carry-protocol -p pi-carry-core
```

| 测试目标 | 数量 | 结果 |
| --- | ---: | --- |
| protocol 单测 | 17 | 通过 |
| core 单测 | 43 | 通过 |
| `server_handshake` | 3 | 通过 |
| `flow`（创建/流式/审批/本对话允许） | 4 | 通过 |
| `e2e_network`（真实 iroh 回环） | 1 | 通过 |
| **合计** | **68** | **通过** |

覆盖点：帧编解码与截断、JSONL 分隔符、未知错误码保留、路径组件与工作区白名单、配置校验、审批状态机（逐次/本对话/记住命令/超时/CAS 竞争）、配对 token/6 位码/锁定、pi RPC 启动（含 Windows `.cmd` 参数转义）、真实 iroh 握手、权限拒绝、设备重连去重与离线标记。

`cargo clippy` 对自有 crate 无 error；vendor 中的 iroh 仍有上游弃用警告。

## 2. CLI 与真实 pi

```bash
./target/debug/pi-carry smoke
./target/debug/pi-carry doctor
./target/debug/pi-carry serve --json
```

- `smoke`：fake-pi + 临时目录 + relay disabled，启动 bridge → 配对 → 创建会话 → prompt → 流式文本 → `message.final` → 退出码 0。
- `doctor`：数据目录可写、pi 解析为 `C:\Users\cyfor\.pi\agent\bin\pi.cmd`、会话目录可读。
- `serve --json`：首行输出 `{"endpointId","nodeName","relayMode","sessions"}`。

真实 pi 黑盒探针（此前版本验证，0.1.3 未重新执行完整模型任务）：

```text
{"promptSent":true,"final":"好的","lastState":"idle"}
```

真实 pi 调用 bash 的审批链路也曾验证：`approval.requested` → `approval.resolve` → 命令输出 → `message.final`。

## 3. 桌面端

```bash
cargo build --release -p pi-carry-cli -p pi-carry-desktop
PI_CARRY_HOME=$PWD/.tmp/desk-smoke-home ./target/release/pi-carry-desktop.exe --smoke
```

- `--smoke` 退出码 0，输出 `piInstalled:true`。
- 单实例：首启 1 个进程，二启输出“pi carry 已在运行”并退出 0，进程数仍为 1。
- release 包解压后仅含 `pi-carry-desktop.exe`、`WebView2Loader.dll`、`README.txt`，在隔离目录启动成功。
- UI 在 2560×1600 / 高 DPI 下截图检查：概览、配对、设备、会话、设置、日志页完整，无横向裁切。
- CDP 实测：主题三档切换与系统主题跟随；设置开关点击即保存，切页后保持；开机自启注册表路径带引号；日志时间为 `+08:00`；浅色主题对比度正常。

## 4. Android

```bash
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease
```

结果：`BUILD SUCCESSFUL`。

| 项目 | 结果 |
| --- | --- |
| JVM 测试 | 27 个通过（协议、客户端密钥、主题、色板对比度、历史解析） |
| `lintDebug` | 0 error；保留 GradleDependency/UseKtx/ObsoleteSdkInt/DataExtractionRules 警告 |
| release 签名 | 两个 ABI 均通过 `apksigner verify` |
| 产物 | `app-arm64-v8a-release.apk`、`app-x86_64-release.apk`，versionCode=4/versionName=0.1.3 |

APK 权限包含 `CHANGE_NETWORK_STATE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`；扫码 Activity 固定竖屏；图片发送使用长边 1568、JPEG q85、EXIF 纠正。

**未完成运行时验证**：构建机无硬件虚拟化，Android Emulator 无法启动；没有可用真机。触摸、扫码、前台服务、真实网络切换尚未在设备上执行。

## 5. 发布产物

`dist/` 中保留：

```text
pi-carry-windows-10-x64-0.1.3.zip
pi-carry-android-8.0-arm64-v8a-0.1.3.apk
pi-carry-android-8.0-x86_64-0.1.3.apk
SHA256SUMS.txt
```

GitHub Release `v0.1.3` 已发布；桌面 zip 解压后 `--smoke` 通过，两个 APK 的签名与版本号已复核。

## 6. 明确未覆盖

- Android 真机/模拟器运行时、instrumented tests。
- Linux/macOS 的托盘、单实例、开机自启与发布包。
- Tauri 安装包（MSI/NSIS/DMG/AppImage）、自动更新、代码签名/公证。
- 自托管 relay 与 4G 实网；只验证了 n0 relay 建连和局域网直连地址生成。
- 外部 pi 写者冲突检测、离线 outbox、blob 图片传输、mDNS 发现。
- 真实模型的长任务稳定性与弱网切换。

## 7. 历史修复验证（摘要）

- 0.1.1：修复 `WebView2Loader.dll` 缺失、底部 tab 点击、扫码横屏、浅色系统状态栏、图片压缩。
- 0.1.2：修复会话页节点名、tab 返回、模型弹层滚动、重复设备授权、PC 在线状态、设置开关持久化、日志时区。
- 0.1.3：审批“本对话都允许”、工具默认折叠、工具历史恢复、Markdown、图片、流式输出。
- 详细缺陷清单与取舍见 [`docs/buglist-close.md`](buglist-close.md)。
