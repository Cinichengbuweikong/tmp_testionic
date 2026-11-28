#!/usr/bin/env bash
#
# 把 iSH-ARM64（everettjf/dsh-ios 的 ish-arm64/）构建成能链进 iOS App 的
# `CapISH.xcframework`（静态库 + 头文件）。
#
# 只能在 macOS 上跑（需要 Xcode / meson / ninja）。非 macOS 直接退出。
#
# 为什么用 meson：iSH 的 emulator 主体（kernel/fs/emu/asbestos）是 meson 构建的
# （ish-arm64/meson.build，default_library=static，产出 libish.a / libish_emu.a /
# libfakefs.a）。xcodeproj 里的 libish 之类 target 是 0 源文件的壳，真正的编译由
# app/xcode-meson.sh + app/xcode-ninja.sh 在 Build Phase 里跑，所以这里复刻那条路。
#
# 产物（都 git-ignore）：
#   ios/ish-vendor/         iSH-ARM64 源码
#   ios/ish-build/          meson 构建目录 + 合并后的 .a + build.log
#   ios/CapISH.xcframework  最终 framework（device + simulator）
#   ios/ish-headers/        ISHShellExecutor.h（CapISHBridge 用它走真引擎路径）
#
# 用法：
#   bash scripts/fetch-ish-ios-runtime.sh
#   ISH_DEBUG=1 bash scripts/fetch-ish-ios-runtime.sh      # 打印每条命令
#   ISH_COMMIT=<sha> bash scripts/fetch-ish-ios-runtime.sh # 固定引擎版本
#
# 失败时把关键行抛成 GitHub annotation（::error::），这样公开 API 也能读到，
# 不需要 token 拉日志。

set -euo pipefail

ISH_REPO="${ISH_REPO:-https://github.com/everettjf/dsh-ios.git}"
ISH_COMMIT="${ISH_COMMIT:-HEAD}"
IOS_MIN="${IOS_MIN:-15.0}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "$HERE/.." && pwd)"
VENDOR="$PLUGIN_DIR/ios/ish-vendor"
BUILD="$PLUGIN_DIR/ios/ish-build"
HEADERS="$PLUGIN_DIR/ios/ish-headers"
OUT="$PLUGIN_DIR/ios/CapISH.xcframework"
LOG="$BUILD/build.log"

mkdir -p "$BUILD"
: > "$LOG"

log() { printf '[ish] %s\n' "$*"; }
warn() { printf '[ish] WARN: %s\n' "$*" >&2; }
die() { printf '[ish] ERROR: %s\n' "$*" >&2; echo "::error::[ish] $*" >&2; exit 1; }

on_err() {
  local rc=$1 line=$2 cmd=$3
  echo "::error::[ish] 失败 rc=$rc @ line $line: $cmd" >&2
  tail -n 30 "$LOG" >&2 || true
}
trap 'on_err "$?" "$LINENO" "$BASH_COMMAND"' ERR

if [[ "$(uname -s)" != "Darwin" ]]; then
  die "只能在 macOS 上运行（当前 $(uname -s)）；iOS 静态库没有跨平台构建的办法"
fi
command -v xcodebuild >/dev/null 2>&1 || die "找不到 xcodebuild"
command -v git >/dev/null 2>&1 || die "找不到 git"
command -v xcrun >/dev/null 2>&1 || die "找不到 xcrun"
command -v python3 >/dev/null 2>&1 || die "找不到 python3（装 meson 用）"

log "xcodebuild: $(xcodebuild -version 2>/dev/null | head -n 1)"
log "pwd=$PWD"

# meson + ninja。macOS runner 的 Homebrew python 是 PEP 668（externally-managed），
# `pip install --user` 会被拒绝，所以统一装进一个 venv 再把它的 bin 放到 PATH 前面。
ensure_build_tools() {
  local venv="$BUILD/venv"
  local missing=0 t
  for t in meson ninja; do
    command -v "$t" >/dev/null 2>&1 || missing=1
  done
  if [ "$missing" -eq 0 ]; then
    log "meson: $(meson --version 2>&1 | head -n 1)"
    log "ninja: $(ninja --version 2>&1 | head -n 1)"
    return 0
  fi

  log "安装 meson + ninja（venv: ${venv}）"
  if [ ! -x "$venv/bin/python3" ]; then
    python3 -m venv "$venv" >>"$LOG" 2>&1 || {
      tail -n 8 "$LOG" >&2
      die "创建 venv 失败（${LOG}）"
    }
  fi
  "$venv/bin/python3" -m pip install --quiet --disable-pip-version-check \
    --upgrade pip meson ninja >>"$LOG" 2>&1 || {
    tail -n 12 "$LOG" >&2
    grep -m 3 -E 'ERROR|error:' "$LOG" | while IFS= read -r line; do
      echo "::error::PIP|$line" >&2
    done
    die "venv 里装不上 meson/ninja（${LOG}）"
  }
  export PATH="$venv/bin:$PATH"
  command -v meson >/dev/null 2>&1 || die "装完还是找不到 meson（PATH=${PATH}）"
  command -v ninja >/dev/null 2>&1 || die "装完还是找不到 ninja（PATH=${PATH}）"
  log "meson: $(meson --version 2>&1 | head -n 1)"
  log "ninja: $(ninja --version 2>&1 | head -n 1)"
}
ensure_build_tools

