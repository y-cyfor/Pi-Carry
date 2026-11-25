#!/usr/bin/env bash
# 统一注入便携工具链环境（Git Bash / WSL 均可 source）
set -a
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export CARRY_ROOT="$ROOT"
export JAVA_HOME="$ROOT/.tools/jdk21/jdk-21.0.13+9"
export ANDROID_HOME="$ROOT/.tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export RUSTUP_HOME="$ROOT/.tools/rustup"
export CARGO_HOME="$ROOT/.tools/cargo"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ROOT/.tools/mingw/mingw64/bin:$CARGO_HOME/bin:$PATH"
set +a
# Windows GNU 工具链的 incremental 元数据已知会损坏（E0463/E0786/rustc 0xc0000409），
# 本仓库统一关闭增量编译，避免“改一行代码就全量重编”的假故障。
export CARGO_INCREMENTAL=0
