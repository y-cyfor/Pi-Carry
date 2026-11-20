#!/usr/bin/env bash
# 构建 Rust 全工作区与 Android debug APK。
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

echo "== Rust workspace =="
cargo build --workspace

echo "== Android =="
cd android
../.tools/gradle-8.11.1/bin/gradle --no-daemon testDebugUnitTest assembleDebug

echo "== 产物 =="
ls -lh app/build/outputs/apk/debug/*.apk
