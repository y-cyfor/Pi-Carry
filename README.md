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

<p align="center"><strong>Turn pi on your computer into a coding agent you can drive from your phone. Sessions, approvals, and model settings stay on your devices — not in a cloud account.</strong></p>

<p align="center">
  <img alt="Windows 10/11" src="https://img.shields.io/badge/Windows-10%2F11-0078D4?logo=windows&logoColor=white">
  <img alt="Linux source only" src="https://img.shields.io/badge/Linux-source--only-FCC624?logo=linux&logoColor=black">
  <img alt="macOS source only" src="https://img.shields.io/badge/macOS-source--only-000000?logo=apple&logoColor=white">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
</p>

<p align="center">
  <a href="LICENSE"><img alt="AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-7C6BFF"></a>
  <img alt="Rust 1.91+" src="https://img.shields.io/badge/Rust-1.91%2B-000000?logo=rust&logoColor=white">
  <img alt="Tauri 2" src="https://img.shields.io/badge/Tauri-2-24C8DB?logo=tauri&logoColor=white">
  <img alt="Kotlin 2.4" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
</p>

## Interface

### Desktop bridge

| Dark overview | Light settings |
| :---: | :---: |
| <img src="assets/screenshots/desktop-dark.png" width="520" alt="Pi Carry desktop dark overview"> | <img src="assets/screenshots/desktop-light.png" width="520" alt="Pi Carry desktop light settings"> |

The desktop app is a tray-resident bridge. It owns the local pi processes, pairing, devices, and diagnostics. The overview shows node status, authorized devices, session counts, pending approvals, relay mode, and LAN addresses. Settings let you change the node name, pi path, default capability for new devices, relay mode, approval timeout, and theme. **The desktop app deliberately has no chat or approval UI**, so approvals cannot be bypassed on the controlled machine.

### Android controller

<img src="assets/screenshots/android.png" width="100%" alt="Pi Carry Android nodes, sessions, chat, and pairing">

The Android app is the primary controller. The nodes screen manages multiple computers and shows direct/relay status. Sessions are grouped by node and can be searched and filtered. The chat screen renders streaming text, thinking, tool calls, approval cards, Markdown, and images; it supports send, steer, queue, abort, and image attachments. Pairing works by QR code, pasted `pi-carry://` link, or manual endpoint ID plus 6-digit code.

## About Pi Carry

Pi Carry is a remote control layer for the pi coding agent. pi keeps running on your computer and keeps using its existing session files. Pi Carry opens an encrypted channel between the phone and the computer, turning the phone into a second screen: watch streaming replies, thinking, and tool execution; send instructions; steer, queue, or abort; approve risky operations; switch models and thinking levels.

It does not copy sessions and does not require a cloud account. The bridge runs as a tray app or CLI and talks to pi over its RPC mode. The phone can store multiple nodes, connect directly on the LAN, and fall back to the n0 relay or a self-hosted relay when NAT traversal is required. Tool calls are intercepted by a pi extension and arbitrated by the bridge; approvals time out to deny. It is built for following long-running work while away from the desk — not for handing code, sessions, or credentials to a third-party service.

Current release: `0.1.3`. The verified production targets are **Windows 10/11 x64 and Android 8.0+**. Linux and macOS keep a source-build path but have not been validated for tray, autostart, single-instance, or packaging. Features that appear in the design documents but are not implemented are listed in [`docs/implementation-status.md`](docs/implementation-status.md).

## Installation

### Requirements

| Platform | Requirement |
| --- | --- |
| Windows | Windows 10/11 x64; Microsoft Edge WebView2 Runtime; 4 GB RAM recommended; verified |
| Linux | glibc 2.31+; WebKitGTK 4.1; libayatana-appindicator3; librsvg2; source build only, not verified |
| macOS | macOS 12+; Intel or Apple Silicon; source build only, not verified, no notarized build |
| Android | Android 8.0 (API 26)+; arm64-v8a or x86_64; about 100 MB free space |
| Runtime | pi 1.5.2 or newer, already configured; installing pi requires Node.js 20+ |
| Network | LAN direct connection works offline; cross-network or 4G needs the n0 relay or a self-hosted relay |

