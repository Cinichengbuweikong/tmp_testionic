#!/usr/bin/env bash
#
# 把 iSH-ARM64（everettjf/dsh-ios 的 ish-arm64/）构建成能链进 iOS App 的
# `CapISH.xcframework`（静态库 + 头文件）。
#
# ⚠️ 只能在 macOS 上跑（需要 Xcode / meson / ninja）。非 macOS 直接退出。
#
# 为什么是 meson 而不是"逐个 clang 编译"：
#   iSH 的 emulator 主体（kernel/fs/emu/asbestos）是 **meson** 构建的
#   （`ish-arm64/meson.build`，`default_library=static`，产出 libish.a / libish_emu.a /
#   libfakefs.a）；它的 Xcode 工程里那些 `libish` target 是 0 源文件的壳，真正的编译由
#   `app/xcode-meson.sh` + `xcode-ninja.sh` 在 Build Phase 里跑。所以这里直接复刻那条路：
#   用 cross file 指定 iOS SDK，meson setup + ninja，再把产物合并成静态库。
#
# 产物（都 git-ignore）：
#   ios/ish-vendor/            iSH-ARM64 源码
#   ios/ish-build/             meson 构建目录 + 合并后的 .a
#   ios/CapISH.xcframework/    最终 framework（device + simulator）
#   ios/ish-headers/           ISHShellExecutor.h（CapISHBridge 用它走真引擎路径）
#
# 用法：
#   bash scripts/fetch-ish-ios-runtime.sh
#   ISH_DEBUG=1 bash scripts/fetch-ish-ios-runtime.sh      # 打印每条命令
#   ISH_COMMIT=<sha> bash scripts/fetch-ish-ios-runtime.sh # 固定引擎版本
#
# 失败时脚本会把关键行抛成 GitHub annotation（`::error::`），这样公开 API 也能读到，
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

log()  { printf '\033[36m[ish]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[ish]\033[0m %s\n' "$*" >&2; }
# die 同时也抛 annotation，方便从公开 API 读到失败原因
die()  { printf '\033[31m[ish]\033[0m %s\n' "$*" >&2; echo "::error::[ish] $*" >&2; exit 1; }
trap 'rc=$?; [ $rc -ne 0 ] && echo "::error::[ish] 失败 rc=$rc @ line $LINENO: $BASH_COMMAND" >&2' ERR

if [[ "$(uname -s)" != "Darwin" ]]; then
  die "这个脚本只能在 macOS 上运行（当前 $(uname -s)）。iOS 静态库没有跨平台构建的办法。"
fi
command -v xcodebuild >/dev/null || die "找不到 xcodebuild"
command -v git >/dev/null || die "找不到 git"
command -v xcrun >/dev/null || die "找不到 xcrun"

log "bash $BASH_VERSION / $(xcodebuild -version | head -1)"
log "pwd=$PWD"

# meson / ninja（macOS runner 上通常没有，用 pip 装到用户目录）
ensure_tool() {
  local tool="$1" hint="$2"
  if ! command -v "$tool" >/dev/null 2>&1; then
    log "安装 $tool（$hint）"
    python3 -m pip install --user --quiet "$tool" >>"$LOG" 2>&1 \
      || die "装不上 $tool（$hint）"
    export PATH="$HOME/Library/Python/$(python3 -c 'import sys;print("%d.%d"%sys.version_info[:2])')/bin:$PATH"
    command -v "$tool" >/dev/null 2>&1 || die "装完还是找不到 $tool"
  fi
  log "$tool $("$tool" --version 2>&1 | head -1)"
}
ensure_tool meson ""
ensure_tool ninja ""

# ---------------------------------------------------------------- 1. 拉源码
if [[ ! -d "$VENDOR/ish-arm64" ]]; then
  log "clone $ISH_REPO @ $ISH_COMMIT（sparse：只要 ish-arm64）"
  mkdir -p "$VENDOR"
  git clone --depth 1 --filter=blob:none --sparse "$ISH_REPO" "$VENDOR" >>"$LOG" 2>&1 \
    || die "git clone 失败（看日志：$LOG）"
  git -C "$VENDOR" sparse-checkout set ish-arm64 >>"$LOG" 2>&1 || die "sparse-checkout 失败"
  if [[ "$ISH_COMMIT" != "HEAD" ]]; then
    git -C "$VENDOR" fetch --depth 1 origin "$ISH_COMMIT" >>"$LOG" 2>&1 \
      && git -C "$VENDOR" checkout -q FETCH_HEAD || die "checkout $ISH_COMMIT 失败"
  fi
else
  log "复用已有源码 $VENDOR"
fi
ISH_SRC="$VENDOR/ish-arm64"
[[ -f "$ISH_SRC/meson.build" ]] || die "源码里没有 ish-arm64/meson.build"
[[ -f "$ISH_SRC/app/ISHShellExecutor.h" ]] || die "源码里没有 app/ISHShellExecutor.h"

mkdir -p "$HEADERS"
cp "$ISH_SRC/app/ISHShellExecutor.h" "$HEADERS/" || die "复制 ISHShellExecutor.h 失败"

