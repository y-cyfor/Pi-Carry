# 重新打包 dist 产物并生成 SHA256 校验和。
# 桌面包放运行必需文件和许可证；Android 包放两个 ABI 的 APK、说明和许可证。
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$desktopDir = 'dist/pi-carry-desktop-win-x64'
$androidDir = 'dist/android'
$desktopZip = 'dist/pi-carry-desktop-0.1.0-win-x64.zip'
$androidZip = 'dist/pi-carry-android-0.1.0-debug.zip'

New-Item -ItemType Directory -Force $desktopDir, $androidDir | Out-Null
Get-ChildItem $desktopDir -File -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem $androidDir -File -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item target/release/pi-carry-desktop.exe $desktopDir/ -Force
Copy-Item target/release/WebView2Loader.dll $desktopDir/ -Force
Copy-Item packaging/desktop-README.txt $desktopDir/README.txt -Force
Copy-Item LICENSE $desktopDir/LICENSE -Force
Copy-Item android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk, `
          android/app/build/outputs/apk/debug/app-x86_64-debug.apk $androidDir/ -Force
Copy-Item LICENSE $androidDir/LICENSE.txt -Force
Copy-Item packaging/android-README.txt $androidDir/README.txt -Force

Remove-Item $desktopZip, $androidZip, `
    dist/pi-carry-0.1.0-win-x64.zip, dist/pi-carry-0.1.0-android-debug.zip -ErrorAction SilentlyContinue
Compress-Archive -Path "$desktopDir/*" -DestinationPath $desktopZip -Force
Compress-Archive -Path "$androidDir/*" -DestinationPath $androidZip -Force

$lines = Get-FileHash $desktopZip, $androidZip -Algorithm SHA256 |
    ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $_.Path -Leaf)" }
$lines | Set-Content dist/SHA256SUMS.txt -Encoding utf8
Get-ChildItem dist/*.zip | Format-Table Name, Length
Get-Content dist/SHA256SUMS.txt
