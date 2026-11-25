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

<p align="center"><strong>パソコン上の pi を、スマートフォンから操作できるコーディングエージェントに。セッション、承認、モデル設定は端末間だけで流れ、クラウドアカウントは不要です。</strong></p>

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

## インターフェース

### デスクトップ側

| ダーク表示 | ライト設定 |
| :---: | :---: |
| <img src="assets/screenshots/desktop-dark.png" width="520" alt="Pi Carry デスクトップのダーク表示"> | <img src="assets/screenshots/desktop-light.png" width="520" alt="Pi Carry デスクトップのライト設定"> |

デスクトップアプリはトレイに常駐するブリッジです。ローカルの pi プロセス、ペアリング、端末、診断を担当します。概要画面にはノード状態、承認済み端末、セッション数、保留中の承認、relay と LAN の接続先が表示されます。設定ではノード名、pi のパス、新規端末の既定権限、relay モード、承認タイムアウト、テーマを変更できます。**デスクトップ側には意図的にチャットと承認の画面がありません**。操作端末側で承認を迂回できないようにするためです。

### Android 側

<img src="assets/screenshots/android.png" width="100%" alt="Pi Carry Android のノード、セッション、チャット、ペアリング">

Android アプリが主な操作画面です。ノード画面では複数のパソコンを管理し、直結か relay かを表示します。セッションはノードごとにまとまり、検索と実行状態での絞り込みができます。チャット画面はストリーミングテキスト、思考、ツール呼び出し、承認カード、Markdown、画像を描画し、送信、割り込み、キュー、中断、画像添付に対応します。ペアリングは QR コード、`pi-carry://` リンクの貼り付け、Endpoint ID と 6 桁コードの手入力で行えます。

## Pi Carry について

Pi Carry は pi コーディングエージェントのリモート操作レイヤーです。pi はパソコン上で動き続け、既存のセッションファイルをそのまま使います。Pi Carry はスマートフォンとパソコンの間に暗号化チャネルを開き、スマートフォンを第二の画面にします。ストリーミング応答、思考、ツール実行の閲覧、指示の送信、割り込み、キュー、中断、危険な操作の承認、モデルと思考レベルの切り替えができます。

セッションを複製せず、クラウドアカウントも不要です。ブリッジはトレイアプリまたは CLI として動作し、pi の RPC モードで通信します。スマートフォンは複数のノードを保持でき、LAN では直結し、NAT 越えが必要な場合は n0 relay または自前の relay を使います。ツール呼び出しは pi 拡張で横取りされ、ブリッジが仲裁します。承認はタイムアウトで拒否になります。デスクを離れている間の長い作業を追うためのものであり、コード、セッション、認証情報を第三者に渡すためのものではありません。

