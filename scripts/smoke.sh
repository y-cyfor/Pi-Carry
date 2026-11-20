#!/usr/bin/env bash
# 一键冒烟：Rust 单测 + CLI fake-pi + 桌面 --smoke。
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

echo "== Build binaries =="
cargo build -p pi-carry-cli -p pi-carry-desktop -p pi-carry-fake-pi

echo "== Rust tests =="
cargo test -p pi-carry-protocol -p pi-carry-core

echo "== CLI smoke =="
./target/debug/pi-carry smoke

echo "== Desktop smoke =="
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
PI_CARRY_HOME="$tmp" ./target/debug/pi-carry-desktop.exe --smoke

echo "SMOKE OK"