if [[ ! -d "$VENDOR/ish-arm64" ]]; then
  log "clone $ISH_REPO @ ${ISH_COMMIT}（sparse，只要 ish-arm64）"
  mkdir -p "$VENDOR"
  git clone --depth 1 --filter=blob:none --sparse "$ISH_REPO" "$VENDOR" >>"$LOG" 2>&1 \
    || die "git clone 失败（${LOG}）"
  git -C "$VENDOR" sparse-checkout set ish-arm64 >>"$LOG" 2>&1 || die "sparse-checkout 失败"
  if [[ "$ISH_COMMIT" != "HEAD" ]]; then
    git -C "$VENDOR" fetch --depth 1 origin "$ISH_COMMIT" >>"$LOG" 2>&1 || die "fetch $ISH_COMMIT 失败"
    git -C "$VENDOR" checkout -q FETCH_HEAD >>"$LOG" 2>&1 || die "checkout $ISH_COMMIT 失败"
  fi
else
  log "复用已有源码 $VENDOR"
fi

ISH_SRC="$VENDOR/ish-arm64"
[[ -f "$ISH_SRC/meson.build" ]] || die "源码里没有 ish-arm64/meson.build"
[[ -f "$ISH_SRC/app/ISHShellExecutor.h" ]] || die "源码里没有 app/ISHShellExecutor.h"

mkdir -p "$HEADERS"
cp "$ISH_SRC/app/ISHShellExecutor.h" "$HEADERS/" || die "复制 ISHShellExecutor.h 失败"

# 把同样的头放进桥 target 的 include/：CapISHBridge.m 靠
# `__has_include("ISHShellExecutor.h")` 决定走真引擎还是桩，所以"头存在"必须与
# "framework 存在"同时成立。这个复制出来的文件是生成物（见 .gitignore）。
BRIDGE_INCLUDE="$PLUGIN_DIR/ios/Sources/CapISHBridge/include"
mkdir -p "$BRIDGE_INCLUDE"
cp "$HEADERS/ISHShellExecutor.h" "$BRIDGE_INCLUDE/" || die "复制头到 CapISHBridge/include 失败"

