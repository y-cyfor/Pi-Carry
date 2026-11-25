<p align="center">
  <img src="assets/logo.svg" width="88" height="88" alt="Pi Carry">
</p>

<h1 align="center">Pi Carry</h1>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a>
</p>

<p align="center"><strong>把电脑上运行的 pi 变成手机可远程驾驶的编程智能体。会话、审批与模型设置在你的设备之间流转，不经过云端账号。</strong></p>

<p align="center">
  <img alt="Windows 10/11" src="https://img.shields.io/badge/Windows-10%2F11-0078D4?logo=windows&logoColor=white">
  <img alt="macOS 11+" src="https://img.shields.io/badge/macOS-11%2B-000000?logo=apple&logoColor=white">
  <img alt="Linux" src="https://img.shields.io/badge/Linux-FCC624?logo=linux&logoColor=black">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
</p>

<p align="center">
  <a href="LICENSE"><img alt="AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-7C6BFF"></a>
  <img alt="Rust 1.91+" src="https://img.shields.io/badge/Rust-1.91%2B-000000?logo=rust&logoColor=white">
  <img alt="Tauri 2" src="https://img.shields.io/badge/Tauri-2-24C8DB?logo=tauri&logoColor=white">
  <img alt="Kotlin 2.4" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
</p>

## 界面

### 桌面受控端

| 深色概览 | 浅色设置 |
| :---: | :---: |
| <img src="assets/screenshots/desktop-dark.png" width="520" alt="Pi Carry 桌面端深色概览"> | <img src="assets/screenshots/desktop-light.png" width="520" alt="Pi Carry 桌面端浅色设置"> |

桌面端是常驻系统托盘的受控端，负责本机 pi 进程、配对、设备和诊断。概览页展示节点状态、已授权设备、会话数量、待审批数量，以及 relay 和局域网直连地址；设置页可修改节点名、pi 路径、默认设备权限、relay 模式、审批超时，并在自动、黑色、白色三档外观之间切换。桌面端不提供聊天和审批入口，避免在电脑上绕过手机端的审批流程。

### Android 控制端

<img src="assets/screenshots/android.png" width="100%" alt="Pi Carry Android 端节点、会话、对话与配对界面">

Android 端是主要控制界面：节点页管理多台电脑并显示直连或 relay 状态；会话页按节点分组，支持搜索和运行状态筛选；对话页显示流式文本、思考过程、工具调用和审批卡片，可发送、插话、排队、打断和附加图片；配对页支持扫码、粘贴配对链接或手动输入 Endpoint ID 与 6 位码。

## 关于 Pi Carry

Pi Carry 是 pi 编程智能体的远程控制层。pi 仍在电脑上运行，继续读写原有的会话文件；Pi Carry 在手机和电脑之间建立加密通道，把手机变成第二块屏幕：查看流式回复、思考过程和工具执行，发送新指令，插话、排队或打断，处理高风险操作审批，切换模型和思考等级。

它不创建会话副本，也不依赖云端账号。桌面受控端以系统托盘应用或 CLI 运行，通过 pi 的 RPC 与本地会话协作；手机可以保存多个节点，在局域网内直连，必要时经 n0 或自托管 relay 穿透 NAT。工具调用通过 pi 扩展拦截后交给桥接核心仲裁，审批超时默认拒绝，桌面端没有审批入口。它适合在离开电脑时继续跟进长任务，而不是把代码、会话或密钥交给第三方服务。