現在のバージョンは `0.3.2` です。Windows 10/11 x64 が主要な検証対象です。macOS 11+ Universal と Linux x64/arm64 のパッケージはリリース処理でビルドされますが、実機での受け入れ確認はまだ不完全です。macOS ビルドは**署名も公証もされていません**。手順は[未署名ビルドのインストール](#未署名ビルドのインストール)を参照してください。

## インストール

### 動作環境

| プラットフォーム | 要件 |
| --- | --- |
| Windows | Windows 10 21H2 以降または Windows 11、x64。Microsoft Edge WebView2 Runtime。4GB 以上のメモリを推奨 |
| macOS | macOS 11 以降。Intel または Apple Silicon（Universal ビルド）。未署名・未公証 |
| Linux | Ubuntu 22.04 以降（x64）、Ubuntu 24.04 以降（arm64 版は 24.04 でビルド）、Debian 12 以降、Fedora 39 以降、RHEL 9 以降。x64 または arm64。WebKitGTK 4.1、GTK 3、libayatana-appindicator3 |
| Android | Android 8.0（API 26）以降。arm64-v8a または x86_64。約 100 MiB の空き容量 |
| 実行環境 | pi 1.5.0 以降をインストール・設定済み（1.5.2 で検証）。pi の導入には Node.js 20 以降が必要 |
| ネットワーク | LAN 直結はオフラインでも動作。別ネットワークや 4G では n0 relay または自前の relay が必要 |

Pi Carry は pi、Node.js、モデルの認証情報を同梱しません。ターミナルで `pi --version` が実行でき、モデルが設定済みであることを確認してください。macOS と Linux では、ログインシェルの環境から pi を見つけられるよう、デスクトップから一度起動してください。

### ダウンロード

リリース資産の名前は固定です。`0.3.2` の例：

| 資産 | 用途 |
| --- | --- |
| `pi-carry-windows-10-x64-0.3.2-setup.exe` | Windows インストーラー（NSIS、現在のユーザー向け、管理者権限不要） |
| `pi-carry-windows-10-x64-0.3.2.zip` | Windows ポータブル版 |
| `pi-carry-macos-11-universal-0.3.2.dmg` | macOS Universal ディスクイメージ |
| `pi-carry-macos-11-universal-0.3.2.app.tar.gz`（`.sig` 付き） | アップデータ用。手動インストールには不要 |
| `pi-carry-linux-22.04-x64-0.3.2.deb` / `.rpm` | Linux x64 用パッケージ |
| `pi-carry-linux-24.04-arm64-0.3.2.deb` / `.rpm` | Linux arm64 用パッケージ（24.04 でビルド） |
| `latest.json` | 内蔵アップデータが使うマニフェスト |
| `SHA256SUMS.txt` | 全資産のチェックサム |

CI はバージョンタグでこれらの資産をビルドし、**ドラフト**の GitHub Release を作成します。メンテナーが確認してから手動で公開します。

### Windows

`pi-carry-windows-10-x64-0.3.2-setup.exe` を実行します。インストーラーは現在のユーザー向けで、管理者権限は不要です。スタートメニューの項目を作成し、`pi-carry://` ディープリンクを登録します。

ポータブル版は `pi-carry-windows-10-x64-0.3.2.zip` を任意のフォルダーに展開し、`pi-carry-desktop.exe` と `WebView2Loader.dll` を同じ場所に置いて exe を実行します。既定ではウィンドウを閉じるとトレイに格納されます。ポータブル版にアンインストーラーはありません。初回起動時にはインストーラーと同じく `pi-carry://` ディープリンクを登録します。「アプリと機能」からの削除が必要な場合はインストーラーを使ってください。

どちらも設定、鍵、状態、ログを `%USERPROFILE%\.pi-carry` に保存します。自動起動は既定で無効です。設定で有効にすると、現在のユーザーの自動起動項目（`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`、値名 `pi-carry`）を登録します。

WebView2 Runtime がない場合は、Microsoft の Evergreen Runtime をインストールしてから Pi Carry を起動し直してください。

### macOS

`pi-carry-macos-11-universal-0.3.2.dmg` を開き、**pi carry** を「アプリケーション」へドラッグし、イメージを排出してから起動します。Universal ビルドのため、Intel と Apple Silicon の両方でネイティブに動作します。

署名も公証もされていないため、初回起動は Gatekeeper に阻止されます。[未署名ビルドのインストール](#未署名ビルドのインストール)を参照してください。初回起動時に `~/.pi-carry/config.toml`、ローカル状態データベース、ペアリング秘密鍵用のキーチェーン項目が作成されます。

### Linux

アーキテクチャに合うパッケージをダウンロードし、ディストリビューションのパッケージマネージャーでインストールします。`dpkg -i` や `rpm -i` を直接使うと依存関係が解決されません。

```bash
# Debian / Ubuntu x64
sudo apt install ./pi-carry-linux-22.04-x64-0.3.2.deb

# Fedora / RHEL x64
sudo dnf install ./pi-carry-linux-22.04-x64-0.3.2.rpm
```

arm64 ではファイル名の `x64` を `arm64` に置き換えます。パッケージが宣言する依存関係：

- deb: `libwebkit2gtk-4.1-0`、`libgtk-3-0`、`libayatana-appindicator3-1`
- rpm: `webkit2gtk4.1`、`gtk3`、`libayatana-appindicator-gtk3`

GNOME は既定でトレイアイコンを表示しません。表示されない場合は AppIndicator/KStatusNotifierItem 拡張（`gnome-shell-extension-appindicator`）をインストールしてください。トレイが使えない場合も Pi Carry はブリッジとして動作し、セルフチェックカードに理由が表示されます。

### 未署名ビルドのインストール

Windows のコード署名証明書はまだ取得しておらず、SignPath Foundation への申請も未提出です。macOS ビルドは意図的に未署名・未公証です。

**Windows SmartScreen。** 初回起動時に「Windows によって PC が保護されました」と表示されることがあります。**詳細情報 → 実行**をクリックします。先にダウンロードを検証してください：

```powershell
Get-FileHash .\pi-carry-windows-10-x64-0.3.2-setup.exe -Algorithm SHA256
# SHA256SUMS.txt の該当行と比較
```

**macOS Gatekeeper。** 初回起動が阻止されたら、**システム設定 → プライバシーとセキュリティ**を開き、**セキュリティ**までスクロールして「pi carry」の**このまま開く**をクリックし、確認します。隔離属性を削除する方法もあります：

```bash
xattr -dr com.apple.quarantine "/Applications/pi carry.app"
```

**macOS キーチェーン。** ペアリング秘密鍵はログインキーチェーンに保存されます。更新や再ビルドの後に再度許可を求められることがあります。信頼できるビルドなら**常に許可**を選んでください。拒否した場合やキーチェーンが使えない場合、Pi Carry は平文の秘密鍵ファイルに降格し、画面上部に警告バナーを表示します。

**チェックサム。** 各リリースには `SHA256SUMS.txt` が含まれます。macOS と Linux では：

```bash
shasum -a 256 -c SHA256SUMS.txt --ignore-missing
# または: sha256sum -c SHA256SUMS.txt --ignore-missing
```

### Android

Releases から ABI に合う APK をダウンロードします。実機は通常 `arm64-v8a`、x86_64 エミュレーターは `x86_64` です。ブラウザーやファイルマネージャーからのインストール許可を求められる場合があります。

### 初回ペアリング

デスクトップ側で「スマートフォンをペアリング」を開き、QR コードを生成するか `pi-carry://pair?...` リンクをコピーします。スマートフォン側は「ノード」→「新しいパソコンをペアリング」でスキャンするかリンクを貼り付けます。Endpoint ID と 6 桁コードの手入力も可能です。QR コードとコードの有効期限は 10 分で、5 回間違えると一時的にロックされます。

Windows と Linux では、インストーラーまたは初回起動が `pi-carry` ディープリンクを登録するため、ペアリングリンクを開くと Pi Carry が起動または前面に出ます。リンクはペアリング画面を**事前入力するだけ**で、自動的に確定することはありません。確定前にスマートフォン側で Endpoint の指紋を確認してください。macOS ではアプリバンドルが scheme を宣言します。`pi-carry-desktop --pair-uri <uri>` はスクリプトとトラブルシューティング用に残されています。

ペアリング後、パソコンはノード一覧に表示されます。新しい端末の既定権限は `full` です。デスクトップ設定の「新規端末の既定権限」で、以後の端末を `control` または `read` に変更できます。既存端末の権限は変更できないため、取り消してから再ペアリングしてください。端末はいつでも取り消せます。取り消された端末の次の要求は拒否され、再ペアリングが必要になります。

### アップデート

設定の**アップデートチャネル**で `stable` または `beta` を選びます。stable は最新の正式リリース、beta は `beta` プレリリースタグに追従し、不安定な場合があります。「起動時に確認」を有効にすると、起動 30 秒後に静かに確認します。トレイメニューや設定から手動で**アップデートを確認**することもできます。

- 更新パッケージは minisign で署名され、インストール前に検証されます。署名の検証に失敗するとインストールは阻止され、署名エラーとして表示されます。
- Windows のインストーラー版は上書き更新します。ポータブル版は自身の exe を置き換えず、新しいバージョンがあることとダウンロード先だけを提示します。
- macOS のアップデータ版は `.app` を上書きします。**未署名アプリの上書き更新は実機での検証が必要です**。失敗した場合は DMG をダウンロードして手動で置き換えてください。
- Linux の更新はシステムのパッケージマネージャーを呼び出し、polkit の認証が必要です。認証をキャンセルすると、Pi Carry は再試行せず手動コマンドを表示します。
- 更新の失敗は署名、認証・昇格、インストールの 3 種類に分類してヒントを表示します。署名の失敗だけがそのバージョンの再通知を抑止します。

ロールバック：

| プラットフォーム | 操作 |
| --- | --- |
| Windows インストーラー | 旧版の `pi-carry-windows-10-x64-<バージョン>-setup.exe` で上書きインストール |
| Windows ポータブル版 | 終了して展開フォルダーを置き換え、`~/.pi-carry` は保持 |
| macOS | 終了して旧版 DMG をマウントし、**pi carry** を「アプリケーション」へドラッグして置き換え |
| Debian / Ubuntu | `sudo apt install --allow-downgrades ./pi-carry-linux-22.04-x64-<バージョン>.deb` |
| Fedora / RHEL | `sudo dnf downgrade pi-carry`、または `sudo dnf install ./pi-carry-linux-22.04-x64-<バージョン>.rpm` |

## データと権限

Pi Carry はアカウント体系もテレメトリーも持たず、セッションデータを自前のサーバーへ送りません。データはスマートフォン、操作対象のパソコン、利用者が選んだ relay の間だけを流れます。

### ローカルデータ

| 場所 | 内容 | 補足 |
| --- | --- | --- |
| `~/.pi/agent/sessions/**/*.jsonl` | pi のセッション履歴 | 唯一の正本。Pi Carry は接続するだけで、既存履歴を複製・改変しない |
| `~/.pi-carry/config.toml` | ノード、転送、承認、画面の設定 | 平文。手で編集可能 |
| OS のキーチェーン | iroh エンドポイント秘密鍵、任意のパスワードハッシュ | Windows Credential Manager、macOS Keychain、Linux Secret Service。サービス名 `dev.picarry.desktop` |
| `~/.pi-carry/secrets.json` | 秘密鍵の降格保存のみ | キーチェーンが使えない、または拒否された場合に使用。Unix では 0600。キーチェーンが正常なら移行マーカーだけが残る |
| `~/.pi-carry/state.db` | 端末、journal、承認、監査記録 | ローカル SQLite |
| `~/.pi-carry/logs/` | 診断ログ | 日次または 10MB でローテーションし、7 日保持。プロンプト本文、ペアリング token、秘密鍵は記録しない |
| Android SharedPreferences | ノード一覧、テーマ、ローカル設定 | アプリ専用領域。アンインストールで削除 |
| Android アプリキャッシュ | 送信待ち画像の圧縮結果 | 利用者が選んだ画像のみ |

Windows のデータディレクトリは `%USERPROFILE%\.pi-carry` です。`PI_CARRY_HOME` でデータディレクトリ全体、`PI_CARRY_SESSIONS` でセッション走査のルートを変更できます。隔離したテストや 2 つ目のプロファイルに使えます。

スマートフォンはセッションの第二の長期保存先ではありません。メッセージとツールイベントは接続中に届き、再接続後はブリッジの journal またはセッションのスナップショットで補完されます。長期保存は pi 自身の `.jsonl` ファイルです。

### Android の権限

| 権限 | 用途 | 扱い |
| --- | --- | --- |
| `INTERNET`、`ACCESS_NETWORK_STATE` | iroh QUIC 接続とネットワーク変化の検出 | ペアリング済みノードとの通信のみ |
| `CHANGE_NETWORK_STATE` | ネットワーク切り替え後の iroh エンドポイント再バインド | 利用者のネットワーク設定は変更しない |
| `CAMERA` | ペアリング QR コードの読み取り | 実行時許可。画像は端末内で処理し、保存も送信もしない |
| `POST_NOTIFICATIONS` | 承認と完了の通知 | Android 13 以降は実行時許可。拒否可能 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE` | 長時間接続の維持 | 常駐通知を表示。利用者が停止できる |
| `RECEIVE_BOOT_COMPLETED` | 起動後に接続用フォアグラウンドサービスを開始 | システム設定で無効化可能。現行版はノード接続を自動復元しない |
| `WAKE_LOCK` | ネットワーク切り替え・短時間処理用の予約 | 宣言のみで、現行版では主動的に保持しない |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 利用者操作で電池最適化の設定を開く | 画面を開くだけで、無言で要求しない |

連絡先、SMS、位置情報、マイク、全ストレージの権限は要求しません。画像はシステムのフォトピッカーで選び、選ばれたものだけを読み、長辺 1568 / JPEG q85 に圧縮してペアリング済みブリッジへ送ります。

### デスクトップの権限

デスクトップアプリは現在のユーザーとして動作し、管理者権限を必要としません。pi のセッションファイル、`~/.pi-carry` の設定・鍵・データベース・ログを読み書きし、ローカルの `pi --mode rpc` 子プロセスを起動します。自動起動を有効にすると、現在のユーザーの起動項目を 1 つ書き込みます：

- Windows: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`、値名 `pi-carry`
- macOS: `~/Library/LaunchAgents/dev.picarry.desktop.plist`
- Linux: `~/.config/autostart/pi-carry.desktop`

無効にすると該当項目を削除します。トレイが使える場合、自動起動コマンドには `--minimized` が付き、ウィンドウを開かずトレイに常駐します。デスクトップアプリはカメラ、マイク、画面内容、キーボード入力にアクセスしません。

### データの削除

Android アプリをアンインストールするとアプリ専用データが削除されます。端末を取り消すと、ブリッジはその端末からの接続を拒否します。`~/.pi-carry` を削除するとペアリング、端末、ローカル journal がリセットされますが、pi のセッション履歴は削除されません。pi のセッションは pi 自身の方法で管理・バックアップしてください。

## 既知の制限

- **プラットフォーム**：Windows 10/11 x64 が主要な検証対象です。macOS と Linux のパッケージはリリース処理でビルドされますが、トレイ、自動起動、ディープリンク、インストールとアンインストールの実機受け入れはまだ不完全です。macOS アプリは未署名・未公証です。
- **Android のバックグラウンド**：起動ブロードキャストはフォアグラウンドサービスを開始するだけで、ノード接続を復元しません。メーカー独自の省電力設定が長時間接続を終了させる場合があります。
- **セッションの競合**：1 つのブリッジ内での単一書き込み者だけを保証します。同じセッションを書く外部の pi プロセスは検出せず、自動的に読み取り専用へ切り替わることもありません。
- **検出**：mDNS は未実装です。ペアリング QR コードに LAN 直結先が含まれます。
- **オフライン送信**：ローカルの outbox がなく、切断中の送信は失敗します。
- **画像**：1 メッセージ最大 10 枚、1 枚 20 MiB、1 セッション 100 MiB。内容アドレス指定の blob チャネル（512KiB 分割、SHA-256、再開対応）を使い、履歴画像はローカル blob キャッシュから描画します。旧クライアントは base64 インラインも利用できます。
- **承認**：読み取り専用の bash も既定では確認します。「このコマンドを記憶」は critical コマンドを自動許可せず、危険なコマンドは毎回承認が必要です。
- **未実装の画面**：汎用拡張 UI、端末権限の変更、デスクトップ端末の名前変更、診断バンドル。
- **署名と配布**：Windows インストーラーは Authenticode 署名がなく、SignPath Foundation への申請も未提出です。macOS は未署名・未公証で、未署名ビルドの上書き更新は実機検証が必要です。Android には instrumented test がありません。
- **Linux のトレイ**：GNOME では AppIndicator 拡張が必要です。トレイ実装がない場合アイコンは使えず、セルフチェックカードに表示されます。

## 免責事項

Pi Carry は独立したオープンソースプロジェクトです。pi、iroh、Tauri の公式プロジェクトではなく、これらの保守者との提携や承認関係はありません。名称と商標はそれぞれの所有者に帰属します。

本プロジェクトは実際のコマンド実行、ファイル変更、ネットワークアクセスを行う AI コーディングエージェントを操作します。**AI の出力は常に正しいとは限らず、誤り、古い情報、偏り、安全でない提案を含む可能性があります**。**モデルが生成したコマンドやコードを盲目的に実行しないでください**。承認前に内容を確認してください。

承認機構は注意喚起と横取りの層であり、**セキュリティサンドボックスではありません**。承認後でも、コマンドはファイル削除、未コミット変更の上書き、`git push --force`、依存関係のインストール、機密ファイルの読み取り、課金を引き起こす可能性があります。**本ツールの使用により、データ損失、コード破損、認証情報の漏洩、サービス停止などの取り返しのつかない結果が生じる可能性があります**。**必ずバックアップを取り**、信頼できるリポジトリとネットワークで使用し、コマンド、作業ディレクトリ、影響範囲を確認してください。

公開 relay を使う場合、運営者は接続時刻、IP アドレス、通信量などのメタデータを見ることがあります。メッセージ内容は iroh によりエンドツーエンドで暗号化されます。より厳しいプライバシー境界が必要なら、LAN 直結か自前の relay を使ってください。スマートフォンを紛失した場合や他者に操作されている場合は、操作対象のパソコンで直ちにその端末を取り消してください。

本ソフトウェアは **GNU Affero General Public License v3.0** の下で「現状のまま」提供され、明示・黙示を問わずいかなる保証も伴いません。**作者と貢献者は、本ソフトウェアの使用によって生じた直接的・間接的な損失、データ損失、法的・コンプライアンス上の問題について責任を負いません**。地域の法律、組織のセキュリティ方針、第三者の利用規約を守って使用してください。

## ビルド

### 共通要件

Rust 1.91 以降、Cargo、Git が必要です。Windows では Visual Studio Build Tools を推奨します。またはリポジトリが設定する GNU ツールチェーンを使います。プロジェクトは `x86_64-pc-windows-gnu` で検証されています。Linux では WebKitGTK 4.1、libayatana-appindicator3、librsvg2 と一般的なビルドツール、macOS では Xcode Command Line Tools が必要です。

```bash
cargo build --release -p pi-carry-cli -p pi-carry-desktop
```

Windows ビルド後、`target/release` に `WebView2Loader.dll` が生成されます。配布時は `pi-carry-desktop.exe` と同じフォルダーに置く必要があります。CLI のみなら `-p pi-carry-cli` をビルドしてください。

Linux の Tauri 依存関係：

```bash
sudo apt install build-essential curl wget file libssl-dev \
  libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev
```

### Android

JDK 21、Android SDK Platform 36、Build Tools 36.0.0、Gradle 8.11.1 が必要です。`ANDROID_HOME` を設定するか、`android/local.properties` に SDK のパスを書きます。Gradle Wrapper を同梱しています：

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

デバッグ APK は `android/app/build/outputs/apk/debug/` に出力されます。リリース版は署名が必要で、デバッグ APK を正式版として配布してはいけません。署名設定と鍵はリリース担当がローカルで管理し、リポジトリには含めません。

リリースビルドには Android keystore と `android/keystore.properties`（Git 管理外）が必要です：

```properties
storeFile=keystore/release.jks
storePassword=***
keyAlias=pi-carry
keyPassword=***
```

`./gradlew assembleRelease` を実行すると `android/app/build/outputs/apk/release/` に出力されます。keystore がない場合は未署名 APK しか得られず、`scripts/package.ps1` はパッケージ化を拒否します。

### テスト

```bash
cargo fmt --all -- --check
cargo test --workspace
node scripts/set-version.mjs --check

bash scripts/smoke.sh
bash scripts/e2e.sh

cd android
./gradlew testDebugUnitTest lintDebug
```

`scripts/smoke.sh` は CLI、デスクトップ、fake-pi をビルドし、Rust テスト、`pi-carry smoke`、隔離した `PI_CARRY_HOME` での実ブリッジ起動を実行します。`scripts/e2e.sh` は fake-pi でブリッジを起動し、ブラックボックスのエンドツーエンドクライアントを実行します。どちらも Linux CI と Windows の Git Bash で動作します。

`node scripts/set-version.mjs --check` は Rust workspace、デスクトップのマニフェスト、Android の `versionName`/`versionCode`、生成ファイルのバージョン一致を検証します。`node scripts/set-version.mjs <version>` で一括更新できます。

### パッケージング

Windows（インストーラーとポータブル zip）：

```powershell
pwsh scripts/package.ps1
```

Linux（現在のアーキテクチャの deb と rpm）：

```bash
bash scripts/package-linux.sh
```

macOS（Universal DMG とアップデータ用成果物）：

```bash
bash scripts/package-macos.sh
```

スクリプトはバージョン付きの資産と `SHA256SUMS.txt` を `dist/` に書き出します。リリース用の更新マニフェストを生成します：

```bash
node scripts/gen-latest-json.mjs --dir dist --version 0.3.2 \
  --base-url https://github.com/y-cyfor/Pi-Carry/releases/download/v0.3.2
```

`scripts/package.ps1` は正式署名済みの Android APK も同梱し、署名 APK がない場合はデバッグ APK で代用せず失敗します。PowerShell 7 を推奨しますが Windows PowerShell 5.1 でも動作します（SHA-256 は `System.Security.Cryptography` で計算し、`Get-FileHash` に依存しません）。

`.github/workflows/release.yml` の `build-android` job が `assembleRelease` で 2 つの ABI の APK をビルドします。リポジトリ secrets の `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` が必要で、欠けている場合は失敗し未署名 APK を出力しません。

### vendor ディレクトリについて

`vendor/iroh` と `vendor/iroh-relay` は Windows GNU リンク環境向けのパッチ複製です。上流の `cdylib` ターゲットが「エクスポートシンボル過多」を引き起こすため、`crate-type` を `lib` に絞っています。ルートの `Cargo.toml` が `[patch.crates-io]` でこのディレクトリを指します。iroh を更新する際はパッチを更新するか、上流で修正済みか確認してください。

## ディレクトリ構成

```text
Pi-Carry/
├── android/                     Android 操作端
│   ├── app/src/main/java/dev/picarry/app/
│   │   ├── data/                モデル、ノード保存、リポジトリ、画像圧縮
│   │   ├── net/                 iroh 接続、フレーム、フォアグラウンドサービス、起動レシーバー
│   │   └── ui/                  テーマ、共通部品、各画面
│   ├── gradle/wrapper/          Gradle Wrapper
│   ├── gradlew
│   └── settings.gradle.kts
├── assets/                      ロゴと README 用スクリーンショット
├── crates/
│   ├── protocol/                carry.v1 フレーム、JSONL、イベント、payload、エラーコード
│   ├── core/                    設定、SQLite、ペアリング、承認、pi RPC、セッション、iroh サーバー
│   ├── cli/                     serve/status/pair/devices/revoke/doctor/smoke
│   ├── desktop/                 Tauri 2 トレイアプリ
│   ├── fake-pi/                 テスト用 pi RPC シミュレーター
│   └── e2e-client/              ブラックボックス探針と E2E クライアント
├── design/desktop-ui/           デスクトップ用静的コンソール（Tauri フロントエンド）
├── extensions/guard.ts          pi ツール呼び出し承認拡張
├── packaging/                   デスクトップ配布資料
├── scripts/                     環境、ビルド、パッケージ、バージョン、アイコン、テスト
├── vendor/                      iroh / iroh-relay のパッチ複製
├── .github/workflows/           CI とドラフトリリース
├── .cargo/config.toml           Windows GNU リンカー設定
├── Cargo.toml                   Rust workspace
├── LICENSE
└── README.md
```

## 依存関係

| 分野 | 主な依存 |
| --- | --- |
| 実行環境 | pi 1.5.0 以降（1.5.2 で検証）、Windows WebView2 Runtime、任意で n0 または自前の iroh relay |
| デスクトップとコア | Rust 1.91 以降、iroh 1.3、tokio、rusqlite（同梱 SQLite）、serde、argon2、qrcode、if-addrs、ulid、keyring |
| デスクトップシェル | Tauri 2、システム WebView、静的 HTML/CSS/JS、tauri-plugin-updater、tauri-plugin-deep-link |
| Android | Kotlin 2.4.10、Jetpack Compose（BOM 2025.02.00）、Material 3、iroh4k 0.2.0、kotlinx.serialization、ZXing、ExifInterface |
| Android テスト | JUnit 4、Robolectric、AndroidX Test |
| ビルド | Cargo、JDK 21、Android SDK 36、Gradle 8.11.1、PowerShell 7（パッケージング） |

サードパーティの依存関係はそれぞれのライセンスに従います。バイナリを配布する場合は、pi、iroh、Tauri、iroh4k などのライセンスと帰属表示の要件を満たしてください。

## コントリビュート

不具合報告や提案は GitHub Issues へお願いします。[cyfor@foxmail.com](mailto:cyfor@foxmail.com) へのメールや、[作者のホームページ](https://www.cyfor.cn/%e5%85%b3%e4%ba%8ecyfor/)の閲覧も可能です。

## ライセンス

Pi Carry は [GNU Affero General Public License v3.0](LICENSE) で公開されています。改変後にネットワーク経由で提供する場合は、AGPL の求めに従って対応するソースを提供してください。
