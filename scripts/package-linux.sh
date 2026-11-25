#!/usr/bin/env bash
# 构建 Linux deb/rpm 安装包（x86_64 / aarch64 自动识别），复制到输出目录并校验依赖声明与校验和。
# 可在任意工作目录运行；产物命名遵循 AGENTS.md §2：pi-carry-linux-<构建机发行版版本>-<arch>-<version>.<ext>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SKIP_BUILD=0
OUT_DIR="dist"

usage() {
    cat <<'EOF'
用法：scripts/package-linux.sh [--skip-build] [--out <dir>]

  --skip-build  跳过构建，直接使用已有 target/release/bundle 产物
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

case "$(uname -m)" in
    x86_64 | amd64) arch="x64" ;;
    aarch64 | arm64) arch="arm64" ;;
    *)
        echo "不支持的架构：$(uname -m)（仅支持 x86_64 / aarch64，不交叉编译）" >&2
        exit 1
        ;;
esac

# 产物名里的系统版本就是构建机的 glibc 基线：在 24.04 上构建却在名字/文档里写 22.04，
# 装到 22.04 上只会得到 GLIBC_2.39 not found（BUG-012）。这里按构建机发行版取真实版本，
# 让 tag 跑在哪个 runner 上，名字就是哪个基线。
os_label=""
if [[ -r /etc/os-release ]]; then
    # shellcheck disable=SC1091
    os_label="$(. /etc/os-release && printf '%s' "${VERSION_ID:-}")"
fi
if [[ -z "$os_label" ]]; then
    echo "无法从 /etc/os-release 读取发行版版本号，拒绝用猜测的版本号命名产物。" >&2
    exit 1
fi

echo "== pi carry $version / linux-$os_label-$arch =="

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    read -r -a tauri_cli <<<"${TAURI_CLI:-cargo tauri}"
    build_args=(build --bundles deb,rpm)
    if [[ -z "${TAURI_SIGNING_PRIVATE_KEY:-}" ]]; then
        echo "提示：未设置 TAURI_SIGNING_PRIVATE_KEY，关闭 updater 签名（不产生 .sig）。"
        build_args+=(--config '{"bundle":{"createUpdaterArtifacts":false}}')
    fi
    echo "== 构建 deb/rpm：${tauri_cli[*]} ${build_args[*]} =="
    # tauri.conf.json 在 crates/desktop，Tauri CLI 没有 --project-dir，必须从该目录运行。
    ( cd "$ROOT/crates/desktop" && "${tauri_cli[@]}" "${build_args[@]}" )
else
    echo "== --skip-build：使用已有 target/release/bundle 产物 =="
fi

find_bundle_file() {
    local dir="$1" pattern="$2"
    [[ -d "$dir" ]] || return 0
    find "$dir" -maxdepth 1 -type f -name "$pattern" -print -quit
}

bundle_root="target/release/bundle"
deb_src="$(find_bundle_file "$bundle_root/deb" '*.deb' || true)"
rpm_src="$(find_bundle_file "$bundle_root/rpm" '*.rpm' || true)"

if [[ -z "$deb_src" ]]; then
    echo "未找到 deb 产物（$bundle_root/deb/*.deb），请先运行 cargo tauri build --bundles deb,rpm" >&2
    exit 1
fi
if [[ -z "$rpm_src" ]]; then
    echo "未找到 rpm 产物（$bundle_root/rpm/*.rpm），请先运行 cargo tauri build --bundles deb,rpm" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
artifacts=()

copy_with_sig() {
    local src="$1" dst="$2"
    cp -f "$src" "$dst"
    artifacts+=("$dst")
    if [[ -f "$src.sig" ]]; then
        cp -f "$src.sig" "$dst.sig"
        artifacts+=("$dst.sig")
    elif [[ -z "${TAURI_SIGNING_PRIVATE_KEY:-}" ]]; then
        echo "提示：缺少更新签名 $src.sig（未注入 TAURI_SIGNING_PRIVATE_KEY 时属正常，updater 将不可用）" >&2
    fi
}

deb_out="$OUT_DIR/pi-carry-linux-$os_label-$arch-$version.deb"
rpm_out="$OUT_DIR/pi-carry-linux-$os_label-$arch-$version.rpm"
copy_with_sig "$deb_src" "$deb_out"
copy_with_sig "$rpm_src" "$rpm_out"

