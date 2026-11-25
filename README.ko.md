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

<p align="center"><strong>컴퓨터에서 실행 중인 pi를 휴대폰에서 조작하는 코딩 에이전트로. 세션, 승인, 모델 설정은 기기 사이에서만 흐르고 클라우드 계정은 필요 없습니다.</strong></p>

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

## 화면

### 데스크톱 브리지

| 다크 개요 | 라이트 설정 |
| :---: | :---: |
| <img src="assets/screenshots/desktop-dark.png" width="520" alt="Pi Carry 데스크톱 다크 개요"> | <img src="assets/screenshots/desktop-light.png" width="520" alt="Pi Carry 데스크톱 라이트 설정"> |

데스크톱 앱은 트레이에 상주하는 브리지입니다. 로컬 pi 프로세스, 페어링, 기기, 진단을 담당합니다. 개요 화면은 노드 상태, 승인된 기기, 세션 수, 대기 중인 승인, relay 및 LAN 주소를 보여 줍니다. 설정에서는 노드 이름, pi 경로, 새 기기의 기본 권한, relay 모드, 승인 시간 제한, 테마를 바꿀 수 있습니다. **데스크톱 앱에는 의도적으로 채팅과 승인 화면이 없습니다.** 제어 대상 컴퓨터에서 승인을 우회하지 못하게 하기 위해서입니다.

### Android 컨트롤러

<img src="assets/screenshots/android.png" width="100%" alt="Pi Carry Android 노드, 세션, 대화, 페어링">

Android 앱이 주 조작 화면입니다. 노드 화면에서는 여러 컴퓨터를 관리하고 직접 연결 또는 relay 상태를 표시합니다. 세션은 노드별로 묶이며 검색과 실행 상태 필터를 지원합니다. 대화 화면은 스트리밍 텍스트, 사고 과정, 도구 호출, 승인 카드, Markdown, 이미지를 렌더링하고 전송, 끼어들기, 대기열, 중단, 이미지 첨부를 지원합니다. 페어링은 QR 코드, `pi-carry://` 링크 붙여넣기, Endpoint ID와 6자리 코드 직접 입력으로 할 수 있습니다.

## Pi Carry 소개

Pi Carry는 pi 코딩 에이전트의 원격 제어 계층입니다. pi는 컴퓨터에서 계속 실행되며 기존 세션 파일을 그대로 사용합니다. Pi Carry는 휴대폰과 컴퓨터 사이에 암호화 채널을 열어 휴대폰을 두 번째 화면으로 만듭니다. 스트리밍 응답, 사고 과정, 도구 실행을 보고, 지시를 보내고, 끼어들거나 대기열에 넣거나 중단하고, 위험한 작업을 승인하고, 모델과 사고 수준을 바꿀 수 있습니다.

세션을 복제하지 않고 클라우드 계정도 필요 없습니다. 브리지는 트레이 앱 또는 CLI로 실행되며 pi의 RPC 모드로 통신합니다. 휴대폰은 여러 노드를 저장할 수 있고 LAN에서는 직접 연결하며 NAT 통과가 필요하면 n0 relay 또는 자체 호스팅 relay를 사용합니다. 도구 호출은 pi 확장이 가로채고 브리지가 중재합니다. 승인은 시간 초과 시 거부됩니다. 책상에서 벗어나 긴 작업을 따라가기 위한 도구이며, 코드나 세션, 자격 증명을 제3자 서비스에 넘기기 위한 것이 아닙니다.