Pi Carry does not bundle pi, Node.js, or model credentials. Make sure `pi --version` works in a terminal and pi is already configured with a model.

### Desktop

Download the Windows portable zip from Releases, extract it anywhere, keep `pi-carry-desktop.exe` and `WebView2Loader.dll` in the same folder, and run the exe. By default the window closes to the tray. On first launch it creates `~/.pi-carry/config.toml`, `~/.pi-carry/secrets.json`, and the local state database.

If WebView2 Runtime is missing, install the Evergreen Runtime from Microsoft. Linux and macOS currently require a source build (see “Build”).

### Android

Download the APK for your ABI from Releases: `arm64-v8a` for most phones, `x86_64` for x86_64 emulators. Android may ask you to allow installation from a browser or file manager.

### First pairing

On the desktop, open “Pair phone” and generate a QR code or copy the `pi-carry://pair?...` link. On the phone, open “Nodes” → “Pair a new computer” and scan or paste the link. You can also enter the endpoint ID and 6-digit code manually. QR codes and codes are valid for 10 minutes; five wrong code attempts trigger a temporary lock.

After pairing, the computer appears in the node list. New devices get `full` capability by default. “Default capability for new devices” in desktop settings can change this for future devices to `control` or `read`. An existing device cannot be changed in place; revoke it and pair again. You can revoke a device at any time from the desktop; its next request is rejected and it must pair again.

## Data handling and permissions

Pi Carry has no account system, no telemetry, and no project-operated server for session data. Data moves only between the phone, the controlled computer, and the relay you choose.

### Local data

| Location | Content | Notes |
| --- | --- | --- |
| `~/.pi/agent/sessions/**/*.jsonl` | pi session history | Source of truth; Pi Carry attaches and does not copy or rewrite existing history |
| `~/.pi-carry/config.toml` | Node, transport, approval, UI settings | Plain text, editable by hand |
| `~/.pi-carry/secrets.json` | iroh endpoint secret, optional password hash | Mode 0600 on Unix; Windows inherits the user-profile ACL; losing it requires re-pairing |
| `~/.pi-carry/state.db` | Devices, journal, approvals, audit records | Local SQLite database |
| `~/.pi-carry/logs/` | Diagnostic logs | Does not log prompt bodies, pairing tokens, or private keys |
| Android SharedPreferences | Node list, theme, local switches | App-private storage; removed on uninstall |
| Android app cache | Compressed images waiting to be sent | Only images explicitly selected by the user |

The phone is not a second long-term session store. Messages and tool events arrive over the live connection; after a reconnect, the bridge journal or a session snapshot fills the gap. The durable session record remains pi’s own `.jsonl` file.

### Android permissions

| Permission | Purpose | Handling |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | iroh QUIC connection and network changes | Only used with paired nodes |
| `CHANGE_NETWORK_STATE` | Re-bind the iroh endpoint after network changes | Does not change user network settings |
| `CAMERA` | Scan pairing QR codes | Runtime permission; frames are processed locally and never stored or uploaded |
| `POST_NOTIFICATIONS` | Approval and completion alerts | Runtime permission on Android 13+; may be denied |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the long-lived connection | Persistent notification; user can stop the service |
| `RECEIVE_BOOT_COMPLETED` | Start the connection foreground service after boot | Can be disabled in system settings; this version does not automatically restore node connections |
| `WAKE_LOCK` | Reserved for network changes/short tasks | Declared but not actively held in this version |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Open battery optimization settings on user request | Only opens the system screen; never requested silently |

The app does not request contacts, SMS, location, microphone, or broad storage access. Images are chosen through the system photo picker, read only when selected, and compressed to long edge 1568 / JPEG q85 before being sent to a paired bridge.

### Desktop permissions

The desktop app runs as the current user and does not require administrator rights. It reads and writes pi session files, `~/.pi-carry` configuration, keys, database, and logs, and starts local `pi --mode rpc` child processes. Enabling autostart writes one entry under the current user’s registry Run key; disabling it removes the entry. The desktop app does not access the camera, microphone, screen contents, or keyboard input.

### Deleting data

Uninstalling the Android app removes its private data. Revoking a device makes the bridge reject that device. Deleting `~/.pi-carry` resets pairing, devices, and the local journal but does not delete pi’s session history. Manage or back up pi sessions through pi itself.

