# 将项目自带的便携工具链加入当前 PowerShell 会话。
$root = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = Join-Path $root ".tools/jdk21/jdk-21.0.13+9"
$env:ANDROID_HOME = Join-Path $root ".tools/android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:RUSTUP_HOME = Join-Path $root ".tools/rustup"
$env:CARGO_HOME = Join-Path $root ".tools/cargo"

$env:PATH = @(
    (Join-Path $env:JAVA_HOME "bin"),
    (Join-Path $root ".tools/mingw/mingw64/bin"),
    (Join-Path $env:CARGO_HOME "bin"),
    (Join-Path $env:ANDROID_HOME "platform-tools"),
    $env:PATH
) -join [IO.Path]::PathSeparator

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "CARGO_HOME=$env:CARGO_HOME"
