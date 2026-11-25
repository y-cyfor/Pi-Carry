# 重新打包 dist 产物并生成 SHA256 校验和（PowerShell 5.1 兼容）。
# Windows：NSIS 安装器（target/release/bundle/nsis/*setup.exe）+ 便携 zip（exe、README、LICENSE，存在 WebView2Loader.dll 时一并带上）。
# Android：每个 ABI 一个正式签名 APK，直接作为独立资产，不压缩。
# 版本号唯一来源是 Cargo.toml 的 [workspace.package] version，并与 crates/desktop/tauri.conf.json 交叉校验。
param(
    [switch]$SkipAndroid,
    [switch]$SkipDesktop,
    [switch]$Sign
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if ($SkipAndroid -and $SkipDesktop) {
    throw "SkipAndroid 与 SkipDesktop 同时启用时没有可打包的产物，请至少保留一类。"
}

function Get-WorkspaceVersion {
    $inSection = $false
    foreach ($line in Get-Content 'Cargo.toml') {
        if ($line -match '^\s*\[([^\]]+)\]\s*$') {
            $inSection = ($matches[1] -eq 'workspace.package')
            continue
        }
        if ($inSection -and $line -match '^\s*version\s*=\s*"([^"]+)"\s*$') {
            return $matches[1]
        }
    }
    throw "无法从 Cargo.toml 的 [workspace.package] 段解析 version。"
}

$version = Get-WorkspaceVersion
$tauriVersion = (Get-Content -Raw 'crates/desktop/tauri.conf.json' | ConvertFrom-Json).version
if ($tauriVersion -ne $version) {
    throw "版本号不一致：Cargo.toml [workspace.package] version = $version，crates/desktop/tauri.conf.json version = $tauriVersion。请先运行 node scripts/set-version.mjs <version> 同步版本号。"
}

New-Item -ItemType Directory -Force 'dist' | Out-Null

$signTool = if ($env:SIGNTOOL) { $env:SIGNTOOL } else { 'signtool' }

# 不依赖 Get-FileHash（受限环境下 Microsoft.PowerShell.Utility 可能未加载）。
function Get-Sha256Hex([string]$Path) {
    $full = (Resolve-Path -LiteralPath $Path).Path
    $stream = [System.IO.File]::OpenRead($full)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            (($sha.ComputeHash($stream)) | ForEach-Object { $_.ToString('x2') }) -join ''
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Invoke-AuthenticodeSign([string]$Path) {
    Write-Host "签名：$Path"
    try {
        & $signTool sign /fd sha256 /tr http://timestamp.digicert.com /td sha256 /a $Path
    } catch {
        throw "签名失败：$Path。签名工具 $signTool 不可用或执行出错：$($_.Exception.Message)"
    }
    if ($LASTEXITCODE -ne 0) {
        throw "签名失败：$Path（$signTool 退出码 $LASTEXITCODE）。"
    }
}

$artifacts = New-Object System.Collections.Generic.List[string]

if (-not $SkipDesktop) {
    $exePath = 'target/release/pi-carry-desktop.exe'
    # Tauri 2 在 MSVC 上静态链接 WebView2LoaderStatic.lib，构建产物里不再有 WebView2Loader.dll；
    # 仅当该 DLL 确实存在（旧工具链 / 动态加载场景）时才一起打进便携包与签名。
    $dllPath = 'target/release/WebView2Loader.dll'
    if (-not (Test-Path $exePath)) {
        throw "缺少 $exePath，请先在 crates/desktop 下运行 cargo tauri build --bundles nsis 构建 Windows 产物。"
    }
    $hasDll = Test-Path $dllPath

    $setups = @(Get-ChildItem 'target/release/bundle/nsis' -Filter '*setup.exe' -File -ErrorAction SilentlyContinue)
    if ($setups.Count -eq 0) {
        throw "缺少 NSIS 安装器，请先在 crates/desktop 下运行 cargo tauri build --bundles nsis。"
    }
    # 优先取版本号与本仓库一致的安装器，避免误把旧版安装器打进本次资产。
    $matched = @($setups | Where-Object { $_.Name -like "*$version*" })
    if ($matched.Count -gt 0) {
        $setups = $matched
    }
    $setupSource = $setups | Sort-Object LastWriteTime -Descending | Select-Object -First 1

    # 先签名主程序与 DLL，便携 zip 才会包含已签名二进制；安装器内部的副本由 tauri 打包时带入。
    if ($Sign) {
        Invoke-AuthenticodeSign $exePath
        if ($hasDll) {
            Invoke-AuthenticodeSign $dllPath
        }
    }

    $desktopDir = 'dist/pi-carry-windows-10-x64'
    $desktopZip = "dist/pi-carry-windows-10-x64-$version.zip"
    $setupTarget = "dist/pi-carry-windows-10-x64-$version-setup.exe"

    New-Item -ItemType Directory -Force $desktopDir | Out-Null
    Get-ChildItem $desktopDir -File -ErrorAction SilentlyContinue | Remove-Item -Force

    Copy-Item $exePath $desktopDir/ -Force
    if ($hasDll) {
        Copy-Item $dllPath $desktopDir/ -Force
    } else {
        Write-Host "未找到 WebView2Loader.dll：Tauri 2 已静态链接 WebView2 加载器，便携包只带 exe。"
    }
    Copy-Item packaging/desktop-README.txt $desktopDir/README.txt -Force
    Copy-Item LICENSE $desktopDir/LICENSE -Force

    Remove-Item $desktopZip -ErrorAction SilentlyContinue
    Compress-Archive -Path "$desktopDir/*" -DestinationPath $desktopZip -Force
    # 只把 zip 留在 dist：publish 用 `gh release create dist/*`，目录会被当成无效资产。
    Remove-Item $desktopDir -Recurse -Force
    $artifacts.Add($desktopZip)

    Copy-Item $setupSource.FullName $setupTarget -Force
    if ($Sign) {
        Invoke-AuthenticodeSign $setupTarget
    }
    $artifacts.Add($setupTarget)

    $setupSigSource = "$($setupSource.FullName).sig"
    if (Test-Path $setupSigSource) {
        Copy-Item $setupSigSource "$setupTarget.sig" -Force
        $artifacts.Add("$setupTarget.sig")
    } elseif ([string]::IsNullOrEmpty($env:TAURI_SIGNING_PRIVATE_KEY)) {
        Write-Warning "缺少 NSIS 更新签名 $setupSigSource（未注入 TAURI_SIGNING_PRIVATE_KEY 时属正常），latest.json 将缺少 Windows 安装器签名。"
    } else {
        throw "已注入 TAURI_SIGNING_PRIVATE_KEY，但缺少 NSIS 更新签名：$setupSigSource。请在 crates/desktop 下运行 cargo tauri build --bundles nsis（保持 createUpdaterArtifacts=true），或确认 tauri-cli 与 tauri crate 版本匹配（版本错配会让日志出现 Failed to add bundler type）。"
    }
}

if (-not $SkipAndroid) {
    $releaseApks = @(
        @{ Src = 'android/app/build/outputs/apk/release/app-arm64-v8a-release.apk'; Dst = "dist/pi-carry-android-8.0-arm64-v8a-$version.apk" },
        @{ Src = 'android/app/build/outputs/apk/release/app-x86_64-release.apk'; Dst = "dist/pi-carry-android-8.0-x86_64-$version.apk" }
    )
    foreach ($apk in $releaseApks) {
        if (-not (Test-Path $apk.Src)) {
            throw "缺少正式签名 APK：$($apk.Src)。请先完成 release 签名构建，不能用 debug APK 代替。"
        }
        Copy-Item $apk.Src $apk.Dst -Force
        $artifacts.Add($apk.Dst)
    }
}

# dist 中遗留的其它版本文件只提示，不自动删除（避免误删上一次发布资产）。
$currentNames = @{}
foreach ($artifact in $artifacts) {
    $currentNames[(Split-Path $artifact -Leaf)] = $true
}
Get-ChildItem 'dist' -File -Filter 'pi-carry-*' -ErrorAction SilentlyContinue | ForEach-Object {
    if (-not $currentNames.ContainsKey($_.Name) -and $_.Name -match '-(\d+\.\d+\.\d+)') {
        if ($matches[1] -ne $version) {
            Write-Warning "dist 中存在其它版本的文件（本次未清理）：$($_.Name)"
        }
    }
}

$lines = $artifacts |
    ForEach-Object { "$(Get-Sha256Hex $_)  $(Split-Path $_ -Leaf)" }
$sumsPath = Join-Path (Get-Location) 'dist/SHA256SUMS.txt'
[System.IO.File]::WriteAllText($sumsPath, (($lines -join "`n") + "`n"), [System.Text.UTF8Encoding]::new($false))

foreach ($artifact in $artifacts) {
    $item = Get-Item $artifact
    Write-Host ("{0,-52} {1,12} bytes  {2,7:N2} MB" -f $item.Name, $item.Length, ($item.Length / 1MB))
}
Get-Content $sumsPath