## Known limitations

- **Platforms**: Windows is built and tray-tested; Android is built, signed, and JVM-tested but has not been validated on a physical device; Linux/macOS are unverified.
- **Android background**: the boot receiver only starts the foreground service; it does not restore node connections. Some OEM battery policies may still kill long connections.
- **Session conflicts**: only one writer per session is enforced inside one bridge. An external pi process writing the same session is not detected, and the app does not switch to read-only automatically.
- **Discovery**: no mDNS. The pairing QR code carries LAN direct addresses.
- **Offline sending**: there is no local outbox; sends fail while disconnected.
- **Images**: the protocol allows 5 images, but the Android picker selects one at a time; images are inline base64, with no blob transport.
- **Approvals**: read-only bash still asks by default; “remember this command” does not currently exclude critical commands, so approve dangerous commands one by one.
- **Missing UI entry points**: approval extension, session fork/rename/entries, generic extension UI, device capability changes, desktop device rename, diagnostics bundle.
- **Delivery**: no Tauri installer, auto-update, code signing/notarization, or Android instrumented tests.

See [`docs/implementation-status.md`](docs/implementation-status.md) for the full list and reasons.

## Disclaimer

Pi Carry is an independent open-source project. It is not affiliated with, endorsed by, or maintained by pi, iroh, or Tauri. Their names and trademarks belong to their respective owners.

This project drives an AI coding agent that can execute real commands, modify files, and access the network. **AI output is not guaranteed to be correct and may contain errors, outdated information, bias, or unsafe suggestions.** **Do not blindly execute model-generated commands or code.** Review what the agent proposes before approving it.

The approval system is a prompt and interception layer, **not a security sandbox**. Even after approval, a command may delete files, overwrite uncommitted changes, run `git push --force`, install dependencies, read sensitive files, or incur costs. **Using this tool may cause data loss, code corruption, credential exposure, service interruption, or other irreversible consequences.** **Keep backups**, use it in trusted repositories and networks, and verify the command, working directory, and impact before approving.

When using a public relay, the relay operator may see metadata such as connection time, IP address, and traffic volume. Message contents are end-to-end encrypted by iroh. For a stricter privacy boundary, use LAN direct connections or a self-hosted relay. If a phone is lost or controlled by someone else, revoke the device on the controlled computer immediately.

This software is provided “as is” under the **GNU Affero General Public License v3.0**, without any express or implied warranty. **The authors and contributors are not liable for any direct or indirect loss, data loss, or legal or compliance issues caused by using this software.** Use it in compliance with local law, organizational security policy, and third-party terms of service.

## Build

### General requirements

Rust 1.91 or newer, Cargo, and Git. On Windows, Visual Studio Build Tools are recommended, or use the GNU toolchain configured by the repository; the project is verified under `x86_64-pc-windows-gnu`. Linux needs WebKitGTK 4.1, libayatana-appindicator3, librsvg2, and normal build tools; macOS needs Xcode Command Line Tools.

```bash
cargo build --release -p pi-carry-cli -p pi-carry-desktop
```

After a Windows build, `WebView2Loader.dll` appears in `target/release`; it must be distributed in the same folder as `pi-carry-desktop.exe`. If you only need the CLI, build `-p pi-carry-cli`.

Linux Tauri dependencies:

```bash
sudo apt install build-essential curl wget file libssl-dev \
  libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev
```

### Android

JDK 21, Android SDK Platform 36, Build Tools 36.0.0, and Gradle 8.11.1 are required. Set `ANDROID_HOME` or write the SDK path to `android/local.properties`. The repository includes the Gradle Wrapper:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

Debug APKs are written to `android/app/build/outputs/apk/debug/`. Release packages must be signed; never distribute a debug APK as a production build. Signing configuration and keys are managed locally by the release owner and are not committed.

A release build needs an Android keystore and `android/keystore.properties` (ignored by Git):

```properties
storeFile=keystore/release.jks
storePassword=***
keyAlias=pi-carry
keyPassword=***
```