build_slice() {
  local sdk="$1"
  local triple="$2"
  local sdkpath build_dir cross libs merged exec_obj a
  sdkpath="$(xcrun --sdk "$sdk" --show-sdk-path)" || die "找不到 $sdk SDK"
  build_dir="$BUILD/meson-$sdk"
  cross="$BUILD/cross-$sdk.txt"

  {
    echo '[binaries]'
    echo "c = 'clang'"
    echo "ar = 'ar'"
    echo ''
    echo '[host_machine]'
    echo "system = 'darwin'"
    echo "cpu_family = 'aarch64'"
    echo "cpu = 'aarch64'"
    echo "endian = 'little'"
    echo ''
    echo '[built-in options]'
    echo "c_args = ['-target', '$triple', '-isysroot', '$sdkpath', '-O2']"
    echo "c_link_args = ['-target', '$triple', '-isysroot', '$sdkpath']"
    echo ''
    echo '[properties]'
    echo 'needs_exe_wrapper = true'
  } > "$cross"

  rm -rf "$build_dir"
  log "meson setup [$sdk]"
  (
    unset SDKROOT IPHONEOS_DEPLOYMENT_TARGET
    export CC_FOR_BUILD="clang"
    meson setup "$build_dir" "$ISH_SRC" \
      --cross-file "$cross" \
      -Dguest_arch=arm64 \
      --buildtype=release
  ) >>"$LOG" 2>&1 || { tail -n 40 "$LOG" >&2; die "meson setup 失败 [$sdk]（${LOG}）"; }

  log "ninja [$sdk]"
  ninja -C "$build_dir" >>"$LOG" 2>&1 || {
    tail -n 60 "$LOG" >&2
    grep -m 5 -E 'error:|fatal error|FAILED' "$LOG" | while IFS= read -r line; do
      echo "::error::[ish] $line" >&2
    done
    die "ninja 失败 [$sdk]（${LOG}）"
  }

  libs=()
  while IFS= read -r a; do
    libs+=("$a")
  done < <(find "$build_dir" -name '*.a' -type f | sort)
  [[ ${#libs[@]} -gt 0 ]] || die "ninja 成功但没有 .a 产物（meson.build 变了？）"
  log "静态库 [$sdk]: ${#libs[@]} 个（$(basename "${libs[0]}") ...）"

  exec_obj="$BUILD/ISHShellExecutor-$sdk.o"
  log "编译 ISHShellExecutor.m [$sdk]"
  xcrun --sdk "$sdk" clang \
    -target "$triple" -isysroot "$sdkpath" \
    -fobjc-arc -fblocks -O2 -DGUEST_ARM64=1 \
    -I"$ISH_SRC" -I"$ISH_SRC/app" -I"$ISH_SRC/deps/libarchive/libarchive" \
    -c "$ISH_SRC/app/ISHShellExecutor.m" -o "$exec_obj" >>"$LOG" 2>&1 \
    || { tail -n 30 "$LOG" >&2; die "ISHShellExecutor.m 编译失败 [$sdk]"; }

  merged="$BUILD/libcapish-$sdk.a"
  rm -f "$merged"
  log "libtool 合并 [$sdk]"
  xcrun --sdk "$sdk" libtool -static -o "$merged" "${libs[@]}" "$exec_obj" >>"$LOG" 2>&1 \
    || { tail -n 30 "$LOG" >&2; die "libtool 合并失败 [$sdk]"; }
  printf '%s\n' "$merged"
}

DEVICE_LIB="$(build_slice iphoneos "arm64-apple-ios${IOS_MIN}" | tail -n 1)"
SIM_LIB="$(build_slice iphonesimulator "arm64-apple-ios${IOS_MIN}-simulator" | tail -n 1)"
[[ -f "$DEVICE_LIB" ]] || die "device 静态库没有产出"
[[ -f "$SIM_LIB" ]] || die "simulator 静态库没有产出"

log "组装 xcframework"
rm -rf "$OUT"
mkdir -p "$BUILD/xcf/ios-arm64/Headers" "$BUILD/xcf/iossim-arm64/Headers"
cp "$DEVICE_LIB" "$BUILD/xcf/ios-arm64/libcapish.a"
cp "$SIM_LIB" "$BUILD/xcf/iossim-arm64/libcapish.a"
cp "$HEADERS/ISHShellExecutor.h" "$BUILD/xcf/ios-arm64/Headers/"
cp "$HEADERS/ISHShellExecutor.h" "$BUILD/xcf/iossim-arm64/Headers/"

xcrun xcodebuild -create-xcframework \
  -library "$BUILD/xcf/ios-arm64/libcapish.a" -headers "$BUILD/xcf/ios-arm64/Headers" \
  -library "$BUILD/xcf/iossim-arm64/libcapish.a" -headers "$BUILD/xcf/iossim-arm64/Headers" \
  -output "$OUT" >>"$LOG" 2>&1 || { tail -n 20 "$LOG" >&2; die "xcodebuild -create-xcframework 失败"; }

log "完成：${OUT}（$(du -sh "$OUT" | cut -f1)）"

# 校验产物是不是"真"的（只有 .a 里出现 iSH 的符号才算成功）。结果同时打到日志与
# annotation（::notice::），不下载 artifact 也能从公开 API 看到。
verify_archive() {
  local lib="$1" tag="$2" bytes members cls ishs
  bytes=$(wc -c < "$lib" | tr -d ' ')
  members=$(ar t "$lib" 2>/dev/null | wc -l | tr -d ' ')
  nm -g "$lib" > "$BUILD/syms-$tag.txt" 2>/dev/null || true
  cls=$(grep -c 'OBJC_CLASS_\$_ISHShellExecutor' "$BUILD/syms-$tag.txt" 2>/dev/null || true)
  ishs=$(grep -c ' T _do_execve\| T _task_start\| T _become_new_init_child' "$BUILD/syms-$tag.txt" 2>/dev/null || true)
  log "校验 [$tag]: ${bytes} bytes, ${members} members, ISHShellExecutor 类=${cls}, iSH 内核符号=${ishs}"
  printf '::notice::[ish] %s: %s bytes / %s members / ISHShellExecutor-class=%s / kernel-syms=%s\n' \
    "$tag" "$bytes" "$members" "${cls:-0}" "${ishs:-0}"
}
verify_archive "$DEVICE_LIB" device
verify_archive "$SIM_LIB" sim
printf '\n下一步（一次性，改宿主 App 工程）：\n'
printf '  1) 把 %s 链接进 App target（SPM: .binaryTarget(name: "CapISH", path: "...")）\n' "$OUT"
printf '  2) App 的 HEADER_SEARCH_PATHS 加上 %s，__has_include("ISHShellExecutor.h") 才会走真引擎\n' "$HEADERS"
printf '  3) 静态库里是 ObjC 类（ISHShellExecutor），链接时加 -ObjC 或 -force_load\n'
printf '  4) npx cap sync ios 后正常构建\n'
