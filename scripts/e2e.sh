#!/usr/bin/env bash
# 端到端门禁：起一个真实桥接节点（fake-pi），再用 e2e 客户端跑功能场景。
# 不依赖图形界面，可在 Linux CI（含容器）与本地 Git Bash 运行。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 本地便携工具链；CI 上直接使用 PATH 中的 cargo / rustup。
if [[ -f scripts/env.sh && -d .tools/cargo ]]; then
    # shellcheck disable=SC1091
    source scripts/env.sh
fi

exe=""
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) exe=".exe" ;;
esac

echo "== 构建 e2e 所需二进制 =="
cargo build -p pi-carry-cli -p pi-carry-fake-pi -p pi-carry-e2e-client

tmp="$(mktemp -d)"
pid=""
cleanup() {
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf "$tmp" 2>/dev/null || true
}
trap cleanup EXIT

echo "== 启动桥接节点 =="
# fake-pi 的 bin 名是 fake-pi（crate 名是 pi-carry-fake-pi）：写错名字会让 resolve_pi
# 回退到 PATH 里的真实 pi，本地门禁“通过”但跑的是另一套东西，CI 上则直接必红（BUG-006）。
# 会话目录也要隔离，否则测试会话会写进开发者真实的 ~/.pi/agent/sessions。
mkdir -p "$tmp/sessions"
PI_CARRY_HOME="$tmp" \
    PI_CARRY_SESSIONS="$tmp/sessions" \
    PI_CARRY_PI="$ROOT/target/debug/fake-pi$exe" \
    "./target/debug/pi-carry$exe" serve --json >"$tmp/serve.log" 2>&1 &
pid=$!

uri=""
for _ in $(seq 1 60); do
    uri="$(sed -n 's/^pairUri: //p' "$tmp/serve.log" | head -n 1)"
    if [[ -n "$uri" ]]; then
        break
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "桥接进程提前退出：" >&2
        cat "$tmp/serve.log" >&2
        exit 1
    fi
    sleep 0.5
done
if [[ -z "$uri" ]]; then
    echo "10 秒内没有拿到配对 URI：" >&2
    cat "$tmp/serve.log" >&2
    exit 1
fi

echo "== e2e features =="
"./target/debug/pi-carry-e2e$exe" features --uri "$uri"

echo "E2E OK"