当前版本 `0.3.2`。Windows 10/11 x64 是主要验证平台；macOS 11+ Universal 与 Linux x64/arm64 安装包由发布流程构建，但尚未完成全部真机验收。macOS 构建**未签名、未公证**，安装方式见[安装未签名版本](#安装未签名版本)。

## 安装

### 系统要求

| 平台 | 要求 |
| --- | --- |
| Windows | Windows 10 21H2+ 或 Windows 11，x64；Microsoft Edge WebView2 Runtime；建议 4GB 以上内存 |
| macOS | macOS 11+；Intel 或 Apple Silicon（Universal 构建）；未签名、未公证 |
| Linux | Ubuntu 22.04+（x64）或 Ubuntu 24.04+（arm64 安装在 24.04 上构建）、Debian 12+、Fedora 39+ 或 RHEL 9+；x64 或 arm64；WebKitGTK 4.1、GTK 3、libayatana-appindicator3 |
| Android | Android 8.0（API 26）及以上；arm64-v8a 或 x86_64；约 100 MiB 可用空间 |
| 运行环境 | 已安装并完成配置的 pi 1.5.0 或更高版本（验证环境为 1.5.2）；安装 pi 需要 Node.js 20+ |
| 网络 | 局域网直连可离线使用；跨网段或 4G 需要 n0 公共 relay 或自托管 relay |

Pi Carry 不捆绑 pi、Node.js 或模型凭据。请先确认在终端中可以直接运行 `pi --version`，并已经完成 pi 的模型配置。macOS 和 Linux 上先启动一次 Pi Carry，让它从登录 shell 环境中找到 pi。

### 下载

发布资产使用固定命名。以 `0.3.2` 为例：

| 资产 | 用途 |
| --- | --- |
| `pi-carry-windows-10-x64-0.3.2-setup.exe` | Windows 安装器（NSIS，当前用户安装，不需要管理员权限） |
| `pi-carry-windows-10-x64-0.3.2.zip` | Windows 便携包 |
| `pi-carry-macos-11-universal-0.3.2.dmg` | macOS Universal 镜像 |
| `pi-carry-macos-11-universal-0.3.2.app.tar.gz`（含 `.sig`） | 更新器产物，手动安装不需要 |
| `pi-carry-linux-22.04-x64-0.3.2.deb` / `.rpm` | Linux x64 安装包 |
| `pi-carry-linux-24.04-arm64-0.3.2.deb` / `.rpm` | Linux arm64 安装包（在 24.04 上构建） |
| `latest.json` | 内置更新器使用的清单 |
| `SHA256SUMS.txt` | 所有资产的校验和 |

CI 在版本 tag 上构建这些资产，并创建**草稿** GitHub Release；维护者复核后手动发布。

### Windows

运行 `pi-carry-windows-10-x64-0.3.2-setup.exe`。安装器按当前用户安装，不需要管理员权限，会创建开始菜单项并注册 `pi-carry://` 深链。

便携版解压 `pi-carry-windows-10-x64-0.3.2.zip` 到任意目录，保持 `pi-carry-desktop.exe` 与 `WebView2Loader.dll` 在同一个文件夹，然后运行 exe。默认关闭窗口会隐藏到托盘。便携版没有卸载器；首次运行时和安装器一样会注册 `pi-carry://` 深链。需要“应用和功能”里的卸载入口时请使用安装器。

两种形态都使用 `%USERPROFILE%\.pi-carry` 保存配置、密钥、状态和日志。开机自启默认关闭；在设置中启用后，会写入当前用户自启项（`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`，值名 `pi-carry`）。

如果系统没有 WebView2 Runtime，请先从 Microsoft 官网安装 Evergreen Runtime，再启动 Pi Carry。

### macOS

打开 `pi-carry-macos-11-universal-0.3.2.dmg`，把 **pi carry** 拖入“应用程序”，推出镜像，然后从“应用程序”启动。构建为 Universal，在 Intel 和 Apple Silicon 上原生运行。

应用未签名、未公证，首次启动会被 Gatekeeper 拦截，处理方式见[安装未签名版本](#安装未签名版本)。首次启动会创建 `~/.pi-carry/config.toml`、本地状态数据库，以及用于保存配对密钥的钥匙串条目。

### Linux

下载对应架构的安装包，用发行版包管理器安装。不要直接使用 `dpkg -i` 或 `rpm -i`，它们不会解析依赖。

```bash
# Debian / Ubuntu x64
sudo apt install ./pi-carry-linux-22.04-x64-0.3.2.deb

# Fedora / RHEL x64
sudo dnf install ./pi-carry-linux-22.04-x64-0.3.2.rpm
```

arm64 机器把文件名中的 `x64` 换成 `arm64`。安装包声明的运行时依赖：

- deb：`libwebkit2gtk-4.1-0`、`libgtk-3-0`、`libayatana-appindicator3-1`
- rpm：`webkit2gtk4.1`、`gtk3`、`libayatana-appindicator-gtk3`

GNOME 默认不显示托盘图标；如果托盘图标没有出现，请安装 AppIndicator/KStatusNotifierItem 扩展（`gnome-shell-extension-appindicator`）。托盘不可用时 Pi Carry 仍会作为桥接运行，自检卡会说明缺失原因。

### 安装未签名版本

项目尚未取得 Windows 代码签名证书，SignPath Foundation 申请也尚未提交；macOS 构建明确不签名、不公证。

**Windows SmartScreen。** 首次启动可能提示“Windows 已保护你的电脑”。点击**更多信息 → 仍要运行**。下载后先校验：

```powershell
Get-FileHash .\pi-carry-windows-10-x64-0.3.2-setup.exe -Algorithm SHA256
# 与 SHA256SUMS.txt 中对应行比较
```

**macOS Gatekeeper。** 首次启动被拦截后，打开**系统设置 → 隐私与安全性**，滚动到**安全性**，为“pi carry”点击**仍要打开**，再确认。也可以移除隔离属性：

```bash
xattr -dr com.apple.quarantine "/Applications/pi carry.app"
```

**macOS 钥匙串。** 应用把配对密钥保存在登录钥匙串中。替换应用或重新构建后，macOS 可能再次询问授权；信任该构建时选择**始终允许**。如果拒绝授权或钥匙串不可用，Pi Carry 会降级为明文密钥文件，并在界面顶部显示警告横幅。

**校验和。** 每个 Release 都包含 `SHA256SUMS.txt`。macOS 和 Linux 上执行：

```bash
shasum -a 256 -c SHA256SUMS.txt --ignore-missing
# 或：sha256sum -c SHA256SUMS.txt --ignore-missing
```

### Android

从 Releases 下载对应 ABI 的 APK：真机通常使用 `arm64-v8a`，x86_64 模拟器使用 `x86_64`。安装时系统可能要求允许来自浏览器或文件管理器的未知来源应用。

### 首次配对

在桌面端打开“配对手机”，生成二维码或复制 `pi-carry://pair?...` 链接。手机端进入“节点”，点击“配对新的电脑”，扫码或粘贴链接；也可以手动输入桌面端显示的 Endpoint ID 和 6 位码。二维码和 6 位码默认 10 分钟有效，连续 5 次输错会锁定一段时间。

Windows 和 Linux 上，安装器或首次运行会注册 `pi-carry` 深链，打开配对链接会启动或聚焦 Pi Carry。链接只**预填**配对页，不会自动完成配对；请在手机上核对 Endpoint 指纹后再确认。macOS 的 scheme 由应用包声明。`pi-carry-desktop --pair-uri <uri>` 仍保留给脚本和排障使用。

配对成功后，电脑会出现在节点列表中。新设备默认获得 `full` 权限；桌面端设置里的“新设备默认权限”可以把之后的新设备改为 `control` 或 `read`。已配对设备不能单独改权限，只能吊销后重新配对。设备列表中可以随时吊销某台设备；该设备的下一次请求会被拒绝，需要重新配对。

### 保持更新

设置页的**更新通道**可选 `stable` 或 `beta`。stable 跟随最新正式版，beta 跟随 `beta` 预发布 tag，可能不稳定。开启“启动时检查”后，Pi Carry 会在启动 30 秒后静默检查一次；也可以从托盘菜单或设置页手动**检查更新**。

- 更新包使用 minisign 验签，安装前校验；签名失败会阻止安装并提示签名错误。
- Windows 安装器版就地覆盖更新；便携版不会替换自身 exe，只提示有新版本并给出下载入口。
- macOS 更新器就地替换 `.app`。**未签名应用的就地更新仍需真机验证**；失败时请下载 DMG 手动替换。
- Linux 更新调用系统包管理器，需要 polkit 授权；取消授权时 Pi Carry 会显示手动命令，不会重试。
- 更新失败会按签名、授权/提权、安装三类提示；只有签名失败会记录该版本，避免反复提示。

回退：

| 平台 | 操作 |
| --- | --- |
| Windows 安装器 | 运行旧版 `pi-carry-windows-10-x64-<版本>-setup.exe` 覆盖安装 |
| Windows 便携版 | 退出程序，替换解压目录，保留 `~/.pi-carry` |
| macOS | 退出 app，挂载旧版 DMG，把 **pi carry** 拖回“应用程序”并替换 |
| Debian / Ubuntu | `sudo apt install --allow-downgrades ./pi-carry-linux-22.04-x64-<版本>.deb` |
| Fedora / RHEL | `sudo dnf downgrade pi-carry`，或 `sudo dnf install ./pi-carry-linux-22.04-x64-<版本>.rpm` |

## 数据处理与权限

Pi Carry 不运营账号系统，不收集遥测，不把会话上传到自有服务器。数据只在手机、受控端电脑和用户选择的 relay 之间流动。

### 本地数据

| 位置 | 内容 | 说明 |
| --- | --- | --- |
| `~/.pi/agent/sessions/**/*.jsonl` | pi 的会话历史 | 唯一事实源；Pi Carry 只 attach，不复制、不改写既有历史 |
| `~/.pi-carry/config.toml` | 节点、传输、审批、界面配置 | 明文配置，可手工编辑 |
| 系统钥匙串 | iroh 端点私钥、可选密码哈希 | Windows Credential Manager、macOS Keychain 或 Linux Secret Service；服务名 `dev.picarry.desktop` |
| `~/.pi-carry/secrets.json` | 仅作密钥降级存储 | 钥匙串不可用或被拒绝时使用；Unix 权限 0600。钥匙串正常时只保留迁移标记 |
| `~/.pi-carry/state.db` | 设备、journal、审批与审计记录 | 本地 SQLite |
| `~/.pi-carry/logs/` | 诊断日志 | 按天或 10 MB 轮转，保留 7 天；不记录 prompt 正文、配对 token 或私钥 |
| Android SharedPreferences | 节点列表、主题和本地开关 | 应用私有目录；卸载应用时删除 |
| Android 应用私有缓存 | 待发送图片的压缩结果 | 仅保存用户主动选择的图片 |

Windows 的数据目录为 `%USERPROFILE%\.pi-carry`。`PI_CARRY_HOME` 可覆盖整个数据目录，`PI_CARRY_SESSIONS` 可覆盖会话扫描目录，用于隔离测试或第二套配置。

手机不是会话的第二份长期存储。消息和工具事件通过持久连接实时接收，断线重连后从受控端的 journal 或会话快照补齐；真正长期保存会话的是 pi 自己的 `.jsonl` 文件。

### Android 权限

| 权限 | 用途 | 处理方式 |
| --- | --- | --- |
| `INTERNET`、`ACCESS_NETWORK_STATE` | 建立 iroh QUIC 连接、检测网络变化 | 仅用于与已配对节点通信 |
| `CHANGE_NETWORK_STATE` | 网络切换后重新绑定 iroh 端点 | 不修改用户的网络配置 |
| `CAMERA` | 扫描配对二维码 | 运行时申请；画面仅用于本地识别，不保存、不上传 |
| `POST_NOTIFICATIONS` | 审批提醒和会话完成提醒 | Android 13+ 运行时申请，可拒绝 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE` | 保持与电脑的长连接 | 常驻通知可见，可随时停止服务 |
| `RECEIVE_BOOT_COMPLETED` | 开机后启动连接前台服务 | 可在系统设置中禁止自启动；当前版本不会自动恢复节点连接 |
| `WAKE_LOCK` | 预留网络切换/短任务唤醒 | 当前版本声明但未主动持有 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 用户主动打开电池优化设置 | 只在用户点击时跳转，不静默申请 |

应用不申请通讯录、短信、位置、麦克风或全盘存储权限。发送图片使用系统照片选择器，只读取用户明确选择的图片，并压缩到长边 1568 / JPEG q85 后发送给已配对桥接。

### 桌面端权限

桌面端以当前用户身份运行，不需要管理员权限。它读取和写入 pi 的会话目录、`~/.pi-carry` 下的配置、密钥、数据库和日志，并启动本机 `pi --mode rpc` 子进程。启用开机自启时，会写入一条当前用户自启项：

- Windows：`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`，值名 `pi-carry`
- macOS：`~/Library/LaunchAgents/dev.picarry.desktop.plist`
- Linux：`~/.config/autostart/pi-carry.desktop`

关闭自启会删除对应条目。托盘可用时，自启命令会追加 `--minimized`，让程序启动后驻留托盘而不是弹出窗口。桌面端不访问摄像头、麦克风、屏幕内容或键盘输入。

### 删除数据

卸载 Android 应用会删除应用私有数据；吊销设备后，受控端不再接受该设备连接。删除 `~/.pi-carry` 会重置配对、设备和本地 journal，但不会删除 pi 的会话历史。pi 的会话文件需要按 pi 自身的方式管理或备份。

## 已知限制

- **平台**：Windows 10/11 x64 是主要验证平台；macOS 和 Linux 安装包已由发布流程构建，但托盘、自启、深链和安装卸载的真机验收仍不完整。macOS 应用未签名、未公证。
- **Android 后台**：开机广播只启动前台服务，不会自动恢复节点连接；部分厂商的省电策略仍可能终止长连接。
- **会话冲突**：当前只保证同一 bridge 内单写者，不检测本机终端里的 pi 是否正在写同一会话，也不会自动切换只读。
- **网络发现**：未实现 mDNS；二维码携带局域网直连地址。
- **离线发送**：没有本地 outbox，断网时发送会失败。
- **图片**：单条最多 10 张、单张 20 MiB、每会话 100 MiB；走内容寻址 blob 通道（512KiB 分片、SHA-256、断点续传），历史图从本地 blob 缓存渲染；老客户端仍可 base64 内联。
- **审批**：只读 bash 默认仍询问；“记住此命令”不会自动放行 critical 命令，危险命令仍需逐次审批。
- **未实现入口**：通用扩展 UI、设备能力调整、桌面设备重命名、诊断包。
- **签名与交付**：Windows 安装器未做 Authenticode 签名，SignPath Foundation 申请尚未提交；macOS 未签名、未公证，未签名就地更新仍需真机验证；Android 没有 instrumented tests。
- **Linux 托盘**：GNOME 需要 AppIndicator 扩展；没有托盘实现时图标不可用，自检卡会提示。

## 免责声明

Pi Carry 是独立开源项目，不是 pi、iroh 或 Tauri 的官方项目，与这些项目的维护者没有隶属或背书关系。相关名称和商标归各自所有者。

本项目调用的是能够执行真实命令、修改文件和访问网络的 AI 编程智能体。**AI 输出并非绝对正确，可能包含错误、过时信息、偏见或不安全的建议**；请在使用前自行甄别，**不要盲目执行模型生成的命令或代码**。

审批机制是提示和拦截层，**不是安全沙箱**。即使经过审批，命令仍可能删除文件、覆盖未提交的修改、执行 `git push --force`、安装依赖、读取敏感文件或产生费用。**使用本工具可能造成数据丢失、代码损坏、凭据泄露、服务中断等不可逆后果**。**请务必保留备份**，在可信的代码库和网络环境中使用，并仔细核对命令、工作目录和影响范围。

通过公共 relay 连接时，relay 运营方可能看到连接时间、IP 地址和流量大小等元数据；消息内容由 iroh 端到端加密。需要更严格的隐私边界时，请使用局域网直连或自托管 relay。手机丢失或设备被他人控制时，请立即在受控端吊销该设备。

本软件按 **GNU Affero General Public License v3.0**“原样”提供，不附带任何明示或暗示的担保。**作者和贡献者不对使用本软件造成的任何直接或间接损失、数据丢失、法律或合规问题负责**。请在遵守当地法律、组织安全策略和第三方服务条款的前提下使用。

## 构建

### 通用要求

Rust 1.91 或更高版本、Cargo、Git。Windows 推荐安装 Visual Studio Build Tools 或使用仓库配置的 GNU 工具链；项目在 `x86_64-pc-windows-gnu` 下验证。Linux 需要 WebKitGTK 4.1、libayatana-appindicator3、librsvg2 及常规编译工具；macOS 需要 Xcode Command Line Tools。

```bash
cargo build --release -p pi-carry-cli -p pi-carry-desktop
```

Windows 构建后，`WebView2Loader.dll` 会出现在 `target/release`，分发时必须与 `pi-carry-desktop.exe` 放在同一目录。只使用 CLI 时构建 `-p pi-carry-cli` 即可。

Linux 的 Tauri 依赖可以参考：

```bash
sudo apt install build-essential curl wget file libssl-dev \
  libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev
```

### Android

需要 JDK 21、Android SDK Platform 36、Build Tools 36.0.0 和 Gradle 8.11.1。设置 `ANDROID_HOME`，或在 `android/local.properties` 中写入 SDK 路径。仓库包含 Gradle Wrapper：

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

调试构建的产物位于 `android/app/build/outputs/apk/debug/`。发布包必须使用正式签名构建，不能把 debug APK 当作正式包分发；签名配置和密钥由发布者在本机管理，不进入仓库。

正式签名构建需要一个 Android keystore，并在 `android/keystore.properties` 中提供以下字段（该文件已被 `.gitignore` 排除）：

```properties
storeFile=keystore/release.jks
storePassword=***
keyAlias=pi-carry
keyPassword=***
```

然后执行 `./gradlew assembleRelease`，产物位于 `android/app/build/outputs/apk/release/`。缺少 keystore 时只会得到 unsigned APK，`scripts/package.ps1` 会拒绝打包。

### 测试

```bash
cargo fmt --all -- --check
cargo test --workspace
node scripts/set-version.mjs --check

bash scripts/smoke.sh
bash scripts/e2e.sh

cd android
./gradlew testDebugUnitTest lintDebug
```

`scripts/smoke.sh` 会构建 CLI、桌面端和 fake-pi，运行 Rust 测试、`pi-carry smoke`，并用隔离的 `PI_CARRY_HOME` 启动真实桌面桥接后退出。`scripts/e2e.sh` 用 fake-pi 启动桥接，再运行黑盒端到端客户端。两个脚本都可以在 Linux CI 和 Windows Git Bash 中运行。

`node scripts/set-version.mjs --check` 校验 Rust workspace、桌面清单、Android `versionName`/`versionCode` 和生成文件中的版本一致；执行 `node scripts/set-version.mjs <version>` 可统一更新。

### 打包

Windows（安装器与便携 zip）：

```powershell
pwsh scripts/package.ps1
```

Linux（当前架构的 deb 与 rpm）：

```bash
bash scripts/package-linux.sh
```

macOS（Universal DMG 与更新器产物）：

```bash
bash scripts/package-macos.sh
```

脚本会把带版本号的资产和 `SHA256SUMS.txt` 写入 `dist/`。生成发布用更新清单：

```bash
node scripts/gen-latest-json.mjs --dir dist --version 0.3.2 \
  --base-url https://github.com/y-cyfor/Pi-Carry/releases/download/v0.3.2
```

`scripts/package.ps1` 会一并打包正式签名的 Android APK；缺少签名 APK 时会直接失败，不会用 debug APK 代替。推荐 PowerShell 7，Windows PowerShell 5.1 也可用（脚本用 `System.Security.Cryptography` 计算 SHA-256，不依赖 `Get-FileHash`）。

`.github/workflows/release.yml` 的 `build-android` job 用 `assembleRelease` 构建两个 ABI 的 APK，需要仓库 secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；缺少时该 job 直接失败，不会产出未签名 APK。

### 关于 vendor 目录

`vendor/iroh` 和 `vendor/iroh-relay` 是为 Windows GNU 链接环境准备的补丁副本：上游库的 `cdylib` 目标会触发导出符号过多的问题，因此将 `crate-type` 收窄为 `lib`。根 `Cargo.toml` 通过 `[patch.crates-io]` 指向这两个目录。升级 iroh 时需要同步更新补丁，或验证上游是否已经修复该问题。

## 目录结构

```text
Pi-Carry/
├── android/                     Android 控制端
│   ├── app/src/main/java/dev/picarry/app/
│   │   ├── data/                模型、节点存储、仓库、图片压缩
│   │   ├── net/                 iroh 连接、帧协议、前台服务、开机广播
│   │   └── ui/                  主题、通用组件、各功能页面
│   ├── gradle/wrapper/          Gradle Wrapper
│   ├── gradlew
│   └── settings.gradle.kts
├── assets/                      Logo 与 README 截图
├── crates/
│   ├── protocol/                carry.v1 帧、JSONL、事件、payload、错误码
│   ├── core/                    配置、SQLite、配对、审批、pi RPC、会话、iroh 服务端
│   ├── cli/                     serve/status/pair/devices/revoke/doctor/smoke
│   ├── desktop/                 Tauri 2 托盘应用
│   ├── fake-pi/                 测试用 pi RPC 模拟器
│   └── e2e-client/              黑盒探针与端到端测试客户端
├── design/desktop-ui/           桌面端静态控制台（Tauri 前端）
├── extensions/guard.ts          pi 工具调用拦截扩展
├── packaging/                   桌面发布说明
├── scripts/                     环境、构建、打包、版本、图标与测试脚本
├── vendor/                      iroh / iroh-relay 补丁副本
├── .github/workflows/           CI 与草稿 Release
├── .cargo/config.toml           Windows GNU 链接器配置
├── Cargo.toml                   Rust workspace
├── LICENSE
└── README.md
```

## 项目依赖

| 类别 | 主要依赖 |
| --- | --- |
| 运行时 | pi 1.5.0+（验证环境为 1.5.2）；Windows WebView2 Runtime；可选 n0 或自托管 iroh relay |
| 桌面与核心 | Rust 1.91+、iroh 1.3、tokio、rusqlite（bundled SQLite）、serde、argon2、qrcode、if-addrs、ulid、keyring |
| 桌面外壳 | Tauri 2、系统 WebView、静态 HTML/CSS/JS、tauri-plugin-updater、tauri-plugin-deep-link |
| Android | Kotlin 2.4.10、Jetpack Compose（BOM 2025.02.00）、Material 3、iroh4k 0.2.0、kotlinx.serialization、ZXing、ExifInterface |
| Android 测试 | JUnit 4、Robolectric、AndroidX Test |
| 构建 | Cargo、JDK 21、Android SDK 36、Gradle 8.11.1、PowerShell 7（打包） |

第三方依赖分别遵循其自身许可证。发布二进制时请同时遵守 pi、iroh、Tauri、iroh4k 等项目的许可与署名要求。

## 贡献

欢迎通过 Issue 反馈问题或提出建议。也可以发送邮件至 [cyfor@foxmail.com](mailto:cyfor@foxmail.com)，或访问[个人主页](https://www.cyfor.cn/%e5%85%b3%e4%ba%8ecyfor/)。

## 许可证

Pi Carry 以 [GNU Affero General Public License v3.0](LICENSE) 发布。若修改后通过网络向他人提供服务，请按 AGPL 的要求提供对应源码。
