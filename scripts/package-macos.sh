#!/usr/bin/env bash
# 构建 macOS Universal（arm64 + x86_64）DMG，复制到输出目录并校验镜像与校验和。
# 本版不签名、不公证：脚本只报告实际签名状态，不做任何声称已验证的推断。
# 可在任意工作目录运行；产物命名遵循 AGENTS.md §2：pi-carry-macos-11-universal-<version>.<ext>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SKIP_BUILD=0
OUT_DIR="dist"
TARGET="universal-apple-darwin"

usage() {
    cat <<'EOF'
用法：scripts/package-macos.sh [--skip-build] [--out <dir>]

  --skip-build  跳过构建，直接使用已有 target/universal-apple-darwin/release/bundle 产物
  --out <dir>   产物输出目录，默认 dist

环境变量：
  TAURI_CLI     tauri CLI 调用方式，默认 "cargo tauri"
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build)
            SKIP_BUILD=1
            shift
            ;;
        --out)
            if [[ $# -lt 2 ]]; then
                echo "--out 需要一个目录参数" >&2
                exit 2
            fi
            OUT_DIR="$2"
            shift 2
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            echo "未知参数：$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "本脚本只能在 macOS 上运行（需要 hdiutil / codesign / lipo）" >&2
    exit 1
fi

# 版本号：Cargo.toml 的 [workspace.package] version，且必须与 desktop 的 tauri.conf.json 一致。
cargo_version="$(
    awk '
        /^\[workspace\.package\]/ { in_section = 1; next }
        /^\[/ { in_section = 0 }
        in_section && /^[[:space:]]*version[[:space:]]*=/ {
            line = $0
            sub(/^[^"]*"/, "", line)
            sub(/".*$/, "", line)
            print line
            exit
        }
    ' Cargo.toml
)"
tauri_version="$(
    awk '
        /^[[:space:]]*"version"[[:space:]]*:/ {
            line = $0
            sub(/^[^:]*:[[:space:]]*"/, "", line)
            sub(/".*$/, "", line)
            print line
            exit
        }
    ' crates/desktop/tauri.conf.json
)"

if [[ -z "$cargo_version" ]]; then
    echo "无法从 Cargo.toml 的 [workspace.package] 段解析 version" >&2
    exit 1
fi
if [[ -z "$tauri_version" ]]; then
    echo "无法从 crates/desktop/tauri.conf.json 解析 version" >&2
    exit 1
fi
if [[ "$cargo_version" != "$tauri_version" ]]; then
    echo "版本号不一致：Cargo.toml [workspace.package] version = $cargo_version，crates/desktop/tauri.conf.json version = $tauri_version" >&2
    exit 1
fi
version="$cargo_version"

echo "== pi carry $version / macos-11 universal =="

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    read -r -a tauri_cli <<<"${TAURI_CLI:-cargo tauri}"
    build_args=(build --target "$TARGET")
    if [[ -z "${TAURI_SIGNING_PRIVATE_KEY:-}" ]]; then
        echo "提示：未设置 TAURI_SIGNING_PRIVATE_KEY，关闭 updater 签名（不产生 .sig）。"
        build_args+=(--config '{"bundle":{"createUpdaterArtifacts":false}}')
    fi
    echo "== 构建 Universal DMG：${tauri_cli[*]} ${build_args[*]} =="
    "${tauri_cli[@]}" "${build_args[@]}"
else
    echo "== --skip-build：使用已有 target/$TARGET/release/bundle 产物 =="
fi

find_bundle_file() {
    local dir="$1" pattern="$2"
    [[ -d "$dir" ]] || return 0
    find "$dir" -maxdepth 1 -name "$pattern" -print -quit
}

bundle_root="target/$TARGET/release/bundle"
dmg_src="$(find_bundle_file "$bundle_root/dmg" '*.dmg' || true)"
app_src="$(find_bundle_file "$bundle_root/macos" '*.app' || true)"
app_archive_src="$(find_bundle_file "$bundle_root/macos" '*.app.tar.gz' || true)"

if [[ -z "$dmg_src" ]]; then
    echo "未找到 DMG 产物（$bundle_root/dmg/*.dmg），请先运行 cargo tauri build --target $TARGET" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
artifacts=()

dmg_out="$OUT_DIR/pi-carry-macos-11-universal-$version.dmg"
cp -f "$dmg_src" "$dmg_out"
artifacts+=("$dmg_out")

if [[ -n "$app_archive_src" ]]; then
    archive_out="$OUT_DIR/pi-carry-macos-11-universal-$version.app.tar.gz"
    cp -f "$app_archive_src" "$archive_out"
    artifacts+=("$archive_out")
    if [[ -f "$app_archive_src.sig" ]]; then
        cp -f "$app_archive_src.sig" "$archive_out.sig"
        artifacts+=("$archive_out.sig")
    else
        echo "提示：缺少更新签名 $app_archive_src.sig（未注入 TAURI_SIGNING_PRIVATE_KEY 时属正常，updater 将不可用）" >&2
    fi
else
    echo "提示：未找到 .app.tar.gz（$bundle_root/macos/*.app.tar.gz），latest.json 将缺少 macOS 更新条目" >&2
fi

# DMG 完整性：损坏的镜像必须在本地就拦下来，不能上传到 Release。
if ! hdiutil verify "$dmg_out" >/dev/null; then
    echo "hdiutil verify 失败：$dmg_out 已损坏" >&2
    exit 1
fi
echo "hdiutil verify 通过：$(basename "$dmg_out")"

# 签名状态只报告事实（本版预期未签名、未公证）。
if [[ -n "$app_src" ]]; then
    codesign_out="$(codesign -dv --verbose=2 "$app_src" 2>&1 || true)"
    if grep -qi 'Signature=adhoc' <<<"$codesign_out"; then
        echo "签名状态：adhoc（临时签名，不可分发给最终用户）"
    elif grep -qi 'Authority=' <<<"$codesign_out"; then
        echo "签名状态：已签名——$(grep -m1 'Authority=' <<<"$codesign_out")"
    else
        echo "签名状态：未签名（本版预期，未公证；首次打开需按文档在「隐私与安全性」放行）"
    fi
else
    echo "签名状态：未找到 .app，跳过 codesign 检查"
fi

# 体积预算（plan §5.2）：DMG 超过 60MB 说明误打包了调试产物，提示但不阻断发布。
limit_bytes=$((60 * 1024 * 1024))
dmg_size="$(stat -f %z "$dmg_out")"
if [[ "$dmg_size" -gt "$limit_bytes" ]]; then
    echo "WARN：DMG 体积 $((dmg_size / 1024 / 1024)) MB 超过 60MB 预算，检查是否误打包 target/ 或调试符号" >&2
fi

# 校验和：合并已有 SHA256SUMS（多平台汇总用），同名条目以本次结果为准，按文件名排序去重。
# 手写输出格式，保证是「<64hex>  <文件名>」（两个空格）。
sums="$OUT_DIR/SHA256SUMS.txt"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
for f in "${artifacts[@]}"; do
    name="$(basename "$f")"
    digest="$(cd "$OUT_DIR" && shasum -a 256 "$name")"
    printf '%s  %s\n' "${digest%% *}" "$name"
done >"$tmp"
if [[ -f "$sums" ]]; then
    cat "$sums" >>"$tmp"
fi
LC_ALL=C sort -s -k2,2 "$tmp" | awk '!seen[$2]++' >"$sums"

echo "== 产物 =="
for f in "${artifacts[@]}"; do
    size="$(stat -f %z "$f")"
    printf '  %-52s %12s bytes  %s MB\n' "$(basename "$f")" "$size" "$(awk -v b="$size" 'BEGIN { printf "%.2f", b / 1048576 }')"
done
echo "== $sums =="
cat "$sums"