# ------------------------------------------------- 2. 每个 SDK 编一份静态库
# cross file 是照 iSH 自己的 app/xcode-meson.sh 写的（它用 xcodebuild 注入 SDKROOT），
# 这里直接写死 -target/-isysroot，脱离 xcodebuild 也能跑。
build_slice() {
  local sdk="$1" target_triple="$2"
  local sdkpath build_dir
  sdkpath="$(xcrun --sdk "$sdk" --show-sdk-path)" || die "找不到 $sdk SDK"
  build_dir="$BUILD/meson-$sdk"
  local cross="$BUILD/cross-$sdk.txt"

  cat > "$cross" <<EOF
[binaries]
c = 'clang'
ar = 'ar'
strip = 'strip'

[host_machine]
system = 'darwin'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = ['-target', '$target_triple', '-isysroot', '$sdkpath', '-O2']
c_link_args = ['-target', '$target_triple', '-isysroot', '$sdkpath']

[properties]
needs_exe_wrapper = true
EOF

  rm -rf "$build_dir"
  log "meson setup [$sdk] -> $build_dir"
  # host 工具（meson 会编一些 build-time 程序）必须用宿主 clang，不能带 iOS SDK
  (
    unset SDKROOT IPHONEOS_DEPLOYMENT_TARGET
    export CC_FOR_BUILD="clang"
    meson setup "$build_dir" "$ISH_SRC" \
      --cross-file "$cross" \
      -Dguest_arch=arm64 \
      --buildtype=release \
      2>>"$LOG" >>"$LOG"
  ) || { tail -n 40 "$LOG" >&2; die "meson setup 失败 [$sdk]（日志：$LOG）"; }

  log "ninja [$sdk]"
  ninja -C "$build_dir" >>"$LOG" 2>&1 || {
    tail -n 60 "$LOG" >&2
    grep -m5 -E 'error:|fatal error|FAILED' "$LOG" | while IFS= read -r l; do echo "::error::[ish] $l" >&2; done
    die "ninja 失败 [$sdk]（日志：$LOG）"
  }

  # 合并所有静态库（libish / libish_emu / libfakefs / ...）
  local libs=()
  while IFS= read -r a; do libs+=("$a"); done < <(find "$build_dir" -name '*.a' -type f | sort)
  [[ ${#libs[@]} -gt 0 ]] || die "ninja 跑完了但没找到 .a（meson.build 变了？）"
  log "静态库：$(printf '%s ' "${libs[@]##*/}")"

  # ISHShellExecutor.m 不在 meson 的库里（它是 app/ 下的 ObjC 门面），单独编进来
  local exec_obj="$BUILD/ISHShellExecutor-$sdk.o"
  log "编译 ISHShellExecutor.m [$sdk]"
  xcrun --sdk "$sdk" clang \
    -target "$target_triple" -isysroot "$sdkpath" \
    -fobjc-arc -fblocks -O2 \
    -DGUEST_ARM64=1 \
    -I"$ISH_SRC" -I"$ISH_SRC/app" -I"$ISH_SRC/deps/libarchive/libarchive" \
    -c "$ISH_SRC/app/ISHShellExecutor.m" -o "$exec_obj" 2>>"$LOG" \
    || { tail -n 30 "$LOG" >&2; die "ISHShellExecutor.m 编译失败 [$sdk]" }

  local merged="$BUILD/libcapish-$sdk.a"
  rm -f "$merged"
  log "libtool 合并 -> $(basename "$merged")"
  xcrun --sdk "$sdk" libtool -static -o "$merged" "${libs[@]}" "$exec_obj" >>"$LOG" 2>&1 \
    || { tail -n 30 "$LOG" >&2; die "libtool 合并失败 [$sdk]"; }
  echo "$merged"
}

DEVICE_LIB="$(build_slice iphoneos "arm64-apple-ios${IOS_MIN}")"
SIM_LIB="$(build_slice iphonesimulator "arm64-apple-ios${IOS_MIN}-simulator")"

# ------------------------------------------------------- 3. 组装 xcframework
log "组装 xcframework -> $OUT"
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

log "完成：$OUT（$(du -sh "$OUT" | cut -f1)）"
cat <<NEXT

下一步（一次性，改宿主 App 工程）：
  1) 把 $OUT 链接进 App target；用 SPM 的话在 App 的 Package.swift 里加
       .binaryTarget(name: "CapISH", path: "../../plugins/capacitor-linux/ios/CapISH.xcframework")
     （或在 Xcode 里拖进 Frameworks）
  2) App target 的 HEADER_SEARCH_PATHS 加上：
       \$(SRCROOT)/../../plugins/capacitor-linux/ios/ish-headers
     这样 CapISHBridge.m 的 __has_include("ISHShellExecutor.h") 才会走真引擎
  3) 静态库里全是 ObjC 类（ISHShellExecutor），App 链接时加 -ObjC（或 -force_load）
  4) npx cap sync ios 后正常构建
NEXT