현재 버전은 `0.3.2`입니다. Windows 10/11 x64가 주요 검증 대상입니다. macOS 11+ Universal과 Linux x64/arm64 패키지는 릴리스 파이프라인에서 빌드되지만 실제 기기 인수 테스트는 아직 완전하지 않습니다. macOS 빌드는 **서명과 공증이 모두 되어 있지 않습니다**. 절차는 [서명되지 않은 빌드 설치](#서명되지-않은-빌드-설치)를 참고하세요.

## 설치

### 요구 사항

| 플랫폼 | 요구 사항 |
| --- | --- |
| Windows | Windows 10 21H2+ 또는 Windows 11, x64. Microsoft Edge WebView2 Runtime. 4GB 이상 메모리 권장 |
| macOS | macOS 11+. Intel 또는 Apple Silicon(Universal 빌드). 서명 및 공증 없음 |
| Linux | Ubuntu 22.04+(x64), Ubuntu 24.04+(arm64 패키지는 24.04에서 빌드), Debian 12+, Fedora 39+ 또는 RHEL 9+. x64 또는 arm64. WebKitGTK 4.1, GTK 3, libayatana-appindicator3 |
| Android | Android 8.0(API 26)+. arm64-v8a 또는 x86_64. 약 100MiB 여유 공간 |
| 런타임 | pi 1.5.0 이상 설치 및 설정 완료(1.5.2에서 검증). pi 설치에는 Node.js 20+ 필요 |
| 네트워크 | LAN 직접 연결은 오프라인에서도 동작. 다른 네트워크나 4G에서는 n0 relay 또는 자체 호스팅 relay 필요 |

Pi Carry는 pi, Node.js, 모델 자격 증명을 함께 배포하지 않습니다. 터미널에서 `pi --version`이 동작하고 모델이 설정되어 있는지 확인하세요. macOS와 Linux에서는 로그인 셸 환경에서 pi를 찾을 수 있도록 데스크톱에서 한 번 실행하세요.

### 다운로드

릴리스 자산 이름은 고정되어 있습니다. `0.3.2` 예시:

| 자산 | 용도 |
| --- | --- |
| `pi-carry-windows-10-x64-0.3.2-setup.exe` | Windows 설치 프로그램(NSIS, 현재 사용자 설치, 관리자 권한 불필요) |
| `pi-carry-windows-10-x64-0.3.2.zip` | Windows 포터블 빌드 |
| `pi-carry-macos-11-universal-0.3.2.dmg` | macOS Universal 디스크 이미지 |
| `pi-carry-macos-11-universal-0.3.2.app.tar.gz`(및 `.sig`) | 업데이터 산출물. 수동 설치에는 불필요 |
| `pi-carry-linux-22.04-x64-0.3.2.deb` / `.rpm` | Linux x64 패키지 |
| `pi-carry-linux-24.04-arm64-0.3.2.deb` / `.rpm` | Linux arm64 패키지(24.04에서 빌드) |
| `latest.json` | 내장 업데이터가 사용하는 매니페스트 |
| `SHA256SUMS.txt` | 모든 자산의 체크섬 |

CI는 버전 태그에서 이 자산들을 빌드하고 **초안** GitHub Release를 만듭니다. 관리자가 검토한 뒤 수동으로 공개합니다.

### Windows

`pi-carry-windows-10-x64-0.3.2-setup.exe`를 실행합니다. 설치 프로그램은 현재 사용자 기준이며 관리자 권한이 필요 없습니다. 시작 메뉴 항목을 만들고 `pi-carry://` 딥링크를 등록합니다.

포터블 빌드는 `pi-carry-windows-10-x64-0.3.2.zip`을 원하는 폴더에 풀고 `pi-carry-desktop.exe`와 `WebView2Loader.dll`을 같은 폴더에 둔 뒤 exe를 실행합니다. 기본적으로 창을 닫으면 트레이로 숨습니다. 포터블 빌드에는 제거 프로그램이 없습니다. 처음 실행하면 설치 프로그램과 마찬가지로 `pi-carry://` 딥링크를 등록합니다. ‘앱 및 기능’의 제거 항목이 필요하면 설치 프로그램을 사용하세요.

두 형태 모두 설정, 키, 상태, 로그를 `%USERPROFILE%\.pi-carry`에 저장합니다. 자동 시작은 기본으로 꺼져 있으며, 설정에서 켜면 현재 사용자 자동 시작 항목(`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, 값 이름 `pi-carry`)을 등록합니다.

WebView2 Runtime이 없으면 Microsoft의 Evergreen Runtime을 설치한 뒤 Pi Carry를 다시 실행하세요.

### macOS

`pi-carry-macos-11-universal-0.3.2.dmg`를 열고 **pi carry**를 **응용 프로그램**으로 끌어다 놓은 뒤 이미지를 배출하고 실행합니다. Universal 빌드라 Intel과 Apple Silicon 모두에서 네이티브로 동작합니다.

서명과 공증이 없어 첫 실행은 Gatekeeper에 의해 차단됩니다. [서명되지 않은 빌드 설치](#서명되지-않은-빌드-설치)를 참고하세요. 첫 실행 시 `~/.pi-carry/config.toml`, 로컬 상태 데이터베이스, 페어링 비밀 키용 키체인 항목이 생성됩니다.

### Linux

아키텍처에 맞는 패키지를 내려받아 배포판 패키지 관리자로 설치합니다. `dpkg -i`나 `rpm -i`를 직접 쓰면 의존성이 해결되지 않습니다.

```bash
# Debian / Ubuntu x64
sudo apt install ./pi-carry-linux-22.04-x64-0.3.2.deb

# Fedora / RHEL x64
sudo dnf install ./pi-carry-linux-22.04-x64-0.3.2.rpm
```

arm64에서는 파일 이름의 `x64`를 `arm64`로 바꿉니다. 패키지가 선언하는 런타임 의존성:

- deb: `libwebkit2gtk-4.1-0`, `libgtk-3-0`, `libayatana-appindicator3-1`
- rpm: `webkit2gtk4.1`, `gtk3`, `libayatana-appindicator-gtk3`

GNOME은 기본으로 트레이 아이콘을 표시하지 않습니다. 아이콘이 보이지 않으면 AppIndicator/KStatusNotifierItem 확장(`gnome-shell-extension-appindicator`)을 설치하세요. 트레이를 쓸 수 없어도 Pi Carry는 브리지로 계속 동작하며 자체 점검 카드에 이유가 표시됩니다.

### 서명되지 않은 빌드 설치

Windows 코드 서명 인증서는 아직 없고 SignPath Foundation 신청도 제출하지 않았습니다. macOS 빌드는 의도적으로 서명과 공증을 하지 않았습니다.

**Windows SmartScreen.** 첫 실행 시 "Windows에서 PC를 보호했습니다"가 표시될 수 있습니다. **추가 정보 → 실행**을 클릭합니다. 먼저 다운로드를 검증하세요:

```powershell
Get-FileHash .\pi-carry-windows-10-x64-0.3.2-setup.exe -Algorithm SHA256
# SHA256SUMS.txt의 해당 줄과 비교
```

**macOS Gatekeeper.** 첫 실행이 차단되면 **시스템 설정 → 개인정보 보호 및 보안**을 열고 **보안**까지 스크롤한 뒤 "pi carry"의 **그래도 열기**를 클릭하고 확인합니다. 격리 속성을 제거할 수도 있습니다:

```bash
xattr -dr com.apple.quarantine "/Applications/pi carry.app"
```

**macOS 키체인.** 페어링 비밀 키는 로그인 키체인에 저장됩니다. 업데이트나 재빌드 후 다시 권한을 물을 수 있습니다. 신뢰하는 빌드라면 **항상 허용**을 선택하세요. 거부하거나 키체인을 쓸 수 없으면 Pi Carry는 평문 비밀 키 파일로 내려가고 화면 상단에 경고 배너를 표시합니다.

**체크섬.** 모든 릴리스에는 `SHA256SUMS.txt`가 포함됩니다. macOS와 Linux에서:

```bash
shasum -a 256 -c SHA256SUMS.txt --ignore-missing
# 또는: sha256sum -c SHA256SUMS.txt --ignore-missing
```

### Android

Releases에서 ABI에 맞는 APK를 내려받습니다. 일반 휴대폰은 `arm64-v8a`, x86_64 에뮬레이터는 `x86_64`입니다. 브라우저나 파일 관리자에서 설치를 허용하라는 요청이 나올 수 있습니다.

### 첫 페어링

데스크톱에서 "휴대폰 페어링"을 열고 QR 코드를 만들거나 `pi-carry://pair?...` 링크를 복사합니다. 휴대폰에서 "노드" → "새 컴퓨터 페어링"을 열고 스캔하거나 링크를 붙여넣습니다. Endpoint ID와 6자리 코드를 직접 입력할 수도 있습니다. QR 코드와 코드는 10분간 유효하며 5번 틀리면 잠시 잠깁니다.

Windows와 Linux에서는 설치 프로그램이나 첫 실행이 `pi-carry` 딥링크를 등록하므로 페어링 링크를 열면 Pi Carry가 실행되거나 앞으로 나옵니다. 링크는 페어링 화면을 **미리 채우기만** 하며 자동으로 확정하지 않습니다. 확정 전에 휴대폰에서 Endpoint 지문을 확인하세요. macOS에서는 앱 번들이 scheme을 선언합니다. `pi-carry-desktop --pair-uri <uri>`는 스크립트와 문제 해결용으로 남아 있습니다.

페어링 후 컴퓨터가 노드 목록에 나타납니다. 새 기기의 기본 권한은 `full`입니다. 데스크톱 설정의 "새 기기 기본 권한"으로 이후 기기를 `control` 또는 `read`로 바꿀 수 있습니다. 기존 기기의 권한은 그 자리에서 바꿀 수 없으며 취소한 뒤 다시 페어링해야 합니다. 기기는 언제든지 취소할 수 있고, 취소된 기기의 다음 요청은 거부되어 다시 페어링해야 합니다.

### 업데이트

설정의 **업데이트 채널**에서 `stable` 또는 `beta`를 고릅니다. stable은 최신 정식 릴리스를, beta는 `beta` 사전 릴리스 태그를 따르며 불안정할 수 있습니다. "시작 시 확인"을 켜면 실행 30초 후 조용히 확인합니다. 트레이 메뉴나 설정에서 직접 **업데이트 확인**을 실행할 수도 있습니다.

- 업데이트 패키지는 minisign으로 서명되고 설치 전에 검증됩니다. 서명 검증에 실패하면 설치가 차단되고 서명 오류로 표시됩니다.
- Windows 설치 프로그램 버전은 제자리에서 덮어씁니다. 포터블 빌드는 자체 exe를 교체하지 않고 새 버전이 있다는 안내와 다운로드 링크만 제공합니다.
- macOS 업데이터 빌드는 `.app`을 제자리에서 교체합니다. **서명되지 않은 앱의 제자리 업데이트는 실제 기기 검증이 필요합니다**. 실패하면 DMG를 내려받아 수동으로 교체하세요.
- Linux 업데이트는 시스템 패키지 관리자를 호출하며 polkit 인증이 필요합니다. 인증을 취소하면 Pi Carry는 재시도하지 않고 수동 명령을 보여 줍니다.
- 업데이트 실패는 서명, 인증/권한 상승, 설치의 세 가지로 분류해 안내합니다. 서명 실패만 해당 버전의 반복 알림을 억제합니다.

롤백:

| 플랫폼 | 작업 |
| --- | --- |
| Windows 설치 프로그램 | 이전 버전의 `pi-carry-windows-10-x64-<버전>-setup.exe`로 덮어쓰기 설치 |
| Windows 포터블 | 종료 후 압축을 푼 폴더를 교체하고 `~/.pi-carry`는 유지 |
| macOS | 종료 후 이전 DMG를 마운트하고 **pi carry**를 응용 프로그램으로 끌어 교체 |
| Debian / Ubuntu | `sudo apt install --allow-downgrades ./pi-carry-linux-22.04-x64-<버전>.deb` |
| Fedora / RHEL | `sudo dnf downgrade pi-carry` 또는 `sudo dnf install ./pi-carry-linux-22.04-x64-<버전>.rpm` |

## 데이터와 권한

Pi Carry에는 계정 시스템, 텔레메트리, 세션 데이터를 위한 자체 서버가 없습니다. 데이터는 휴대폰, 제어 대상 컴퓨터, 사용자가 선택한 relay 사이에서만 흐릅니다.

### 로컬 데이터

| 위치 | 내용 | 비고 |
| --- | --- | --- |
| `~/.pi/agent/sessions/**/*.jsonl` | pi 세션 기록 | 유일한 원본. Pi Carry는 연결만 하고 기존 기록을 복제하거나 다시 쓰지 않음 |
| `~/.pi-carry/config.toml` | 노드, 전송, 승인, 화면 설정 | 평문이며 직접 편집 가능 |
| OS 키체인 | iroh 엔드포인트 비밀 키, 선택적 비밀번호 해시 | Windows Credential Manager, macOS Keychain, Linux Secret Service. 서비스 이름 `dev.picarry.desktop` |
| `~/.pi-carry/secrets.json` | 비밀 키 대체 저장소 전용 | 키체인을 쓸 수 없거나 거부될 때 사용. Unix 권한 0600. 키체인이 정상이면 마이그레이션 표시만 남음 |
| `~/.pi-carry/state.db` | 기기, journal, 승인, 감사 기록 | 로컬 SQLite |
| `~/.pi-carry/logs/` | 진단 로그 | 매일 또는 10MB마다 회전하며 7일 보관. 프롬프트 본문, 페어링 토큰, 비밀 키는 기록하지 않음 |
| Android SharedPreferences | 노드 목록, 테마, 로컬 설정 | 앱 전용 저장소. 제거 시 삭제 |
| Android 앱 캐시 | 전송 대기 이미지의 압축 결과 | 사용자가 선택한 이미지만 |

Windows의 데이터 디렉터리는 `%USERPROFILE%\.pi-carry`입니다. `PI_CARRY_HOME`으로 데이터 디렉터리 전체를, `PI_CARRY_SESSIONS`로 세션 검색 루트를 바꿀 수 있습니다. 격리된 테스트나 두 번째 프로필에 사용하세요.

휴대폰은 세션의 두 번째 장기 저장소가 아닙니다. 메시지와 도구 이벤트는 연결 중에 도착하고 재연결 후 브리지 journal이나 세션 스냅샷으로 보완됩니다. 장기 보관은 pi 자체의 `.jsonl` 파일이 담당합니다.

### Android 권한

| 권한 | 용도 | 처리 |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | iroh QUIC 연결과 네트워크 변화 감지 | 페어링된 노드와의 통신에만 사용 |
| `CHANGE_NETWORK_STATE` | 네트워크 전환 후 iroh 엔드포인트 재바인딩 | 사용자의 네트워크 설정을 바꾸지 않음 |
| `CAMERA` | 페어링 QR 코드 스캔 | 런타임 권한. 화면은 기기에서만 처리하고 저장하거나 전송하지 않음 |
| `POST_NOTIFICATIONS` | 승인 및 완료 알림 | Android 13+ 런타임 권한. 거부 가능 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 장기 연결 유지 | 상주 알림 표시. 사용자가 중지 가능 |
| `RECEIVE_BOOT_COMPLETED` | 부팅 후 연결 포그라운드 서비스 시작 | 시스템 설정에서 차단 가능. 현재 버전은 노드 연결을 자동 복원하지 않음 |
| `WAKE_LOCK` | 네트워크 전환/짧은 작업용 예약 | 선언만 되어 있고 현재 버전에서 능동적으로 보유하지 않음 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 사용자 요청 시 배터리 최적화 설정 열기 | 화면만 열며 조용히 요청하지 않음 |

연락처, SMS, 위치, 마이크, 전체 저장소 권한은 요청하지 않습니다. 이미지는 시스템 사진 선택기로 고르고 선택된 것만 읽어 긴 변 1568 / JPEG q85로 압축한 뒤 페어링된 브리지로 보냅니다.

### 데스크톱 권한

데스크톱 앱은 현재 사용자로 실행되며 관리자 권한이 필요 없습니다. pi 세션 파일, `~/.pi-carry`의 설정·키·데이터베이스·로그를 읽고 쓰며 로컬 `pi --mode rpc` 자식 프로세스를 시작합니다. 자동 시작을 켜면 현재 사용자 항목 하나를 기록합니다:

- Windows: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, 값 이름 `pi-carry`
- macOS: `~/Library/LaunchAgents/dev.picarry.desktop.plist`
- Linux: `~/.config/autostart/pi-carry.desktop`

자동 시작을 끄면 해당 항목을 삭제합니다. 트레이를 쓸 수 있으면 자동 시작 명령에 `--minimized`가 붙어 창 없이 트레이에 상주합니다. 데스크톱 앱은 카메라, 마이크, 화면 내용, 키보드 입력에 접근하지 않습니다.

### 데이터 삭제

Android 앱을 제거하면 앱 전용 데이터가 삭제됩니다. 기기를 취소하면 브리지가 그 기기의 연결을 거부합니다. `~/.pi-carry`를 삭제하면 페어링, 기기, 로컬 journal이 초기화되지만 pi 세션 기록은 삭제되지 않습니다. pi 세션은 pi 자체 방식으로 관리하거나 백업하세요.

## 알려진 제한

- **플랫폼**: Windows 10/11 x64가 주요 검증 대상입니다. macOS와 Linux 패키지는 릴리스 파이프라인에서 빌드되지만 트레이, 자동 시작, 딥링크, 설치·제거의 실제 기기 인수 테스트는 아직 불완전합니다. macOS 앱은 서명과 공증이 없습니다.
- **Android 백그라운드**: 부팅 리시버는 포그라운드 서비스만 시작하고 노드 연결을 복원하지 않습니다. 일부 제조사 절전 정책은 장기 연결을 종료할 수 있습니다.
- **세션 충돌**: 하나의 브리지 안에서 단일 작성자만 보장합니다. 같은 세션을 쓰는 외부 pi 프로세스를 감지하지 않고 자동으로 읽기 전용으로 전환하지도 않습니다.
- **검색**: mDNS가 없습니다. 페어링 QR 코드에 LAN 직접 연결 주소가 포함됩니다.
- **오프라인 전송**: 로컬 outbox가 없어 연결이 끊긴 동안 전송은 실패합니다.
- **이미지**: 메시지당 최대 10장, 장당 20MiB, 세션당 100MiB. 콘텐츠 주소 지정 blob 채널(512KiB 분할, SHA-256, 이어받기)을 사용하고 기록 이미지는 로컬 blob 캐시에서 렌더링합니다. 구형 클라이언트는 base64 인라인도 사용할 수 있습니다.
- **승인**: 읽기 전용 bash도 기본으로 확인합니다. "이 명령 기억"은 critical 명령을 자동 승인하지 않으며 위험한 명령은 매번 승인해야 합니다.
- **미구현 화면**: 범용 확장 UI, 기기 권한 변경, 데스크톱 기기 이름 변경, 진단 번들.
- **서명과 배포**: Windows 설치 프로그램은 Authenticode 서명이 없고 SignPath Foundation 신청도 제출하지 않았습니다. macOS는 서명과 공증이 없으며 서명되지 않은 빌드의 제자리 업데이트는 실제 기기 검증이 필요합니다. Android에는 instrumented test가 없습니다.
- **Linux 트레이**: GNOME은 AppIndicator 확장이 필요합니다. 트레이 구현이 없으면 아이콘을 쓸 수 없고 자체 점검 카드에 표시됩니다.

## 면책 조항

Pi Carry는 독립 오픈 소스 프로젝트입니다. pi, iroh, Tauri의 공식 프로젝트가 아니며 해당 유지 관리자와 제휴하거나 승인을 받은 관계가 아닙니다. 이름과 상표는 각 소유자에게 있습니다.

이 프로젝트는 실제 명령을 실행하고 파일을 수정하며 네트워크에 접근하는 AI 코딩 에이전트를 제어합니다. **AI 출력은 항상 정확하지 않으며 오류, 오래된 정보, 편향, 안전하지 않은 제안을 포함할 수 있습니다.** **모델이 생성한 명령이나 코드를 무조건 실행하지 마세요.** 승인 전에 내용을 확인하세요.

승인 메커니즘은 주의와 가로채기 계층이며 **보안 샌드박스가 아닙니다**. 승인 후에도 명령은 파일 삭제, 커밋되지 않은 변경 덮어쓰기, `git push --force`, 의존성 설치, 민감한 파일 읽기, 비용 발생을 일으킬 수 있습니다. **이 도구를 사용하면 데이터 손실, 코드 손상, 자격 증명 노출, 서비스 중단 등 되돌릴 수 없는 결과가 발생할 수 있습니다.** **반드시 백업을 유지하고** 신뢰할 수 있는 저장소와 네트워크에서 사용하며 명령, 작업 디렉터리, 영향을 확인하세요.

공용 relay를 사용하면 운영자가 연결 시간, IP 주소, 트래픽 양 같은 메타데이터를 볼 수 있습니다. 메시지 내용은 iroh가 종단 간 암호화합니다. 더 엄격한 프라이버시 경계가 필요하면 LAN 직접 연결이나 자체 호스팅 relay를 사용하세요. 휴대폰을 잃어버리거나 다른 사람이 제어한다면 제어 대상 컴퓨터에서 즉시 해당 기기를 취소하세요.

이 소프트웨어는 **GNU Affero General Public License v3.0**에 따라 "있는 그대로" 제공되며 명시적이든 묵시적이든 어떤 보증도 하지 않습니다. **저자와 기여자는 이 소프트웨어 사용으로 인한 직접적·간접적 손실, 데이터 손실, 법적 또는 규정 준수 문제에 대해 책임지지 않습니다.** 지역 법률, 조직의 보안 정책, 제3자 서비스 약관을 준수하여 사용하세요.

## 빌드

### 공통 요구 사항

Rust 1.91 이상, Cargo, Git이 필요합니다. Windows에서는 Visual Studio Build Tools를 권장하거나 저장소가 설정한 GNU 툴체인을 사용합니다. 프로젝트는 `x86_64-pc-windows-gnu`에서 검증되었습니다. Linux에는 WebKitGTK 4.1, libayatana-appindicator3, librsvg2와 일반 빌드 도구가, macOS에는 Xcode Command Line Tools가 필요합니다.

```bash
cargo build --release -p pi-carry-cli -p pi-carry-desktop
```

Windows 빌드 후 `target/release`에 `WebView2Loader.dll`이 생성되며 배포 시 `pi-carry-desktop.exe`와 같은 폴더에 두어야 합니다. CLI만 필요하면 `-p pi-carry-cli`를 빌드하세요.

Linux Tauri 의존성:

```bash
sudo apt install build-essential curl wget file libssl-dev \
  libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev
```

### Android

JDK 21, Android SDK Platform 36, Build Tools 36.0.0, Gradle 8.11.1이 필요합니다. `ANDROID_HOME`을 설정하거나 `android/local.properties`에 SDK 경로를 기록하세요. 저장소에 Gradle Wrapper가 포함되어 있습니다:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

디버그 APK는 `android/app/build/outputs/apk/debug/`에 출력됩니다. 릴리스 패키지는 서명이 필요하며 디버그 APK를 정식 빌드로 배포해서는 안 됩니다. 서명 설정과 키는 릴리스 담당자가 로컬에서 관리하며 저장소에 포함하지 않습니다.

릴리스 빌드에는 Android keystore와 `android/keystore.properties`(Git 제외)가 필요합니다:

```properties
storeFile=keystore/release.jks
storePassword=***
keyAlias=pi-carry
keyPassword=***
```

`./gradlew assembleRelease`를 실행하면 `android/app/build/outputs/apk/release/`에 출력됩니다. keystore가 없으면 서명되지 않은 APK만 나오고 `scripts/package.ps1`은 패키징을 거부합니다.

### 테스트

```bash
cargo fmt --all -- --check
cargo test --workspace
node scripts/set-version.mjs --check

bash scripts/smoke.sh
bash scripts/e2e.sh

cd android
./gradlew testDebugUnitTest lintDebug
```

`scripts/smoke.sh`는 CLI, 데스크톱, fake-pi를 빌드하고 Rust 테스트, `pi-carry smoke`, 격리된 `PI_CARRY_HOME`으로 실제 브리지 실행을 수행합니다. `scripts/e2e.sh`는 fake-pi로 브리지를 시작하고 블랙박스 엔드투엔드 클라이언트를 실행합니다. 둘 다 Linux CI와 Windows Git Bash에서 동작합니다.

`node scripts/set-version.mjs --check`는 Rust workspace, 데스크톱 매니페스트, Android `versionName`/`versionCode`, 생성 파일의 버전 일치를 검사합니다. `node scripts/set-version.mjs <version>`으로 한꺼번에 갱신할 수 있습니다.

### 패키징

Windows(설치 프로그램과 포터블 zip):

```powershell
pwsh scripts/package.ps1
```

Linux(현재 아키텍처의 deb와 rpm):

```bash
bash scripts/package-linux.sh
```

macOS(Universal DMG와 업데이터 산출물):

```bash
bash scripts/package-macos.sh
```

스크립트는 버전이 붙은 자산과 `SHA256SUMS.txt`를 `dist/`에 기록합니다. 릴리스용 업데이트 매니페스트 생성:

```bash
node scripts/gen-latest-json.mjs --dir dist --version 0.3.2 \
  --base-url https://github.com/y-cyfor/Pi-Carry/releases/download/v0.3.2
```

`scripts/package.ps1`은 정식 서명된 Android APK를 함께 패키징하며, 서명 APK가 없으면 디버그 APK로 대체하지 않고 실패합니다. PowerShell 7을 권장하지만 Windows PowerShell 5.1에서도 동작합니다(SHA-256은 `System.Security.Cryptography`로 계산하며 `Get-FileHash`에 의존하지 않습니다).

`.github/workflows/release.yml`의 `build-android` job이 `assembleRelease`로 두 ABI APK를 빌드합니다. 저장소 secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`가 필요하며, 없으면 실패하고 서명되지 않은 APK를 출력하지 않습니다.

### vendor 디렉터리

`vendor/iroh`와 `vendor/iroh-relay`는 Windows GNU 링크 환경용 패치 사본입니다. 업스트림 `cdylib` 타깃이 "내보낸 심볼 과다" 문제를 일으켜 `crate-type`을 `lib`로 좁혔습니다. 루트 `Cargo.toml`이 `[patch.crates-io]`로 이 디렉터리를 가리킵니다. iroh를 업그레이드할 때는 패치를 갱신하거나 업스트림에서 수정되었는지 확인하세요.

## 디렉터리 구조

```text
Pi-Carry/
├── android/                     Android 컨트롤러
│   ├── app/src/main/java/dev/picarry/app/
│   │   ├── data/                모델, 노드 저장, 리포지토리, 이미지 압축
│   │   ├── net/                 iroh 연결, 프레임, 포그라운드 서비스, 부팅 리시버
│   │   └── ui/                  테마, 공용 컴포넌트, 화면
│   ├── gradle/wrapper/          Gradle Wrapper
│   ├── gradlew
│   └── settings.gradle.kts
├── assets/                      로고와 README 스크린샷
├── crates/
│   ├── protocol/                carry.v1 프레임, JSONL, 이벤트, payload, 오류 코드
│   ├── core/                    설정, SQLite, 페어링, 승인, pi RPC, 세션, iroh 서버
│   ├── cli/                     serve/status/pair/devices/revoke/doctor/smoke
│   ├── desktop/                 Tauri 2 트레이 앱
│   ├── fake-pi/                 테스트용 pi RPC 시뮬레이터
│   └── e2e-client/              블랙박스 프로브와 E2E 클라이언트
├── design/desktop-ui/           데스크톱 정적 콘솔(Tauri 프런트엔드)
├── extensions/guard.ts          pi 도구 호출 승인 확장
├── packaging/                   데스크톱 배포 문서
├── scripts/                     환경, 빌드, 패키징, 버전, 아이콘, 테스트 스크립트
├── vendor/                      iroh / iroh-relay 패치 사본
├── .github/workflows/           CI와 초안 릴리스
├── .cargo/config.toml           Windows GNU 링커 설정
├── Cargo.toml                   Rust workspace
├── LICENSE
└── README.md
```

## 의존성

| 영역 | 주요 의존성 |
| --- | --- |
| 런타임 | pi 1.5.0+(1.5.2에서 검증), Windows WebView2 Runtime, 선택적 n0 또는 자체 호스팅 iroh relay |
| 데스크톱과 코어 | Rust 1.91+, iroh 1.3, tokio, rusqlite(내장 SQLite), serde, argon2, qrcode, if-addrs, ulid, keyring |
| 데스크톱 셸 | Tauri 2, 시스템 WebView, 정적 HTML/CSS/JS, tauri-plugin-updater, tauri-plugin-deep-link |
| Android | Kotlin 2.4.10, Jetpack Compose(BOM 2025.02.00), Material 3, iroh4k 0.2.0, kotlinx.serialization, ZXing, ExifInterface |
| Android 테스트 | JUnit 4, Robolectric, AndroidX Test |
| 빌드 | Cargo, JDK 21, Android SDK 36, Gradle 8.11.1, PowerShell 7(패키징) |

서드파티 의존성은 각자의 라이선스를 따릅니다. 바이너리를 배포할 때는 pi, iroh, Tauri, iroh4k 등의 라이선스와 저작자 표시 요구 사항을 지켜야 합니다.

## 기여

버그 신고와 제안은 GitHub Issues를 이용해 주세요. [cyfor@foxmail.com](mailto:cyfor@foxmail.com)으로 메일을 보내거나 [작성자 홈페이지](https://www.cyfor.cn/%e5%85%b3%e4%ba%8ecyfor/)를 방문할 수도 있습니다.

## 라이선스

Pi Carry는 [GNU Affero General Public License v3.0](LICENSE)으로 배포됩니다. 수정 후 네트워크를 통해 제공한다면 AGPL 요구에 따라 해당 소스를 제공해야 합니다.