Then run `./gradlew assembleRelease`; outputs go to `android/app/build/outputs/apk/release/`. Without a keystore you only get an unsigned APK, and `scripts/package.ps1` refuses to package it.

### Tests

```bash
cargo fmt --all -- --check
cargo test -p pi-carry-protocol -p pi-carry-core
./target/debug/pi-carry smoke
./target/debug/pi-carry-desktop --smoke

cd android
./gradlew testDebugUnitTest lintDebug
```

`pi-carry smoke` uses the built-in fake-pi to verify protocol, sessions, approvals, and streaming events. `--smoke` starts the real bridge, prints node information, and exits. Full manual acceptance should also cover real pi, a real phone, and the system tray.

### Packaging

Regenerate the Windows portable package:

```powershell
pwsh scripts/package.ps1
```

The script collects the desktop exe, `WebView2Loader.dll`, a short README, and the signed Android APKs, then writes SHA256 checksums. It fails if a signed APK is missing and never substitutes a debug APK. PowerShell 7 is required; Windows PowerShell 5.1 is not guaranteed to provide `Get-FileHash`.

### About the vendor directory

`vendor/iroh` and `vendor/iroh-relay` are patched copies for the Windows GNU link environment. The upstream `cdylib` target triggers “too many exported symbols”, so their `crate-type` is narrowed to `lib`. The root `Cargo.toml` points to these directories with `[patch.crates-io]`. When upgrading iroh, update the patches or verify that upstream has fixed the issue.

## Project layout

```text
Pi-Carry/
├── android/                     Android controller
│   ├── app/src/main/java/dev/picarry/app/
│   │   ├── data/                Models, node storage, repository, image compression
│   │   ├── net/                 iroh connection, framing, foreground service, boot receiver
│   │   └── ui/                  Theme, shared components, screens
│   ├── gradle/wrapper/          Gradle Wrapper
│   ├── gradlew
│   └── settings.gradle.kts
├── assets/                      Logo and README screenshots
├── crates/
│   ├── protocol/                carry.v1 frames, JSONL, events, payloads, error codes
│   ├── core/                    Config, SQLite, pairing, approval, pi RPC, sessions, iroh server
│   ├── cli/                     serve/status/pair/devices/revoke/doctor/smoke
│   ├── desktop/                 Tauri 2 tray app
│   ├── fake-pi/                 Deterministic pi RPC simulator for tests
│   └── e2e-client/              Black-box probe and end-to-end client
├── desktop-ui/                  Static desktop console
├── extensions/guard.ts          pi tool-call approval extension
├── packaging/                   Desktop release notes
├── scripts/                     Environment, build, package, icon scripts
├── vendor/                      Patched iroh / iroh-relay copies
├── .cargo/config.toml           Windows GNU linker configuration
├── Cargo.toml                   Rust workspace
├── LICENSE
└── README.md
```

## Dependencies

| Area | Main dependencies |
| --- | --- |
| Runtime | pi 1.5.2+; Windows WebView2 Runtime; optional n0 or self-hosted iroh relay |
| Desktop and core | Rust 1.91+, iroh 1.3, tokio, rusqlite (bundled SQLite), serde, argon2, qrcode, if-addrs, ulid |
| Desktop shell | Tauri 2, system WebView, static HTML/CSS/JS |
| Android | Kotlin 2.4.10, Jetpack Compose (BOM 2025.02.00), Material 3, iroh4k 0.2.0, kotlinx.serialization, ZXing, ExifInterface |
| Android tests | JUnit 4, Robolectric, AndroidX Test |
| Build | Cargo, JDK 21, Android SDK 36, Gradle 8.11.1, PowerShell 7 (packaging) |

Third-party dependencies keep their own licenses. When distributing binaries, comply with the licenses and attribution requirements of pi, iroh, Tauri, iroh4k, and other dependencies.

## Contributing

Use GitHub Issues for bug reports and suggestions. You can also email [cyfor@foxmail.com](mailto:cyfor@foxmail.com) or visit the [author’s homepage](https://www.cyfor.cn/%e5%85%b3%e4%ba%8ecyfor/).

## License

Pi Carry is released under the [GNU Affero General Public License v3.0](LICENSE). If you modify it and provide it as a network service, you must offer the corresponding source under the AGPL.
