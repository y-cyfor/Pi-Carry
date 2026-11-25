#!/usr/bin/env bash
# 一键冒烟：Rust 单测 + CLI fake-pi + 桌面 --smoke。
set -euo pipefail
cd "$(dirname "$0")/.."

# 本地便携工具链；CI 上直接使用 PATH 中的 cargo / rustup。
if [[ -f scripts/env.sh && -d .tools/cargo ]]; then
    # shellcheck disable=SC1091
    source scripts/env.sh
fi

exe=""
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) exe=".exe" ;;
esac

echo "== Build binaries =="
cargo build -p pi-carry-cli -p pi-carry-desktop -p pi-carry-fake-pi

echo "== Rust tests =="
cargo test -p pi-carry-protocol -p pi-carry-core

echo "== CLI smoke =="
./target/debug/pi-carry$exe smoke

echo "== Desktop smoke =="
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# 深链探针：--smoke 只验证解析与“预填”路径，不接触系统协议注册。
# 形态必须与 node_status/begin_pairing 的真实产物一致：relay 写模式名（n0）、
# 多条 addr（局域网 + 回环 IPv6）。只用手写单 addr、无 relay 的链接会让门禁假绿（BUG-001）。
pair_endpoint="$(printf 'a%.0s' $(seq 1 64))"
pair_token="$(printf 'b%.0s' $(seq 1 32))"
smoke_json="$(PI_CARRY_HOME="$tmp" ./target/debug/pi-carry-desktop$exe --smoke \
    --pair-uri "pi-carry://pair?v=1&endpoint=$pair_endpoint&token=$pair_token&name=smoke&relay=n0&addr=127.0.0.1%3A55277&addr=192.168.1.9%3A55277&addr=%5B%3A%3A1%5D%3A55279")"
echo "$smoke_json"
grep -q '"pairUriOk": true' <<<"$smoke_json"
# 真实产物 → 解析器：证明产品自己生成的链接能被自己的深链入口接受。
grep -q '"pairUriRoundTrip": true' <<<"$smoke_json"

echo "SMOKE OK"
