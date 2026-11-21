# 重新打包 dist 产物并生成 SHA256 校验和。
# PC 包：exe、README、WebView2Loader.dll、LICENSE，压缩为 zip。
# Android：每个 ABI 一个正式签名 APK，直接作为独立资产，不压缩。
param(
    [switch]$SkipAndroid
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$version = '0.1.2'
$desktopDir = 'dist/pi-carry-windows-10-x64'
$desktopZip = "dist/pi-carry-windows-10-x64-$version.zip"
$androidArm64 = "dist/pi-carry-android-8.0-arm64-v8a-$version.apk"
$androidX64 = "dist/pi-carry-android-8.0-x86_64-$version.apk"

New-Item -ItemType Directory -Force $desktopDir | Out-Null
Get-ChildItem $desktopDir -File -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item target/release/pi-carry-desktop.exe $desktopDir/ -Force
Copy-Item target/release/WebView2Loader.dll $desktopDir/ -Force
Copy-Item packaging/desktop-README.txt $desktopDir/README.txt -Force
Copy-Item LICENSE $desktopDir/LICENSE -Force

Remove-Item $desktopZip -ErrorAction SilentlyContinue
Compress-Archive -Path "$desktopDir/*" -DestinationPath $desktopZip -Force
$artifacts = @($desktopZip)

if (-not $SkipAndroid) {
    $releaseApks = @(
        @{ Src = 'android/app/build/outputs/apk/release/app-arm64-v8a-release.apk'; Dst = $androidArm64 },
        @{ Src = 'android/app/build/outputs/apk/release/app-x86_64-release.apk'; Dst = $androidX64 }
    )
    foreach ($apk in $releaseApks) {
        if (-not (Test-Path $apk.Src)) {
            throw "缺少正式签名 APK：$($apk.Src)。请先完成 release 签名构建，不能用 debug APK 代替。"
        }
        Copy-Item $apk.Src $apk.Dst -Force
        $artifacts += $apk.Dst
    }
}

$lines = Get-FileHash $artifacts -Algorithm SHA256 |
    ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $_.Path -Leaf)" }
$text = ($lines -join "`n") + "`n"
[System.IO.File]::WriteAllText((Join-Path (Get-Location) 'dist/SHA256SUMS.txt'), $text, [System.Text.UTF8Encoding]::new($false))
Get-ChildItem $artifacts | Format-Table Name, Length
Get-Content dist/SHA256SUMS.txt