# 注入了签名私钥就必须同时产出 deb/rpm 的 .sig，否则 latest.json 缺 Linux 平台、updater 失效。
if [[ -n "${TAURI_SIGNING_PRIVATE_KEY:-}" ]]; then
    missing_sigs=()
    for sig in "$deb_out.sig" "$rpm_out.sig"; do
        [[ -f "$sig" ]] || missing_sigs+=("$sig")
    done
    if [[ ${#missing_sigs[@]} -gt 0 ]]; then
        echo "已注入 TAURI_SIGNING_PRIVATE_KEY，但缺少 updater 签名：" >&2
        printf '  - %s\n' "${missing_sigs[@]}" >&2
        echo "请在 crates/desktop 下用 cargo tauri build --bundles deb,rpm 重新构建（保持 createUpdaterArtifacts=true）。" >&2
        exit 1
    fi
fi

# 依赖声明检查：托盘依赖必须显式声明，否则装上了也没有图标。
missing=()
if command -v dpkg-deb >/dev/null 2>&1; then
    deb_info="$(dpkg-deb -I "$deb_out" || true)"
    for dep in libwebkit2gtk-4.1-0 libgtk-3-0 libayatana-appindicator3-1; do
        if ! grep -qF -- "$dep" <<<"$deb_info"; then
            missing+=("deb 缺少依赖声明：$dep")
        fi
    done
else
    echo "提示：未找到 dpkg-deb，跳过 deb 依赖检查（请在 Debian/Ubuntu 环境复核）" >&2
fi

if command -v rpm >/dev/null 2>&1; then
    rpm_info="$(rpm -qp --requires "$rpm_out" 2>/dev/null || true)"
    # 依赖可能写成发行版包名（webkit2gtk4.1 / gtk3），也可能是 RPM 的 soname 自动依赖
    # （libwebkit2gtk-4.1.so.0()(64bit) / libgtk-3.so.0()(64bit)），两种都代表依赖已声明。
    rpm_dep_declared() {
        local cand
        for cand in "$@"; do
            grep -qF -- "$cand" <<<"$rpm_info" && return 0
        done
        return 1
    }
    rpm_dep_declared "webkit2gtk4.1" "libwebkit2gtk-4.1.so" ||
        missing+=("rpm 缺少依赖声明：webkit2gtk4.1")
    rpm_dep_declared "gtk3" "libgtk-3.so" ||
        missing+=("rpm 缺少依赖声明：gtk3")
    rpm_dep_declared "libayatana-appindicator-gtk3" "libayatana-appindicator3.so" ||
        missing+=("rpm 缺少依赖声明：libayatana-appindicator-gtk3")
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "== rpm 实际 Requires（rpm -qp --requires $rpm_out）==" >&2
        printf '%s\n' "$rpm_info" >&2
    fi
else
    echo "提示：未找到 rpm 命令，跳过 rpm 依赖检查（安装 rpm 包后再复核）" >&2
fi

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "依赖声明检查未通过（修改 crates/desktop/tauri.conf.json 的 bundle.linux 段）：" >&2
    printf '  - %s\n' "${missing[@]}" >&2
    exit 1
fi

# 校验和：合并已有 SHA256SUMS（多平台汇总用），同名条目以本次结果为准，按文件名排序去重。
# 手写输出格式，保证是「<64hex>  <文件名>」（两个空格）；sha256sum 在部分平台会写成 "hash *file"。
sums="$OUT_DIR/SHA256SUMS.txt"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
for f in "${artifacts[@]}"; do
    name="$(basename "$f")"
    digest="$(cd "$OUT_DIR" && sha256sum "$name")"
    printf '%s  %s\n' "${digest%% *}" "$name"
done >"$tmp"
if [[ -f "$sums" ]]; then
    cat "$sums" >>"$tmp"
fi
LC_ALL=C sort -s -k2,2 "$tmp" | awk '!seen[$2]++' >"$sums"

echo "== 产物 =="
for f in "${artifacts[@]}"; do
    size="$(stat -c %s "$f")"
    printf '  %-52s %12s bytes  %s MB\n' "$(basename "$f")" "$size" "$(awk -v b="$size" 'BEGIN { printf "%.2f", b / 1048576 }')"
done
echo "== $sums =="
cat "$sums"
